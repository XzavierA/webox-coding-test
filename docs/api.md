# Proposed API contract

JSON uses `camelCase`, date-only values use ISO `YYYY-MM-DD`, instants use ISO-8601 with an offset, and money is a two-decimal string such as `"22.50"`. All user-facing messages are English.

| Namespace | Audience | Authorization |
|---|---|---|
| `/api/v1/auth/**` | token lifecycle | Explicit public/authenticated rule per endpoint |
| `/api/v1/app/**` | employee application | `Bearer` JWT with `EMPLOYEE` role |
| `/api/v1/admin/**` | Console | `Bearer` JWT with `ADMIN` role |

## 1. Common behavior

- API calls use `Authorization: Bearer <short-lived-access-jwt>`.
- The rotating refresh token is an opaque value in a path-scoped `HttpOnly` cookie and is sent only to `/api/v1/auth/refresh` or logout. Access tokens remain in browser memory.
- List endpoints return `{items, page, size, totalItems, totalPages}`. Default/max sizes are 20/100.
- State-changing requests accept `X-Correlation-Id`; the server creates one if absent.
- Order placement requires `Idempotency-Key: <UUID>`.
- Errors use `application/problem+json`:

```json
{
  "type": "https://webox.local/problems/inventory-insufficient",
  "title": "Inventory is insufficient",
  "status": 409,
  "detail": "Some dishes no longer have the requested quantity.",
  "code": "INVENTORY_INSUFFICIENT",
  "correlationId": "01J...",
  "details": {
    "items": [{"dishId": 9, "name": "Classic Beef Burger", "requested": 2, "remaining": 1}]
  }
}
```

## 2. Authentication and current user

| Method | Path | Access | Purpose |
|---|---|---|---|
| POST | `/api/v1/auth/register` | public | Register employee, return access JWT, set refresh cookie |
| POST | `/api/v1/auth/login` | public | Return access JWT, set refresh cookie |
| POST | `/api/v1/auth/refresh` | refresh cookie | Rotate refresh family and return access JWT |
| POST | `/api/v1/auth/logout` | refresh cookie/access JWT | Revoke refresh family and clear cookie |
| GET | `/api/v1/auth/me` | access JWT | Current ID, email, role, display name |

Registration accepts `email`, `password`, and `displayName`; password is 8–72 characters with at least one ASCII letter and digit. Login errors intentionally use a generic `Invalid email or password.` response.

## 3. Menu, dishes, and stock

| Method | Path | Access | Purpose |
|---|---|---|---|
| GET | `/api/v1/app/menu?date=&q=&categories=&page=&size=&recommended=` | employee | Published daily menu with stock and preference metadata |
| GET | `/api/v1/app/menu/dishes/{dishId}?date=` | employee | Dish details and active customization options |
| GET | `/api/v1/app/menu/stock-stream?date=` | employee | SSE stock updates |

Menu items include `menuItemId`, `dish`, `basePrice`, `remainingStock`, `stockVersion`, `lowStock`, `soldOut`, and optional `preferenceMatches`. `q` is at most 50 characters; categories are allowlisted enums.

SSE event example:

```text
event: stock.changed
id: 18442
data: {"date":"2026-10-24","menuItemId":9,"remainingStock":2,"stockVersion":7}
```

## 4. Preferences and addresses

| Method | Path | Access | Purpose |
|---|---|---|---|
| GET | `/api/v1/app/preferences` | employee | Get dietary preferences |
| PUT | `/api/v1/app/preferences` | employee | Replace validated preference set |
| GET | `/api/v1/app/addresses` | employee | List saved addresses |
| POST | `/api/v1/app/addresses` | employee | Add address |
| PATCH | `/api/v1/app/addresses/{id}` | owner | Edit/default address |
| DELETE | `/api/v1/app/addresses/{id}` | owner | Soft-delete address |

Preference payload contains `allergens`, `preferredCategories`, `spiceLevel`, `tasteIntensity`, `budgetMin`, and `budgetMax`. Budget values are nullable decimal strings and min must not exceed max.

## 5. Quote and orders

| Method | Path | Access | Purpose |
|---|---|---|---|
| POST | `/api/v1/app/orders/quote` | employee | Reprice and validate cart; resolve slot |
| POST | `/api/v1/app/orders` | employee | Start idempotent reservation/order submission |
| GET | `/api/v1/app/order-submissions/{submissionId}` | owner | Poll asynchronous durable confirmation |
| GET | `/api/v1/app/orders?page=&size=&status=` | employee | Own order history |
| GET | `/api/v1/app/orders/{orderId}` | owner | Order details and snapshots |
| POST | `/api/v1/app/orders/{orderId}/cancel` | owner | Cancel a Pending order and restore stock |

