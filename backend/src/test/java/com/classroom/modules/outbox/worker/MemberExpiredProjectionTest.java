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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-19: MEMBER_EXPIRED (a paid member's access ran out) is projected like MEMBER_REMOVED - the Neo4j membership edge disappears, the MongoDB
 * learning-event document is written - and is idempotent: replaying it, or receiving it twice, changes nothing more. It is also one of the
 * Neo4j event types, so the "Neo4j is down" filter treats it like its siblings.
 */
@ExtendWith(MockitoExtension.class)
class MemberExpiredProjectionTest {

    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private Neo4jSyncService neo4jSyncService;
    @Mock private LearningEventRepository learningEventRepository;

    private final TestClock clock = TestClock.at("2026-01-01T00:00:00Z");
    private OutboxWorker outboxWorker;

    @BeforeEach
    void setUp() {
        outboxWorker = new OutboxWorker(outboxEventRepository, neo4jSyncService, new OutboxProperties(), clock, Runnable::run);
        ReflectionTestUtils.setField(outboxWorker, "learningEventRepository", learningEventRepository);
        lenient().when(outboxEventRepository.claimEvent(any(), any())).thenReturn(1);
        lenient().when(outboxEventRepository.markProcessed(any(), any())).thenReturn(1);
        lenient().when(outboxEventRepository.markTransientFailure(any(), any())).thenReturn(1);
        lenient().when(outboxEventRepository.markPermanentFailure(any(), any(), anyInt(), any(), any())).thenReturn(1);
        lenient().when(outboxEventRepository.existsEarlierUncompletedEvent(any(), any(), anyLong())).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        outboxWorker.shutdown();
    }

    private static OutboxEvent event(String type, long sequenceNo) {
        OutboxEvent event = new OutboxEvent("CLASSROOM", "class-1", type,
                "{\"userId\":\"u-1\",\"classId\":\"class-1\",\"expiredAt\":\"2026-01-01T00:00:00Z\"}");
        event.setSequenceNo(sequenceNo);
        return event;
    }

    private void heads(OutboxEvent... events) {
        when(outboxEventRepository.findEligiblePendingEvents(any(), any(Pageable.class))).thenReturn(List.of(events));
    }

    @Test
    @DisplayName("MEMBER_EXPIRED removes the Neo4j membership edge (it never re-creates it) and writes the MongoDB document")
    void memberExpiredRemovesTheEdge() {
        heads(event("MEMBER_EXPIRED", 7L));

        outboxWorker.processOutboxEvents();

        verify(neo4jSyncService).removeUserClassMembership("u-1", "class-1");
        verify(neo4jSyncService, never()).syncUserClassMembership(any(), any());
        ArgumentCaptor<LearningEventDocument> doc = ArgumentCaptor.forClass(LearningEventDocument.class);
        verify(learningEventRepository).save(doc.capture());
        assertEquals("MEMBER_EXPIRED", doc.getValue().getEventType());
        assertEquals("class-1", doc.getValue().getAggregateId());
        verify(outboxEventRepository).markProcessed(any(), any());
    }

    @Test
    @DisplayName("the same MEMBER_EXPIRED processed twice only deletes an already-deleted edge; a renewal's MEMBER_JOINED afterwards puts it back, in order")
    void idempotentAndOrdered() {
        // one head per aggregate per poll: the same aggregate's events are projected on consecutive polls, in sequence order
        OutboxEvent expired = event("MEMBER_EXPIRED", 10L);
        OutboxEvent expiredAgain = event("MEMBER_EXPIRED", 11L);
        OutboxEvent renewed = event("MEMBER_JOINED", 12L);
        when(outboxEventRepository.findEligiblePendingEvents(any(), any(Pageable.class)))
                .thenReturn(List.of(expired), List.of(expiredAgain), List.of(renewed));

        outboxWorker.processOutboxEvents();
        outboxWorker.processOutboxEvents();
        outboxWorker.processOutboxEvents();

        InOrder order = inOrder(neo4jSyncService);
        order.verify(neo4jSyncService, times(2)).removeUserClassMembership("u-1", "class-1");
        order.verify(neo4jSyncService).syncUserClassMembership("u-1", "class-1");
    }

    @Test
    @DisplayName("MEMBER_EXPIRED is one of the Neo4j event types, so it is held back with MEMBER_JOINED / MEMBER_REMOVED while Neo4j is down")
    void isANeo4jEventType() {
        assertTrue(OutboxWorker.NEO4J_EVENT_TYPES.contains("MEMBER_EXPIRED"));
        assertTrue(OutboxWorker.MEMBERSHIP_ENDED_EVENT_TYPES.containsAll(List.of("MEMBER_REMOVED", "MEMBER_EXPIRED")));
    }
}
