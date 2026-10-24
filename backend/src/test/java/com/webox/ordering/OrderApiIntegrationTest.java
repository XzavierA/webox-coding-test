package com.webox.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.TreeMap;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import com.webox.platform.outbox.OutboxPublisher;
import com.webox.ordering.api.InventoryReservationRecovery;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OrderApiIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired StringRedisTemplate redis;
    @Autowired OutboxPublisher outbox;
    @Autowired InventoryReservationRecovery recovery;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void quoteRepricesOptionsAndOrderPlacementIsIdempotentAndPersistsSnapshots() {
        String token = register();
        long addressId = createAddress(token);
        LocalDate menuDate = LocalDate.now(ZoneId.of("Asia/Shanghai"))
                .plusDays(2L + Math.floorMod(UUID.randomUUID().hashCode(), 20_000));
        createPublishedMenu(menuDate, 4, 20);
        Map<String, Object> request = request(menuDate, addressId, List.of(4));
        String streamKey = "webox:inventory:{" + menuDate + "}:events";
        long priorStreamSize = redis.opsForStream().size(streamKey) == null ? 0L : redis.opsForStream().size(streamKey);

        ResponseEntity<Map> quote = post("/api/v1/app/orders/quote", request, token, null);
        assertThat(quote.getStatusCode().value()).isEqualTo(200);
        assertThat(quote.getBody()).containsEntry("resolvedDeliveryDate", menuDate.toString())
                .containsEntry("resolvedMealSlot", "LUNCH").containsEntry("total", "26.50");
        request.put("acceptedResolvedDeliveryDate", menuDate.toString()); request.put("acceptedResolvedMealSlot", "LUNCH");
        String key = UUID.randomUUID().toString();
        ResponseEntity<Map> created = post("/api/v1/app/orders", request, token, key);
        assertThat(created.getStatusCode().value()).as(String.valueOf(created.getBody())).isEqualTo(201);
        assertThat(created.getBody()).containsEntry("status", "PENDING").containsEntry("total", "26.50");
        long orderId = ((Number) created.getBody().get("id")).longValue();
        String reservationId = jdbc.queryForObject("SELECT reservation_id FROM idempotency_record WHERE order_id=?", String.class, orderId);
        String reservationKey = "webox:inventory:{" + menuDate + "}:reservations";
        assertThat(redis.opsForHash().get(reservationKey, reservationId)).isEqualTo("CONFIRMED");
        assertThat(redis.opsForStream().groups(streamKey).stream().map(group -> group.groupName()).toList())
                .contains("webox-order-confirmation");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_item_option WHERE order_item_id=(SELECT id FROM order_item WHERE order_id=?)", Integer.class, orderId)).isEqualTo(1);
        ResponseEntity<Map> replay = post("/api/v1/app/orders", request, token, key);
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody().get("id")).isEqualTo(created.getBody().get("id"));
        assertThat(redis.opsForStream().size(streamKey)).isEqualTo(priorStreamSize + 1);
        MapRecord<String, Object, Object> originalReservation = redis.opsForStream().range(streamKey, Range.unbounded()).stream()
                .filter(record -> reservationId.equals(record.getValue().get("reservationId"))).findFirst().orElseThrow();
        String quantitySpec = originalReservation.getValue().get("quantities").toString();
        long stockBeforeDuplicate = jdbc.queryForObject("SELECT remaining_stock FROM daily_menu_item WHERE dish_id=4 AND daily_menu_id=(SELECT id FROM daily_menu WHERE menu_date=?)", Long.class, menuDate);
        redis.opsForStream().add(streamKey, Map.of("type", "RESERVED", "reservationId", reservationId,
                "date", menuDate.toString(), "quantities", quantitySpec));
        recovery.recoverPendingReservations();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE order_id=? AND movement_type='ORDER_DEDUCT'", Integer.class, orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT remaining_stock FROM daily_menu_item WHERE dish_id=4 AND daily_menu_id=(SELECT id FROM daily_menu WHERE menu_date=?)", Long.class, menuDate)).isEqualTo(stockBeforeDuplicate);
        Map<String, Object> changed = request(menuDate, addressId, List.of(5));
        changed.put("acceptedResolvedDeliveryDate", menuDate.toString()); changed.put("acceptedResolvedMealSlot", "LUNCH");
        assertThat(post("/api/v1/app/orders", changed, token, key).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void completedIdempotencyReplayPrecedesCurrentCatalogPlanning() {
        String token = register();
        long addressId = createAddress(token);
        LocalDate menuDate = LocalDate.now(ZoneId.of("Asia/Shanghai"))
                .plusDays(2L + Math.floorMod(UUID.randomUUID().hashCode(), 20_000));
        createPublishedMenu(menuDate, 4, 5);
        Map<String, Object> request = request(menuDate, addressId, List.of(4));
        request.put("acceptedResolvedDeliveryDate", menuDate.toString());
        request.put("acceptedResolvedMealSlot", "LUNCH");
        String key = UUID.randomUUID().toString();

        ResponseEntity<Map> created = post("/api/v1/app/orders", request, token, key);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        long orderId = ((Number) created.getBody().get("id")).longValue();
        try {
            jdbc.update("UPDATE dish SET published=FALSE,version=version+1 WHERE id=4");
            ResponseEntity<Map> replay = post("/api/v1/app/orders", request, token, key);
            assertThat(replay.getStatusCode().value()).isEqualTo(200);
            assertThat(((Number) replay.getBody().get("id")).longValue()).isEqualTo(orderId);
        } finally {
            jdbc.update("UPDATE dish SET published=TRUE,version=version+1 WHERE id=4");
        }
    }

    @Test
    void processingIdempotencyReplayReturnsAcceptedSubmissionForPolling() throws Exception {
        String token = register();
        long addressId = createAddress(token);
        long userId = ((Number) getMe(token).get("id")).longValue();
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(3);
        Map<String, Object> body = request(date, addressId, List.of());
        body.put("items", List.of(Map.of("dishId", 1, "quantity", 1, "optionValueIds", List.of())));
        String canonical = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(new TreeMap<>(body));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        String key = UUID.randomUUID().toString();
        String submissionId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO idempotency_record(user_id,idempotency_key,request_hash,reservation_id,state,expires_at) VALUES(?,?,?,?,'PROCESSING',DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 24 HOUR))",
                userId, key, hash, submissionId);

        @SuppressWarnings("unchecked") ResponseEntity<Map>[] captured = new ResponseEntity[1];
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT state FROM idempotency_record WHERE user_id=? AND idempotency_key=? FOR UPDATE", String.class, userId, key);
            captured[0] = post("/api/v1/app/orders", body, token, key);
        });
        ResponseEntity<Map> response = captured[0];

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(response.getBody()).containsEntry("state", "PROCESSING").containsEntry("submissionId", submissionId);
        assertThat(response.getBody().get("statusUrl")).isEqualTo("/api/v1/app/order-submissions/" + submissionId);
    }

    @Test
    void retryResumesProcessingSubmissionWithExistingRedisReservationWithoutDeductingTwice() throws Exception {
        String token = register();
        long addressId = createAddress(token);
        long userId = ((Number) getMe(token).get("id")).longValue();
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai"))
                .plusDays(600L + Math.floorMod(UUID.randomUUID().hashCode(), 20_000));
        jdbc.update("INSERT INTO daily_menu(menu_date,status,publication_version,published_at) VALUES(?,'PUBLISHED',1,CURRENT_TIMESTAMP(3))", date);
        long menuId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO daily_menu_item(daily_menu_id,dish_id,initial_stock,remaining_stock,visible) VALUES(?,1,5,5,TRUE)", menuId);
        long menuItemId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Map<String, Object> body = request(date, addressId, List.of());
        body.put("items", List.of(Map.of("dishId", 1, "quantity", 2, "optionValueIds", List.of())));
        String hash = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(new TreeMap<>(body));
        hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(hash.getBytes(StandardCharsets.UTF_8)));
        String key = UUID.randomUUID().toString();
        String reservationId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO idempotency_record(user_id,idempotency_key,request_hash,reservation_id,state,expires_at) VALUES(?,?,?,?,'PROCESSING',DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 24 HOUR))",
                userId, key, hash, reservationId);
        String stockKey = "webox:inventory:{" + date + "}:stock";
        String reservationKey = "webox:inventory:{" + date + "}:reservations";
        String streamKey = "webox:inventory:{" + date + "}:events";
        redis.opsForHash().put(stockKey, Long.toString(menuItemId), "3");
        redis.opsForHash().put(reservationKey, reservationId, "RESERVED|" + menuItemId + ":2");
        redis.opsForStream().add(streamKey, Map.of("type", "RESERVED", "reservationId", reservationId,
                "date", date.toString(), "quantities", menuItemId + ":2", "userId", Long.toString(userId),
                "idempotencyKey", key, "requestHash", hash));

        ResponseEntity<Map> resumed = post("/api/v1/app/orders", body, token, key);

        assertThat(resumed.getStatusCode().value()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT state FROM idempotency_record WHERE user_id=? AND idempotency_key=?", String.class, userId, key)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT remaining_stock FROM daily_menu_item WHERE id=?", Integer.class, menuItemId)).isEqualTo(3);
        assertThat(redis.opsForHash().get(stockKey, Long.toString(menuItemId))).isEqualTo("3");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE order_id=? AND movement_type='ORDER_DEDUCT'", Integer.class, resumed.getBody().get("id"))).isEqualTo(1);
    }

    private Map<String, Object> getMe(String token) {
        return http.exchange("/api/v1/auth/me", HttpMethod.GET, bearer(token), Map.class).getBody();
    }

    @Test
    void quoteRequiresExactlyOneOwnedAddressChoice() {
        String token = register();
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("deliveryDate", LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(2).toString());
        request.put("mealSlot", "LUNCH"); request.put("items", List.of(Map.of("dishId", 1, "quantity", 1, "optionValueIds", List.of())));
        ResponseEntity<Map> missing = post("/api/v1/app/orders/quote", request, token, null);
        assertThat(missing.getStatusCode().value()).isEqualTo(400);
        long addressId = createAddress(token);
        request.put("addressId", addressId);
        request.put("newAddress", Map.of("label", "Home", "addressText", "1 Example Road", "isDefault", false, "save", false));
        assertThat(post("/api/v1/app/orders/quote", request, token, null).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void employeeCancellationRestoresMySqlAndRedisStockExactlyOnce() {
        String token = register(); long addressId = createAddress(token);
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai"))
                .plusDays(3L + Math.floorMod(UUID.randomUUID().hashCode(), 20_000));
        jdbc.update("INSERT INTO daily_menu(menu_date,status,publication_version,published_at) VALUES(?,'PUBLISHED',1,CURRENT_TIMESTAMP(3))", date);
        long dailyMenuId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO daily_menu_item(daily_menu_id,dish_id,initial_stock,remaining_stock,visible) VALUES(?,1,4,4,TRUE)", dailyMenuId);
        long menuItemId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        int before = jdbc.queryForObject("SELECT remaining_stock FROM daily_menu_item WHERE id=?", Integer.class, menuItemId);
        Map<String, Object> order = request(date, addressId, List.of()); order.put("items", List.of(Map.of("dishId", 1, "quantity", 1, "optionValueIds", List.of())));
        ResponseEntity<Map> created = post("/api/v1/app/orders", order, token, UUID.randomUUID().toString());
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        long orderId = ((Number) created.getBody().get("id")).longValue();
        assertThat(jdbc.queryForObject("SELECT remaining_stock FROM daily_menu_item WHERE id=?", Integer.class, menuItemId)).isEqualTo(before - 1);

        ResponseEntity<Map> cancelled = post("/api/v1/app/orders/" + orderId + "/cancel", Map.of("expectedVersion", 0), token, null);
        assertThat(cancelled.getStatusCode().value()).isEqualTo(200);
        assertThat(cancelled.getBody()).containsEntry("status", "CANCELLED");
        assertThat(jdbc.queryForObject("SELECT remaining_stock FROM daily_menu_item WHERE id=?", Integer.class, menuItemId)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE order_id=? AND movement_type='CANCEL_RESTORE'", Integer.class, orderId)).isEqualTo(1);
        assertThat(post("/api/v1/app/orders/" + orderId + "/cancel", Map.of("expectedVersion", 0), token, null).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void administratorTransitionsOrderAndDashboardUsesConfirmedDatabaseData() {
        String token = register(); long addressId = createAddress(token); String admin = adminToken();
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(5L + Math.floorMod(UUID.randomUUID().hashCode(), 20_000));
        jdbc.update("INSERT INTO daily_menu(menu_date,status,publication_version,published_at) VALUES(?,'PUBLISHED',1,CURRENT_TIMESTAMP(3))", date);
        long menuId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO daily_menu_item(daily_menu_id,dish_id,initial_stock,remaining_stock,visible) VALUES(?,1,1,1,TRUE)", menuId);
        Map<String, Object> orderRequest = request(date, addressId, List.of());
        orderRequest.put("items", List.of(Map.of("dishId", 1, "quantity", 1, "optionValueIds", List.of())));
        ResponseEntity<Map> created = post("/api/v1/app/orders", orderRequest, token, UUID.randomUUID().toString());
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        long id = ((Number) created.getBody().get("id")).longValue();
        ResponseEntity<Map> confirmed = post("/api/v1/admin/orders/" + id + "/transitions", Map.of("targetStatus", "CONFIRMED", "expectedVersion", 0), admin, null);
        assertThat(confirmed.getStatusCode().value()).isEqualTo(200);
        assertThat(confirmed.getBody()).containsEntry("status", "CONFIRMED").containsEntry("version", 1);
        ResponseEntity<Map> completed = post("/api/v1/admin/orders/" + id + "/transitions", Map.of("targetStatus", "COMPLETED", "expectedVersion", 1), admin, null);
        assertThat(completed.getStatusCode().value()).isEqualTo(200);
        assertThat(completed.getBody()).containsEntry("status", "COMPLETED").containsEntry("version", 2);

        ResponseEntity<Map> dashboard = http.exchange("/api/v1/admin/dashboard?date=" + date, HttpMethod.GET, bearer(admin), Map.class);
        assertThat(dashboard.getStatusCode().value()).isEqualTo(200);
        Map overview = (Map) dashboard.getBody().get("overview");
        assertThat(overview).containsEntry("totalOrders", 1).containsEntry("completed", 1).containsEntry("revenue", "22.00");
        assertThat((List<?>) dashboard.getBody().get("trend")).hasSize(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_status_history WHERE order_id=?", Integer.class, id)).isEqualTo(3);
    }

    @Test
    void placingOrderInvalidatesCachedDashboardSnapshot() {
        String employee = register();
        long addressId = createAddress(employee);
        String admin = adminToken();
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai"))
                .plusDays(30L + Math.floorMod(UUID.randomUUID().hashCode(), 20_000));
        jdbc.update("INSERT INTO daily_menu(menu_date,status,publication_version,published_at) VALUES(?,'PUBLISHED',1,CURRENT_TIMESTAMP(3))", date);
        long menuId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO daily_menu_item(daily_menu_id,dish_id,initial_stock,remaining_stock,visible) VALUES(?,1,2,2,TRUE)", menuId);

        String cacheKey = "webox:dashboard:" + date;
        ResponseEntity<Map> before = http.exchange("/api/v1/admin/dashboard?date=" + date, HttpMethod.GET, bearer(admin), Map.class);
        assertThat(((Map<?, ?>) before.getBody().get("overview")).get("totalOrders")).isEqualTo(0);
        assertThat(redis.hasKey(cacheKey)).isTrue();
        Map<String, Object> order = request(date, addressId, List.of());
        order.put("items", List.of(Map.of("dishId", 1, "quantity", 1, "optionValueIds", List.of())));
        assertThat(post("/api/v1/app/orders", order, employee, UUID.randomUUID().toString()).getStatusCode().value()).isEqualTo(201);

        outbox.publishBatch();

        assertThat(redis.hasKey(cacheKey)).isFalse();
        ResponseEntity<Map> after = http.exchange("/api/v1/admin/dashboard?date=" + date, HttpMethod.GET, bearer(admin), Map.class);
        assertThat(((Map<?, ?>) after.getBody().get("overview")).get("totalOrders")).isEqualTo(1);
    }

    @Test
    void concurrentOrdersForTheLastServingConfirmExactlyOneReservation() throws Exception {
        String firstToken = register(); long firstAddress = createAddress(firstToken);
        String secondToken = register(); long secondAddress = createAddress(secondToken);
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai"))
                .plusDays(10L + Math.floorMod(UUID.randomUUID().hashCode(), 20_000));
        jdbc.update("INSERT INTO daily_menu(menu_date,status,publication_version,published_at) VALUES(?,'PUBLISHED',1,CURRENT_TIMESTAMP(3))", date);
        long dailyMenuId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO daily_menu_item(daily_menu_id,dish_id,initial_stock,remaining_stock,visible) VALUES(?,1,1,1,TRUE)", dailyMenuId);
        long menuItemId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> submitAtBarrier(ready, start, date, firstAddress, firstToken));
            Future<Integer> second = pool.submit(() -> submitAtBarrier(ready, start, date, secondAddress, secondToken));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            List<Integer> statuses = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        } finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT remaining_stock FROM daily_menu_item WHERE id=?", Integer.class, menuItemId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE daily_menu_item_id=? AND movement_type='ORDER_DEDUCT'", Integer.class, menuItemId)).isEqualTo(1);
        assertThat(redis.opsForHash().get("webox:inventory:{" + date + "}:stock", Long.toString(menuItemId))).isEqualTo("0");
        assertThat(redis.opsForStream().size("webox:inventory:{" + date + "}:events")).isEqualTo(1L);
    }

    @Test
    void multiItemRedisReservationRejectsAtomicallyWhenAnyItemIsShort() {
        String token = register();
        long addressId = createAddress(token);
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai"))
                .plusDays(50L + Math.floorMod(UUID.randomUUID().hashCode(), 20_000));
        jdbc.update("INSERT INTO daily_menu(menu_date,status,publication_version,published_at) VALUES(?,'PUBLISHED',1,CURRENT_TIMESTAMP(3))", date);
        long menuId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO daily_menu_item(daily_menu_id,dish_id,initial_stock,remaining_stock,visible) VALUES(?,1,3,3,TRUE)", menuId);
        long firstItem = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO daily_menu_item(daily_menu_id,dish_id,initial_stock,remaining_stock,visible) VALUES(?,4,3,3,TRUE)", menuId);
        long secondItem = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        String stockKey = "webox:inventory:{" + date + "}:stock";
        redis.opsForHash().put(stockKey, Long.toString(firstItem), "3");
        redis.opsForHash().put(stockKey, Long.toString(secondItem), "1");
        Map<String, Object> order = new java.util.LinkedHashMap<>();
        order.put("deliveryDate", date.toString()); order.put("mealSlot", "LUNCH"); order.put("addressId", addressId);
        order.put("items", List.of(Map.of("dishId", 1, "quantity", 2, "optionValueIds", List.of()),
                Map.of("dishId", 4, "quantity", 2, "optionValueIds", List.of(4))));

        ResponseEntity<Map> response = post("/api/v1/app/orders", order, token, UUID.randomUUID().toString());

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(redis.opsForHash().get(stockKey, Long.toString(firstItem))).isEqualTo("3");
        assertThat(redis.opsForHash().get(stockKey, Long.toString(secondItem))).isEqualTo("1");
        assertThat(jdbc.queryForObject("SELECT remaining_stock FROM daily_menu_item WHERE id IN (?,?) ORDER BY id LIMIT 1", Integer.class, firstItem, secondItem)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT SUM(remaining_stock) FROM daily_menu_item WHERE id IN (?,?)", Integer.class, firstItem, secondItem)).isEqualTo(6);
    }

    @Test
    void missingRedisInventoryIsRebuiltFromMySqlBeforeReservation() {
        String token = register();
        long addressId = createAddress(token);
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai"))
                .plusDays(80L + Math.floorMod(UUID.randomUUID().hashCode(), 20_000));
        jdbc.update("INSERT INTO daily_menu(menu_date,status,publication_version,published_at) VALUES(?,'PUBLISHED',1,CURRENT_TIMESTAMP(3))", date);
        long menuId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO daily_menu_item(daily_menu_id,dish_id,initial_stock,remaining_stock,visible) VALUES(?,1,5,5,TRUE)", menuId);
        long menuItemId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        String stockKey = "webox:inventory:{" + date + "}:stock";
        redis.delete(stockKey);
        Map<String, Object> order = request(date, addressId, List.of());
        order.put("items", List.of(Map.of("dishId", 1, "quantity", 2, "optionValueIds", List.of())));

        ResponseEntity<Map> response = post("/api/v1/app/orders", order, token, UUID.randomUUID().toString());

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT remaining_stock FROM daily_menu_item WHERE id=?", Integer.class, menuItemId)).isEqualTo(3);
        assertThat(redis.opsForHash().get(stockKey, Long.toString(menuItemId))).isEqualTo("3");
    }

    @Test
    void mysqlFailureAfterReservationLeavesTerminalFailedSubmissionAndCompensatesStock() {
        String token = register();
        long addressId = createAddress(token);
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai"))
                .plusDays(100L + Math.floorMod(UUID.randomUUID().hashCode(), 20_000));
        jdbc.update("INSERT INTO daily_menu(menu_date,status,publication_version,published_at) VALUES(?,'PUBLISHED',1,CURRENT_TIMESTAMP(3))", date);
        long menuId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO daily_menu_item(daily_menu_id,dish_id,initial_stock,remaining_stock,visible) VALUES(?,1,4,4,TRUE)", menuId);
        long menuItemId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        String trigger = "fail_order_item_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON order_item FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test failure'");
        String key = UUID.randomUUID().toString();
        Map<String, Object> order = request(date, addressId, List.of());
        order.put("items", List.of(Map.of("dishId", 1, "quantity", 1, "optionValueIds", List.of())));
        ResponseEntity<Map> failed;
        try {
            failed = post("/api/v1/app/orders", order, token, key);
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger);
        }

        assertThat(failed.getStatusCode().value()).isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key=?", Integer.class, key)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM idempotency_record WHERE idempotency_key=?", String.class, key)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT remaining_stock FROM daily_menu_item WHERE id=?", Integer.class, menuItemId)).isEqualTo(4);
        assertThat(redis.opsForHash().get("webox:inventory:{" + date + "}:reservations",
                jdbc.queryForObject("SELECT reservation_id FROM idempotency_record WHERE idempotency_key=?", String.class, key))).isEqualTo("COMPENSATED");
        assertThat(post("/api/v1/app/orders", order, token, key).getStatusCode().value()).isEqualTo(409);
    }

    private int submitAtBarrier(CountDownLatch ready, CountDownLatch start, LocalDate date, long addressId, String token) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("concurrency barrier timed out");
        Map<String, Object> order = request(date, addressId, List.of());
        order.put("items", List.of(Map.of("dishId", 1, "quantity", 1, "optionValueIds", List.of())));
        return post("/api/v1/app/orders", order, token, UUID.randomUUID().toString()).getStatusCode().value();
    }

    private Map<String, Object> request(LocalDate date, long addressId, List<Integer> optionIds) {
        return new java.util.LinkedHashMap<>(Map.of("deliveryDate", date.toString(), "mealSlot", "LUNCH", "addressId", addressId,
                "items", List.of(Map.of("dishId", 4, "quantity", 1, "optionValueIds", optionIds))));
    }
    private long createPublishedMenu(LocalDate date, long dishId, int stock) {
        jdbc.update("INSERT INTO daily_menu(menu_date,status,publication_version,published_at) VALUES(?,'PUBLISHED',1,CURRENT_TIMESTAMP(3))", date);
        long menuId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO daily_menu_item(daily_menu_id,dish_id,initial_stock,remaining_stock,visible) VALUES(?,?,?,?,TRUE)", menuId, dishId, stock, stock);
        return menuId;
    }
    private String register() {
        ResponseEntity<Map> response = http.postForEntity("/api/v1/auth/register", Map.of("email", "order-" + UUID.randomUUID() + "@example.test",
                "password", "Password123", "displayName", "Order Tester"), Map.class);
        return response.getBody().get("accessToken").toString();
    }
    private long createAddress(String token) {
        ResponseEntity<Map> response = post("/api/v1/app/addresses", Map.of("label", "Home", "addressText", "1 Example Road", "isDefault", true), token, null);
        return ((Number) response.getBody().get("id")).longValue();
    }
    private String adminToken() {
        String email = "admin-" + UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO app_user(email,password_hash,display_name,role,enabled) VALUES(?,?,?,'ADMIN',TRUE)", email, passwords.encode("AdminPass123"), "Order Admin");
        ResponseEntity<Map> response = http.postForEntity("/api/v1/auth/login", Map.of("email", email, "password", "AdminPass123"), Map.class);
        return response.getBody().get("accessToken").toString();
    }
    private HttpEntity<Void> bearer(String token) { HttpHeaders headers = new HttpHeaders(); headers.setBearerAuth(token); return new HttpEntity<>(headers); }
    private ResponseEntity<Map> post(String path, Object body, String token, String key) {
        HttpHeaders headers = new HttpHeaders(); headers.setBearerAuth(token);
        if (key != null) headers.set("Idempotency-Key", key);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }
}
