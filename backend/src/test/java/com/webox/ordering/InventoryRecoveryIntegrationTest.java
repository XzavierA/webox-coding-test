package com.webox.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import com.webox.ordering.api.InventoryReservationRecovery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "webox.inventory.recovery-poll-ms=600000", "webox.inventory.missing-record-grace-ms=0"})
@ActiveProfiles("test")
class InventoryRecoveryIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired InventoryReservationRecovery recovery;
    @Autowired PasswordEncoder passwords;

    @Test
    void crashBeforeMysqlConfirmationCompensatesReservationOnceOnStreamRecovery() {
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai"))
                .plusDays(500L + Math.floorMod(UUID.randomUUID().hashCode(), 20_000));
        jdbc.update("INSERT INTO daily_menu(menu_date,status,publication_version) VALUES(?,'DRAFT',1)", date);
        long menuId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO daily_menu_item(daily_menu_id,dish_id,initial_stock,remaining_stock,visible) VALUES(?,1,5,5,TRUE)", menuId);
        long itemId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        String email = "inventory-crash-" + UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO app_user(email,password_hash,display_name,role,enabled) VALUES(?,?,?,'EMPLOYEE',TRUE)", email, passwords.encode("Password123"), "Crash Recovery");
        long userId = jdbc.queryForObject("SELECT id FROM app_user WHERE email=?", Long.class, email);
        String reservationId = UUID.randomUUID().toString();
        String idempotencyKey = UUID.randomUUID().toString();
        String requestHash = "a".repeat(64);
        jdbc.update("INSERT INTO idempotency_record(user_id,idempotency_key,request_hash,reservation_id,state,expires_at) " +
                        "VALUES(?,?,?,?,'PROCESSING',DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 24 HOUR))",
                userId, idempotencyKey, requestHash, reservationId);
        String stockKey = "webox:inventory:{" + date + "}:stock";
        String reservationKey = "webox:inventory:{" + date + "}:reservations";
        String streamKey = "webox:inventory:{" + date + "}:events";
        redis.opsForHash().put(stockKey, Long.toString(itemId), "3");
        redis.opsForHash().put(reservationKey, reservationId, "RESERVED|" + itemId + ":2");
        Map<String, String> event = Map.of("type", "RESERVED", "reservationId", reservationId,
                "date", date.toString(), "quantities", itemId + ":2", "userId", Long.toString(userId),
                "idempotencyKey", idempotencyKey, "requestHash", requestHash);
        redis.opsForStream().add(streamKey, event);

        recovery.recoverPendingReservations();

        assertThat(redis.opsForHash().get(stockKey, Long.toString(itemId))).isEqualTo("5");
        assertThat(redis.opsForHash().get(reservationKey, reservationId)).isEqualTo("COMPENSATED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record WHERE user_id=? AND idempotency_key=?", Integer.class, userId, idempotencyKey)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM idempotency_record WHERE user_id=? AND idempotency_key=?", String.class, userId, idempotencyKey)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_order WHERE delivery_date=?", Integer.class, date)).isZero();

        redis.opsForStream().add(streamKey, event);
        recovery.recoverPendingReservations();
        assertThat(redis.opsForHash().get(stockKey, Long.toString(itemId))).isEqualTo("5");
        assertThat(redis.opsForStream().groups(streamKey).stream().map(group -> group.pendingCount()).toList()).containsOnly(0L);
    }
}