Quote/place request shape:

```json
{
  "deliveryDate": "2026-10-24",
  "mealSlot": "LUNCH",
  "addressId": 12,
  "newAddress": null,
  "acceptedResolvedSlot": null,
  "items": [
    {"dishId": 4, "quantity": 2, "optionValueIds": [8, 11]}
  ]
}
```

The quote response contains authoritative line prices, `total`, `resolvedDeliveryDate`, `resolvedMealSlot`, `slotAdjusted`, warnings (`BUDGET_EXCEEDED`, etc.), unavailable items, and optional `existingOrder`. When a new address is supplied, it is validated and saved only as part of successful placement if `saveAddress=true`.

`POST /api/v1/app/orders` returns `201` when MySQL confirmation finishes in the wait budget, `202` with `submissionId`, `statusUrl`, and `Retry-After` while still processing, and `200` when replaying the completed same request/key. A reused key with a different request returns `409`. The UI never displays order success for `202`; it polls or safely retries with the same key. An active-order conflict includes its order ID so the UI can offer `View Existing Order`.

## 6. Console catalog and menus

All endpoints require `ADMIN`.

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/admin/dishes?q=&category=&published=&page=&size=` | Search/filter all dishes |
| POST | `/api/v1/admin/dishes` | Create English dish and option definitions |
| GET | `/api/v1/admin/dishes/{id}` | Edit model |
| PUT | `/api/v1/admin/dishes/{id}` | Replace editable data with optimistic version |
| PATCH | `/api/v1/admin/dishes/{id}/publication` | Publish/unpublish |
| POST | `/api/v1/admin/media/images` | Validated image upload; returns relative media URL |
| GET | `/api/v1/admin/menus/{date}` | Dated menu editor model |
| PUT | `/api/v1/admin/menus/{date}` | Create/update offered dishes and initial/current stock |
| POST | `/api/v1/admin/menus/{date}/publish` | Publish menu |

Dish writes contain `version`; stale edits return `409 OPTIMISTIC_LOCK_CONFLICT`. Menu stock cannot be reduced below already sold net quantity. Removing an offered item with order references is disallowed; it can be hidden from new browsing instead.

## 7. Admin order processing

All endpoints require `ADMIN`. Operator actions are synchronous commands; no operator queue is used.

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/admin/orders?q=&date=&mealSlot=&status=&page=&size=` | Search/filter all orders |
| GET | `/api/v1/admin/orders/{orderId}` | Order details plus status history |
| POST | `/api/v1/admin/orders/{orderId}/transitions` | Apply a permitted conditional transition |

Transition payload is `{targetStatus, expectedVersion, reason}`. Allowed transitions are `PENDING → CONFIRMED`, `PENDING → CANCELLED`, `CONFIRMED → COMPLETED`, and `CONFIRMED → CANCELLED`. Terminal states reject changes with `409 INVALID_ORDER_TRANSITION`; a stale version returns `409 OPTIMISTIC_LOCK_CONFLICT`. Every successful transition records admin actor, from/to status, reason, and timestamp.

## 8. Console dashboard

| Method | Path | Access | Purpose |
|---|---|---|---|
| GET | `/api/v1/admin/dashboard?date=&refresh=` | admin | One consistent dashboard snapshot |

Response shape:

```json
{
  "asOf": "2026-10-24T11:05:00+08:00",
  "date": "2026-10-24",
  "overview": {"totalOrders": 42, "revenue": "1188.50", "completed": 10, "pending": 20, "confirmed": 9, "cancelled": 3},
  "topDishes": [{"dishId": 1, "name": "Kung Pao Chicken", "quantity": 21}],
  "mealDistribution": [{"mealSlot": "LUNCH", "orders": 36}, {"mealSlot": "DINNER", "orders": 3}],
  "trend": [{"date": "2026-10-18", "orders": 31, "revenue": "850.00"}],
  "lowStock": [{"dishId": 9, "name": "Classic Beef Burger", "remainingStock": 2}]
}
```

## 9. Reserved AI extension

`POST /api/v1/app/recommendations/stream` is reserved behind `features.aiRecommendation`. It accepts `{prompt, date, mealSlot}` and returns SSE `status`, `recommendation`, `complete`, or `error` events. Recommendation events contain validated current dish cards and an English reason. When disabled the endpoint returns `404 FEATURE_NOT_AVAILABLE`, avoiding a non-functional UI control.

