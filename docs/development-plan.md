# Development plan and acceptance gates

Implementation begins only after the design and open decisions are approved. The plan prioritizes complete vertical flows over disconnected screens.

## Phase 0 — Foundations

Deliver:

- Maven/Spring Boot and Vite/React strict-TypeScript projects
- environment profiles, Flyway, MySQL/Redis connectivity, Redis persistence expectations, health checks
- shared error contract, OpenAPI, logging/correlation ID
- frontend application shells, API client, design tokens, responsive baseline
- CI commands and Testcontainers foundation

Gate: clean database migrates, both projects start from README, health/readiness are meaningful, and no secret is committed.

## Phase 1 — Identity and authorization

Deliver registration/login/refresh/logout/current-user, BCrypt, short access JWT, rotating refresh cookie with reuse detection, one JWT authentication filter/current-actor contract, App/Admin URI rules, admin bootstrap, and rate limits.

Gate: browser reload refreshes login without exposing tokens to JavaScript; refresh reuse revokes the family; duplicate email and invalid credentials show English feedback; employee access to Admin APIs is denied; integration and E2E tests pass.

## Phase 2 — Catalog, daily menu, and Console management

Deliver dish/options/allergens model, media upload, admin CRUD/publication, dated menu/stock editor, English seed data for today/tomorrow, employee menu/details/filter/search/pagination, and cache-aside behavior.

Gate: admin changes are visible after cache invalidation; unpublished dishes are absent; required options and exact display prices work; mobile/desktop layouts pass.

## Phase 3 — Preferences and cart

Deliver settings, recommendation toggle deterministic ranking, allergen confirmation, configuration-aware local cart, quantity controls, five-serving UX, exact money handling, and budget warning.

Gate: component tests cover all warning/limit paths; different configurations remain separate lines; malicious client totals cannot affect later server quote.

## Phase 4 — Ordering and business rules

Deliver addresses, quote, cutoff resolver, server repricing, active-order uniqueness, durable submission/idempotency, history/detail/employee cancel, Admin order list/detail and complete audited state machine, immutable snapshots, and English errors.

Gate: registration-to-order and history/cancel E2E pass; every allowed/forbidden Admin transition is tested; exact cutoff tests pass; same-key retry returns one order; concurrent requests cannot create two active same-slot orders.

## Phase 5 — Inventory and real-time updates

Deliver Redis Lua all-or-none reservation, Stream consumer group, idempotent MySQL confirmation, `201/202` submission flow, compensation, movement ledger, reconciliation, outbox/Pub/Sub/SSE, low/sold-out UI, and reconnect revalidation.

Gate: concurrent last-stock test proves no negative stock/oversell in both Redis and MySQL; duplicate/redelivered Stream events create one order; crash-window tests converge; failed multi-item reservation changes nothing; cancellation restores exactly once; a second browser updates without manual refresh.

## Phase 6 — Dashboard

Deliver real-order aggregates, 15-second Redis cache/invalidation, overview, Top 10 bar chart, meal distribution, seven-day order/revenue trend, low-stock list, manual and 30-second refresh.

Gate: integration fixtures prove all numbers and cancellation semantics; Console E2E renders empty/loading/error/populated states; data changes after a real order.

## Phase 7 — Hardening and handoff

Deliver failure-path tests, ArchUnit rules, accessibility/responsive checks, query/index review, cache/limiter metrics, final OpenAPI, root README, demo script, and AI conversation exports.

Gate: backend `verify`, frontend lint/type/test, and Playwright all pass from a clean checkout; fresh local startup follows only documented steps; delivery checklist is complete.

## Optional Phase 8 — AI implementation (not currently requested)

After mandatory acceptance, configure a real provider, implement structured streaming, policy pre/post-filters, seven-day exclusion, card hydration, provider timeout/circuit breaker, prompt-injection defenses, and live-provider tests behind a separate profile.

## Test coverage matrix

| Risk | Unit | Integration | E2E/manual |
|---|---:|---:|---:|
| Password/JWT/refresh/roles | ✓ | ✓ | ✓ |
| Price/options/five servings | ✓ | ✓ | ✓ |
| Cutoff normalization | ✓ | ✓ | ✓ |
| Active-order uniqueness | ✓ | ✓ concurrent | ✓ |
| Idempotency | ✓ | ✓ concurrent | timeout/retry ✓ |
| No overselling/compensation/reconciliation | ✓ | ✓ concurrent + crash windows | two-browser ✓ |
| Cache consistency |  | ✓ with Redis | admin-to-menu ✓ |
| SSE stock freshness | ✓ reducer | ✓ publisher | two-browser ✓ |
| Dashboard correctness | ✓ | ✓ SQL fixtures | ✓ |
| Responsive/accessibility | ✓ component |  | mobile/desktop + axe |

## Definition of done for every feature

- Frontend and backend path is complete; no mock data remains in production paths.
- Authorization, input limits, English success/error/empty/loading states, and observability are included.
- Tests demonstrate the business rule at the lowest useful level plus one end-to-end happy path.
- API/schema/documentation changes ship with code in the same review.
- Cache invalidation and failure behavior are explicit for every cached write path.

