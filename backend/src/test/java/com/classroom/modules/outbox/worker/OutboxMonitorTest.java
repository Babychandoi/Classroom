package com.classroom.modules.outbox.worker;

import com.classroom.modules.outbox.model.OutboxEvent;
import com.classroom.modules.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** R20-04: the counts and sink states an operator sees (health details, log line, studio status). */
@ExtendWith(MockitoExtension.class)
class OutboxMonitorTest {

    @Mock
    private OutboxEventRepository repository;
    @Mock
    private OutboxWorker worker;

    private final TestClock clock = TestClock.at("2026-01-01T00:10:00Z");
    private final OutboxProperties properties = new OutboxProperties();
    private OutboxMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new OutboxMonitor(repository, worker, properties, clock);
        lenient().when(worker.sinkStatuses()).thenReturn(List.of(
                new SinkStatus("MONGO", true, true, "CLOSED", 0, null, null, null),
                new SinkStatus("NEO4J", true, false, "OPEN", 3, Instant.parse("2026-01-01T00:08:00Z"),
                        Instant.parse("2026-01-01T00:10:15Z"), "Unable to connect")));
    }

    private static List<Object[]> counts(Object... statusAndCount) {
        java.util.ArrayList<Object[]> rows = new java.util.ArrayList<>();
        for (int i = 0; i < statusAndCount.length; i += 2) {
            rows.add(new Object[]{statusAndCount[i], statusAndCount[i + 1]});
        }
        return rows;
    }

    @Test
    @DisplayName("snapshot: counts by status, age of the oldest pending event, cached for cache-ms")
    void snapshotCountsAndCaches() {
        OutboxEvent oldest = new OutboxEvent("CLASSROOM", "c", "MEMBER_JOINED", "{}");
        oldest.setCreatedAt(Instant.parse("2026-01-01T00:09:00Z"));
        when(repository.countByStatuses(anyCollection())).thenReturn(counts("PENDING", 12L, "DEAD_LETTER", 2L, "PROCESSING", 1L));
        when(repository.findFirstByStatusOrderBySequenceNoAsc("PENDING")).thenReturn(Optional.of(oldest));

        OutboxMonitor.Snapshot s = monitor.snapshot();

        assertEquals(12, s.pending());
        assertEquals(2, s.deadLetter());
        assertEquals(1, s.processing());
        assertEquals(0, s.failed());
        assertEquals(60L, s.oldestPendingSeconds());
        assertTrue(s.needsAttention());

        clock.advance(Duration.ofSeconds(1));
        monitor.snapshot();
        verify(repository, times(1)).countByStatuses(anyCollection());
        clock.advance(Duration.ofSeconds(10));
        monitor.snapshot();
        verify(repository, times(2)).countByStatuses(anyCollection());
    }

    @Test
    @DisplayName("an empty outbox reports zeros, no oldest-pending age and needs no attention")
    void emptyOutbox() {
        when(repository.countByStatuses(anyCollection())).thenReturn(List.of());

        OutboxMonitor.Snapshot s = monitor.snapshot();

        assertEquals(0, s.pending());
        assertNull(s.oldestPendingSeconds());
        assertFalse(s.needsAttention());
    }

    @Test
    @DisplayName("health: always UP (never takes the app out of rotation) with counts, attention flag and per-store state")
    void healthIndicatorReportsDetailsButStaysUp() {
        when(repository.countByStatuses(anyCollection())).thenReturn(counts("PENDING", 30L, "DEAD_LETTER", 1L));
        when(repository.findFirstByStatusOrderBySequenceNoAsc("PENDING")).thenReturn(Optional.empty());

        Health health = new OutboxHealthIndicator(monitor).health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(30L, health.getDetails().get("pending"));
        assertEquals(1L, health.getDetails().get("deadLetter"));
        assertEquals(true, health.getDetails().get("attention"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> sinks = (Map<String, Map<String, Object>>) health.getDetails().get("sinks");
        assertEquals("UP", sinks.get("mongo").get("status"));
        assertEquals("DOWN", sinks.get("neo4j").get("status"));
        assertEquals("Unable to connect", sinks.get("neo4j").get("lastError"));
    }

    @Test
    @DisplayName("health: a failing count query is UNKNOWN, not DOWN")
    void healthIndicatorSurvivesADatabaseError() {
        when(repository.countByStatuses(anyCollection())).thenThrow(new IllegalStateException("db down"));

        assertEquals(Status.UNKNOWN, new OutboxHealthIndicator(monitor).health().getStatus());
    }

    @Test
    @DisplayName("class status: counts only the events that belong to the class (by payload classId or CLASSROOM aggregate)")
    void classStatusIsScopedToTheClass() {
        OutboxEvent mine = new OutboxEvent("CLASSROOM", "class-1", "MEMBER_JOINED", "{\"classId\":\"class-1\"}");
        mine.setStatus("DEAD_LETTER");
        OutboxEvent minePending = new OutboxEvent("EXAM", "attempt-9", "EXAM_SUBMITTED", "{\"classId\":\"class-1\"}");
        OutboxEvent other = new OutboxEvent("CLASSROOM", "class-2", "MEMBER_JOINED", "{\"classId\":\"class-2\"}");
        other.setStatus("DEAD_LETTER");
        when(repository.findByStatusInOrderBySequenceNoAsc(anyList(), any(Pageable.class))).thenReturn(List.of(mine, minePending, other));

        Map<String, Object> status = monitor.classStatus("class-1");

        assertEquals(1L, status.get("deadLetter"));
        assertEquals(1L, status.get("pending"));
        assertEquals(false, status.get("truncated"));
        @SuppressWarnings("unchecked")
        Map<String, Object> sinks = (Map<String, Object>) status.get("sinks");
        assertEquals("UP", sinks.get("mongo"));
        assertEquals("DOWN", sinks.get("neo4j"));
    }
}
