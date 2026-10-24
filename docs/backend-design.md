# Backend design

## 1. Technology baseline

| Concern | Choice | Reason |
|---|---|---|
| Runtime | Java 17, Spring Boot 3.x | Required runtime, mature production ecosystem |
| Web/security | Spring MVC, Spring Security, Nimbus JWT support | REST/SSE, one authentication filter, explicit URI/role boundaries |
| Persistence | Spring Data JPA for aggregates; JdbcTemplate for inventory/report queries | Maintainability plus explicit SQL where correctness/performance matters |
| Schema | MySQL 8, Flyway | Independent durable database and repeatable migrations |
| Redis | Lettuce/Spring Data Redis | Cache, refresh tokens, Lua reservation, Streams, Pub/Sub, and rate limits |
| API docs | springdoc-openapi | Executable contract and Swagger UI in local profile |
| Mapping/validation | Manual mappers, Jakarta Bean Validation | Explicit boundary behavior without entity leakage |
| Tests | JUnit 5, AssertJ, MockMvc, Testcontainers MySQL/Redis | Production-equivalent integration behavior; no H2 |
| Observability | Actuator, Micrometer, structured Logback JSON | Health, metrics, and traceable operations |

Dependency versions will be pinned in the build and upgraded through reviewed pull requests. Maven Wrapper is committed so the only host build prerequisite is JDK 17 plus Docker or local MySQL/Redis.

## 2. Project and package layout

```text
backend/
├── pom.xml
├── src/main/java/com/webox/
│   ├── WeboxApplication.java
│   ├── shared/                 # errors, money, clock, security context, paging
│   ├── identity/
│   │   ├── api/                # controllers and request/response DTOs
│   │   ├── application/        # RegisterUser, Login handled by Security, CurrentUser
│   │   ├── domain/
│   │   └── infrastructure/
│   ├── catalog/                # dishes, allergens, option groups/values, media
│   ├── menu/                   # dated menus and employee read model
│   ├── preference/
│   ├── ordering/               # quote, submission, employee/admin state machine
│   ├── inventory/              # Lua reservation, Stream consumer, movements, reconciliation
│   ├── reporting/              # dashboard projections
│   ├── recommendation/         # disabled port and future provider adapter
│   └── platform/               # Redis, outbox, rate limit, OpenAPI, config
├── src/main/resources/
│   ├── application.yml
│   ├── application-local.yml
│   └── db/migration/
└── src/test/
```

Each feature uses `api → application → domain`, with infrastructure implementing domain/application ports. Controllers never expose JPA entities. Repositories are package-private where practical. Architecture tests (ArchUnit) forbid API/domain dependencies on infrastructure and cross-feature repository access.

## 3. Domain responsibilities

| Module | Owns | Does not own |
|---|---|---|
| `identity` | registration, credentials, access JWT, refresh rotation, role, current actor | profile food preferences |
| `catalog` | dish lifecycle, allergens, option definitions, image metadata | dated availability/stock |
| `menu` | date publication, offered dishes, initial stock, browse queries | decrement correctness |
| `preference` | allergen/cuisine/taste/spice/budget settings | LLM logic |
| `ordering` | cutoff normalization, quote, submission/idempotency, active-order rule, snapshots, audited state machine | cache implementation |
| `inventory` | Redis reservation, idempotent MySQL confirmation/restore, movement ledger, compensation/reconciliation | cart quantities before checkout |
| `reporting` | read-only aggregates and DTOs | duplicated reporting tables initially |
| `recommendation` | context assembly/provider port/policy post-filter | authoritative menu or allergy data |

## 4. Transaction design

### Place order

The request and asynchronous consumer form one recoverable workflow:

1. Validate `Idempotency-Key`, canonicalize the request, and claim `(user_id, key)` in MySQL with request hash and `PROCESSING` state.
2. Normalize date/slot, check the active-order/submission rule, validate current catalog/options, and calculate `BigDecimal` totals.
3. Call one date-scoped Redis Lua script. It verifies all quantities, decrements all-or-none, stores the reservation, and `XADD`s a reservation event atomically.
4. A Redis Stream consumer group reads the event and opens a MySQL transaction. It rechecks the idempotency/reservation state, conditionally updates confirmed stock in stable item order, inserts the order/snapshots/movements/initial status history/outbox rows, and completes the idempotency record.
5. After commit it acknowledges the Stream entry and marks the Redis reservation confirmed. Redelivery observes the completed unique reservation and does not decrement again.
6. The HTTP request waits within a configured budget. It returns `201` if completed, `202` with a submission/status URL if still processing, `409` for a deterministic conflict, or the previously completed order for a same-key retry.

