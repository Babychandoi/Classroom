package com.classroom.modules.outbox.model;

import jakarta.persistence.*;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
    @Column(name = "claim_token", length = 36)
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    private String leaseToken;
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getLeaseToken() { return leaseToken; }
    public void setLeaseToken(String leaseToken) { this.leaseToken = leaseToken; }

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "payload_json", columnDefinition = "MEDIUMTEXT", nullable = false)
    private String payloadJson;

    @Column(nullable = false, length = 32)
    private String status = "PENDING"; // PENDING, PROCESSING, PROCESSED, FAILED, DEAD_LETTER

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * Classification of the LAST failure (R20-04b): {@code TRANSIENT} (the dependency was unreachable - never counts toward the
     * dead-letter limit) or {@code PERMANENT} (the event itself cannot be projected). {@code null} while the event never failed and
     * for rows written before V36.
     */
    @Column(name = "failure_kind", length = 16)
    private String failureKind;

    /** How many times the automatic re-drive job has replayed this event out of DEAD_LETTER (V36). Reset by a manual replay. */
    @Column(name = "auto_replay_count", nullable = false)
    private int autoReplayCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    /**
     * Monotonic insert order, assigned by the database.
     *
     * <p>This is the authoritative ordering key for events of the same aggregate. {@code createdAt}
     * only has second precision in the schema, so two transitions written in the same second are
     * indistinguishable by timestamp and any tie-break on the random {@code id} could order them
     * opposite to the order they actually happened in.</p>
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "sequence_no", insertable = false, updatable = false)
    private Long sequenceNo;

    public OutboxEvent() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
    }

    public OutboxEvent(String aggregateType, String aggregateId, String eventType, String payloadJson) {
        this.id = UUID.randomUUID().toString();
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payloadJson = payloadJson;
        this.status = "PENDING";
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public void setAggregateType(String aggregateType) {
        this.aggregateType = aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(String aggregateId) {
        this.aggregateId = aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getFailureKind() {
        return failureKind;
    }

    public void setFailureKind(String failureKind) {
        this.failureKind = failureKind;
    }

    public int getAutoReplayCount() {
        return autoReplayCount;
    }

    public void setAutoReplayCount(int autoReplayCount) {
        this.autoReplayCount = autoReplayCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }

    public Long getSequenceNo() {
        return sequenceNo;
    }

    public void setSequenceNo(Long sequenceNo) {
        this.sequenceNo = sequenceNo;
    }
}
