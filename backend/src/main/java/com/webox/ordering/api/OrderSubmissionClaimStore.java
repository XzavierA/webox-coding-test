package com.webox.ordering.api;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OrderSubmissionClaimStore {
    private final JdbcTemplate jdbc;

    public OrderSubmissionClaimStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Object> claim(long userId, String key, String requestHash, String candidateReservationId) {
        jdbc.update("INSERT IGNORE INTO idempotency_record(user_id,idempotency_key,request_hash,reservation_id,state,expires_at) " +
                        "VALUES(?,?,?,?,'PROCESSING',DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 24 HOUR))",
                userId, key, requestHash, candidateReservationId);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT request_hash,state,order_id,reservation_id,failure_code " +
                "FROM idempotency_record WHERE user_id=? AND idempotency_key=?", userId, key);
        return rows.get(0);
    }
}
