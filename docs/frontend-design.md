# Frontend design

## 1. Is React + TypeScript suitable?

Yes. The product combines a card-oriented employee experience, complex checkout state, a data-heavy admin Console, responsive mobile/desktop layouts, real-time SSE updates, and charts. React's component ecosystem, TanStack Query cache model, mature form/testing libraries, and broad hiring familiarity fit this mix well. TypeScript protects API and money/state boundaries.

Vue 3 would also be a sound choice and can be more concise for a small team, while Next.js would add server-rendering and server/runtime concepts that provide little value for this authenticated internal SPA. The recommended stack is therefore **React + TypeScript + Vite**, without SSR.

## 2. Proposed stack

| Concern | Choice |
|---|---|
| Build/runtime | Vite, React, strict TypeScript |
| Routing | React Router with employee/admin route trees |
| Server state | TanStack Query; generated or typed API clients split into App/Admin namespaces |
| Local cart/UI state | Zustand with per-user persisted cart and schema version |
| Forms | React Hook Form + Zod; server errors mapped by field |
| UI foundation | Ant Design components/tokens plus scoped CSS for responsive employee cards |
| Charts | Apache ECharts through a small adapter component |
| Money | Integer fen in UI computations/formatting; API decimal strings parsed at boundary |
| Tests | Vitest, React Testing Library, MSW, Playwright |

Ant Design is particularly effective for the Console tables/forms. Employee pages use the same accessible primitives but a dedicated responsive shell, not a desktop Console squeezed onto mobile.

## 3. Source layout

```text
frontend/
├── src/
│   ├── app/               # router, providers, auth bootstrap, error boundary
│   ├── api/               # client, generated types, ProblemDetail mapping, SSE
│   ├── shared/            # UI primitives, money/date helpers, constants
│   ├── features/
│   │   ├── auth/
│   │   ├── menu/
│   │   ├── cart/
│   │   ├── checkout/
│   │   ├── orders/
│   │   ├── preferences/
│   │   ├── console-catalog/
│   │   ├── console-menu/
│   │   ├── console-dashboard/
│   │   └── recommendation/ # feature-flagged extension boundary
│   ├── layouts/           # EmployeeLayout and ConsoleLayout
│   └── main.tsx
├── e2e/
└── public/
```

Feature folders own page, components, hooks, schema, and tests. Shared code must be domain-neutral. API decimal strings never flow directly into arithmetic; date-only values remain `YYYY-MM-DD` and are not converted through UTC timestamps.

## 4. Routes and access

```text
/login, /register                         public
/menu, /dishes/:id, /cart, /checkout     employee
/orders, /orders/:id, /settings           employee
/console/dishes, /console/menus           admin
/console/orders, /console/orders/:id      admin
/console/dashboard                        admin
```

Login/registration returns an access JWT that is kept only in memory; a refresh cookie is inaccessible to JavaScript. On reload, the app calls `/api/v1/auth/refresh`, rotates the cookie, installs the new access JWT, then loads `/api/v1/auth/me`. One request wrapper attaches `Authorization: Bearer ...`, performs a single-flight refresh after an eligible `401`, and retries at most once. Client guards improve UX but are not security boundaries; App/Admin API prefixes and backend role checks are authoritative.

## 5. Employee experience and responsiveness

- Mobile uses a compact top bar and bottom navigation, a one-column menu, sticky cart summary, full-width drawers/modals, and touch targets of at least 44 px.
- Tablet uses two columns; desktop uses three/four depending on container width and a persistent cart affordance.
- Menu filter chips support multiple categories. Search is limited to 50 characters and debounced about 250 ms; in-flight obsolete queries are cancelled.
- Results are server-paged and retain previous data during transitions. Card images have fixed aspect ratio, lazy loading, responsive sources, and skeleton placeholders.
- Dish detail requires all mandatory option groups before Add to Cart. The displayed price updates from integer fen.
- At five servings, all relevant add buttons disable with `Maximum 5 items per order.` This is immediate UX; server enforcement remains final.
- Rapid repeated Add to Cart clicks are serialized per card for 300 ms while still allowing intentional quantity changes in the cart. Functional state updates prevent lost increments.

