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
import org.junit.jupiter.api.Test;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R20-04 / R20-05 end to end against a real database (H2 in this suite; the same SQL runs on MySQL in the drill): the real repository
 * queries, the real worker, the retention and re-drive jobs, and FAKE projection stores that fail on demand. A private in-memory
 * database and {@code classroom.outbox.enabled=false} keep the application's own background worker from touching these events.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:outbox-pipeline;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "classroom.outbox.enabled=false",
        "classroom.outbox.retention.enabled=false",
        "classroom.outbox.redrive.enabled=false",
        "classroom.seed.demo.enabled=false"
})
class OutboxPipelineScenarioTest {

    private static final AtomicLong SEQUENCE = new AtomicLong(1000);

    @Autowired
    private OutboxEventRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    private final TestClock clock = TestClock.at("2026-03-01T00:00:00Z");
    private final OutboxProperties properties = new OutboxProperties();

    // ---- fake projection stores ------------------------------------------------------------------------------------------
    private final AtomicInteger mongoTransientFailuresLeft = new AtomicInteger();
    private final Set<String> mongoPoisonEventIds = ConcurrentHashMap.newKeySet();
    private final List<String> mongoSaved = new CopyOnWriteArrayList<>();
    private final AtomicBoolean neo4jDown = new AtomicBoolean();
    private final List<String> neo4jCalls = new CopyOnWriteArrayList<>();

    private OutboxWorker worker;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM outbox_events");
        mongoTransientFailuresLeft.set(0);
        mongoPoisonEventIds.clear();
        mongoSaved.clear();
        neo4jDown.set(false);
        neo4jCalls.clear();

        LearningEventRepository mongo = mock(LearningEventRepository.class);
        when(mongo.save(any())).thenAnswer(invocation -> {
            LearningEventDocument doc = invocation.getArgument(0);
            if (mongoPoisonEventIds.contains(doc.getEventId())) {
                throw new IllegalArgumentException("poison payload " + doc.getEventId());
            }
            if (mongoTransientFailuresLeft.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                throw new DataAccessResourceFailureException("Timed out after 3000 ms while waiting for a server",
                        new MongoTimeoutException("Timed out after 3000 ms"));
            }
            mongoSaved.add(doc.getEventId());
            return doc;
        });
        Neo4jSyncService neo4j = mock(Neo4jSyncService.class);
        doAnswer(invocation -> {
            if (neo4jDown.get()) {
                throw new RuntimeException("Neo4j projection failed: Unable to connect to neo4j:7687",
                        new ServiceUnavailableException("Unable to connect to neo4j:7687, ensure the database is running"));
            }
            neo4jCalls.add("join:" + invocation.getArgument(0) + "@" + invocation.getArgument(1));
            return null;
        }).when(neo4j).syncUserClassMembership(any(), any());
        doAnswer(invocation -> {
            if (neo4jDown.get()) {
                throw new RuntimeException("Neo4j projection failed: Unable to connect", new ServiceUnavailableException("Unable to connect"));
            }
            neo4jCalls.add("remove:" + invocation.getArgument(0) + "@" + invocation.getArgument(1));
            return null;
        }).when(neo4j).removeUserClassMembership(any(), any());

