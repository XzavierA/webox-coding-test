# WeBox Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:test-driven-development` and execute every behavioral step red → green → refactor. Do not spawn additional agents.

**Goal:** Build the Java 17 Spring Boot modular monolith in `backend/`, implementing the binding OpenAPI contract with MySQL/Redis integration.

**Architecture:** Package-by-feature modular monolith. MySQL is the durable authority; Redis provides refresh state, caching, limits, atomic stock reservation, Streams, and SSE fan-out. A centralized Spring Security JWT filter establishes `AuthenticatedActor`, while URI and method/domain checks enforce authorization.

**Tech Stack:** Java 17, Spring Boot, Spring MVC/Security/Validation/Data JPA/Data Redis, Flyway, MySQL, Redis/Lettuce, Nimbus/Jose JWT support, springdoc, JUnit 5, AssertJ, MockMvc, ArchUnit.

**Spec:** `docs/backend-design.md`, `docs/database/schema.sql`, `docs/database/seed.sql`, `docs/openapi.yaml`, and `docs/api-contract-decisions.md`

## Global Constraints

- Write only under `backend/` and `.superpowers/reports/backend-report.md`; never edit `frontend/` or `docs/`.
- Compile and test with `JAVA_HOME=$(/usr/libexec/java_home -v 17)` and Maven Wrapper.
- Local integration endpoints are MySQL `root`/empty password at `127.0.0.1:3306` and Redis/no auth at `127.0.0.1:6379`.
- Use a dedicated `webox_test` schema for destructive integration setup; never clean/drop an unknown schema.
- Treat `docs/openapi.yaml` as binding. All response messages rendered to users are English.
- Every behavior starts with a focused failing test and observed expected failure. Do not add production behavior before its red test.
- Do not commit while the frontend agent is working in the same repository.

## Planned file structure

```text
backend/
├── pom.xml
├── mvnw
├── src/main/java/com/webox/
│   ├── shared/
│   ├── identity/{api,application,domain,infrastructure}/
│   ├── catalog/{api,application,domain,infrastructure}/
│   ├── menu/
│   ├── preference/
│   ├── ordering/
│   ├── inventory/
│   ├── reporting/
│   └── platform/{security,redis,outbox,config}/
├── src/main/resources/{application.yml,application-local.yml,db/migration/}
└── src/test/java/com/webox/
```

### Task 1: Build, configuration, Flyway schema, and English seeds

**Files:** create Maven project/config, versioned migrations, application profiles, health endpoint tests.

**Produces:** Java 17 build, `webox` local configuration, `webox_test` integration profile, schema validation, today's/tomorrow's published menu, and local demo-user bootstrap from environment passwords.

- [ ] Write failing context/integration tests that require MySQL/Redis connectivity, Flyway tables, today/tomorrow menu rows, and no plaintext password.
- [ ] Run the focused tests with JDK 17 and observe expected missing-schema/config failures.
- [ ] Add minimal build/config/migrations copied and versioned from the reviewed DDL; copy visually selected seed images into backend resources/uploads.
- [ ] Re-run focused tests and `./mvnw -q -DskipTests package`.

### Task 2: JWT authentication, rotating refresh cookie, and unified actor authorization

**Files:** create `identity/*`, `platform/security/*`, security tests.

**Produces:** register/login/refresh/logout/me, BCrypt, 15-minute JWT, 14-day rotating refresh family in Redis, reuse detection, `JwtAuthenticationFilter`, `AuthenticatedActor`, URI roles.

- [ ] Write failing unit/MockMvc/integration tests for password rules, duplicate email, generic login error, token claims/expiry, refresh rotation/reuse revocation, Origin checks, employee Admin denial, expired JWT, and explicit actor propagation.
- [ ] Observe red failures for missing behavior.
- [ ] Implement the minimum security flow. Store only refresh-token hashes and never accept algorithm/role/user from request fields.
- [ ] Run security tests and full tests; inspect logs to ensure tokens/passwords are absent.

