package com.classroom.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R20-05 / R20-04 / R20-08: static guard on V36 (the behaviour on a real MySQL, including "idle poll < 50 ms with 300 000 PROCESSED rows", is
 * measured on the drill). The migration must build its indexes ONLINE, be replayable, and carry the UTC note that documents R20-08.
 */
class V36MigrationScriptTest {

    private String script() throws Exception {
        return new String(new ClassPathResource("db/migration/V36__outbox_indexes_failure_kind_and_retention.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private String statements() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String line : script().split("\\R")) {
            if (!line.trim().startsWith("--")) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("V36 adds the three indexes matching the worker's queries, online (INPLACE, LOCK=NONE)")
    void indexesAreOnline() throws Exception {
        String sql = statements();
        assertTrue(sql.contains("idx_outbox_status_seq (status, sequence_no)"));
        assertTrue(sql.contains("idx_outbox_status_processed (status, processed_at)"));
        assertTrue(sql.contains("idx_outbox_aggregate_status_seq (aggregate_type, aggregate_id, status, sequence_no)"));
        assertTrue(sql.contains("ALGORITHM=INPLACE, LOCK=NONE"), "index builds must not block concurrent inserts");
        assertFalse(sql.toUpperCase().contains("ALGORITHM=COPY"));
    }

    @Test
    @DisplayName("V36 adds the failure classification / auto-replay columns as an INSTANT (metadata-only) change")
    void columnsAreInstant() throws Exception {
        String sql = statements();
        assertTrue(sql.contains("ADD COLUMN failure_kind VARCHAR(16) NULL"));
        assertTrue(sql.contains("ADD COLUMN auto_replay_count INT NOT NULL DEFAULT 0"));
        assertTrue(sql.contains("ALGORITHM=INSTANT"));
    }

    @Test
    @DisplayName("V36 is replayable after a partially applied run: every DDL is guarded by an information_schema check")
    void statementsAreGuarded() throws Exception {
        String sql = statements();
        assertTrue(sql.contains("information_schema.columns"));
        assertTrue(sql.contains("information_schema.statistics"));
        assertEquals(2, sql.split("PREPARE stmt FROM @ddl;").length - 1, "exactly the two guarded statements");
    }

    @Test
    @DisplayName("R20-08: the V36 header documents that the database must run in UTC and names the startup preflight and its override")
    void headerCarriesTheUtcNote() throws Exception {
        String header = script();
        assertTrue(header.contains("R20-08"));
        assertTrue(header.contains("V30"));
        assertTrue(header.contains("UTC"));
        assertTrue(header.contains("app.db.allow-non-utc-migration"));
        assertTrue(header.contains("DatabaseTimeZonePreflight"));
    }

    @Test
    @DisplayName("V36 itself must NOT carry the requires-utc-server marker: it only adds columns and indexes and must run on any server zone")
    void v36DoesNotRequireUtc() throws Exception {
        assertFalse(script().toLowerCase().contains("requires-utc-server"));
    }
}