        worker = new OutboxWorker(repository, neo4j, properties, clock, Runnable::run);
        ReflectionTestUtils.setField(worker, "learningEventRepository", mongo);
    }

    @AfterEach
    void tearDown() {
        worker.shutdown();
        jdbc.update("DELETE FROM outbox_events");
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    /** Inserts an event and gives it the next insert-sequence number (MySQL's AUTO_INCREMENT does that in production). */
    private String insert(String aggregateType, String aggregateId, String eventType, String payload) {
        OutboxEvent saved = repository.saveAndFlush(new OutboxEvent(aggregateType, aggregateId, eventType, payload));
        jdbc.update("UPDATE outbox_events SET sequence_no = ? WHERE id = ?", SEQUENCE.incrementAndGet(), saved.getId());
        return saved.getId();
    }

    private String join(String classId, String userId) {
        return insert("CLASSROOM", classId, "MEMBER_JOINED", "{\"userId\":\"" + userId + "\",\"classId\":\"" + classId + "\"}");
    }

    private Map<String, Object> row(String id) {
        return jdbc.queryForMap("SELECT status, retry_count, failure_kind, auto_replay_count, error_message FROM outbox_events WHERE id = ?", id);
    }

    private String status(String id) {
        return String.valueOf(row(id).get("STATUS"));
    }

    private long count(String status) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE status = ?", Long.class, status);
    }

    /** Polls once a second (of test time) until the condition holds, failing the test on any dead letter when {@code noDeadLetters}. */
    private int pollUntil(BooleanSupplier done, int maxPolls, boolean noDeadLetters) {
        for (int i = 1; i <= maxPolls; i++) {
            worker.processOutboxEvents();
            if (noDeadLetters) {
                assertEquals(0, count("DEAD_LETTER"), "nothing may be dead-lettered by a dependency outage (poll " + i + ")");
            }
            if (done.getAsBoolean()) {
                return i;
            }
            clock.advanceSeconds(1);
        }
        throw new AssertionError("condition not reached within " + maxPolls + " polls");
    }

    private List<String> savedOf(List<String> ids) {
        return mongoSaved.stream().filter(ids::contains).distinct().toList();
    }

    // ------------------------------------------------------------------------------------------- R20-04b: recovery

    @Test
    @DisplayName("R20-04b: MongoDB fails 12 times in a row (more than the 5-retry limit), then recovers -> NO dead letter, every event processed, order preserved")
    void transientMongoOutageDoesNotDeadLetterAndPreservesOrder() {
        List<String> joins = new ArrayList<>();
        for (int i = 0; i < 5; i++) joins.add(join("class-A", "u-" + i));
        List<String> exam = List.of(
                insert("EXAM", "attempt-1", "EXAM_SUBMITTED", "{\"userId\":\"u-1\",\"classId\":\"class-A\"}"),
                insert("EXAM", "attempt-1", "EXAM_PUBLISHED", "{\"userId\":\"u-1\",\"classId\":\"class-A\"}"));
        String order = insert("COMMERCE", "order-1", "ORDER_PAID", "{}");
        int total = joins.size() + exam.size() + 1;
        mongoTransientFailuresLeft.set(12);

        int polls = pollUntil(() -> count("PROCESSED") == total, 600, true);

        assertEquals(total, count("PROCESSED"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE retry_count <> 0", Long.class),
                "a dependency outage must never consume the permanent retry budget");
        assertEquals(joins, savedOf(joins), "per-aggregate order: the joins of the class were projected in insert order");
        assertEquals(exam, savedOf(exam), "per-aggregate order: submitted before published");
        assertNotNull(order);
        assertTrue(polls > 12, "recovery needs the breaker's probes (took " + polls + " test-seconds)");
    }

    @Test
    @DisplayName("R20-04b: while Neo4j is down MongoDB-only events keep flowing; the class's joins wait (PENDING, retry 0) and resume in order after recovery")
    void neo4jOutageOnlyHoldsTheEventsThatNeedIt() {
        List<String> joins = new ArrayList<>();
        for (int i = 0; i < 4; i++) joins.add(join("class-B", "u-" + i));
        String exam = insert("EXAM", "attempt-9", "EXAM_SUBMITTED", "{\"userId\":\"u-9\"}");
        neo4jDown.set(true);

        pollUntil(() -> "PROCESSED".equals(status(exam)), 30, true);
        for (int i = 0; i < 60; i++) { // a minute of outage
            worker.processOutboxEvents();
            clock.advanceSeconds(1);
        }

        assertEquals("PROCESSED", status(exam), "the MongoDB-only event is not held up by the Neo4j outage");
        for (String id : joins) {
            assertEquals("PENDING", status(id));
            assertEquals(0, ((Number) row(id).get("RETRY_COUNT")).intValue());
        }
        assertEquals("TRANSIENT", row(joins.get(0)).get("FAILURE_KIND"));
        assertTrue(String.valueOf(row(joins.get(0)).get("ERROR_MESSAGE")).contains("Neo4j"));
        assertEquals(0, count("DEAD_LETTER"));
        assertFalse(worker.sinkStatuses().get(1).up());
        assertTrue(worker.sinkStatuses().get(0).up());

        neo4jDown.set(false);
        pollUntil(() -> count("PROCESSED") == 5, 60, true);

        assertEquals(List.of("join:u-0@class-B", "join:u-1@class-B", "join:u-2@class-B", "join:u-3@class-B"), neo4jCalls,
                "the joins were applied to Neo4j once, in insert order");
        assertTrue(worker.sinkStatuses().get(1).up());
    }

    // ------------------------------------------------------------------------ poison events (deterministic failures)

    @Test
    @DisplayName("a poison event still dead-letters after 5 attempts and blocks ITS aggregate only; other aggregates are unaffected; a replay revives it in order")
    void poisonEventDeadLettersBlocksItsAggregateAndIsReplayable() {
        String first = join("class-P", "u-1");
        String poison = join("class-P", "u-2");
        String behind = join("class-P", "u-3");
        String other = join("class-Q", "u-9");
        mongoPoisonEventIds.add(poison);

        pollUntil(() -> "DEAD_LETTER".equals(status(poison)), 120, false);

        assertEquals("PROCESSED", status(first));
        assertEquals(5, ((Number) row(poison).get("RETRY_COUNT")).intValue(), "dead-lettered after exactly the retry limit");
        assertEquals("PERMANENT", row(poison).get("FAILURE_KIND"));
        assertTrue(String.valueOf(row(poison).get("ERROR_MESSAGE")).contains("poison payload"));
        assertEquals("PENDING", status(behind), "the aggregate behind the poison event is blocked, as designed");
        assertEquals("PROCESSED", status(other), "another aggregate is unaffected");
        for (int i = 0; i < 30; i++) { // it stays blocked, without hammering
            worker.processOutboxEvents();
            clock.advanceSeconds(1);
        }
        assertEquals("PENDING", status(behind));
        assertEquals(5, ((Number) row(poison).get("RETRY_COUNT")).intValue());

        // the operator fixes the cause and replays (the same call the Studio endpoint makes)
        mongoPoisonEventIds.clear();
        assertEquals(1, worker.replayFailedEvents("class-P"));
        assertEquals("PENDING", status(poison));
        pollUntil(() -> "PROCESSED".equals(status(behind)), 30, true);

        assertEquals("PROCESSED", status(poison));
        assertEquals(List.of(first, poison, behind), savedOf(List.of(first, poison, behind)), "replayed in the original order");
        assertEquals(0, ((Number) row(poison).get("AUTO_REPLAY_COUNT")).intValue());
    }

    // ------------------------------------------------------------------------------------------- automatic re-drive

    private String deadLetter(String classId, String userId, String kind, String message, Duration deadFor) {
        String id = join(classId, userId);
        OutboxEvent event = repository.findById(id).orElseThrow();
        event.setStatus("DEAD_LETTER");
        event.setRetryCount(5);
        event.setFailureKind(kind);
        event.setErrorMessage(message);
        event.setProcessedAt(clock.instant().minus(deadFor));
        repository.saveAndFlush(event);
        return id;
    }

    @Test
    @DisplayName("R20-04b: an event dead-lettered by an OLD version during an outage (error text only) is re-driven automatically and then processed")
    void legacyTransientDeadLetterIsRedrivenAndProcessed() {
        String stuck = deadLetter("class-R", "u-1", null,
                "Neo4j: Neo4j projection failed: Unable to connect to neo4j:7687, ensure the database is running", Duration.ofMinutes(3));
        String behind = join("class-R", "u-2");
        OutboxRedriveJob redrive = new OutboxRedriveJob(repository, properties, clock);

        assertEquals(1, redrive.redriveDeadLetters());

        assertEquals("PENDING", status(stuck));
        assertEquals(1, ((Number) row(stuck).get("AUTO_REPLAY_COUNT")).intValue());
        assertTrue(String.valueOf(row(stuck).get("ERROR_MESSAGE")).contains("[auto-replay 1/3]"));
        pollUntil(() -> "PROCESSED".equals(status(behind)), 20, true);
        assertEquals("PROCESSED", status(stuck));
        assertEquals(List.of(stuck, behind), savedOf(List.of(stuck, behind)));
    }

    @Test
    @DisplayName("R20-04b: a poison dead letter is auto-replayed at most 3 times (each after 30 minutes), then only a manual replay revives it")
    void autoReplayBudgetIsBounded() {
        String poison = deadLetter("class-S", "u-1", "PERMANENT", "MongoDB: poison", Duration.ofMinutes(45));
        mongoPoisonEventIds.add(poison);
        OutboxRedriveJob redrive = new OutboxRedriveJob(repository, properties, clock);

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertEquals(1, redrive.redriveDeadLetters(), "auto-replay " + attempt);
            pollUntil(() -> "DEAD_LETTER".equals(status(poison)), 120, false); // it fails again, 5 permanent failures
            clock.advance(Duration.ofMinutes(31));
        }

        assertEquals(3, ((Number) row(poison).get("AUTO_REPLAY_COUNT")).intValue());
        assertEquals(0, redrive.redriveDeadLetters(), "the budget is spent: the event stays DEAD_LETTER for an operator");
        assertEquals("DEAD_LETTER", status(poison));

        assertEquals(1, worker.replayFailedEvents("class-S"), "a manual replay still works and resets the budget");
        assertEquals(0, ((Number) row(poison).get("AUTO_REPLAY_COUNT")).intValue());
    }

    @Test
    @DisplayName("R20-04b: a fresh dead letter is not replayed before its grace period, and only the head of an aggregate is replayed")
    void redriveHonoursGracePeriodAndAggregateHead() {
        String fresh = deadLetter("class-T", "u-1", "TRANSIENT", "Neo4j: Unable to connect", Duration.ofSeconds(20));
        String behind = deadLetter("class-T", "u-2", "TRANSIENT", "Neo4j: Unable to connect", Duration.ofMinutes(10));
        OutboxRedriveJob redrive = new OutboxRedriveJob(repository, properties, clock);

        assertEquals(0, redrive.redriveDeadLetters(), "the head has been dead for 20 s only; the one behind it is gated by the head");
        assertEquals("DEAD_LETTER", status(fresh));
        assertEquals("DEAD_LETTER", status(behind));

        clock.advanceSeconds(60);
        assertEquals(1, redrive.redriveDeadLetters());
        assertEquals("PENDING", status(fresh));
        assertEquals("DEAD_LETTER", status(behind), "the second one waits until the head is done");
    }

    // ------------------------------------------------------------------------------------------ R20-05: throughput

    @Test
    @DisplayName("R20-05: 200 members joining ONE class are projected in 4 polls (50 per pass), in order - it took one poll cycle per event (~400 s)")
    void twoHundredJoinsToOneClassDrainInFourPolls() {
        List<String> joins = new ArrayList<>();
        for (int i = 0; i < 200; i++) joins.add(join("class-BIG", "u-" + i));

        worker.processOutboxEvents();
        assertEquals(50, count("PROCESSED"), "one poll projects a whole pass of 50 consecutive events of the aggregate");
        worker.processOutboxEvents();
        worker.processOutboxEvents();
        worker.processOutboxEvents();

        assertEquals(200, count("PROCESSED"));
        assertEquals(joins, savedOf(joins), "MongoDB saw the joins in insert order");
        List<String> expectedNeo4j = new ArrayList<>();
        for (int i = 0; i < 200; i++) expectedNeo4j.add("join:u-" + i + "@class-BIG");
        assertEquals(expectedNeo4j, neo4jCalls, "Neo4j saw them in insert order, exactly once each");
    }

    @Test
    @DisplayName("R20-05: many aggregates are all dispatched in one poll; the eligible query skips blocked aggregates instead of starving later ones")
    void manyAggregatesAreDispatchedTogether() {
        for (int i = 0; i < 30; i++) insert("EXAM", "attempt-" + i, "EXAM_SUBMITTED", "{\"userId\":\"u-" + i + "\"}");

        worker.processOutboxEvents();

        assertEquals(30, count("PROCESSED"));
    }

    // ----------------------------------------------------------------------------------------------- R20-05: retention

    private String processedAt(String aggregateId, Duration age, String status) {
        String id = insert("EXAM", aggregateId, "EXAM_SUBMITTED", "{}");
        OutboxEvent event = repository.findById(id).orElseThrow();
        event.setStatus(status);
        event.setProcessedAt(clock.instant().minus(age));
        repository.saveAndFlush(event);
        return id;
    }

    @Test
    @DisplayName("R20-05: retention purges only PROCESSED events older than 7 days, in small batches; PENDING / DEAD_LETTER are never touched")
    void retentionPurgesOnlyOldProcessedEvents() {
        List<String> old = new ArrayList<>();
        for (int i = 0; i < 5; i++) old.add(processedAt("old-" + i, Duration.ofDays(8), "PROCESSED"));
        String recent = processedAt("recent", Duration.ofDays(1), "PROCESSED");
        String oldPending = processedAt("old-pending", Duration.ofDays(30), "PENDING");
        String oldDead = processedAt("old-dead", Duration.ofDays(30), "DEAD_LETTER");
        String legacy = processedAt("legacy", Duration.ofDays(1), "PROCESSED"); // PROCESSED without processed_at, created 9 days ago
        jdbc.update("UPDATE outbox_events SET processed_at = NULL, created_at = ? WHERE id = ?",
                Timestamp.from(clock.instant().minus(Duration.ofDays(9))), legacy);
        properties.getRetention().setBatchSize(2);
        properties.getRetention().setPauseMs(0);
        OutboxRetentionJob retention = new OutboxRetentionJob(repository, properties, clock);

        int purged = retention.purgeExpired();

        assertEquals(6, purged, "5 old PROCESSED + 1 legacy PROCESSED without a timestamp, 2 at a time");
        for (String id : old) assertFalse(repository.existsById(id));
        assertFalse(repository.existsById(legacy));
        assertTrue(repository.existsById(recent));
        assertTrue(repository.existsById(oldPending));
        assertTrue(repository.existsById(oldDead));
        assertEquals(0, retention.purgeExpired(), "a second run has nothing to do");
    }
}
