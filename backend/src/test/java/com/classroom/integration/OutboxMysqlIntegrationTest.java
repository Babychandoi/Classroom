package com.classroom.integration;

import com.classroom.modules.outbox.model.OutboxEvent;
import com.classroom.modules.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R20-04 / R20-05 on the real MySQL of the integration stack: the V36 schema, the targeted UPDATEs of the worker (transient vs permanent
 * failure, guarded terminal writes), the per-aggregate batch fetch, the retention and re-drive queries.
 *
 * <p>The live application context of the other integration tests runs its own outbox worker against this database, so every event created
 * here is kept out of its reach: a PENDING event carries a high {@code retry_count} and a fresh {@code processed_at}, which puts it deep in
 * the back-off window (minutes) of the worker's eligibility query; everything else is PROCESSED / DEAD_LETTER, which no worker touches.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("integration")
class OutboxMysqlIntegrationTest {

    @Autowired
    private OutboxEventRepository repository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;

    private final String aggregateType = "R21IT";
    private final String aggregateId = "agg-" + UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM outbox_events WHERE aggregate_type = ?", aggregateType);
    }

    private String create(String status, int retryCount, Instant processedAt) {
        OutboxEvent event = new OutboxEvent(aggregateType, aggregateId, "R21_EVENT", "{}");
        event.setStatus(status);
        event.setRetryCount(retryCount);
        event.setProcessedAt(processedAt);
        return repository.saveAndFlush(event).getId();
    }

    /** A PENDING event the live worker will not touch for minutes. */
    private String parkedPending() {
        return create("PENDING", 8, Instant.now());
    }

    private String field(String id, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM outbox_events WHERE id = ?", String.class, id);
    }

    @Test
    @DisplayName("V36 created the failure_kind / auto_replay_count columns and the three worker indexes")
    void v36SchemaIsPresent() {
        List<String> indexes = jdbc.queryForList("SELECT DISTINCT index_name FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = 'outbox_events'", String.class);
        assertTrue(indexes.containsAll(Set.of("idx_outbox_status_seq", "idx_outbox_status_processed", "idx_outbox_aggregate_status_seq")), indexes.toString());
        List<String> columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = 'outbox_events'", String.class);
        assertTrue(columns.containsAll(Set.of("failure_kind", "auto_replay_count")), columns.toString());
    }

    @Test
    @DisplayName("V36 is replayable: running the whole script again on the migrated schema changes nothing and does not fail")
    void v36CanBeReplayed() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V36__outbox_indexes_failure_kind_and_retention.sql"));
        }
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() "
                + "AND table_name = 'outbox_events' AND index_name = 'idx_outbox_status_seq' AND seq_in_index = 1", Long.class));
    }

    @Test
    @DisplayName("a transient failure releases the claim WITHOUT touching retry_count; a permanent one counts a retry; the terminal writes are guarded")
    void failureWritesMatchTheirMeaning() {
        String id = parkedPending();

        assertEquals(1, repository.claimEvent(id, Instant.now()));
        assertEquals("PROCESSING", field(id, "status"));
        assertEquals(1, repository.markTransientFailure(id, "[TRANSIENT] MongoDB: Timed out after 3000 ms"));
        assertEquals("PENDING", field(id, "status"));
        assertEquals("8", field(id, "retry_count"), "the outage did not consume a retry");
        assertEquals("TRANSIENT", field(id, "failure_kind"));

        assertEquals(0, repository.markTransientFailure(id, "again"), "guarded: only a PROCESSING row can be released");
        assertEquals(0, repository.markProcessed(id, Instant.now()), "guarded: a PENDING row cannot be marked processed");

        assertEquals(1, repository.claimEvent(id, Instant.now()));
        assertEquals(1, repository.markPermanentFailure(id, "PENDING", 9, "MongoDB: poison", Instant.now()));
        assertEquals("9", field(id, "retry_count"));
        assertEquals("PERMANENT", field(id, "failure_kind"));

        assertEquals(1, repository.claimEvent(id, Instant.now()));
        assertEquals(1, repository.markProcessed(id, Instant.now()));
        assertEquals("PROCESSED", field(id, "status"));
        assertEquals(null, field(id, "error_message"));
        assertEquals(null, field(id, "failure_kind"));
    }

    @Test
    @DisplayName("findPendingAfter returns the PENDING events behind a sequence number in insert order, bounded by the page size")
    void pendingAfterIsOrderedAndBounded() {
        String head = parkedPending();
        String second = parkedPending();
        String third = parkedPending();
        String fourth = parkedPending();
        long headSeq = repository.findById(head).orElseThrow().getSequenceNo();

        List<OutboxEvent> all = repository.findPendingAfter(aggregateType, aggregateId, headSeq, PageRequest.of(0, 10));
        assertEquals(List.of(second, third, fourth), all.stream().map(OutboxEvent::getId).toList());

        List<OutboxEvent> bounded = repository.findPendingAfter(aggregateType, aggregateId, headSeq, PageRequest.of(0, 2));
        assertEquals(List.of(second, third), bounded.stream().map(OutboxEvent::getId).toList());
        assertEquals(List.of(), repository.findPendingAfter(aggregateType, "no-such-aggregate", 0, PageRequest.of(0, 10)));
    }

    @Test
    @DisplayName("the ordering gate sees a DEAD_LETTER (or PROCESSING) event behind which later events must wait")
    void gateSeesUncompletedEvents() {
        String processed = create("PROCESSED", 0, Instant.now());
        String dead = create("DEAD_LETTER", 5, Instant.now());
        String later = parkedPending();
        long deadSeq = repository.findById(dead).orElseThrow().getSequenceNo();
        long laterSeq = repository.findById(later).orElseThrow().getSequenceNo();

        assertEquals(false, repository.existsEarlierUncompletedEvent(aggregateType, aggregateId, deadSeq));
        assertEquals(true, repository.existsEarlierUncompletedEvent(aggregateType, aggregateId, laterSeq));
        assertTrue(repository.findById(processed).isPresent());
    }

    @Test
    @DisplayName("retention: old PROCESSED ids are found through (status, processed_at) and deleted by id; other statuses are never deleted")
    void retentionQueries() {
        Instant old = Instant.now().minus(9, ChronoUnit.DAYS);
        String oldProcessed = create("PROCESSED", 0, old);
        String recentProcessed = create("PROCESSED", 0, Instant.now());
        String oldDead = create("DEAD_LETTER", 5, old);
        String parked = parkedPending();
        jdbc.update("UPDATE outbox_events SET processed_at = ? WHERE id = ?", Timestamp.from(old), parked);
        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);

        List<String> ids = repository.findProcessedIdsBefore(cutoff, PageRequest.of(0, 1000)).stream()
                .filter(id -> Set.of(oldProcessed, recentProcessed, oldDead, parked).contains(id)).toList();
        assertEquals(List.of(oldProcessed), ids);

        assertEquals(1, repository.deleteProcessedByIds(List.of(oldProcessed, oldDead, parked)),
                "only the PROCESSED row is deletable even if other ids are passed");
        assertEquals(false, repository.existsById(oldProcessed));
        assertTrue(repository.existsById(recentProcessed));
        assertTrue(repository.existsById(oldDead));
        assertTrue(repository.existsById(parked));
    }

    @Test
    @DisplayName("re-drive: dead letters are offered oldest first while their auto-replay budget lasts; autoReplay counts a use and re-queues")
    void redriveQueries() {
        Instant dead = Instant.now().minus(10, ChronoUnit.MINUTES);
        String replayable = create("DEAD_LETTER", 5, dead);
        String exhausted = create("DEAD_LETTER", 5, dead);
        jdbc.update("UPDATE outbox_events SET auto_replay_count = 3 WHERE id = ?", exhausted);
        String tooFresh = create("DEAD_LETTER", 5, Instant.now());
        Set<String> mine = Set.of(replayable, exhausted, tooFresh);

        List<String> candidates = repository.findRedriveCandidates(3, Instant.now().minus(1, ChronoUnit.MINUTES), PageRequest.of(0, 500))
                .stream().map(OutboxEvent::getId).filter(mine::contains).toList();
        assertEquals(List.of(replayable), candidates);

        assertEquals(1, repository.autoReplay(replayable, "[auto-replay 1/3]"));
        assertEquals("PENDING", field(replayable, "status"));
        assertEquals("1", field(replayable, "auto_replay_count"));
        assertEquals("0", field(replayable, "retry_count"));
        assertEquals(0, repository.autoReplay(replayable, "again"), "guarded: only a DEAD_LETTER row can be re-driven");
    }

    @Test
    @DisplayName("countByStatuses reports the not-yet-finished events by status")
    void statusCounts() {
        create("PROCESSED", 0, Instant.now());
        create("DEAD_LETTER", 5, Instant.now());
        parkedPending();

        long dead = 0;
        long pending = 0;
        for (Object[] row : repository.countByStatuses(List.of("PENDING", "PROCESSING", "FAILED", "DEAD_LETTER"))) {
            if ("DEAD_LETTER".equals(row[0])) dead = ((Number) row[1]).longValue();
            if ("PENDING".equals(row[0])) pending = ((Number) row[1]).longValue();
        }
        assertTrue(dead >= 1);
        assertTrue(pending >= 1);
    }
}
