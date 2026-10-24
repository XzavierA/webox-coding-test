package com.webox.platform.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxPublisher {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public OutboxPublisher(JdbcTemplate jdbc, StringRedisTemplate redis, ObjectMapper mapper) {
        this.jdbc = jdbc; this.redis = redis; this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${webox.outbox.poll-delay-ms:500}")
    public void publishBatch() {
        List<Map<String, Object>> events = jdbc.queryForList("SELECT id,event_type,payload FROM outbox_event WHERE published_at IS NULL AND next_attempt_at<=CURRENT_TIMESTAMP(3) ORDER BY id LIMIT 50");
        for (Map<String, Object> event : events) publish(event);
    }

    private void publish(Map<String, Object> event) {
        long id = ((Number) event.get("id")).longValue();
        try {
            @SuppressWarnings("unchecked") Map<String, Object> payload = mapper.readValue(event.get("payload").toString(), Map.class);
            String type = event.get("event_type").toString();
            if ("stock.changed".equals(type)) {
                Object date = payload.get("date");
                redis.convertAndSend("webox:stock:" + LocalDate.parse(date.toString()), mapper.writeValueAsString(payload));
            } else if ("order.placed".equals(type)) {
                redis.delete("webox:dashboard:" + LocalDate.parse(payload.get("date").toString()));
            } else if ("order.status.changed".equals(type)) {
                String date = jdbc.queryForObject("SELECT delivery_date FROM customer_order WHERE id=?", String.class, Long.parseLong(event.get("aggregate_id").toString()));
                if (date != null) redis.delete("webox:dashboard:" + date);
            }
            jdbc.update("UPDATE outbox_event SET published_at=CURRENT_TIMESTAMP(3) WHERE id=? AND published_at IS NULL", id);
        } catch (Exception ex) {
            jdbc.update("UPDATE outbox_event SET attempt_count=attempt_count+1,next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL LEAST(attempt_count+1,30) SECOND) WHERE id=? AND published_at IS NULL", id);
        }
    }
}