### Task 3: Catalog, daily menus, preferences, addresses, media, and caching

**Files:** create feature packages, repositories/mappers/controllers, tests.

**Produces:** App menu/detail/filter/search/pagination, preference/address APIs, Admin dish/options/image/publication and daily-menu APIs, cache-aside invalidation.

- [ ] Write failing domain/API/integration tests for English DTOs, name/description search, enum/length limits, unpublished visibility, option constraints, ownership, image MIME/size checks, optimistic versions, menu stock edit invariants, cache hit/invalidation/fallback.
- [ ] Observe expected failures.
- [ ] Implement minimal domain and adapters; use parameter binding and sort allowlists.
- [ ] Run feature tests, MySQL/Redis integration tests, and full suite.

### Task 4: Quote, cutoff, idempotency, and order persistence

**Files:** create `ordering/*`, policy and API tests.

**Produces:** exact pricing/options, five-serving rule, `Asia/Shanghai` cutoff resolution, active-order uniqueness, address snapshot, immutable order snapshots, idempotency/submission records, history/detail.

- [ ] Write failing pure tests for 09:59/10:00/14:59/15:00 boundaries, required options, BigDecimal totals, quantity sum, and budget warning.
- [ ] Write failing integration tests for active-slot races, same-key replay, different-hash conflict, ownership, and order snapshot stability.
- [ ] Implement minimal policies/use cases and API responses matching OpenAPI.
- [ ] Run focused and complete suites.

### Task 5: Redis reservation, Stream confirmation, compensation, SSE, and cancellation

**Files:** create `inventory/*`, Redis Lua resources, workers/reconciliation, tests.

**Produces:** all-or-none date-scoped Lua reservation + `XADD`, consumer-group MySQL confirmation, `201/202` submission API, deduplication, compensation, reconciliation, versioned SSE, exactly-once restoration.

- [ ] Write failing tests for last-N concurrent reservations, multi-item rollback, duplicate/redelivered Stream entry, crash before commit, crash after commit before XACK, terminal compensation, Redis restart recovery, stale version suppression, and repeated cancellation.
- [ ] Observe failures against real local Redis/MySQL test schemas; arbitrary sleeps are forbidden—wait on states/events with bounds.
- [ ] Implement Lua/consumer/reaper/reconciler and durable success gate. Redis reservation alone must never return an Order success representation.
- [ ] Run concurrency/failure tests repeatedly, then full suite. Record Redis/MySQL final quantities and movement totals.

### Task 6: Admin order state machine and dashboard

**Files:** create Admin order commands/read APIs, status history, reporting queries/cache, tests.

**Produces:** Admin search/detail, conditional Pending→Confirmed→Completed and cancellation transitions, audit actor/reason, exactly-once restore, dashboard overview/Top10/distribution/seven-day trend/low stock from real data.

- [ ] Write failing state-table tests for every allowed and forbidden transition, stale expectedVersion, actor role, and terminal behavior.
- [ ] Write failing SQL fixture tests for cancelled-order dashboard semantics, all seven dates including zero days, Top 10, and threshold 0–3.
- [ ] Implement conditional transitions/history/outbox and indexed reporting queries with 15-second cache/invalidation.
- [ ] Run focused integration tests and full suite.

### Task 7: Contract, architecture, and delivery verification

**Files:** create OpenAPI conformance tests, ArchUnit tests, backend README, `.env.example`, report.

- [ ] Write failing tests that compare registered paths/methods/content types and critical schema examples with `docs/openapi.yaml`; add ArchUnit boundary tests.
- [ ] Implement only the adapter/documentation changes needed to pass.
- [ ] Run `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw verify` against `webox_test` and Redis; start the local app and smoke login/menu/order/Admin/dashboard APIs.
- [ ] Create `.superpowers/reports/backend-report.md` with features, exact commands/results, red-green evidence, schema changes, credentials used only as environment variables, and concerns.
- [ ] Return only status, one-line test summary, and concerns to the coordinator.

