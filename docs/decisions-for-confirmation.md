# Decisions for confirmation

The design is implementable with the recommended defaults below. Approval of the design without exceptions will be treated as approval of these defaults.

| # | Decision | Recommended default | Impact if changed |
|---:|---|---|---|
| 1 | Dashboard definition | “Today” means delivery date; total orders includes cancelled, revenue and rankings exclude cancelled | Changes report SQL and acceptance fixtures only |
| 2 | Cutoff auto-switch UX | Quote shows the new date/slot and requires explicit acknowledgement before placement | Silent switching is faster but risks surprising users |
| 3 | Console order processing | **Confirmed:** include list/detail and the complete audited state machine; no operator message queue | Adds conditional transitions, audit history, Admin UI/API/tests |
| 4 | Cart persistence | Per-user browser-local cart, no cross-device synchronization | Server cart adds tables/APIs/concurrency and cleanup work |
| 5 | Authentication | **Confirmed:** short access JWT in `Authorization`; rotating long-lived refresh token in `HttpOnly` cookie | Requires refresh rotation, reuse detection, logout revocation, and in-memory access-token handling |
| 6 | Product images | Visually select and copy the best nine supplied JPEGs into stable project seed media during implementation | The provisional numeric mapping should not be relied on yet |
| 7 | Local infrastructure | Support the requested local root/no-password MySQL and local Redis; optional Compose as convenience | Compose-only would conflict with an existing local service workflow |
| 8 | Admin demo account | Local-profile bootstrap from environment passwords; no credential/hash embedded in SQL | A committed fixed demo password is simpler but weaker hygiene |
| 9 | AI | Build the extension port/feature flag only; do not integrate an LLM in the required scope | Live AI requires provider/key/model and adds failure/security testing |

## Resolved review additions

- Employee and Admin APIs have separate URI namespaces.
- One JWT authentication filter builds the authenticated actor context; URI rules and method-level checks enforce employee/admin authorization.
- Redis controls atomic stock reservations. MySQL confirmation is asynchronous internally, but user-visible order success waits for durable MySQL completion.
- Console order processing includes conditional state transitions and history without introducing an operator queue.

## Approval gate

Please confirm the design and the defaults above, or list the decision numbers to change. No frontend/backend scaffolding, database creation, or seed-asset copying will be performed before that confirmation.

