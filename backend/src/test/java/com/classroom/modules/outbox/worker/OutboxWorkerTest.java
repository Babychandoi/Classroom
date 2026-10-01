package com.classroom.modules.outbox.worker;

import com.classroom.modules.outbox.model.OutboxEvent;
import com.classroom.modules.outbox.repository.OutboxEventRepository;
import com.classroom.modules.projection.mongo.LearningEventDocument;
import com.classroom.modules.projection.mongo.LearningEventRepository;
import com.classroom.modules.projection.neo4j.Neo4jSyncService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.ConnectException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

    private final TestClock clock = TestClock.at("2026-01-01T00:00:00Z");
    private final OutboxProperties properties = new OutboxProperties();
    private OutboxWorker outboxWorker;

    @BeforeEach
    void setUp() {
        // A same-thread executor keeps these tests deterministic; the threading contract has its own tests below.
        outboxWorker = new OutboxWorker(outboxEventRepository, neo4jSyncService, properties, clock, Runnable::run);
        ReflectionTestUtils.setField(outboxWorker, "learningEventRepository", learningEventRepository);
        lenient().when(outboxEventRepository.claimEvent(any(), any())).thenReturn(1);
        lenient().when(outboxEventRepository.markProcessed(any(), any())).thenReturn(1);
        lenient().when(outboxEventRepository.markTransientFailure(any(), any())).thenReturn(1);
        lenient().when(outboxEventRepository.markPermanentFailure(any(), any(), anyInt(), any(), any())).thenReturn(1);
        // Defaults for the calls a test overrides for particular arguments (strict stubs would flag the other arguments otherwise).
        lenient().when(outboxEventRepository.existsEarlierUncompletedEvent(any(), any(), anyLong())).thenReturn(false);
        lenient().doNothing().when(neo4jSyncService).syncUserClassMembership(any(), any());
        lenient().doNothing().when(neo4jSyncService).removeUserClassMembership(any(), any());
    }

    @AfterEach
    void tearDown() {
        outboxWorker.shutdown();
    }

    private void heads(OutboxEvent... events) {
        when(outboxEventRepository.findEligiblePendingEvents(any(), any(Pageable.class))).thenReturn(List.of(events));
    }

    private static OutboxEvent event(String aggregateType, String aggregateId, String type, String payload, long sequenceNo) {
        OutboxEvent event = new OutboxEvent(aggregateType, aggregateId, type, payload);
        event.setSequenceNo(sequenceNo);
        return event;
    }

    // ------------------------------------------------------------------------------------- success / failure basics

    @Test
    @DisplayName("Finding 11: a PERMANENT MongoDB failure keeps the event PENDING for a back-off retry, counts a retry, and does NOT mark PROCESSED")
    void testPermanentMongoFailureDoesNotMarkProcessed() {
        OutboxEvent event = event("COMMERCE", "order-1", "ORDER_PAID", "{}", 1L);
        heads(event);
        when(learningEventRepository.save(any())).thenThrow(new IllegalStateException("MongoDB rejected the document"));

        outboxWorker.processOutboxEvents();

        assertEquals("PENDING", event.getStatus());
        assertEquals(1, event.getRetryCount());
        assertEquals("PERMANENT", event.getFailureKind());
        assertTrue(event.getErrorMessage().contains("MongoDB"));
        verify(outboxEventRepository).markPermanentFailure(eq(event.getId()), eq("PENDING"), eq(1), contains("MongoDB"), any());
        verify(outboxEventRepository, never()).markProcessed(any(), any());
    }

    @Test
    @DisplayName("R20-04b: a TRANSIENT MongoDB failure (timeout) does NOT count a retry and never dead-letters; the event is released to PENDING")
    void testTransientMongoFailureDoesNotCountARetry() {
        OutboxEvent event = event("COMMERCE", "order-1", "ORDER_PAID", "{}", 1L);
        heads(event);
        when(learningEventRepository.save(any())).thenThrow(new RuntimeException("MongoDB connection timeout"));

        outboxWorker.processOutboxEvents();

        assertEquals("PENDING", event.getStatus());
        assertEquals(0, event.getRetryCount(), "a dependency outage must not consume the event's permanent retry budget");
        assertEquals("TRANSIENT", event.getFailureKind());
        assertTrue(event.getErrorMessage().contains("MongoDB"));
        verify(outboxEventRepository).markTransientFailure(eq(event.getId()), contains("MongoDB"));
        verify(outboxEventRepository, never()).markPermanentFailure(any(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("Finding 11: a PERMANENT Neo4j failure keeps the event PENDING for retry and does NOT mark PROCESSED")
    void testPermanentNeo4jFailureDoesNotMarkProcessed() {
        OutboxEvent event = event("CLASSROOM", "user-1", "MEMBER_JOINED", "{}", 2L);
        heads(event);
        doThrow(new IllegalArgumentException("Neo4j statement rejected"))
                .when(neo4jSyncService).syncUserClassMembership(any(), any());

        outboxWorker.processOutboxEvents();

        assertEquals("PENDING", event.getStatus());
        assertEquals(1, event.getRetryCount());
        assertTrue(event.getErrorMessage().contains("Neo4j"));
        verify(outboxEventRepository).markPermanentFailure(eq(event.getId()), eq("PENDING"), eq(1), contains("Neo4j"), any());
        verify(outboxEventRepository, never()).markProcessed(any(), any());
    }

    @Test
    @DisplayName("Finding 11: Successful projections transition event to PROCESSED")
    void testSuccessfulProjectionMarksProcessed() {
        OutboxEvent event = event("COMMERCE", "order-1", "ORDER_PAID", "{}", 3L);
        heads(event);

        outboxWorker.processOutboxEvents();

        assertEquals("PROCESSED", event.getStatus());
        assertNotNull(event.getProcessedAt());
        assertNull(event.getErrorMessage());
        verify(outboxEventRepository).markProcessed(eq(event.getId()), any());
    }

    @Test
    @DisplayName("Finding 6: When MongoDB repository is null but projection enabled, event is NOT marked PROCESSED")
    void testNullMongoRepositoryDoesNotMarkProcessed() {
        ReflectionTestUtils.setField(outboxWorker, "learningEventRepository", null);
        ReflectionTestUtils.setField(outboxWorker, "mongoEnabled", true);
        OutboxEvent event = event("COMMERCE", "order-1", "ORDER_PAID", "{}", 4L);
        heads(event);

        outboxWorker.processOutboxEvents();

        assertEquals("PENDING", event.getStatus());
        assertEquals(1, event.getRetryCount());
        assertNotNull(event.getErrorMessage());
        assertTrue(event.getErrorMessage().contains("MongoDB"));
        verify(outboxEventRepository).markPermanentFailure(eq(event.getId()), eq("PENDING"), eq(1), contains("MongoDB"), any());
    }

    @Test
    @DisplayName("Finding 6: When MongoDB projection is explicitly disabled by config, event succeeds without Mongo")
    void testDisabledMongoProjectionSucceeds() {
        ReflectionTestUtils.setField(outboxWorker, "learningEventRepository", null);
        ReflectionTestUtils.setField(outboxWorker, "mongoEnabled", false);
        OutboxEvent event = event("COMMERCE", "order-1", "ORDER_PAID", "{}", 5L);
        heads(event);

        outboxWorker.processOutboxEvents();

        assertEquals("PROCESSED", event.getStatus());
        assertNotNull(event.getProcessedAt());
        assertNull(event.getErrorMessage());
        verify(outboxEventRepository).markProcessed(eq(event.getId()), any());
    }

    @Test
    @DisplayName("Finding 8: MEMBER_JOINED extracts userId and classId from payload for Neo4j projection")
    void testMemberJoinedExtractsPayloadForNeo4j() {
        OutboxEvent event = event("CLASSROOM", "class-123", "MEMBER_JOINED", "{\"userId\":\"user-456\",\"classId\":\"class-123\"}", 6L);
        heads(event);

        outboxWorker.processOutboxEvents();

        assertEquals("PROCESSED", event.getStatus());
        verify(neo4jSyncService).syncUserClassMembership("user-456", "class-123");
        verify(learningEventRepository).save(argThat(doc ->
                doc.getEventType().equals("MEMBER_JOINED") && doc.getAggregateId().equals("class-123")));
    }

    @Test
    @DisplayName("Finding 8: LESSON_COMPLETED projects learning activity to MongoDB (and only MongoDB)")
    void testLessonCompletedProjectsToMongo() {
        OutboxEvent event = event("LEARNING", "lesson-789", "LESSON_COMPLETED", "{\"userId\":\"u-1\",\"classId\":\"c-1\"}", 7L);
        heads(event);

        outboxWorker.processOutboxEvents();

        assertEquals("PROCESSED", event.getStatus());
        verify(learningEventRepository).save(argThat(doc ->
                doc.getEventType().equals("LESSON_COMPLETED") && doc.getAggregateId().equals("lesson-789")));
        verifyNoInteractions(neo4jSyncService);
    }

    @Test
    @DisplayName("Finding 11: Outbox event transitions to DEAD_LETTER after 5 PERMANENT failures without being dropped")
    void testDeadLetterTransitionAfterFiveRetries() {
        OutboxEvent event = event("COMMERCE", "order-1", "ORDER_PAID", "{}", 8L);
        event.setRetryCount(4);
        heads(event);
        when(learningEventRepository.save(any())).thenThrow(new IllegalStateException("MongoDB persistent rejection"));

        outboxWorker.processOutboxEvents();

        assertEquals("DEAD_LETTER", event.getStatus());
        assertEquals(5, event.getRetryCount());
        assertNotNull(event.getErrorMessage());
        verify(outboxEventRepository).markPermanentFailure(eq(event.getId()), eq("DEAD_LETTER"), eq(5), any(), any());
    }

    @Test
    @DisplayName("R1-09: reclaiming a stale PROCESSING event increments retryCount so it eventually dead-letters")
    void testStaleProcessingReclaimIncrementsRetryCount() {
        when(outboxEventRepository.findEligiblePendingEvents(any(), any(Pageable.class))).thenReturn(List.of());

        outboxWorker.processOutboxEvents();

        verify(outboxEventRepository).deadLetterStaleProcessingEventsAtRetryLimit(any(), eq(5));
        verify(outboxEventRepository).resetStaleProcessingEvents(any(), eq(5));
    }

    @Test
    @DisplayName("R20-05: the stale-PROCESSING reclaim runs every stale-scan-interval, not on every poll")
    void testStaleReclaimIsThrottled() {
        when(outboxEventRepository.findEligiblePendingEvents(any(), any(Pageable.class))).thenReturn(List.of());

        outboxWorker.processOutboxEvents();
        clock.advance(Duration.ofSeconds(1));
        outboxWorker.processOutboxEvents();
        outboxWorker.processOutboxEvents();
        verify(outboxEventRepository, times(1)).resetStaleProcessingEvents(any(), anyInt());

        clock.advance(Duration.ofSeconds(20));
        outboxWorker.processOutboxEvents();
        verify(outboxEventRepository, times(2)).resetStaleProcessingEvents(any(), anyInt());
    }

    @Test
    @DisplayName("classroom.outbox.enabled=false: the poll does nothing at all")
    void testDisabledPollDoesNothing() {
        properties.setEnabled(false);

        outboxWorker.processOutboxEvents();

        verifyNoInteractions(outboxEventRepository, learningEventRepository, neo4jSyncService);
    }

    // ---------------------------------------------------------------------------------------------------- replay

    @Test
    @DisplayName("Finding 11: replayFailedEvents resets DEAD_LETTER and FAILED events to PENDING with retryCount 0 (and a fresh auto-replay budget)")
    void testReplayFailedEvents() {
        OutboxEvent deadLetterEvent = event("COMMERCE", "order-1", "ORDER_PAID", "{}", 9L);
        deadLetterEvent.setStatus("DEAD_LETTER");
        deadLetterEvent.setRetryCount(5);
        deadLetterEvent.setAutoReplayCount(3);
        deadLetterEvent.setFailureKind("PERMANENT");

        OutboxEvent failedEvent = event("COMMERCE", "order-2", "ORDER_PAID", "{}", 10L);
        failedEvent.setStatus("FAILED");
        failedEvent.setRetryCount(5);

        when(outboxEventRepository.findTop50ByStatusOrderBySequenceNoAsc("DEAD_LETTER")).thenReturn(List.of(deadLetterEvent));
        when(outboxEventRepository.findTop50ByStatusOrderBySequenceNoAsc("FAILED")).thenReturn(List.of(failedEvent));

        int replayed = outboxWorker.replayFailedEvents();

        assertEquals(2, replayed);
        assertEquals("PENDING", deadLetterEvent.getStatus());
        assertEquals(0, deadLetterEvent.getRetryCount());
        assertEquals(0, deadLetterEvent.getAutoReplayCount(), "a manual replay resets the automatic re-drive budget");
        assertNull(deadLetterEvent.getFailureKind());
        assertEquals("PENDING", failedEvent.getStatus());
        assertEquals(0, failedEvent.getRetryCount());
        verify(outboxEventRepository).save(deadLetterEvent);
        verify(outboxEventRepository).save(failedEvent);
    }

    @Test
    @DisplayName("Replay: A replayed DEAD_LETTER event returns to PENDING so ordering is re-established")
    void testReplayRequeuesDeadLetterEventForOrderedRetry() {
        OutboxEvent removal = event("CLASSROOM", "class-1", "MEMBER_REMOVED", "{\"userId\":\"u-1\",\"classId\":\"class-1\"}", 20L);
        removal.setStatus("DEAD_LETTER");
        removal.setRetryCount(5);
        when(outboxEventRepository.findByStatusInOrderBySequenceNoAsc(eq(List.of("DEAD_LETTER", "FAILED")), any(Pageable.class)))
                .thenReturn(List.of(removal));

        int replayed = outboxWorker.replayFailedEvents("class-1");

        assertEquals(1, replayed);
        assertEquals("PENDING", removal.getStatus());
        assertEquals(0, removal.getRetryCount());
        verify(outboxEventRepository).save(removal);
    }

    // --------------------------------------------------------------------------------------- back-off and ordering

    @Test
    @DisplayName("Finding 11: Exponential backoff skips premature retry of a PERMANENT failure within its back-off window")
    void testExponentialBackoffSkipsPrematureRetry() {
        OutboxEvent event = event("COMMERCE", "order-1", "ORDER_PAID", "{}", 11L);
        event.setRetryCount(3); // backoff is 2^3 = 8 seconds
        event.setProcessedAt(clock.instant()); // just attempted right now
        heads(event);

        outboxWorker.processOutboxEvents();

        verify(learningEventRepository, never()).save(any());
        verify(outboxEventRepository, never()).claimEvent(any(), any());

        clock.advance(Duration.ofSeconds(9));
        outboxWorker.processOutboxEvents();
        verify(learningEventRepository).save(any());
    }

    @Test
    @DisplayName("Ordering: When event 1 for aggregate A is in backoff, newer event 2 for aggregate A is NOT processed")
    void testAggregateOrderingPreservedWhenPriorEventBackingOff() {
        OutboxEvent event1 = event("CLASSROOM", "class-1", "MEMBER_JOINED", "{\"userId\":\"u-1\",\"classId\":\"class-1\"}", 12L);
        event1.setRetryCount(2);
        event1.setProcessedAt(clock.instant()); // in backoff window
        OutboxEvent event2 = event("CLASSROOM", "class-1", "MEMBER_REMOVED", "{\"userId\":\"u-1\",\"classId\":\"class-1\"}", 13L);
        heads(event1, event2);

        outboxWorker.processOutboxEvents();

        verify(neo4jSyncService, never()).removeUserClassMembership(any(), any());
        verify(outboxEventRepository, never()).markProcessed(eq(event2.getId()), any());
        verify(outboxEventRepository, never()).claimEvent(eq(event2.getId()), any());
    }

    @Test
    @DisplayName("Ordering: two events of one aggregate in the same batch never run beside each other - the second waits for the first")
    void testAggregateOrderingPreservedInBatch() {
        OutboxEvent event1 = event("CLASSROOM", "class-1", "MEMBER_JOINED", "{\"userId\":\"u-1\",\"classId\":\"class-1\"}", 14L);
        OutboxEvent event2 = event("CLASSROOM", "class-1", "MEMBER_REMOVED", "{\"userId\":\"u-1\",\"classId\":\"class-1\"}", 15L);
        heads(event1, event2);

        outboxWorker.processOutboxEvents();

        assertEquals("PROCESSED", event1.getStatus());
        verify(outboxEventRepository).markProcessed(eq(event1.getId()), any());
        // event2 is handled only as a follower of event1's pass (none was returned), never as a second head of the same poll.
        assertEquals("PENDING", event2.getStatus());
        verify(outboxEventRepository, never()).claimEvent(eq(event2.getId()), any());
    }

    @Test
    @DisplayName("Claim: When claimEvent returns 0 (claimed by another worker), event is skipped")
    void testDistributedClaimSkipsAlreadyClaimedEvent() {
        OutboxEvent event = event("COMMERCE", "order-1", "ORDER_PAID", "{}", 16L);
        heads(event);
        when(outboxEventRepository.claimEvent(eq(event.getId()), any())).thenReturn(0);

        outboxWorker.processOutboxEvents();

        verify(learningEventRepository, never()).save(any());
        verify(outboxEventRepository, never()).markProcessed(any(), any());
    }

    @Test
    @DisplayName("Ordering: When an earlier uncompleted event exists for the aggregate, the later event is claimed, released and not projected")
    void testAggregateBlockedWhenEarlierUncompletedEventExists() {
        OutboxEvent event = event("CLASSROOM", "class-1", "MEMBER_JOINED", "{\"userId\":\"u-1\",\"classId\":\"class-1\"}", 17L);
        heads(event);
        when(outboxEventRepository.existsEarlierUncompletedEvent(eq("CLASSROOM"), eq("class-1"), anyLong())).thenReturn(true);

        outboxWorker.processOutboxEvents();

        verify(learningEventRepository, never()).save(any());
        verify(neo4jSyncService, never()).syncUserClassMembership(any(), any());
        verify(outboxEventRepository).releaseClaim(event.getId());
        verify(outboxEventRepository, never()).markProcessed(any(), any());
    }

    @Test
    @DisplayName("Ordering: A later MEMBER_JOINED is not projected while an earlier DEAD_LETTER MEMBER_REMOVED is unresolved")
    void testLaterJoinBlockedByEarlierDeadLetterRemoval() {
        OutboxEvent laterJoin = event("CLASSROOM", "class-1", "MEMBER_JOINED", "{\"userId\":\"u-1\",\"classId\":\"class-1\"}", 18L);
        heads(laterJoin);
        when(outboxEventRepository.existsEarlierUncompletedEvent(eq("CLASSROOM"), eq("class-1"), anyLong())).thenReturn(true);

        outboxWorker.processOutboxEvents();

        verify(neo4jSyncService, never()).syncUserClassMembership(any(), any());
        assertEquals("PENDING", laterJoin.getStatus());
    }

    @Test
    @DisplayName("Ordering: Same-aggregate events sharing a created_at are ordered by sequence, not by id")
    void testSameTimestampEventsAreOrderedBySequenceNotId() {
        // created_at has second precision, so a remove and the join that followed it can carry the identical timestamp.
        // Ordering must come from the insert sequence; tie-breaking on the random id could apply the join before the remove.
        Instant sharedTimestamp = Instant.parse("2026-01-01T00:00:00Z");
        OutboxEvent removal = event("CLASSROOM", "class-1", "MEMBER_REMOVED", "{\"userId\":\"u-1\",\"classId\":\"class-1\"}", 100L);
        removal.setCreatedAt(sharedTimestamp);
        OutboxEvent join = event("CLASSROOM", "class-1", "MEMBER_JOINED", "{\"userId\":\"u-1\",\"classId\":\"class-1\"}", 101L);
        join.setCreatedAt(sharedTimestamp);
        heads(removal, join);

        outboxWorker.processOutboxEvents();

        verify(neo4jSyncService).removeUserClassMembership("u-1", "class-1");
        verify(neo4jSyncService, never()).syncUserClassMembership(any(), any());
        assertEquals("PROCESSED", removal.getStatus());
        assertEquals("PENDING", join.getStatus());
        // The gate is asked about the sequence number, never about the timestamp or the id.
        verify(outboxEventRepository, times(1)).existsEarlierUncompletedEvent("CLASSROOM", "class-1", 100L);
    }

    @Test
    @DisplayName("Ordering: An event without a sequence number is deferred rather than applied out of order")
    void testEventWithoutSequenceNumberIsDeferred() {
        OutboxEvent event = event("CLASSROOM", "class-1", "MEMBER_JOINED", "{\"userId\":\"u-1\",\"classId\":\"class-1\"}", 1L);
        event.setSequenceNo(null);
        heads(event);

        outboxWorker.processOutboxEvents();

        verify(outboxEventRepository, never()).claimEvent(eq(event.getId()), any());
        verify(neo4jSyncService, never()).syncUserClassMembership(any(), any());
        assertEquals("PENDING", event.getStatus());
    }

    // ------------------------------------------------------------------------------- R20-05: batching per aggregate

    @Test
    @DisplayName("R20-05: consecutive events of ONE aggregate are projected in a single pass, in order (was: one per 2 s poll)")
    void testConsecutiveEventsOfOneAggregateAreProjectedInOnePass() {
        OutboxEvent head = event("CLASSROOM", "class-1", "MEMBER_JOINED", "{\"userId\":\"u-0\",\"classId\":\"class-1\"}", 1L);
        List<OutboxEvent> followers = new ArrayList<>();
        for (int i = 1; i < 50; i++) {
            followers.add(event("CLASSROOM", "class-1", "MEMBER_JOINED", "{\"userId\":\"u-" + i + "\",\"classId\":\"class-1\"}", 1L + i));
        }
        heads(head);
        when(outboxEventRepository.findPendingAfter(eq("CLASSROOM"), eq("class-1"), eq(1L), any(Pageable.class))).thenReturn(followers);

        outboxWorker.processOutboxEvents();

        InOrder inOrder = inOrder(neo4jSyncService);
        for (int i = 0; i < 50; i++) {
            inOrder.verify(neo4jSyncService).syncUserClassMembership("u-" + i, "class-1");
        }
        verify(outboxEventRepository, times(50)).markProcessed(any(), any());
        assertEquals("PROCESSED", head.getStatus());
        followers.forEach(f -> assertEquals("PROCESSED", f.getStatus()));
    }

    @Test
    @DisplayName("R20-05: a pass stops at the first event that fails - later events of the aggregate are not attempted (order preserved)")
    void testPassStopsAtFirstFailure() {
        OutboxEvent e1 = event("CLASSROOM", "class-1", "MEMBER_JOINED", "{\"userId\":\"u-1\",\"classId\":\"class-1\"}", 1L);
        OutboxEvent e2 = event("CLASSROOM", "class-1", "MEMBER_JOINED", "{\"userId\":\"u-2\",\"classId\":\"class-1\"}", 2L);
        OutboxEvent e3 = event("CLASSROOM", "class-1", "MEMBER_JOINED", "{\"userId\":\"u-3\",\"classId\":\"class-1\"}", 3L);
        heads(e1);
        when(outboxEventRepository.findPendingAfter(eq("CLASSROOM"), eq("class-1"), eq(1L), any(Pageable.class))).thenReturn(List.of(e2, e3));
        doThrow(new IllegalArgumentException("poison")).when(neo4jSyncService).syncUserClassMembership("u-2", "class-1");

        outboxWorker.processOutboxEvents();

        assertEquals("PROCESSED", e1.getStatus());
        assertEquals("PENDING", e2.getStatus());
        assertEquals(1, e2.getRetryCount());
        verify(neo4jSyncService, never()).syncUserClassMembership("u-3", "class-1");
        verify(outboxEventRepository, never()).claimEvent(eq(e3.getId()), any());
    }

    @Test
    @DisplayName("R20-05: the ordering gate is re-checked for every follower, so a gap (non-PENDING event) ends the pass")
    void testGapEndsThePass() {
        OutboxEvent e1 = event("CLASSROOM", "class-1", "MEMBER_JOINED", "{\"userId\":\"u-1\",\"classId\":\"class-1\"}", 1L);
        OutboxEvent e3 = event("CLASSROOM", "class-1", "MEMBER_JOINED", "{\"userId\":\"u-3\",\"classId\":\"class-1\"}", 3L); // seq 2 is DEAD_LETTER
        heads(e1);
        when(outboxEventRepository.findPendingAfter(eq("CLASSROOM"), eq("class-1"), eq(1L), any(Pageable.class))).thenReturn(List.of(e3));
        when(outboxEventRepository.existsEarlierUncompletedEvent("CLASSROOM", "class-1", 3L)).thenReturn(true);

        outboxWorker.processOutboxEvents();

        assertEquals("PROCESSED", e1.getStatus());
        verify(neo4jSyncService, never()).syncUserClassMembership("u-3", "class-1");
        verify(outboxEventRepository).releaseClaim(e3.getId());
    }

    // ------------------------------------------------------------------- R20-04b: dependency down / circuit breaker

    @Test
    @DisplayName("R20-04b: while MongoDB is down the poll does not even read events; the first probe after the back-off decides")
    void testMongoDownSuspendsProjectionAndProbesAfterBackoff() {
        OutboxEvent event = event("COMMERCE", "order-1", "ORDER_PAID", "{}", 1L);
        heads(event);
        when(learningEventRepository.save(any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Timed out while waiting for a server"))
                .thenReturn(null);

        outboxWorker.processOutboxEvents(); // t=0: the attempt fails, the MongoDB breaker opens
        assertEquals(0, event.getRetryCount());
        assertFalse(outboxWorker.sinkStatuses().get(0).up());
        verify(outboxEventRepository, times(1)).findEligiblePendingEvents(any(), any(Pageable.class));

        clock.advance(Duration.ofSeconds(1));
        outboxWorker.processOutboxEvents(); // t=1 s < 2 s back-off: nothing is read, nothing is called
        verify(outboxEventRepository, times(1)).findEligiblePendingEvents(any(), any(Pageable.class));
        verify(learningEventRepository, times(1)).save(any());

        clock.advance(Duration.ofSeconds(2));
        outboxWorker.processOutboxEvents(); // t=3 s: probe succeeds, breaker closes, event processed
        verify(learningEventRepository, times(2)).save(any());
        assertEquals("PROCESSED", event.getStatus());
        assertTrue(outboxWorker.sinkStatuses().get(0).up());
        assertEquals(0, event.getRetryCount());
    }

    @Test
    @DisplayName("R20-04b: a two-minute outage never dead-letters anything and the backlog drains as soon as the store answers")
    void testLongOutageNeverDeadLettersAndRecovers() {
        OutboxEvent event = event("CLASSROOM", "class-1", "MEMBER_JOINED", "{\"userId\":\"u-1\",\"classId\":\"class-1\"}", 1L);
        heads(event);
        AtomicReference<Boolean> neo4jUp = new AtomicReference<>(false);
        doAnswer(invocation -> {
            if (!neo4jUp.get()) {
                throw new RuntimeException("Neo4j projection failed: Unable to connect to neo4j:7687, ensure the database is running",
                        new ConnectException("Connection refused"));
            }
            return null;
        }).when(neo4jSyncService).syncUserClassMembership(any(), any());

        for (int second = 0; second < 120; second++) { // a poll every second for two minutes
            outboxWorker.processOutboxEvents();
            clock.advance(Duration.ofSeconds(1));
        }

        assertEquals("PENDING", event.getStatus(), "an outage must never dead-letter the head event");
        assertEquals(0, event.getRetryCount());
        // Probes are paced by the back-off (2, 4, 8, 15, 15 ... s), not one per poll: ~10 attempts in 120 s, not 120.
        verify(neo4jSyncService, atMost(12)).syncUserClassMembership(any(), any());
        verify(outboxEventRepository, never()).markPermanentFailure(any(), any(), anyInt(), any(), any());

        neo4jUp.set(true);
        clock.advance(Duration.ofSeconds(16)); // the next probe is due within the cap
        outboxWorker.processOutboxEvents();

        assertEquals("PROCESSED", event.getStatus());
        assertTrue(outboxWorker.sinkStatuses().get(1).up());
    }

    @Test
    @DisplayName("R20-04b: while Neo4j is down, events that need only MongoDB keep flowing and Neo4j events are excluded IN SQL")
    void testNeo4jDownDoesNotStarveMongoOnlyEvents() {
        OutboxEvent join = event("CLASSROOM", "class-1", "MEMBER_JOINED", "{\"userId\":\"u-1\",\"classId\":\"class-1\"}", 1L);
        OutboxEvent exam = event("EXAM", "attempt-1", "EXAM_SUBMITTED", "{\"userId\":\"u-1\"}", 2L);
        heads(join);
        doThrow(new org.neo4j.driver.exceptions.ServiceUnavailableException("Unable to connect to neo4j:7687"))
                .when(neo4jSyncService).syncUserClassMembership(any(), any());

        outboxWorker.processOutboxEvents(); // Neo4j breaker opens on the join
        assertFalse(outboxWorker.sinkStatuses().get(1).up());
        assertEquals(0, join.getRetryCount());

        when(outboxEventRepository.findEligiblePendingEventsExcludingTypes(any(), eq(OutboxWorker.NEO4J_EVENT_TYPES), any(Pageable.class)))
                .thenReturn(List.of(exam));
        clock.advance(Duration.ofSeconds(1));
        outboxWorker.processOutboxEvents();

        verify(outboxEventRepository).findEligiblePendingEventsExcludingTypes(any(), eq(OutboxWorker.NEO4J_EVENT_TYPES), any(Pageable.class));
        assertEquals("PROCESSED", exam.getStatus(), "a MongoDB-only event is not held up by the Neo4j outage");
        verify(neo4jSyncService, times(1)).syncUserClassMembership(any(), any());
    }

    // ------------------------------------------------------------------------------ R20-04a: threading / single-flight

    @Test
    @DisplayName("R20-04a: the same aggregate is never queued twice while its task is queued or running (single-flight)")
    void testSingleFlightPerAggregate() {
        List<Runnable> queued = new ArrayList<>();
        OutboxWorker queueing = new OutboxWorker(outboxEventRepository, neo4jSyncService, properties, clock, queued::add);
        ReflectionTestUtils.setField(queueing, "learningEventRepository", learningEventRepository);
        OutboxEvent event = event("CLASSROOM", "class-1", "MEMBER_JOINED", "{\"userId\":\"u-1\",\"classId\":\"class-1\"}", 1L);
        heads(event);

        queueing.processOutboxEvents();
        queueing.processOutboxEvents();
        queueing.processOutboxEvents();

        assertEquals(1, queued.size(), "three polls saw the same head but only ONE task may exist for the aggregate");
        assertEquals(1, queueing.inFlightAggregates());

        queued.get(0).run();
        assertEquals(0, queueing.inFlightAggregates(), "the aggregate is released when its task ends");

        queueing.processOutboxEvents();
        assertEquals(2, queued.size());
    }

    @Test
    @DisplayName("R20-04a: a saturated executor is not an error - the aggregate is simply picked up by a later poll")
    void testSaturatedExecutorIsTolerated() {
        Executor saturated = task -> {
            throw new RejectedExecutionException("full");
        };
        OutboxWorker worker = new OutboxWorker(outboxEventRepository, neo4jSyncService, properties, clock, saturated);
        heads(event("COMMERCE", "order-1", "ORDER_PAID", "{}", 1L), event("COMMERCE", "order-2", "ORDER_PAID", "{}", 2L));

        assertDoesNotThrow(worker::processOutboxEvents);

        assertEquals(0, worker.inFlightAggregates());
        verify(outboxEventRepository, never()).claimEvent(any(), any());
    }

    @Test
    @DisplayName("R20-04a: blocking MongoDB I/O runs on the dedicated outbox executor, NOT on the thread that called the scheduled poll")
    void testBlockingIoRunsOffTheSchedulerThread() throws Exception {
        properties.setWorkers(2);
        OutboxWorker real = new OutboxWorker(outboxEventRepository, neo4jSyncService, properties, clock, null);
        try {
            ReflectionTestUtils.setField(real, "learningEventRepository", learningEventRepository);
            OutboxEvent event = event("COMMERCE", "order-1", "ORDER_PAID", "{}", 1L);
            heads(event);
            CountDownLatch inIo = new CountDownLatch(1);
            CountDownLatch releaseIo = new CountDownLatch(1);
            AtomicReference<String> ioThread = new AtomicReference<>();
            when(learningEventRepository.save(any())).thenAnswer(invocation -> {
                ioThread.set(Thread.currentThread().getName());
                inIo.countDown();
                releaseIo.await(10, TimeUnit.SECONDS); // a MongoDB that hangs for its whole timeout
                return null;
            });

            long started = System.nanoTime();
            real.processOutboxEvents(); // the "scheduler" thread
            long pollMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(inIo.await(5, TimeUnit.SECONDS), "the projection must have started");
            assertTrue(pollMillis < 1000, "the scheduled poll must return immediately, not wait for the store (took " + pollMillis + " ms)");
            assertNotEquals(Thread.currentThread().getName(), ioThread.get());
            assertTrue(ioThread.get().startsWith("outbox-worker-"), "I/O must run on the outbox executor, was " + ioThread.get());
            releaseIo.countDown();
        } finally {
            real.shutdown();
        }
    }
}
