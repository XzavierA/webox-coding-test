# Local initialization and delivery plan

This document describes the target repository after implementation. At the current design-review stage only `docs/` exists; the commands become actionable after approval and scaffolding.

## 1. Prerequisites

- JDK 17
- Node.js active LTS and npm
- MySQL 8 as an independent local process, reachable as `root` with no password per the requested local environment
- Redis 7 as an independent local process
- Maven Wrapper and npm lockfile committed by the project

Local no-password root access is accepted only as the requested development default. Staging/production must use a least-privilege `webox_app` account and secrets supplied outside Git.

## 2. Proposed repository layout

```text
webox-coding-test/
├── README.md
├── backend/
├── frontend/
├── docs/
├── ai-conversations/          # complete native AI conversation exports
├── data/uploads/              # gitignored local uploads
├── .env.example
└── compose.yml                # optional convenience, still independent MySQL/Redis processes
```

## 3. Database and Redis initialization

For the requested local MySQL account:

```bash
mysql -u root < docs/database/schema.sql
mysql -u root < docs/database/seed.sql
redis-cli ping
```

The application default configuration is equivalent to:

```text
DB_URL=jdbc:mysql://localhost:3306/webox?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
DB_USERNAME=root
DB_PASSWORD=
REDIS_HOST=localhost
REDIS_PORT=6379
APP_TIME_ZONE=Asia/Shanghai
```

In the implemented project, Flyway runs the versioned equivalent of `schema.sql`; `seed.sql` becomes a local/dev repeatable seed migration or an explicit seed command, not an unconditional production migration. `spring.jpa.hibernate.ddl-auto=validate` prevents accidental schema drift.

## 4. Target startup

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

```bash
cd frontend
npm ci
npm run dev
```

The final root README will include exact URLs, demo-account creation, health checks, Swagger/OpenAPI location, seed/reset procedure, and troubleshooting for occupied ports, MySQL authentication, Redis availability, and timezone mismatch.

## 5. Configuration contract

`.env.example` documents only non-secret examples. Required production values fail startup with a clear message. Planned properties include database/Redis endpoints, JWT issuer/audience/signing keys, access/refresh lifetimes, refresh-cookie security, Redis Stream consumer/retry/compensation settings, upload path/limits, rate-limit buckets, menu/dashboard cache TTLs, outbox batch/retry settings, feature flags, and optional LLM provider/model/key.

AI recommendation defaults to disabled. Supplying a key alone does not expose it; both provider configuration and the feature flag are required.

## 6. Verification commands after implementation

```bash
cd backend && ./mvnw verify
cd frontend && npm run lint && npm run type-check && npm run test -- --run
cd frontend && npm run e2e
```

Backend verification uses Testcontainers MySQL/Redis and never substitutes H2. An additional concurrency test runs against MySQL 8 to prove no overselling and idempotent order creation.

## 7. Required delivery artifacts

- Runnable frontend and backend with locked dependencies
- Root README from zero to running application
- OpenAPI document plus this human-readable API summary
- Architecture and data-flow documentation
- Executable automated tests and test commands
- MySQL migration and English demo seed data for today's menu
- Complete original AI-tool conversation export under `ai-conversations/`
- `.env.example`, no real secrets or API keys
- Brief demo script: employee order, second-browser stock update, cancellation restore, admin menu edit, dashboard refresh

## 8. Operational notes

- Do not reset or reseed a shared environment. The local reset command will require an explicit `local` profile and confirmation.
- Inventory Redis must enable persistence and reserve memory under `noeviction`; generic cache eviction must not remove reservation or Stream keys. Production should use replication and monitor pending-entry age and reconciliation drift.
- Uploaded files live outside the source tree in runtime environments and require backup alongside the database until an object-storage adapter is introduced.
- Database schema changes are forward migrations. Destructive migrations require a backup/rollback plan.
- Keep credentials out of prompts and logs from the start so the required complete, original AI conversation exports can be delivered unchanged. If a secret is exposed, revoke/rotate it immediately and document the incident without replacing the original export.

