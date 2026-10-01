package com.classroom.modules.outbox.worker;

import com.classroom.modules.outbox.model.OutboxEvent;
import com.classroom.modules.outbox.repository.OutboxEventRepository;
import com.classroom.modules.projection.mongo.LearningEventDocument;
import com.classroom.modules.projection.mongo.LearningEventRepository;
import com.classroom.modules.projection.neo4j.Neo4jSyncService;
import com.mongodb.MongoTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R20-04 / R20-05 on the REAL MySQL of the integration stack, through a real worker with FAKE projection stores: a store that fails with
 * transient errors and then recovers loses nothing and dead-letters nothing, a poison event dead-letters after its retry budget and is
 * replayable, and 60 consecutive events of one aggregate are projected in order within one pass.
 *
 * <p>The application context of the other integration tests runs its own outbox worker against the same database (with the real stores),
 * and this test must neither steal their events nor lose its own to them:
 * <ul>
 *   <li>every event here is created PENDING with {@code retry_count = BASE_RETRIES} and a fresh {@code processed_at}: for a worker at the real
 *       time it is still inside its back-off window (minutes), while THIS test's worker runs on a clock set two hours ahead, for which the
 *       window is long over. The test worker's retry ceiling is raised by the same base so "5 permanent failures" still means 5;</li>
 *   <li>the test does not call the global poll ({@code processOutboxEvents}); it hands the heads of ITS aggregates to
 *       {@code projectAggregate}, the very method the executor tasks run, so nobody else's events are touched.</li>
 * </ul>
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("integration")
@org.springframework.transaction.annotation.Transactional
class OutboxPipelineMysqlIntegrationTest {

    private static final int BASE_RETRIES = 8; // back-off 2^8 = 256 s for a worker at the real time
    private static final String AGGREGATE_TYPE = "R21PIPE";
    /**
     * Test time advances 301 s per poll: the events carry retry_count = BASE_RETRIES (see the class comment), so this worker's own permanent
     * back-off (2^retry s, at most 300 s) must have elapsed at every poll. It does not change what is proved: breaker probes are due
     * every poll, and the order/no-dead-letter/batching assertions do not depend on the pacing.
     */
    private static final long POLL_STEP_SECONDS = 301;

    @Autowired
    private OutboxEventRepository repository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private final TestClock clock = new TestClock(Instant.now().plusSeconds(2 * 3600));
    private final OutboxProperties properties = new OutboxProperties();
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    private final AtomicInteger mongoTransientFailuresLeft = new AtomicInteger();
    private final Set<String> mongoPoisonEventIds = ConcurrentHashMap.newKeySet();
    private final List<String> mongoSaved = new CopyOnWriteArrayList<>();
    private final AtomicBoolean neo4jDown = new AtomicBoolean();
    private final List<String> neo4jCalls = new CopyOnWriteArrayList<>();
    private OutboxWorker worker;

    @BeforeEach
    void setUp() {
        properties.setMaxRetries(BASE_RETRIES + 5);
        LearningEventRepository mongo = mock(LearningEventRepository.class);
        when(mongo.save(any())).thenAnswer(invocation -> {
            LearningEventDocument doc = invocation.getArgument(0);
            if (mongoPoisonEventIds.contains(doc.getEventId())) {
                throw new IllegalArgumentException("poison payload " + doc.getEventId());
            }
            if (mongoTransientFailuresLeft.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                throw new DataAccessResourceFailureException("Timed out after 3000 ms", new MongoTimeoutException("Timed out after 3000 ms"));
            }
            mongoSaved.add(doc.getEventId());
            return doc;
        });
        Neo4jSyncService neo4j = mock(Neo4jSyncService.class);
        doAnswer(invocation -> {
            if (neo4jDown.get()) {
                throw new RuntimeException("Neo4j projection failed", new ServiceUnavailableException("Unable to connect to neo4j:7687"));
            }
            neo4jCalls.add(invocation.getArgument(0) + "@" + invocation.getArgument(1));
            return null;
        }).when(neo4j).syncUserClassMembership(any(), any());
        worker = new OutboxWorker(repository, neo4j, properties, clock, Runnable::run);
        ReflectionTestUtils.setField(worker, "learningEventRepository", mongo);
    }

    @AfterEach
    void cleanup() {
        worker.shutdown();
        jdbc.update("DELETE FROM outbox_events WHERE aggregate_type = ?", AGGREGATE_TYPE);
    }

    private String join(String aggregateId, String userId) {
        OutboxEvent event = new OutboxEvent(AGGREGATE_TYPE, aggregateId, "MEMBER_JOINED",
                "{\"userId\":\"" + userId + "\",\"classId\":\"" + aggregateId + "\"}");
        event.setRetryCount(BASE_RETRIES);
        event.setProcessedAt(Instant.now());
        return repository.saveAndFlush(event).getId();
    }

    private Map<String, Object> row(String id) {
        return jdbc.queryForMap("SELECT status, retry_count, failure_kind, auto_replay_count FROM outbox_events WHERE id = ?", id);
    }

    private String status(String id) {
        return String.valueOf(row(id).get("status"));
    }

