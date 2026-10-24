# WeBox Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:test-driven-development` and execute every behavioral step red → green → refactor. Do not spawn additional agents.

**Goal:** Build the complete responsive employee SPA and Admin Console in `frontend/`, integrated against the binding OpenAPI contract.

**Architecture:** React + strict TypeScript + Vite. TanStack Query owns server state, Zustand owns user-scoped cart state, React Hook Form/Zod own input validation, Ant Design supplies accessible primitives, and ECharts renders the dashboard. All API access passes through one bearer-token client with single-flight refresh.

**Tech Stack:** React, TypeScript, Vite, React Router, TanStack Query, Zustand, React Hook Form, Zod, Ant Design, ECharts, Vitest, React Testing Library, MSW, Playwright, axe.

**Spec:** `docs/frontend-design.md`, `docs/openapi.yaml`, and `docs/api-contract-decisions.md`

## Global Constraints

- Write only under `frontend/` and `.superpowers/reports/frontend-report.md`; never edit `backend/` or `docs/`.
- Treat `docs/openapi.yaml` as the binding field/path contract. Report ambiguity instead of changing the contract.
- All user-visible strings, seeded mock fixtures, errors, chart labels, and accessibility labels are English.
- Access JWT is memory-only. Refresh uses credentials and the HttpOnly cookie; never use local/session storage for tokens.
- Currency arithmetic uses integer fen at the API boundary; render `¥` with exactly two decimals.
- Every behavior starts with a focused failing test whose failure is observed and recorded in the report.
- Do not commit while the backend agent is working in the same repository.

## Planned file structure

```text
frontend/
├── package.json
├── vite.config.ts
├── playwright.config.ts
├── src/
│   ├── app/{App,router,providers}.tsx
│   ├── api/{client,auth,types,sse}.ts
│   ├── shared/{money,date,problem}.ts
│   ├── layouts/{EmployeeLayout,AdminLayout}.tsx
│   ├── features/{auth,menu,cart,preferences,checkout,orders}/
│   ├── features/admin/{catalog,menus,orders,dashboard}/
│   └── test/{server,handlers,fixtures}.ts
└── e2e/
```

### Task 1: Tooling, generated contract types, and API client

**Files:** create `frontend/package.json`, Vite/TS/Vitest configs, `src/api/types.ts`, `src/api/client.ts`, `src/api/client.test.ts`, `src/shared/money.ts`, `src/shared/money.test.ts`, test setup and MSW server.

**Produces:** `apiRequest<T>()`, `setAccessToken()`, `clearAccessToken()`, `bootstrapRefresh()`, `moneyToFen()`, `formatCny()`.

- [ ] Write failing tests proving `22.50 → 2250`, exact CNY formatting, bearer attachment, one refresh for concurrent `401`s, one retry only, and ProblemDetail parsing.
- [ ] Run `npm test -- --run src/shared/money.test.ts src/api/client.test.ts`; confirm failures are missing behavior rather than setup errors.
- [ ] Add the minimal client/money implementation and generated OpenAPI types.
- [ ] Re-run the focused tests, then `npm run type-check`; keep both green.

### Task 2: Authentication, actor bootstrap, routing, and layouts

**Files:** create `src/features/auth/*`, `src/app/router.tsx`, `src/layouts/*`, auth tests.

**Produces:** `AuthProvider`, `useAuth()`, `RequireRole`, `/login`, `/register`, employee routes, and `/console/*` Admin routes.

- [ ] Write failing component tests for validation, generic bad-credential feedback, reload refresh bootstrap, employee rejection from Admin routes, admin navigation, and logout state clearing.
- [ ] Verify focused tests fail for absent behavior.
- [ ] Implement forms and route guards using the contract endpoints; never decode JWT as authorization truth.
- [ ] Run auth/layout tests and axe smoke assertions; refactor only while green.

### Task 3: Employee menu, preferences, dish options, and cart

**Files:** create `src/features/menu/*`, `preferences/*`, `cart/*` and focused tests.

**Produces:** responsive paged menu, multi-category/search controls, preference editor/recommend toggle, dish detail, allergen confirmation, and persisted per-user cart.

- [ ] Write failing tests for debounced 50-character search, filter serialization, low/sold-out UI, required option groups, configuration-sensitive cart identity, allergen confirmation, maximum five servings, and integer-fen totals.
- [ ] Observe the expected failures.
- [ ] Implement minimal hooks/components/stores. Persist only cart schema/user ID, never auth tokens.
- [ ] Run feature tests; then run all unit tests to detect cross-feature regressions.

### Task 4: Checkout, asynchronous confirmation, and employee orders

**Files:** create `src/features/checkout/*`, `orders/*`, tests.

**Produces:** quote/reprice flow, address choice, cutoff acknowledgement, budget warning, durable Idempotency-Key lifecycle, `202` polling, order history/detail, and Pending cancellation.

- [ ] Write failing tests for slot adjustment acknowledgement, existing-order navigation, one idempotency key across timeout/retry, no cart clear on `202`, success only on completed order, inventory conflict editing, and cancellation version conflict.
- [ ] Verify every test fails for the intended missing behavior.
- [ ] Implement the state machine with explicit `editing → quoting → ready → submitting → confirming → completed/failed` states.
- [ ] Run checkout/order tests and the complete Vitest suite.

### Task 5: Admin catalog, daily menus, orders, and dashboard

**Files:** create `src/features/admin/catalog/*`, `menus/*`, `orders/*`, `dashboard/*`, tests.

**Produces:** searchable dish table/form/image upload/publication, date menu editor, complete order transition UI, and live/manual-refresh dashboard.

- [ ] Write failing tests for server pagination/filtering, nested option validation, optimistic conflict refresh, same-day menu stock editing, allowed/forbidden transition actions, dashboard empty/loading/error/data states, and low-stock/chart table fallback.
- [ ] Observe focused red results.
- [ ] Implement Admin screens solely against `/api/v1/admin/**` schemas.
- [ ] Run Admin tests, all unit tests, and type-check.

### Task 6: Real-time stock, responsive/accessibility behavior, and E2E

**Files:** create `src/api/sse.ts`, SSE reducer tests, `e2e/employee.spec.ts`, `e2e/admin.spec.ts`, responsive styles.

**Produces:** bearer-capable fetch SSE with version ordering/reconnect, mobile/desktop UX, and core browser flows.

- [ ] Write failing tests that ignore stale stock versions, revalidate on reconnect/focus, and disable sold-out controls.
- [ ] Implement SSE parsing/reconnect with bounded backoff and periodic query revalidation.
- [ ] Write Playwright scenarios for employee register/login → configured dish → quote/place/poll → detail/cancel and Admin login → dish/menu edit → order transition → dashboard.
- [ ] Run `npm run lint`, `npm run type-check`, `npm test -- --run`, and `npm run build`. Run Playwright against the backend when available; otherwise run it against MSW and record that live E2E remains for coordinator verification.

### Task 7: Handoff report

**File:** create `.superpowers/reports/frontend-report.md`.

- [ ] Record created features, exact test/build commands and outputs, each observed red-to-green cycle, remaining contract concerns, and live-integration prerequisites.
- [ ] Return only status, one-line test summary, and concerns to the coordinator.

