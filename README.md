# WeBox

WeBox is an enterprise employee meal-ordering platform with a responsive employee application and an Admin Console. It implements the complete Tier 1 and Tier 2 scope from the supplied PRD, plus real-time inventory deduction and the operations dashboard from Tier 3. The AI recommendation boundary is reserved for a future provider integration.

All user-facing content, validation feedback, menu data, and dashboard labels are English.

## Features

### Employee application

- Email registration and login with BCrypt passwords
- Short-lived access JWT plus rotating `HttpOnly` refresh cookie
- Daily menu search, multi-category filters, dish details, options, and exact CNY pricing
- Dietary preferences, cuisine/spice ranking, allergen confirmation, and budget warnings
- Configuration-aware cart with a five-serving order limit
- Server-authoritative quote, cutoff-slot adjustment, idempotent placement, and inventory validation
- Order history, details, and Pending-order cancellation
- Live stock updates over authenticated SSE, including low-stock and sold-out states

### Admin Console

- Dish search, creation, editing, publication, option groups, allergens, and image uploads
- Daily menu and stock management for today or future dates
- Order search, details, audited status transitions, and cancellation
- Dashboard overview, Top 10 dishes, meal-slot distribution, seven-day trend, and low-stock alerts

## Architecture

```text
React + TypeScript SPA
        │ JSON / SSE
        │ Bearer JWT + refresh cookie
        ▼
Java 17 + Spring Boot modular backend
        ├── MySQL: durable users, catalog, menus, orders, stock and reporting
        └── Redis: refresh state, caches, rate limits, inventory reservations,
                   Streams, Pub/Sub and SSE fan-out
```

- Employee APIs: `/api/v1/app/**`
- Admin APIs: `/api/v1/admin/**`
- Authentication APIs: `/api/v1/auth/**`
- Binding contract: [`docs/openapi.yaml`](docs/openapi.yaml)
- Architecture details: [`docs/architecture.md`](docs/architecture.md)

Inventory admission uses an all-or-none Redis Lua reservation and Redis Stream record. MySQL conditionally confirms stock before an order can return success. Reservation IDs are idempotent; pending Stream entries support recovery and one-time compensation. MySQL remains the reporting and confirmed-inventory authority.

## Technology stack

| Area | Technology |
|---|---|
| Frontend | React 19, TypeScript, Vite, React Router, TanStack Query, Zustand, React Hook Form, Zod, ECharts |
| Backend | Java 17, Spring Boot 3.3, Spring Security, Spring JDBC, Flyway, springdoc-openapi |
| Data | MySQL, Redis |
| Testing | JUnit 5, MockMvc, ArchUnit, Vitest, Testing Library, MSW, Playwright, axe |

## Repository layout

```text
backend/                  Spring Boot service, migrations and backend tests
frontend/                 React SPA, unit tests and browser tests
docs/                     architecture, API, schema and development documentation
backend/uploads/seed/     local seed dish images
```

## Prerequisites

- JDK 17
- Maven 3.9+ or the included Maven Wrapper
- Node.js and npm
- MySQL reachable at `127.0.0.1:3306`
- Redis reachable at `127.0.0.1:6379`

The local defaults use MySQL user `root` with an empty password and Redis without authentication. Do not use these credentials outside local development.

## Local setup

### 1. Create the database

```bash
mysql -h 127.0.0.1 -P 3306 -u root -e \
  "CREATE DATABASE IF NOT EXISTS webox CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
redis-cli -h 127.0.0.1 -p 6379 ping
```

Flyway applies the schema and English menu seed automatically when the backend starts.

### 2. Start the backend

```bash
cd backend

export WEBOX_DB_URL='jdbc:mysql://127.0.0.1:3306/webox?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
export WEBOX_DB_USER='root'
export WEBOX_DB_PASSWORD=''
export WEBOX_REDIS_HOST='127.0.0.1'
export WEBOX_REDIS_PORT='6379'
export WEBOX_JWT_SECRET='replace-this-with-a-random-secret-of-at-least-32-bytes'
export WEBOX_DEMO_ADMIN_PASSWORD='Admin1234'
export WEBOX_DEMO_EMPLOYEE_PASSWORD='Employee1234'

JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw spring-boot:run
```

On non-macOS systems, set `JAVA_HOME` to the installed JDK 17 directory instead.

Optional local accounts created by these environment variables:

| Role | Email | Password source |
|---|---|---|
| Admin | `admin@webox.local` | `WEBOX_DEMO_ADMIN_PASSWORD` |
| Employee | `employee@webox.local` | `WEBOX_DEMO_EMPLOYEE_PASSWORD` |

The bootstrap is idempotent and stores only BCrypt hashes.

Backend URLs:

- API: `http://localhost:8080`
- Health: `http://localhost:8080/actuator/health`
- OpenAPI: `http://localhost:8080/v3/api-docs`
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`

### 3. Start the frontend

In another terminal:

```bash
cd frontend
npm ci
npm run dev
```

Open `http://localhost:5173`. Vite proxies `/api` to the backend at `http://localhost:8080`.

## Verification

Backend tests use the dedicated `webox_test` schema and the local Redis instance. Never point the test profile at production data.

```bash
cd backend
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw verify
```

Latest backend verification: **47 tests, 0 failures, 0 errors, 0 skipped**.

```bash
cd frontend
npm ci
npm run lint
npm run type-check
npm test -- --run
npm run build
npm run test:e2e
npm audit
```

Latest frontend verification: **41 unit tests and 6 desktop/mobile Playwright + axe checks passed**, with zero npm audit vulnerabilities. Browser E2E currently uses API route mocks; a live full-stack E2E run remains a recommended final deployment check.

## Important design rules

- Money is transferred as two-decimal strings, calculated with `BigDecimal` on the backend, and handled as integer fen on the frontend.
- The backend always recalculates prices and validates dish options, availability, cutoff rules, quantity limits, and ownership.
- A Redis reservation is not by itself a successful order; success requires a committed MySQL order.
- Employees can cancel only `Pending` orders. Admin transitions are `Pending → Confirmed → Completed`, with cancellation allowed from `Pending` or `Confirmed`.
- Dashboard values come from real orders. Cancelled orders count toward total orders but not revenue or rankings.
- Allergens do not hide dishes from the menu. The employee receives a confirmation warning when adding a matching dish.

## Documentation

- [Requirements and scope](docs/requirements-and-scope.md)
- [Frontend design](docs/frontend-design.md)
- [Backend design](docs/backend-design.md)
- [API guide](docs/api.md)
- [MySQL schema](docs/database/schema.sql)
- [Development plan](docs/development-plan.md)
- [Appendix A score assessment](docs/appendix-a-score-assessment.md)

## Known limitations

- The AI recommendation provider is intentionally not implemented; the module/API extension seam is reserved.
- Recovery resumes a durable `Processing` submission when the client retries the same idempotency key. A stale unconfirmed Stream reservation is compensated and marked failed rather than reconstructing the full order automatically.
- Redis inventory rebuild is lazy per item; there is no scheduled full-date drift scan.
- Several supplied seed images are approximate visual matches because the asset package does not contain an exact photograph for every PRD dish.
- Before final candidate delivery, place the complete original AI coding conversation exports under `ai-conversations/` as required by the PRD.