    private long count(String status) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE aggregate_type = ? AND status = ?", Long.class, AGGREGATE_TYPE, status);
    }

    /** One "poll" restricted to this test's aggregates: the head (first PENDING event) of each goes to the worker's real per-aggregate pass. */
    private void pollAggregates(String... aggregateIds) {
        // Bulk lease/replay updates bypass Hibernate's identity map.
        entityManager.clear();
        for (String aggregateId : aggregateIds) {
            List<OutboxEvent> heads = repository.findPendingAfter(AGGREGATE_TYPE, aggregateId, 0, PageRequest.of(0, 1));
            if (!heads.isEmpty()) {
                worker.projectAggregate(heads.get(0));
            }
        }
    }

    private void pollUntil(BooleanSupplier done, int maxPolls, boolean noDeadLetters, String... aggregateIds) {
        for (int i = 1; i <= maxPolls; i++) {
            pollAggregates(aggregateIds);
            if (noDeadLetters) {
                assertEquals(0, count("DEAD_LETTER"), "an outage must never dead-letter (poll " + i + ")");
            }
            if (done.getAsBoolean()) {
                return;
            }
            clock.advanceSeconds(POLL_STEP_SECONDS);
        }
        throw new AssertionError("condition not reached within " + maxPolls + " polls");
    }

    @Test
    @DisplayName("MySQL: a store that fails 12 times with transient errors then recovers -> nothing dead-lettered, retry_count untouched, all events projected in order")
    void transientOutageRecovers() {
        List<String> joins = new ArrayList<>();
        for (int i = 0; i < 6; i++) joins.add(join("class-" + suffix, "u-" + i));
        mongoTransientFailuresLeft.set(12);

        pollUntil(() -> count("PROCESSED") == joins.size(), 600, true, "class-" + suffix);

        assertEquals(joins, mongoSaved.stream().filter(joins::contains).distinct().toList(), "insert order preserved");
        assertEquals(6, neo4jCalls.size());
        for (String id : joins) {
            assertEquals("PROCESSED", status(id));
        }
    }

    @Test
    @DisplayName("MySQL: while Neo4j is down the events wait as PENDING with retry_count untouched (failure_kind TRANSIENT) and resume in order")
    void neo4jOutageKeepsEventsPending() {
        List<String> joins = new ArrayList<>();
        for (int i = 0; i < 4; i++) joins.add(join("class-" + suffix, "u-" + i));
        neo4jDown.set(true);

        for (int i = 0; i < 40; i++) {
            pollAggregates("class-" + suffix);
            clock.advanceSeconds(POLL_STEP_SECONDS);
        }
        for (String id : joins) {
            assertEquals("PENDING", status(id));
            assertEquals(BASE_RETRIES, ((Number) row(id).get("retry_count")).intValue());
        }
        assertEquals("TRANSIENT", row(joins.get(0)).get("failure_kind"));
        assertEquals(0, count("DEAD_LETTER"));

        neo4jDown.set(false);
        pollUntil(() -> count("PROCESSED") == joins.size(), 60, true, "class-" + suffix);
        assertEquals(List.of("u-0@class-" + suffix, "u-1@class-" + suffix, "u-2@class-" + suffix, "u-3@class-" + suffix), neo4jCalls);
    }

    @Test
    @DisplayName("MySQL: a poison event dead-letters after 5 permanent failures, blocks only its aggregate, and a replay revives it in order")
    void poisonEventDeadLettersAndIsReplayable() {
        String first = join("class-" + suffix, "u-1");
        String poison = join("class-" + suffix, "u-2");
        String behind = join("class-" + suffix, "u-3");
        String other = join("class-other-" + suffix, "u-9");
        mongoPoisonEventIds.add(poison);

        pollUntil(() -> "DEAD_LETTER".equals(status(poison)), 200, false, "class-" + suffix, "class-other-" + suffix);

        assertEquals("PROCESSED", status(first));
        assertEquals(BASE_RETRIES + 5, ((Number) row(poison).get("retry_count")).intValue());
        assertEquals("PERMANENT", row(poison).get("failure_kind"));
        assertEquals("PENDING", status(behind), "the aggregate behind the poison event waits, as designed");
        assertEquals("PROCESSED", status(other), "another aggregate is not affected");

        mongoPoisonEventIds.clear();
        // The operator's replay, done in SQL so the revived event stays parked for the live workers of the other contexts (the real
        // replayFailedEvents also resets retry_count to 0, which would make it instantly eligible for THEM; that method is covered on H2).
        assertEquals(1, jdbc.update("UPDATE outbox_events SET status = 'PENDING', retry_count = ?, processed_at = ?, failure_kind = NULL WHERE id = ? AND status = 'DEAD_LETTER'",
                BASE_RETRIES, java.sql.Timestamp.from(Instant.now()), poison));
        pollUntil(() -> "PROCESSED".equals(status(behind)), 60, true, "class-" + suffix);
        assertEquals("PROCESSED", status(poison));
        assertEquals(List.of(first, poison, behind), mongoSaved.stream().filter(List.of(first, poison, behind)::contains).distinct().toList());
    }

    @Test
    @DisplayName("MySQL: 60 consecutive events of ONE aggregate are projected in a single pass of 50 plus one more, in insert order")
    void consecutiveEventsAreBatched() {
        List<String> joins = new ArrayList<>();
        for (int i = 0; i < 60; i++) joins.add(join("class-big-" + suffix, "u-" + i));

        pollAggregates("class-big-" + suffix);
        assertEquals(50, count("PROCESSED"), "one pass = the head + 49 followers");
        pollAggregates("class-big-" + suffix);

        assertEquals(60, count("PROCESSED"));
        assertEquals(joins, mongoSaved.stream().filter(joins::contains).toList());
        assertTrue(neo4jCalls.get(0).startsWith("u-0@") && neo4jCalls.get(59).startsWith("u-59@"));
    }
}