## 6. Preferences and warnings

The cart add command compares dish allergens against the current profile. A match opens an English confirmation listing exact allergens. Confirming adds the configured line; cancelling changes nothing. This is never a hidden filter.

When “Recommended for me” is on, the API returns a deterministic preference score and match reasons; the UI sorts/highlights those dishes. Spice and cuisine affect this score. Taste intensity is stored and displayed but reserved primarily for the future AI provider. Checkout compares total against the budget maximum and shows a non-blocking warning.

## 7. Cart and checkout state

A cart-line identity is a stable hash of `dishId` plus sorted option-value IDs. Thus equal configurations merge and different configurations remain separate. Persistence includes a schema version and user ID; logout removes sensitive/query state and makes another user's cart inaccessible.

Checkout flow:

1. User selects address/date/slot and the UI requests `/orders/quote`.
2. The response replaces client prices with authoritative values, reports budget/inventory/menu issues, an existing order, and any slot switch.
3. User explicitly accepts a changed slot and submits with a UUID `Idempotency-Key` retained until a definitive result.
4. UI disables submission while pending. A `202` displays `Confirming your order…` and polls the submission; a network timeout retries with the same key.
5. Only a MySQL-confirmed order shows the success page and clears placed lines. Inventory/compensation failure highlights affected lines and returns to an editable cart.

## 8. Real-time stock

An authenticated fetch-based SSE client subscribes for the visible date because native `EventSource` cannot attach the bearer header. Events update only newer `stockVersion` values in TanStack Query caches. Cards show `Only N left` for 1–3 and `Sold out` for 0; controls become disabled.

SSE is advisory. Initial page load, reconnection, window focus, and a 30-second interval revalidate the menu. A connection badge is unnecessary unless disconnection persists; the UI can quietly fall back to refresh and show a small English notice.

## 9. Console design

- Desktop Console uses side navigation; narrow screens collapse it to a drawer.
- Dish table supports debounced search, category filter, server pagination, edit, and publish toggle.
- The dish form handles English metadata, fixed-enum allergens/spice/category, nested option groups/values, image preview, upload progress, and server validation.
- Daily Menu defaults to tomorrow but permits today. It shows publish status and editable stock per dish; destructive stock reductions below sold quantity are rejected by the API.
- Dashboard cards/charts share one response timestamp, expose a `Refresh` button, and auto-refresh every 30 seconds while visible. Charts include labels/tooltips and a table/text fallback for accessibility.
- Orders provides search/filter/detail and only state-machine-valid actions. Every transition sends `expectedVersion`; conflicts refresh the detail and explain that another administrator changed the order.

## 10. Frontend score coverage

| Appendix A.1 criterion | Design evidence |
|---|---|
| Responsive | Separate employee/Console shells, 1/2/3–4 column layout, mobile navigation |
| Pagination/list performance | Server pagination, debounced/cancelled search, lazy images, stable cache; virtualization can be added beyond 200 visible rows |
| Debounce/throttle | Search debounce, per-card add serialization, disabled submit with durable idempotency key |
| Price precision | Decimal string boundary and integer-fen UI calculations; backend reprice |
| Input validation | Shared Zod/RHF constraints, length limits, safe rendering, server validation mapping |

## 11. Frontend tests

- Unit/component tests cover cart identity, five-item limit, exact totals, required options, allergen dialog, budget warning, role guards, and stock event ordering.
- MSW integration tests cover ProblemDetail errors, slot changes, idempotent timeout/retry, and inventory conflicts.
- Playwright runs employee registration → menu → configured cart → asynchronous confirmation → order detail/cancel, and admin login → dish/menu edit → order Confirm/Complete or Cancel → dashboard.
- Responsive E2E runs at representative mobile and desktop viewports; accessibility smoke tests use axe.

