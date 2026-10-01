package com.classroom.modules.outbox.worker;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;
import java.time.Instant;

@Component
public class OutboxLeaseStore {
    private final JdbcTemplate jdbc;
    public OutboxLeaseStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public int claim(String id, String token, Instant now) {
        return jdbc.update("UPDATE outbox_events SET status='PROCESSING', processed_at=?, claim_token=? WHERE id=? AND status='PENDING'", Timestamp.from(now), token, id);
    }
    public int release(String id, String token) {
        return jdbc.update("UPDATE outbox_events SET status='PENDING', claim_token=NULL WHERE id=? AND status='PROCESSING' AND claim_token=?", id, token);
    }
    public int processed(String id, String token, Instant now) {
        return jdbc.update("UPDATE outbox_events SET status='PROCESSED', processed_at=?, error_message=NULL, failure_kind=NULL, claim_token=NULL WHERE id=? AND status='PROCESSING' AND claim_token=?", Timestamp.from(now), id, token);
    }
    public int transientFailure(String id, String token, String message) {
        return jdbc.update("UPDATE outbox_events SET status='PENDING', error_message=?, failure_kind='TRANSIENT', claim_token=NULL WHERE id=? AND status='PROCESSING' AND claim_token=?", message, id, token);
    }
    public int permanentFailure(String id, String token, String status, int retries, String message, Instant now) {
        return jdbc.update("UPDATE outbox_events SET status=?, retry_count=?, error_message=?, failure_kind='PERMANENT', processed_at=?, claim_token=NULL WHERE id=? AND status='PROCESSING' AND claim_token=?", status, retries, message, Timestamp.from(now), id, token);
    }
}
