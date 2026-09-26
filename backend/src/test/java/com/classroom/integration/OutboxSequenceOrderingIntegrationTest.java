package com.classroom.integration;

import com.classroom.modules.outbox.model.OutboxEvent;
import com.classroom.modules.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Database-backed regression test for per-aggregate outbox ordering.
 *
 * <p>{@code outbox_events.created_at} only has second precision, so two membership transitions for
 * the same class can share it. The worker used to tie-break on the random {@code id}, which could
 * order a MEMBER_REMOVED after the MEMBER_JOINED that actually followed it and leave the Neo4j
 * membership projection in the wrong state. Ordering now comes from the database insert sequence.</p>
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("integration")
public class OutboxSequenceOrderingIntegrationTest {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String aggregateId = "seq-class-" + UUID.randomUUID().toString().substring(0, 8);
    private final List<String> createdIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        if (!createdIds.isEmpty()) {
            jdbcTemplate.update("DELETE FROM outbox_events WHERE aggregate_id = ?", aggregateId);
            jdbcTemplate.batchUpdate("DELETE FROM outbox_events WHERE id = ?", createdIds, createdIds.size(),
                    (statement, id) -> statement.setString(1, id));
        }
    }

    @Test
    @DisplayName("Events sharing a created_at are ordered and gated by their insert sequence, not by id")
    void testInsertSequenceOrdersEventsThatShareATimestamp() {
        OutboxEvent removal = save("MEMBER_REMOVED");
        OutboxEvent join = save("MEMBER_JOINED");

        // Force the exact collision the second-precision column allows.
        jdbcTemplate.update("UPDATE outbox_events SET created_at = '2026-01-01 00:00:00' WHERE aggregate_id = ?",
                aggregateId);

        assertNotNull(removal.getSequenceNo(), "The database must assign an insert sequence");
        assertNotNull(join.getSequenceNo(), "The database must assign an insert sequence");
        assertTrue(join.getSequenceNo() > removal.getSequenceNo(),
                "The later insert must carry the higher sequence");

        // The worker's batch query returns them in insert order regardless of id or timestamp.
        List<OutboxEvent> pending = outboxEventRepository.findTop50ByStatusOrderBySequenceNoAsc("PENDING")
                .stream().filter(e -> aggregateId.equals(e.getAggregateId())).toList();
        assertEquals(List.of("MEMBER_REMOVED", "MEMBER_JOINED"),
                pending.stream().map(OutboxEvent::getEventType).toList());

        // And the causal gate agrees: the join is blocked while the removal is unprocessed.
        assertFalse(outboxEventRepository.existsEarlierUncompletedEvent(
                "CLASSROOM", aggregateId, removal.getSequenceNo()));
        assertTrue(outboxEventRepository.existsEarlierUncompletedEvent(
                "CLASSROOM", aggregateId, join.getSequenceNo()));
    }

    @Test
    @DisplayName("Eligible batch skips blocked aggregates and backoff rows instead of starving later aggregates")
    void eligibleBatchAdvancesPastBlockedAndBackoffEvents() {
        OutboxEvent blocked = save("MEMBER_REMOVED");
        OutboxEvent sameAggregate = save("MEMBER_JOINED");
        OutboxEvent unrelated = outboxEventRepository.saveAndFlush(
                new OutboxEvent("COMMERCE", "unrelated-" + UUID.randomUUID(), "ORDER_PAID", "{}"));
        createdIds.add(unrelated.getId());
        blocked.setStatus("DEAD_LETTER");
        outboxEventRepository.save(blocked);
        sameAggregate.setStatus("PENDING");
        sameAggregate.setRetryCount(1);
        sameAggregate.setProcessedAt(java.time.Instant.now());
        outboxEventRepository.save(sameAggregate);
        outboxEventRepository.flush();

        List<OutboxEvent> eligible = outboxEventRepository.findEligiblePendingEvents(
                java.time.Instant.now(), PageRequest.of(0, 50));
        assertFalse(eligible.stream().anyMatch(e -> e.getId().equals(sameAggregate.getId())));
        assertTrue(eligible.stream().anyMatch(e -> e.getId().equals(unrelated.getId())));
    }

    private OutboxEvent save(String eventType) {
        OutboxEvent event = outboxEventRepository.saveAndFlush(
                new OutboxEvent("CLASSROOM", aggregateId, eventType, "{\"userId\":\"u-1\"}"));
        createdIds.add(event.getId());
        return event;
    }
}
