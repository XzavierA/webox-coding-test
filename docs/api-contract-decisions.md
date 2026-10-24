# API contract execution decisions

`docs/openapi.yaml` is the binding frontend/backend contract. `docs/api.md` explains intent; if wording differs, OpenAPI field names and shapes win until both documents are deliberately revised together.

## Completeness gate

| Boundary | Binding decision |
|---|---|
| Namespaces | Employee `/api/v1/app/**`; Console `/api/v1/admin/**`; tokens `/api/v1/auth/**` |
| Auth transport | `Authorization: Bearer <JWT>`; 15-minute access token in memory only |
| Refresh | `webox_refresh` opaque rotating cookie; 14 days; `HttpOnly`; `SameSite=Strict`; `Path=/api/v1/auth`; `Secure` except local |
| Local origins | `http://localhost:5173`; credentials allowed only for refresh/logout; allowed headers include `Authorization`, `Content-Type`, `Idempotency-Key`, `X-Correlation-Id` |
| Cookie CSRF | Refresh/logout reject missing or non-allowlisted `Origin`; state-changing App/Admin APIs do not authenticate by cookie |
| Actor | JWT filter creates user ID and role; client-supplied user/role fields are never accepted |
| Money | JSON string with exactly two decimals; frontend converts to integer fen for arithmetic; backend uses `BigDecimal` |
| Time | Date `YYYY-MM-DD`; instant ISO-8601 with offset; business clock `Asia/Shanghai` |
| Pagination | Zero-based `page`; default 20; maximum 100; response carries `page,size,totalItems,totalPages,items` |
| Errors | `application/problem+json` with `code`, English `detail`, `correlationId`, optional `fieldErrors/details` |
| Order request | Exactly one of `addressId/newAddress`; total quantity ≤ 5; server reprices all lines |
| Cutoff change | Quote returns resolved date/slot; placement must echo accepted values or receives `422 ORDER_SLOT_ADJUSTED` |
| Idempotency | UUID `Idempotency-Key`; same hash replays; different hash returns `409 IDEMPOTENCY_KEY_REUSED` |
| Async inventory | `201` means MySQL-confirmed; `202` means keep polling; UI cannot show success or clear cart on `202` |
| State machine | Employee cancels Pending; Admin confirms Pending, completes Confirmed, or cancels Pending/Confirmed with expected version |
| Stock SSE | Fetch-based SSE with bearer header; `stock.changed` payload contains date/menuItemId/remainingStock/stockVersion |
| Dashboard | Delivery-date based; total includes cancelled; revenue/rank/distribution exclude cancelled; trend has seven dates |
| AI | Provider seam only; no live AI endpoint is required in this implementation |

## Local integration endpoints

```text
Frontend: http://localhost:5173
Backend:  http://localhost:8080
MySQL:    127.0.0.1:3306 / root / empty password / database webox
Redis:    127.0.0.1:6379 / no authentication
```

The verified host currently has JDK 17.0.12 installed (although JDK 21 is the shell default), MySQL responds as `root@localhost`, and Redis responds with `PONG`. Backend commands must set `JAVA_HOME=$(/usr/libexec/java_home -v 17)` so compilation proves Java 17 compatibility.

## Parallel ownership

- Frontend agent writes only `frontend/` and its report file.
- Backend agent writes only `backend/`, runtime media copied under `backend/`, and its report file.
- Neither agent changes `docs/openapi.yaml` or shared design documents. Contract ambiguities are reported to the coordinator rather than resolved unilaterally.
- The coordinator owns cross-stack verification, contract adjudication, shared documentation, and final acceptance.