If MySQL rejects a reservation because reconciliation found drift or a competing invariant, the consumer performs an idempotent Lua compensation and marks the submission `FAILED`. A pending-entry reaper retries transient failures; a reservation-expiry worker compensates only when MySQL proves the submission never completed. User-visible success is therefore never based only on Redis.

### Order state machine and cancellation

All transitions use `UPDATE ... WHERE id = ? AND status = ? AND version = ?`, append `order_status_history`, and enqueue an outbox event in the same MySQL transaction.

| Actor | From | To | Inventory effect |
|---|---|---|---|
| Employee | `PENDING` | `CANCELLED` | Restore exactly once |
| Admin | `PENDING` | `CONFIRMED` | None |
| Admin | `PENDING` | `CANCELLED` | Restore exactly once |
| Admin | `CONFIRMED` | `COMPLETED` | None |
| Admin | `CONFIRMED` | `CANCELLED` | Restore exactly once |

`COMPLETED` and `CANCELLED` are terminal. Restoration is committed in MySQL first and emitted through the outbox to Redis; a temporarily stale Redis value is conservative (too low), and versioned reconciliation repairs it. The unique inventory movement prevents a repeated transition from restoring twice. No operator queue is required.

## 5. Authentication and actor context

- Access JWT lifetime defaults to 15 minutes. It contains subject/user ID, role, issuer, audience, issued-at, expiry, and token ID; no profile or preference data.
- The SPA stores access JWT only in memory and sends `Authorization: Bearer ...`. It never stores either token in `localStorage`.
- Refresh is an opaque 256-bit value in a path-scoped `HttpOnly`, `Secure`, `SameSite` cookie. Redis stores only an HMAC/hash, user ID, family ID, generation, expiry, and revoked flag.
- `/api/v1/auth/refresh` rotates token and cookie in one atomic Redis Lua operation. Old-token reuse revokes the family and emits a security event.
- `JwtAuthenticationFilter` builds `AuthenticatedActor`; `SecurityFilterChain` applies App/Admin prefixes. `@PreAuthorize` and domain checks enforce ownership and transition rights.
- Key IDs support signing-key rotation. Clock skew is bounded; issuer/audience/algorithm are fixed, never accepted from untrusted headers.

## 6. Pricing and validation

- MySQL uses `DECIMAL(10,2)`; Java uses `BigDecimal` with scale 2 and `RoundingMode.UNNECESSARY` for persisted prices.
- Option values contribute a fixed `price_delta`. An item unit price is dish base price plus selected deltas; subtotal is unit price × quantity.
- Required groups must have exactly one selection. Single-select groups accept at most one. Multi-select groups de-duplicate values.
- All selected values must belong to the chosen dish and be active.
- Order snapshots preserve dish/option names and prices even after catalog edits.
- Search term ≤ 50, email/address ≤ 200, names/descriptions/options have limits reflected in DTO and DDL.

## 7. Cutoff policy

`MealSlotResolver` is a pure, clock-driven component:

```text
requested future slot                 -> unchanged
today Lunch before 10:00              -> today Lunch
today Lunch at/after 10:00 before 15:00 -> today Dinner
today Lunch/Dinner at/after 15:00      -> tomorrow Lunch
today Dinner before 15:00             -> today Dinner
past date                              -> validation error
```

Cutoff is exclusive: exactly `10:00:00` closes Lunch and exactly `15:00:00` closes Dinner. The quote endpoint returns the resolved slot and warnings before `Place Order`; the order endpoint repeats resolution to prevent time-of-check/time-of-use errors.

## 8. Cache, inventory stream, and real-time behavior

### Menu cache

- Key: `webox:menu:v{schemaVersion}:{date}:{publicationVersion}`.
- Value: normalized English dish/menu metadata. Query-specific filtering and pagination occur over the bounded daily set to avoid a combinatorial key space.
- TTL: five minutes plus ±10% jitter. Admin dish/menu changes bump the date publication version after commit.
- Browse responses use confirmed/reserved Redis stock views with version metadata. Checkout admission uses Redis Lua, while final success always requires the MySQL conditional confirmation described above.
- A short Redis `SET NX PX` rebuild lease prevents stampedes. Callers that miss the lease read MySQL instead of waiting indefinitely.

