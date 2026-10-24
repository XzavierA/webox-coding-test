package com.webox.ordering.api;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class InventoryReservationRecovery {
    private static final String GROUP = "webox-order-confirmation";
    private static final String CONSUMER = "webox-order-confirmation-worker";
    private static final DefaultRedisScript<Long> COMPENSATE_SCRIPT = new DefaultRedisScript<>(
            "local state=redis.call('HGET',KEYS[2],ARGV[1]); if state=='CONFIRMED' or state=='COMPENSATED' then return 0 end; " +
                    "local n=tonumber(ARGV[2]); for i=1,n do redis.call('HINCRBY',KEYS[1],ARGV[3+(i-1)*2],tonumber(ARGV[4+(i-1)*2])) end; " +
                    "redis.call('HSET',KEYS[2],ARGV[1],'COMPENSATED'); return 1;", Long.class);
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final long missingRecordGraceMillis;

    public InventoryReservationRecovery(StringRedisTemplate redis, JdbcTemplate jdbc,
                                        @Value("${webox.inventory.missing-record-grace-ms:30000}") long missingRecordGraceMillis) {
        this.redis = redis; this.jdbc = jdbc; this.missingRecordGraceMillis = missingRecordGraceMillis;
    }

    public void ensureGroup(String streamKey) {
        try {
            if (redis.opsForStream().groups(streamKey).stream().anyMatch(group -> GROUP.equals(group.groupName()))) return;
        } catch (DataAccessException ignored) { }
        try {
            redis.opsForStream().createGroup(streamKey, ReadOffset.from("0-0"), GROUP);
        } catch (DataAccessException ex) {
            Throwable cause = ex;
            while (cause != null) {
                if (cause.getMessage() != null && cause.getMessage().contains("BUSYGROUP")) return;
                cause = cause.getCause();
            }
            throw ex;
        }
    }

    @Scheduled(fixedDelayString = "${webox.inventory.recovery-poll-ms:1000}")
    public synchronized void recoverPendingReservations() {
        List<LocalDate> dates = jdbc.queryForList("SELECT menu_date FROM daily_menu", LocalDate.class);
        for (LocalDate date : dates) {
            String streamKey = streamKey(date);
            if (!Boolean.TRUE.equals(redis.hasKey(streamKey))) continue;
            try {
                ensureGroup(streamKey);
                Consumer consumer = Consumer.from(GROUP, CONSUMER);
                StreamReadOptions options = StreamReadOptions.empty().count(50);
                process(streamKey, redis.opsForStream().read(consumer, options,
                        StreamOffset.create(streamKey, ReadOffset.from("0-0"))));
                process(streamKey, redis.opsForStream().read(consumer, options,
                        StreamOffset.create(streamKey, ReadOffset.lastConsumed())));
            } catch (RuntimeException ignored) {
                // Leave entries pending. The next poll or process restart retries them.
            }
        }
    }

    private void process(String streamKey, List<MapRecord<String, Object, Object>> records) {
        if (records == null) return;
        for (MapRecord<String, Object, Object> record : records) {
            try { process(streamKey, record); }
            catch (RuntimeException ignored) { /* Keep failed work pending for redelivery. */ }
        }
    }

    private void process(String streamKey, MapRecord<String, Object, Object> record) {
        Map<Object, Object> event = record.getValue();
        String reservationId = value(event, "reservationId");
        LocalDate date = LocalDate.parse(value(event, "date"));
        String statusKey = reservationKey(date);
        Object current = redis.opsForHash().get(statusKey, reservationId);
        if ("CONFIRMED".equals(current) || "COMPENSATED".equals(current)) {
            acknowledge(streamKey, record.getId());
            return;
        }
        List<String> states = jdbc.queryForList("SELECT state FROM idempotency_record WHERE reservation_id=?", String.class, reservationId);
        if (states.contains("COMPLETED")) {
            redis.opsForHash().put(statusKey, reservationId, "CONFIRMED");
            acknowledge(streamKey, record.getId());
            return;
        }
        if (states.contains("PROCESSING") && System.currentTimeMillis() - record.getId().getTimestamp() >= missingRecordGraceMillis) {
            jdbc.update("UPDATE idempotency_record SET state='FAILED',failure_code='ORDER_CONFIRMATION_RECOVERED',completed_at=CURRENT_TIMESTAMP(3) " +
                            "WHERE reservation_id=? AND state='PROCESSING' AND created_at<=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL ? MICROSECOND)",
                    reservationId, missingRecordGraceMillis * 1000);
            states = jdbc.queryForList("SELECT state FROM idempotency_record WHERE reservation_id=?", String.class, reservationId);
            if (states.contains("COMPLETED")) {
                redis.opsForHash().put(statusKey, reservationId, "CONFIRMED");
                acknowledge(streamKey, record.getId());
                return;
            }
        }
        if (states.contains("FAILED") || (states.isEmpty() && System.currentTimeMillis() - record.getId().getTimestamp() >= missingRecordGraceMillis)) {
            if (states.isEmpty() && event.get("userId") != null && event.get("idempotencyKey") != null && event.get("requestHash") != null) {
                jdbc.update("INSERT IGNORE INTO idempotency_record(user_id,idempotency_key,request_hash,reservation_id,state,failure_code,expires_at) " +
                                "VALUES(?,?,?,?,'FAILED','ORDER_CONFIRMATION_RECOVERED',DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 24 HOUR))",
                        Long.parseLong(value(event, "userId")), value(event, "idempotencyKey"), value(event, "requestHash"), reservationId);
            }
            compensate(date, reservationId, value(event, "quantities"));
            acknowledge(streamKey, record.getId());
        }
    }

    private void compensate(LocalDate date, String reservationId, String spec) {
        List<String> pairs = new ArrayList<>();
        if (spec != null && !spec.isBlank()) for (String pair : spec.split(",")) {
            String[] parts = pair.split(":", 2);
            pairs.add(parts[0]); pairs.add(parts[1]);
        }
        List<String> args = new ArrayList<>(); args.add(reservationId); args.add(Integer.toString(pairs.size() / 2)); args.addAll(pairs);
        redis.execute(COMPENSATE_SCRIPT, List.of(stockKey(date), reservationKey(date)), args.toArray());
    }

    private void acknowledge(String streamKey, RecordId id) { redis.opsForStream().acknowledge(streamKey, GROUP, id); }
    private String value(Map<Object, Object> event, String name) { return event.get(name) == null ? null : event.get(name).toString(); }
    private String streamKey(LocalDate date) { return "webox:inventory:{" + date + "}:events"; }
    private String stockKey(LocalDate date) { return "webox:inventory:{" + date + "}:stock"; }
    private String reservationKey(LocalDate date) { return "webox:inventory:{" + date + "}:reservations"; }
}
