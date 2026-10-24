# WeBox backend

The backend is a Java 17 Spring Boot service. Flyway creates the MySQL schema and seeds English menu data. MySQL is authoritative; Redis stores refresh-token state, menu cache entries, inventory reservation stock and Streams, short dashboard caches, and outbox event fan-out.

## Local run

Start MySQL and Redis locally. Create the `webox` MySQL database, then export the values from `.env.example` into your shell and start the app:

```sh
export WEBOX_DB_URL='jdbc:mysql://127.0.0.1:3306/webox?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
export WEBOX_DB_USER=root
export WEBOX_DB_PASSWORD=''
export WEBOX_JWT_SECRET='replace-this-with-a-random-secret-of-at-least-32-bytes'
export WEBOX_DEMO_ADMIN_PASSWORD='choose-a-local-admin-password'
export WEBOX_DEMO_EMPLOYEE_PASSWORD='choose-a-local-employee-password'
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw spring-boot:run
```

Optional demo accounts are created only when their password environment variables are set. Existing accounts are left intact. Passwords are BCrypt hashed; the application does not log their values. The local profile permits the frontend origin `http://localhost:5173` and does not mark refresh cookies Secure. Do not reuse these local settings for a deployed service.

The OpenAPI document is available at `http://localhost:8080/v3/api-docs`; Swagger UI is at `http://localhost:8080/swagger-ui/index.html`.

## API areas

- `/api/v1/auth/**`: registration, login, refresh-token rotation, logout, and current user.
- `/api/v1/app/**`: published menus, dietary preferences, saved addresses, quotes, orders, cancellation, and stock SSE.
- `/api/v1/admin/**`: dish and menu management, media upload, order transitions, and dashboard reports.

App and Admin routes require an access JWT in the `Authorization: Bearer ...` header. Admin routes require the Admin role. Refresh and logout require the allowlisted `Origin` header and use the `webox_refresh` HttpOnly cookie.

Uploaded images are stored under `WEBOX_MEDIA_ROOT` (default `./uploads`) and served through `/media/images/**`. Menu metadata uses a versioned five-minute Redis cache; inventory values are read from MySQL and refreshed in the response. Successful reservations atomically decrement date-scoped Redis stock and append a Redis Stream event, then confirm against MySQL before returning an order. MySQL outbox events feed stock SSE and invalidate dashboard cache entries.

## Verification

The test profile connects to the dedicated `webox_test` schema and local Redis at `127.0.0.1:6379`:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./mvnw verify
```

Tests use real MySQL and Redis services; no H2 replacement is configured. Never point the `test` profile at an unknown or production schema.