### Inventory reservation and reconciliation

- Keys use `{inventory:YYYY-MM-DD}` hash tags so every item in an order is in one Redis Cluster slot.
- Lua records reservation ID and before/after versions and appends a Stream entry in the same command. Reservation commands are idempotent.
- Consumer concurrency is bounded; each message has a unique MySQL reservation/idempotency key. `XAUTOCLAIM` recovers abandoned pending entries.
- Redis uses persistence and `noeviction` for critical keys. A daily bootstrap loads from MySQL only when no unprocessed reservation exists.
- Continuous reconciliation compares MySQL confirmed stock plus movements with Redis confirmed/reserved quantities. Drift pages an operator, blocks the affected date from new reservations, and repairs from MySQL plus still-valid reservations.

### Events and SSE

- The transactional outbox publisher claims rows using `FOR UPDATE SKIP LOCKED`, publishes versioned events, and records publication time.
- Redis channel is an internal fan-out transport; browser clients connect to `/api/v1/app/menu/stock-stream?date=...`.
- SSE sends `stock.changed`, heartbeat, and `resync.required`. It never exposes internal entity payloads.
- On initial connect/reconnect the UI fetches a fresh menu page; it also revalidates on window focus and every 30 seconds.

### Rate limits

Redis Lua performs atomic token-bucket updates. Proposed defaults are 10 login attempts/10 minutes/IP, 10 order attempts/minute/user, and 120 general API requests/minute/user. Values are configuration, not hard-coded policy. `429` includes `Retry-After`.

## 9. Dashboard queries

The reporting repository uses explicit, indexed SQL and maps directly to immutable DTOs:

- overview groups by status for a delivery date; revenue sums non-cancelled order totals;
- Top 10 sums item quantities for non-cancelled orders and uses item snapshot names;
- meal split counts non-cancelled orders by `meal_slot`;
- trend groups delivery date for the inclusive last seven dates;
- low stock reads today's published menu where remaining stock ≤ 3.

Results cache for 15 seconds and are invalidated by order placed/cancelled/status-changed and menu-stock events. The endpoint also supports `refresh=true` for the Console refresh button, restricted and rate-limited.

## 10. Error contract and status codes

Spring `ProblemDetail` is extended with `code`, `correlationId`, `fieldErrors`, and optional `details`. Messages are English.

| Condition | HTTP | Code |
|---|---:|---|
| Invalid input | 400 | `VALIDATION_FAILED` |
| Missing/invalid/expired access JWT | 401 | `AUTHENTICATION_REQUIRED` |
| Wrong role/ownership | 403 | `ACCESS_DENIED` |
| Missing resource | 404 | `RESOURCE_NOT_FOUND` |
| Active order exists | 409 | `ACTIVE_ORDER_EXISTS` |
| Idempotency conflict/in progress | 409 | `IDEMPOTENCY_KEY_REUSED` / `REQUEST_IN_PROGRESS` |
| Inventory conflict | 409 | `INVENTORY_INSUFFICIENT` |
| Invalid order transition/version | 409 | `INVALID_ORDER_TRANSITION` / `OPTIMISTIC_LOCK_CONFLICT` |
| Business cutoff/menu mismatch | 422 | `ORDER_SLOT_ADJUSTED` or `MENU_ITEM_UNAVAILABLE` |
| Rate limited | 429 | `RATE_LIMITED` |

The quote endpoint normally handles slot adjustment as a successful response with a warning. `ORDER_SLOT_ADJUSTED` is reserved for a placement request whose resolved slot changed after the quote and requires explicit user review.

## 11. Test strategy

- **Unit:** cutoff boundaries, five-serving rule, price calculations, required options, preference ranking, allergen matching.
- **Application:** use cases with fake clock and ports; authorization and ownership paths.
- **Integration:** Testcontainers MySQL/Redis; Flyway, JWT/refresh rotation and reuse, URI roles, repository SQL, Stream recovery, cache invalidation, outbox, idempotency, and every state transition.
- **Concurrency:** 20+ threads reserve the last N units; assert MySQL-confirmed successful quantity equals N, stock is zero, Redis/MySQL reconcile, movement ledger balances, and no duplicate active order exists.
- **API:** MockMvc/OpenAPI-compatible request/response and English errors.
- **Failure:** crash before/after MySQL commit and Stream acknowledgement, duplicate delivery, Redis restart/loss, compensation retry, outbox retry, deadlock retry, refresh-token reuse, and signing-key rotation.

