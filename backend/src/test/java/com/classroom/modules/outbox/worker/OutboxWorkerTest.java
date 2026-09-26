package com.classroom.modules.outbox.worker;

import com.classroom.modules.outbox.model.OutboxEvent;
import com.classroom.modules.outbox.repository.OutboxEventRepository;
import com.classroom.modules.projection.mongo.LearningEventRepository;
import com.classroom.modules.projection.neo4j.Neo4jSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class OutboxWorkerTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private Neo4jSyncService neo4jSyncService;
    @Mock
    private LearningEventRepository learningEventRepository;

    private OutboxWorker outboxWorker;

    @BeforeEach
    void setUp() {
        outboxWorker = new OutboxWorker(outboxEventRepository, neo4jSyncService);
        ReflectionTestUtils.setField(outboxWorker, "learningEventRepository", learningEventRepository);
        lenient().when(outboxEventRepository.claimEvent(any(), any())).thenReturn(1);
    }

    @Test
    @DisplayName("Finding 11: MongoDB projection failure keeps event PENDING for retry and does NOT mark PROCESSED")
    void testMongoFailureDoesNotMarkProcessed() {
        OutboxEvent event = new OutboxEvent("COMMERCE", "order-1", "ORDER_PAID", "{}");
        event.setSequenceNo(1L);
        when(outboxEventRepository.findEligiblePendingEvents(any(), any(Pageable.class))).thenReturn(List.of(event));
        when(learningEventRepository.save(any())).thenThrow(new RuntimeException("MongoDB connection timeout"));

        outboxWorker.processOutboxEvents();

        assertEquals("PENDING", event.getStatus());
        assertEquals(1, event.getRetryCount());
        assertNotNull(event.getErrorMessage());
        assertTrue(event.getErrorMessage().contains("MongoDB"));
        verify(outboxEventRepository).save(event);
    }

    @Test
    @DisplayName("Finding 11: Neo4j projection failure keeps event PENDING for retry and does NOT mark PROCESSED")
    void testNeo4jFailureDoesNotMarkProcessed() {
        OutboxEvent event = new OutboxEvent("CLASSROOM", "user-1", "MEMBER_JOINED", "{}");
        event.setSequenceNo(2L);
        when(outboxEventRepository.findEligiblePendingEvents(any(), any(Pageable.class))).thenReturn(List.of(event));
        doThrow(new RuntimeException("Neo4j Bolt connection error"))
                .when(neo4jSyncService).syncUserClassMembership(any(), any());

        outboxWorker.processOutboxEvents();

        assertEquals("PENDING", event.getStatus());
        assertEquals(1, event.getRetryCount());
        assertNotNull(event.getErrorMessage());
        assertTrue(event.getErrorMessage().contains("Neo4j"));
        verify(outboxEventRepository).save(event);
    }

    @Test
    @DisplayName("Finding 11: Successful projections transition event to PROCESSED")
    void testSuccessfulProjectionMarksProcessed() {
        OutboxEvent event = new OutboxEvent("COMMERCE", "order-1", "ORDER_PAID", "{}");
        event.setSequenceNo(3L);
        when(outboxEventRepository.findEligiblePendingEvents(any(), any(Pageable.class))).thenReturn(List.of(event));

        outboxWorker.processOutboxEvents();

        assertEquals("PROCESSED", event.getStatus());
        assertNotNull(event.getProcessedAt());
        assertNull(event.getErrorMessage());
        verify(outboxEventRepository).save(event);
    }

    @Test
    @DisplayName("Finding 6: When MongoDB repository is null but projection enabled, event is NOT marked PROCESSED")
    void testNullMongoRepositoryDoesNotMarkProcessed() {
        ReflectionTestUtils.setField(outboxWorker, "learningEventRepository", null);
        ReflectionTestUtils.setField(outboxWorker, "mongoEnabled", true);

        OutboxEvent event = new OutboxEvent("COMMERCE", "order-1", "ORDER_PAID", "{}");

        event.setSequenceNo(4L);
        when(outboxEventRepository.findEligiblePendingEvents(any(), any(Pageable.class))).thenReturn(List.of(event));

        outboxWorker.processOutboxEvents();

        assertEquals("PENDING", event.getStatus());
        assertEquals(1, event.getRetryCount());
        assertNotNull(event.getErrorMessage());
        assertTrue(event.getErrorMessage().contains("MongoDB"));
        verify(outboxEventRepository).save(event);
    }

    @Test
    @DisplayName("Finding 6: When MongoDB projection is explicitly disabled by config, event succeeds without Mongo")
    void testDisabledMongoProjectionSucceeds() {
        ReflectionTestUtils.setField(outboxWorker, "learningEventRepository", null);
        ReflectionTestUtils.setField(outboxWorker, "mongoEnabled", false);

        OutboxEvent event = new OutboxEvent("COMMERCE", "order-1", "ORDER_PAID", "{}");

        event.setSequenceNo(5L);
        when(outboxEventRepository.findEligiblePendingEvents(any(), any(Pageable.class))).thenReturn(List.of(event));

        outboxWorker.processOutboxEvents();

        assertEquals("PROCESSED", event.getStatus());
        assertNotNull(event.getProcessedAt());
        assertNull(event.getErrorMessage());
        verify(outboxEventRepository).save(event);
    }

    @Test
    @DisplayName("Finding 8: MEMBER_JOINED extracts userId and classId from payload for Neo4j projection")
    void testMemberJoinedExtractsPayloadForNeo4j() {
        OutboxEvent event = new OutboxEvent("CLASSROOM", "class-123", "MEMBER_JOINED", "{\"userId\":\"user-456\",\"classId\":\"class-123\"}");
        event.setSequenceNo(6L);
        when(outboxEventRepository.findEligiblePendingEvents(any(), any(Pageable.class))).thenReturn(List.of(event));

        outboxWorker.processOutboxEvents();

        assertEquals("PROCESSED", event.getStatus());
        verify(neo4jSyncService).syncUserClassMembership("user-456", "class-123");
        verify(learningEventRepository).save(argThat(doc ->
                doc.getEventType().equals("MEMBER_JOINED") && doc.getAggregateId().equals("class-123")));
    }

    @Test
    @DisplayName("Finding 8: LESSON_COMPLETED projects learning activity to MongoDB")
    void testLessonCompletedProjectsToMongo() {
        OutboxEvent event = new OutboxEvent("LEARNING", "lesson-789", "LESSON_COMPLETED", "{\"userId\":\"u-1\",\"classId\":\"c-1\"}");
        event.setSequenceNo(7L);
        when(outboxEventRepository.findEligiblePendingEvents(any(), any(Pageable.class))).thenReturn(List.of(event));

        outboxWorker.processOutboxEvents();

        assertEquals("PROCESSED", event.getStatus());
        verify(learningEventRepository).save(argThat(doc ->
                doc.getEventType().equals("LESSON_COMPLETED") && doc.getAggregateId().equals("lesson-789")));
    }

    @Test
    @DisplayName("Finding 11: Outbox event transitions to DEAD_LETTER after 5 retries without being dropped")
    void testDeadLetterTransitionAfterFiveRetries() {
        OutboxEvent event = new OutboxEvent("COMMERCE", "order-1", "ORDER_PAID", "{}");
        event.setSequenceNo(8L);
        event.setRetryCount(4);
        when(outboxEventRepository.findEligiblePendingEvents(any(), any(Pageable.class))).thenReturn(List.of(event));
        when(learningEventRepository.save(any())).thenThrow(new RuntimeException("MongoDB persistent outage"));

        outboxWorker.processOutboxEvents();

        assertEquals("DEAD_LETTER", event.getStatus());
        assertEquals(5, event.getRetryCount());
        assertNotNull(event.getErrorMessage());
        verify(outboxEventRepository).save(event);
    }

    @Test
    @DisplayName("Finding 11: replayFailedEvents resets DEAD_LETTER and FAILED events to PENDING with retryCount 0")
    void testReplayFailedEvents() {
        OutboxEvent deadLetterEvent = new OutboxEvent("COMMERCE", "order-1", "ORDER_PAID", "{}");
        deadLetterEvent.setSequenceNo(9L);
        deadLetterEvent.setStatus("DEAD_LETTER");
        deadLetterEvent.setRetryCount(5);

        OutboxEvent failedEvent = new OutboxEvent("COMMERCE", "order-2", "ORDER_PAID", "{}");

        failedEvent.setSequenceNo(10L);
        failedEvent.setStatus("FAILED");
        failedEvent.setRetryCount(5);

        when(outboxEventRepository.findTop50ByStatusOrderBySequenceNoAsc("DEAD_LETTER")).thenReturn(List.of(deadLetterEvent));
        when(outboxEventRepository.findTop50ByStatusOrderBySequenceNoAsc("FAILED")).thenReturn(List.of(failedEvent));

        int replayed = outboxWorker.replayFailedEvents();

        assertEquals(2, replayed);
        assertEquals("PENDING", deadLetterEvent.getStatus());
        assertEquals(0, deadLetterEvent.getRetryCount());
        assertEquals("PENDING", failedEvent.getStatus());
        assertEquals(0, failedEvent.getRetryCount());
        verify(outboxEventRepository).save(deadLetterEvent);
        verify(outboxEventRepository).save(failedEvent);
    }

    @Test
    @DisplayName("Finding 11: Exponential backoff skips premature retry within backoff window")
    void testExponentialBackoffSkipsPrematureRetry() {
        OutboxEvent event = new OutboxEvent("COMMERCE", "order-1", "ORDER_PAID", "{}");
        event.setSequenceNo(11L);
        event.setRetryCount(3); // backoff is 2^3 = 8 seconds
        event.setProcessedAt(java.time.Instant.now()); // Just attempted right now

        when(outboxEventRepository.findEligiblePendingEvents(any(), any(Pageable.class))).thenReturn(List.of(event));

        outboxWorker.processOutboxEvents();

        // Skipped: save not called because backoff is active
        verify(learningEventRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(event);
    }

    @Test
    @DisplayName("Ordering: When event 1 for aggregate A is in backoff, newer event 2 for aggregate A is NOT processed")
    void testAggregateOrderingPreservedWhenPriorEventBackingOff() {
        OutboxEvent event1 = new OutboxEvent("CLASSROOM", "class-1", "MEMBER_JOINED", "{\"userId\":\"u-1\",\"classId\":\"class-1\"}");
        event1.setSequenceNo(12L);
        event1.setRetryCount(2);
        event1.setProcessedAt(java.time.Instant.now()); // in backoff window

        OutboxEvent event2 = new OutboxEvent("CLASSROOM", "class-1", "MEMBER_REMOVED", "{\"userId\":\"u-1\",\"classId\":\"class-1\"}");

        event2.setSequenceNo(13L);

        when(outboxEventRepository.findEligiblePendingEvents(any(), any(Pageable.class))).thenReturn(List.of(event1, event2));

        outboxWorker.processOutboxEvents();

        // Neither event1 nor event2 should be projected because event1 is backing off and event2 belongs to same aggregate
        verify(neo4jSyncService, never()).removeUserClassMembership(any(), any());
        verify(outboxEventRepository, never()).save(event2);
    }

    @Test
    @DisplayName("Ordering: Multiple events for same aggregate in same batch are processed sequentially, not concurrently")
    void testAggregateOrderingPreservedInBatch() {
        OutboxEvent event1 = new OutboxEvent("CLASSROOM", "class-1", "MEMBER_JOINED", "{\"userId\":\"u-1\",\"classId\":\"class-1\"}");
        event1.setSequenceNo(14L);
        OutboxEvent event2 = new OutboxEvent("CLASSROOM", "class-1", "MEMBER_REMOVED", "{\"userId\":\"u-1\",\"classId\":\"class-1\"}");
        event2.setSequenceNo(15L);

        when(outboxEventRepository.findEligiblePendingEvents(any(), any(Pageable.class))).thenReturn(List.of(event1, event2));

        outboxWorker.processOutboxEvents();

        // Event1 is processed and marked PROCESSED
        assertEquals("PROCESSED", event1.getStatus());
        verify(outboxEventRepository).save(event1);

        // Event2 for the same aggregate was blocked in this batch to preserve causal order
        assertEquals("PENDING", event2.getStatus());
        verify(outboxEventRepository, never()).save(event2);
    }

    @Test
    @DisplayName("Claim: When claimEvent returns 0 (claimed by another worker), event is skipped")
    void testDistributedClaimSkipsAlreadyClaimedEvent() {
        OutboxEvent event = new OutboxEvent("COMMERCE", "order-1", "ORDER_PAID", "{}");
        event.setSequenceNo(16L);
        when(outboxEventRepository.findEligiblePendingEvents(any(), any(Pageable.class))).thenReturn(List.of(event));
        when(outboxEventRepository.claimEvent(eq(event.getId()), any())).thenReturn(0);

        outboxWorker.processOutboxEvents();

        verify(learningEventRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(event);
    }

    @Test
    @DisplayName("Ordering: When an earlier uncompleted event exists for the aggregate, the later event is skipped")
    void testAggregateBlockedWhenEarlierUncompletedEventExists() {
        OutboxEvent event = new OutboxEvent("CLASSROOM", "class-1", "MEMBER_JOINED", "{\"userId\":\"u-1\",\"classId\":\"class-1\"}");
        event.setSequenceNo(17L);
        when(outboxEventRepository.findEligiblePendingEvents(any(), any(Pageable.class))).thenReturn(List.of(event));
        when(outboxEventRepository.existsEarlierUncompletedEvent(
                eq("CLASSROOM"), eq("class-1"), anyLong())).thenReturn(true);

        outboxWorker.processOutboxEvents();

        verify(learningEventRepository, never()).save(any());
        verify(neo4jSyncService, never()).syncUserClassMembership(any(), any());
        verify(outboxEventRepository, never()).claimEvent(eq(event.getId()), any());
        verify(outboxEventRepository, never()).save(event);
    }

    @Test
    @DisplayName("Ordering: A later MEMBER_JOINED is not projected while an earlier DEAD_LETTER MEMBER_REMOVED is unresolved")
    void testLaterJoinBlockedByEarlierDeadLetterRemoval() {
        // The earlier MEMBER_REMOVED is in DEAD_LETTER, so it is absent from the PENDING batch.
        // Only the aggregate-wide ordering gate can see it.
        OutboxEvent laterJoin = new OutboxEvent("CLASSROOM", "class-1", "MEMBER_JOINED", "{\"userId\":\"u-1\",\"classId\":\"class-1\"}");
        laterJoin.setSequenceNo(18L);
        when(outboxEventRepository.findEligiblePendingEvents(any(), any(Pageable.class))).thenReturn(List.of(laterJoin));
        when(outboxEventRepository.existsEarlierUncompletedEvent(
                eq("CLASSROOM"), eq("class-1"), anyLong())).thenReturn(true);

        outboxWorker.processOutboxEvents();

        verify(neo4jSyncService, never()).syncUserClassMembership(any(), any());
        assertEquals("PENDING", laterJoin.getStatus());
    }

    @Test
    @DisplayName("Ordering: A claim is released when an earlier event appears between the gate check and the claim")
    void testClaimReleasedWhenEarlierEventAppearsAfterClaim() {
        OutboxEvent event = new OutboxEvent("CLASSROOM", "class-1", "MEMBER_JOINED", "{\"userId\":\"u-1\",\"classId\":\"class-1\"}");
        event.setSequenceNo(19L);
        when(outboxEventRepository.findEligiblePendingEvents(any(), any(Pageable.class))).thenReturn(List.of(event));
        when(outboxEventRepository.existsEarlierUncompletedEvent(
                eq("CLASSROOM"), eq("class-1"), anyLong()))
                .thenReturn(false)   // pre-claim gate passes
                .thenReturn(true);   // a competing worker claimed an earlier event meanwhile

        outboxWorker.processOutboxEvents();

        verify(outboxEventRepository).releaseClaim(event.getId());
        verify(neo4jSyncService, never()).syncUserClassMembership(any(), any());
        verify(outboxEventRepository, never()).save(event);
    }

    @Test
    @DisplayName("Replay: A replayed DEAD_LETTER event returns to PENDING so ordering is re-established")
    void testReplayRequeuesDeadLetterEventForOrderedRetry() {
        OutboxEvent removal = new OutboxEvent("CLASSROOM", "class-1", "MEMBER_REMOVED", "{\"userId\":\"u-1\",\"classId\":\"class-1\"}");
        removal.setSequenceNo(20L);
        removal.setStatus("DEAD_LETTER");
        removal.setRetryCount(5);
        when(outboxEventRepository.findByStatusInOrderBySequenceNoAsc(List.of("DEAD_LETTER", "FAILED")))
                .thenReturn(List.of(removal));

        int replayed = outboxWorker.replayFailedEvents("class-1");

        assertEquals(1, replayed);
        assertEquals("PENDING", removal.getStatus());
        assertEquals(0, removal.getRetryCount());
        verify(outboxEventRepository).save(removal);
    }

    @Test
    @DisplayName("Ordering: Same-aggregate events sharing a created_at are ordered by sequence, not by id")
    void testSameTimestampEventsAreOrderedBySequenceNotId() {
        // The schema stores created_at with second precision, so a remove and the join that
        // followed it can carry the identical timestamp. Ordering must come from the insert
        // sequence; tie-breaking on the random id could apply the join before the remove.
        java.time.Instant sharedTimestamp = java.time.Instant.parse("2026-01-01T00:00:00Z");

        OutboxEvent removal = new OutboxEvent("CLASSROOM", "class-1", "MEMBER_REMOVED", "{\"userId\":\"u-1\",\"classId\":\"class-1\"}");
        removal.setCreatedAt(sharedTimestamp);
        removal.setSequenceNo(100L);

        OutboxEvent join = new OutboxEvent("CLASSROOM", "class-1", "MEMBER_JOINED", "{\"userId\":\"u-1\",\"classId\":\"class-1\"}");
        join.setCreatedAt(sharedTimestamp);
        join.setSequenceNo(101L);

        when(outboxEventRepository.findEligiblePendingEvents(any(), any(Pageable.class))).thenReturn(List.of(removal, join));

        outboxWorker.processOutboxEvents();

        // The earlier-sequenced removal runs; the join is held back for a later pass.
        verify(neo4jSyncService).removeUserClassMembership("u-1", "class-1");
        verify(neo4jSyncService, never()).syncUserClassMembership(any(), any());
        assertEquals("PROCESSED", removal.getStatus());
        assertEquals("PENDING", join.getStatus());

        // The gate is asked about the sequence number, never about the timestamp or the id.
        // Checked once before the claim and once after it; never by timestamp or id.
        verify(outboxEventRepository, times(2)).existsEarlierUncompletedEvent("CLASSROOM", "class-1", 100L);
    }

    @Test
    @DisplayName("Ordering: An event without a sequence number is deferred rather than applied out of order")
    void testEventWithoutSequenceNumberIsDeferred() {
        OutboxEvent event = new OutboxEvent("CLASSROOM", "class-1", "MEMBER_JOINED", "{\"userId\":\"u-1\",\"classId\":\"class-1\"}");
        event.setSequenceNo(null);
        when(outboxEventRepository.findEligiblePendingEvents(any(), any(Pageable.class))).thenReturn(List.of(event));

        outboxWorker.processOutboxEvents();

        verify(outboxEventRepository, never()).claimEvent(eq(event.getId()), any());
        verify(neo4jSyncService, never()).syncUserClassMembership(any(), any());
        assertEquals("PENDING", event.getStatus());
    }
}
