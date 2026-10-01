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
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Queries of the transactional outbox. Indexes matching every query below are created by V36 (status+sequence_no,
 * status+processed_at and aggregate+status+sequence_no): without them each poll full-scanned the table (R20-05).
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {
    /** Ordered by the database insert sequence, see {@link OutboxEvent#getSequenceNo()}. */
    List<OutboxEvent> findTop50ByStatusOrderBySequenceNoAsc(String status);

    /**
     * Select only currently retryable events whose earlier aggregate events are complete - i.e. the HEAD of each aggregate's
     * order. Uses (status, sequence_no) for the outer scan and (aggregate_type, aggregate_id, status, sequence_no) for the
     * ordering gate, so it costs the number of PENDING rows, never the number of PROCESSED ones.
     */
    @Query(value = "SELECT e.* FROM outbox_events e WHERE e.status = 'PENDING' " +
            "AND (e.retry_count = 0 OR e.processed_at IS NULL " +
            "OR TIMESTAMPADD(SECOND, CASE e.retry_count WHEN 1 THEN 2 WHEN 2 THEN 4 WHEN 3 THEN 8 " +
            "WHEN 4 THEN 16 WHEN 5 THEN 32 WHEN 6 THEN 64 WHEN 7 THEN 128 WHEN 8 THEN 256 ELSE 300 END, e.processed_at) <= :now) " +
            "AND NOT EXISTS (SELECT earlier.id FROM outbox_events earlier WHERE earlier.aggregate_type = e.aggregate_type " +
            "AND earlier.aggregate_id = e.aggregate_id AND earlier.status <> 'PROCESSED' " +
            "AND earlier.sequence_no < e.sequence_no) ORDER BY e.sequence_no ASC", nativeQuery = true)
    List<OutboxEvent> findEligiblePendingEvents(@Param("now") Instant now, Pageable pageable);

    /**
     * Same as {@link #findEligiblePendingEvents} but without the given event types. Used while a projection store is down: events
     * that need it are excluded IN SQL, so they cannot fill every slot of the batch and starve the aggregates that do not need it.
     */
    @Query(value = "SELECT e.* FROM outbox_events e WHERE e.status = 'PENDING' AND e.event_type NOT IN (:excludedTypes) " +
            "AND (e.retry_count = 0 OR e.processed_at IS NULL " +
            "OR TIMESTAMPADD(SECOND, CASE e.retry_count WHEN 1 THEN 2 WHEN 2 THEN 4 WHEN 3 THEN 8 " +
            "WHEN 4 THEN 16 WHEN 5 THEN 32 WHEN 6 THEN 64 WHEN 7 THEN 128 WHEN 8 THEN 256 ELSE 300 END, e.processed_at) <= :now) " +
            "AND NOT EXISTS (SELECT earlier.id FROM outbox_events earlier WHERE earlier.aggregate_type = e.aggregate_type " +
            "AND earlier.aggregate_id = e.aggregate_id AND earlier.status <> 'PROCESSED' " +
            "AND earlier.sequence_no < e.sequence_no) ORDER BY e.sequence_no ASC", nativeQuery = true)
    List<OutboxEvent> findEligiblePendingEventsExcludingTypes(@Param("now") Instant now,
                                                              @Param("excludedTypes") Collection<String> excludedTypes,
                                                              Pageable pageable);

    /**
     * The PENDING events that follow {@code afterSequenceNo} in one aggregate, in order - the rest of a batch that starts at the
     * aggregate's head (R20-05). The caller still claims and re-checks the ordering gate for every event it processes, so a gap
     * (a non-PENDING event in between) simply ends the batch there.
     */
    @Query("SELECT e FROM OutboxEvent e WHERE e.aggregateType = :aggregateType AND e.aggregateId = :aggregateId " +
            "AND e.status = 'PENDING' AND e.sequenceNo > :afterSequenceNo ORDER BY e.sequenceNo ASC")
    List<OutboxEvent> findPendingAfter(@Param("aggregateType") String aggregateType,
                                       @Param("aggregateId") String aggregateId,
                                       @Param("afterSequenceNo") long afterSequenceNo,
                                       Pageable pageable);

    List<OutboxEvent> findByStatusInOrderBySequenceNoAsc(List<String> statuses);

    /** Bounded variant for operator screens / replay so a huge dead-letter backlog cannot be pulled into memory at once. */
    List<OutboxEvent> findByStatusInOrderBySequenceNoAsc(List<String> statuses, Pageable pageable);

    boolean existsByAggregateTypeAndAggregateIdAndEventType(String aggregateType, String aggregateId, String eventType);

    Optional<OutboxEvent> findFirstByStatusOrderBySequenceNoAsc(String status);

    @Modifying
    @Transactional
    @Query("UPDATE OutboxEvent e SET e.status = 'PROCESSING', e.processedAt = :now WHERE e.id = :id AND e.status = 'PENDING'")
    int claimEvent(@Param("id") String id, @Param("now") Instant now);

    /** Terminal success. Guarded by PROCESSING so a row reclaimed as stale (and possibly re-run elsewhere) is not overwritten. */
    @Modifying
    @Transactional
    @Query("UPDATE OutboxEvent e SET e.status = 'PROCESSED', e.processedAt = :now, e.errorMessage = NULL, e.failureKind = NULL " +
            "WHERE e.id = :id AND e.status = 'PROCESSING'")
    int markProcessed(@Param("id") String id, @Param("now") Instant now);

    /**
     * A transient (dependency) failure: back to PENDING WITHOUT touching retry_count, so it can never lead to DEAD_LETTER. The
     * message is kept for operators; the sink's circuit breaker paces the next attempt.
     */
    @Modifying
    @Transactional
    @Query("UPDATE OutboxEvent e SET e.status = 'PENDING', e.errorMessage = :message, e.failureKind = 'TRANSIENT' " +
            "WHERE e.id = :id AND e.status = 'PROCESSING'")
    int markTransientFailure(@Param("id") String id, @Param("message") String message);

    /** A permanent failure: counts toward the dead-letter limit ({@code status} is PENDING for another try or DEAD_LETTER). */
    @Modifying
    @Transactional
    @Query("UPDATE OutboxEvent e SET e.status = :status, e.retryCount = :retryCount, e.errorMessage = :message, " +
            "e.failureKind = 'PERMANENT', e.processedAt = :now WHERE e.id = :id AND e.status = 'PROCESSING'")
    int markPermanentFailure(@Param("id") String id, @Param("status") String status, @Param("retryCount") int retryCount,
                             @Param("message") String message, @Param("now") Instant now);

    /**
     * Reclaims events stuck in PROCESSING past the stale threshold (a worker that claimed the
     * event crashed, or otherwise never reached a terminal status) and counts the reclaim as a
     * retry. Without this, a payload that always crashes whichever worker processes it would be
     * reclaimed as PENDING forever without ever accumulating retries, so it could never reach
     * DEAD_LETTER. An event already at the retry ceiling is dead-lettered directly instead of
     * being handed out for another attempt.
     */
    @Modifying
    @Transactional
    @Query("UPDATE OutboxEvent e SET e.status = 'DEAD_LETTER', e.errorMessage = 'Reclaimed from stale PROCESSING after exceeding max retries' " +
            "WHERE e.status = 'PROCESSING' AND e.processedAt < :staleThreshold AND e.retryCount >= :maxRetries")
    int deadLetterStaleProcessingEventsAtRetryLimit(@Param("staleThreshold") Instant staleThreshold, @Param("maxRetries") int maxRetries);

    @Modifying
    @Transactional
    @Query("UPDATE OutboxEvent e SET e.status = 'PENDING', e.retryCount = e.retryCount + 1, " +
            "e.errorMessage = 'Reclaimed from stale PROCESSING (worker did not complete in time)' " +
            "WHERE e.status = 'PROCESSING' AND e.processedAt < :staleThreshold AND e.retryCount < :maxRetries")
    int resetStaleProcessingEvents(@Param("staleThreshold") Instant staleThreshold, @Param("maxRetries") int maxRetries);

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

    // ------------------------------------------------------------------------------------------------ retention (R20-05)

    /** Ids of the oldest PROCESSED events processed before {@code cutoff} (index: status, processed_at). */
    @Query("SELECT e.id FROM OutboxEvent e WHERE e.status = 'PROCESSED' AND e.processedAt < :cutoff ORDER BY e.processedAt ASC")
    List<String> findProcessedIdsBefore(@Param("cutoff") Instant cutoff, Pageable pageable);

    /** PROCESSED rows that never got a processed_at (should not exist) are aged by created_at instead. */
    @Query("SELECT e.id FROM OutboxEvent e WHERE e.status = 'PROCESSED' AND e.processedAt IS NULL AND e.createdAt < :cutoff")
    List<String> findProcessedWithoutTimestampCreatedBefore(@Param("cutoff") Instant cutoff, Pageable pageable);

    @Modifying
    @Transactional
    @Query("DELETE FROM OutboxEvent e WHERE e.id IN :ids AND e.status = 'PROCESSED'")
    int deleteProcessedByIds(@Param("ids") Collection<String> ids);

    // -------------------------------------------------------------------------------------- re-drive / visibility (R20-04)

    /** Counts of the not-yet-finished events by status (index: status, sequence_no). */
    @Query("SELECT e.status, COUNT(e) FROM OutboxEvent e WHERE e.status IN :statuses GROUP BY e.status")
    List<Object[]> countByStatuses(@Param("statuses") Collection<String> statuses);

    /**
     * Dead letters that may still be replayed automatically, oldest first: not exhausted and dead for at least {@code maxAge}
     * (the caller narrows further by failure kind). Uses (status, sequence_no).
     */
    @Query("SELECT e FROM OutboxEvent e WHERE e.status = 'DEAD_LETTER' AND e.autoReplayCount < :maxAutoReplays " +
            "AND (e.processedAt IS NULL OR e.processedAt <= :deadSince) ORDER BY e.sequenceNo ASC")
    List<OutboxEvent> findRedriveCandidates(@Param("maxAutoReplays") int maxAutoReplays,
                                            @Param("deadSince") Instant deadSince, Pageable pageable);

    /** Puts a dead letter back into the queue for one more automatic attempt. Counts against the per-event auto-replay budget. */
    @Modifying
    @Transactional
    @Query("UPDATE OutboxEvent e SET e.status = 'PENDING', e.retryCount = 0, e.processedAt = NULL, " +
            "e.autoReplayCount = e.autoReplayCount + 1, e.errorMessage = :message WHERE e.id = :id AND e.status = 'DEAD_LETTER'")
    int autoReplay(@Param("id") String id, @Param("message") String message);
}
