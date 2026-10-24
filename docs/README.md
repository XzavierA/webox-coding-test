# WeBox design documentation

This directory is the review package for the proposed WeBox implementation. It is a design-only deliverable: no application, database, or infrastructure has been initialized yet.

## Recommended reading order

1. [Requirements and scope](requirements-and-scope.md) — requirement traceability, assumptions, and exclusions
2. [System architecture](architecture.md) — architecture choice, modules, data flow, reliability, and AI extension seam
3. [Backend design](backend-design.md) — Spring Boot structure, concurrency, caching, security, and tests
4. [Frontend design](frontend-design.md) — React suitability, application structure, responsive UX, and tests
5. [API contract](api.md) — proposed HTTP/SSE endpoints and error conventions
   - [Binding OpenAPI 3.1 contract](openapi.yaml)
   - [API execution decisions](api-contract-decisions.md)
6. [Database schema](database/schema.sql) — executable MySQL 8 DDL
7. [Seed data plan](database/seed-data.md) — English demo data and product-image mapping
8. [Local initialization and delivery](setup-and-delivery.md) — proposed prerequisites, configuration, startup, and delivery checklist
9. [Development plan](development-plan.md) — phased implementation and acceptance gates
10. [Appendix A score assessment](appendix-a-score-assessment.md) — projected score, evidence gates, and deduction risks
11. [Decisions for confirmation](decisions-for-confirmation.md) — assumptions and resolved review decisions

## Decision summary

- **Architecture:** modular monolith, not microservices. It preserves clear domain boundaries without creating distributed-system overhead for roughly 200 employees.
- **Frontend:** React + TypeScript is suitable. It supports a responsive employee SPA and data-heavy Console with one component model and a mature testing ecosystem.
- **Backend:** Java 17 + Spring Boot, package-by-feature, with application/domain/infrastructure boundaries inside each feature.
- **Persistence:** MySQL 8 is the system of record. Money is stored as `DECIMAL(10,2)` and calculated with Java `BigDecimal`.
- **API boundaries:** employee APIs use `/api/v1/app/**`, Console APIs use `/api/v1/admin/**`, and authentication uses `/api/v1/auth/**`.
- **Authentication:** short-lived access JWT in the `Authorization` header plus a rotating long-lived refresh token in a secure `HttpOnly` cookie.
- **Redis:** hot-menu cache, rate limiting, refresh-token state, atomic inventory reservation, Redis Streams, and stock event fan-out.
- **Inventory:** Redis Lua serializes hot-stock reservations and appends a Stream event atomically. An asynchronous idempotent consumer confirms the change in MySQL; no order is reported successful before MySQL confirmation.
- **Real time:** Server-Sent Events (SSE) push stock changes; reconnect/focus revalidation makes event loss self-healing.
- **AI readiness:** a provider-neutral recommendation port and SSE contract are reserved, but Tier 3 AI is not in the requested implementation scope.
- **Console orders:** no message queue is required for operator actions, but the Admin API implements and audits the complete `Pending → Confirmed → Completed` / cancellation state machine.
- **User-facing language:** English only. Internal code and documentation may use English; this review explanation is in Chinese.

