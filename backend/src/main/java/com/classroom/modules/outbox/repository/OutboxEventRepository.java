package com.classroom.modules.outbox.repository;

import com.classroom.modules.outbox.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {
    /** Ordered by the database insert sequence, see {@link OutboxEvent#getSequenceNo()}. */
    List<OutboxEvent> findTop50ByStatusOrderBySequenceNoAsc(String status);

    /** Select only currently retryable events whose earlier aggregate events are complete. */
    @Query(value = "SELECT e.* FROM outbox_events e WHERE e.status = 'PENDING' " +
            "AND (e.retry_count = 0 OR e.processed_at IS NULL " +
            "OR TIMESTAMPADD(SECOND, CASE e.retry_count WHEN 1 THEN 2 WHEN 2 THEN 4 WHEN 3 THEN 8 " +
            "WHEN 4 THEN 16 WHEN 5 THEN 32 WHEN 6 THEN 64 WHEN 7 THEN 128 WHEN 8 THEN 256 ELSE 300 END, e.processed_at) <= :now) " +
            "AND NOT EXISTS (SELECT earlier.id FROM outbox_events earlier WHERE earlier.aggregate_type = e.aggregate_type " +
            "AND earlier.aggregate_id = e.aggregate_id AND earlier.status <> 'PROCESSED' " +
            "AND earlier.sequence_no < e.sequence_no) ORDER BY e.sequence_no ASC", nativeQuery = true)
    List<OutboxEvent> findEligiblePendingEvents(@Param("now") Instant now, Pageable pageable);

    List<OutboxEvent> findByStatusInOrderBySequenceNoAsc(List<String> statuses);
    boolean existsByAggregateTypeAndAggregateIdAndEventType(String aggregateType, String aggregateId, String eventType);

    @Modifying
    @Transactional
    @Query("UPDATE OutboxEvent e SET e.status = 'PROCESSING', e.processedAt = :now WHERE e.id = :id AND e.status = 'PENDING'")
    int claimEvent(@Param("id") String id, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("UPDATE OutboxEvent e SET e.status = 'PENDING' WHERE e.status = 'PROCESSING' AND e.processedAt < :staleThreshold")
    int resetStaleProcessingEvents(@Param("staleThreshold") Instant staleThreshold);

    /** Releases a claim taken by this worker so the event is retried in order on a later pass. */
    @Modifying
    @Transactional
    @Query("UPDATE OutboxEvent e SET e.status = 'PENDING' WHERE e.id = :id AND e.status = 'PROCESSING'")
    int releaseClaim(@Param("id") String id);

    /**
     * True when any strictly earlier event for the same aggregate has not reached PROCESSED.
     * This covers events outside the current batch window and events parked in PROCESSING,
     * FAILED or DEAD_LETTER, so a later event can never be projected ahead of an earlier one.
     *
     * <p>"Earlier" is decided by the database insert sequence rather than {@code created_at}:
     * the timestamp column only has second precision, so two transitions of the same aggregate
     * can share it, and the previous tie-break on the random {@code id} could then order a remove
     * after the join that followed it.</p>
     */
    @Query("SELECT COUNT(e) > 0 FROM OutboxEvent e WHERE e.aggregateType = :aggregateType AND e.aggregateId = :aggregateId AND e.status <> 'PROCESSED' AND e.sequenceNo < :sequenceNo")
    boolean existsEarlierUncompletedEvent(@Param("aggregateType") String aggregateType,
                                          @Param("aggregateId") String aggregateId,
                                          @Param("sequenceNo") long sequenceNo);
}
