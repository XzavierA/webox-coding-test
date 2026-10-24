# Requirements and scope

## 1. Delivery scope

The implementation must cover all Tier 1 and Tier 2 requirements plus the selected Tier 3 capabilities below.

| Area | Required result | Acceptance evidence |
|---|---|---|
| Authentication | Register with any valid email, unique email, password policy, short access JWT, rotating refresh cookie, logout, role authorization | API/component/E2E tests; employee receives `403` for Admin APIs |
| Daily menu | Responsive cards, details, English data, multi-category filter, name/description search, customization pricing | Employee flow E2E; pagination/performance test |
| Cart | Quantity/edit/remove, configuration-sensitive lines, exact total, five-serving limit | Unit/component tests and disabled-button UX |
| Checkout/orders | Slot/date/address, server-side repricing, idempotent submission, history/detail/cancel | Integration tests including duplicate request |
| Preferences | Allergens, cuisine, spice, taste, budget, preference sorting/highlighting | Allergen confirmation and budget warning E2E |
| Meal rules | Maximum five servings, cutoff auto-switch, one active order per date/slot | Clock-controlled tests and database constraint test |
| Console dishes | Search/filter/create/edit/publish state/image upload/daily menu and stock | Admin E2E and authorization tests |
| Console orders | Search/detail plus audited, conditional order-state transitions without an operator queue | State-machine integration tests and Admin E2E |
| Inventory | Remaining stock, low/sold-out states, no oversell, checkout recheck, cancellation restore | Concurrent integration test and two-browser E2E |
| Dashboard | Today overview, Top 10, meal split, seven-day trend, low-stock list; real order data | Seeded order integration tests and Console E2E |
| AI extension seam | Provider-neutral context, policy filter, streaming endpoint shape, feature flag | Architecture/API review; no live LLM required in this scope |

All customer-visible strings, including validation and API error messages rendered by the UI, are English. Currency is CNY and displayed with `¥`.

## 2. Requirement-to-module mapping

| PRD | Frontend feature | Backend module | Main tables/infrastructure |
|---|---|---|---|
| 3.1 | `auth` | `identity` | `app_user`, JWT signing, Redis refresh-token family |
| 3.2 | `menu` | `catalog`, `menu` | `dish*`, `daily_menu*`, Redis menu cache |
| 3.3 | `cart` | Checkout validation only | Browser storage; authoritative catalog in MySQL |
| 3.4 | `checkout`, `orders` | `ordering` | `customer_order*`, `user_address`, `idempotency_record` |
| 4.1 | `profile` | `preference` | `user_preference`, allergen/category joins |
| 4.2 | `cart`, `checkout` | `ordering`, `policy` | unique active-slot constraint, injected `Clock` |
| 4.3 | `console/catalog` | `catalog`, `menu`, `media` | dish/options/menu tables, local media adapter |
| 5.1 | menu stock UI | `inventory`, `realtime` | Redis Lua reservation/Stream, idempotent MySQL confirmation, outbox, SSE |
| 5.2 extension | disabled/flagged AI entry | `recommendation` port | provider adapter seam; optional audit table later |
| 5.3 | `console/dashboard` | `reporting` | real order queries, short Redis cache |

## 3. Explicit business interpretations

- A serving is the sum of all cart quantities; option add-ons do not add servings.
- The maximum of five is checked in the UI and again by the backend.
- The server resolves cutoff rules in `Asia/Shanghai`; browser time is never authoritative.
- After a cutoff, the requested slot is normalized to same-day Dinner when available, otherwise next-day Lunch. The response explicitly reports the switch before final placement; the server validates that every cart dish is offered in the resolved menu.
- An active duplicate means `Pending` or `Confirmed`. `Completed` and `Cancelled` do not block a new order. The database enforces this rule in addition to the application check.
- Order count on the dashboard includes orders created for today's delivery date, including cancelled orders. Revenue excludes cancelled orders. This is called out for confirmation.
- Dashboard “today” is based on delivery date, not record creation time.
- Cart state is local to the signed-in browser and namespaced by user ID. Cross-device cart synchronization is not required by the PRD. Every value is revalidated/repriced on the server.
- Employees may cancel only `Pending` orders. Administrators may cancel `Pending` or `Confirmed` orders. Every cancellation restores inventory exactly once.
- Admin order transitions are `Pending → Confirmed → Completed`, `Pending → Cancelled`, or `Confirmed → Cancelled`; `Completed` and `Cancelled` are terminal.
- Dish deletion is not exposed. “Unpublish” preserves order history and referential integrity.

## 4. Scope boundaries

Console order processing is in scope as a list/detail view and a complete synchronous operator state machine. It does not require a message queue, kitchen workflow orchestration, payment, refunds, delivery routing, or notifications. Email verification, multilingual UI, and live LLM integration are also outside this scope.

## 5. Non-functional targets

| Quality | Initial target |
|---|---|
| Menu read | p95 < 300 ms locally with warm cache; first useful UI < 2 s on a typical laptop |
| Order placement | p95 < 800 ms locally excluding client network; exactly one result per idempotency key |
| Capacity | 200 employees, burst-oriented 09:30–10:00 traffic; design target 50 order requests/s locally |
| Availability behavior | MySQL failure never produces a successful order; pending Redis reservations retry/compensate. Redis inventory failure fails ordering closed while ordinary reads may fall back to MySQL |
| Security | BCrypt, short access JWT, rotating HttpOnly refresh cookie, validation, prepared queries, centralized role checks, upload validation, rate limits |
| Accessibility | Keyboard navigation, labelled forms, semantic controls, visible focus, status not conveyed by color alone |
| Observability | Structured logs with request/user/order IDs, Actuator health/metrics, cache and order outcome metrics |

## 6. Architecture alternatives considered

1. **Recommended: modular monolith.** Fast local operation and transactions, while feature packages keep ownership explicit. It can later split reporting or recommendations behind existing ports.
2. **Simple controller-service-repository monolith.** Lowest initial code volume, but catalog, menu, ordering, and inventory policies tend to leak into a large service layer and become harder to test.
3. **Microservices.** Independent scaling is unnecessary for this load and would add deployment, distributed transaction, tracing, and contract overhead without business value.

