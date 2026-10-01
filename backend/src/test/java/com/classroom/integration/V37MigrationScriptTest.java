package com.classroom.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-19: static guard on V37 (the behaviour on a real MySQL - Flyway applies it and Hibernate validates the mapping - is covered by the
 * integration suite). The migration must be cheap on big tables, replayable, and leave every existing row as it is.
 */
class V37MigrationScriptTest {

    private String script() throws Exception {
        return new String(new ClassPathResource("db/migration/V37__class_visibility_access_invites.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /** The script without its comment lines. */
    private String statements() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String line : script().split("\\R")) {
            if (!line.trim().startsWith("--")) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("V37 adds every new column with the documented type and default, as an INSTANT (metadata-only) change")
    void columnsAreInstantWithTheDocumentedDefaults() throws Exception {
        String sql = statements();
        assertTrue(sql.contains("ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT ''PUBLIC''"), "existing classes stay PUBLIC");
        assertTrue(sql.contains("ADD COLUMN access_type VARCHAR(16) NOT NULL DEFAULT ''FREE''"), "existing classes stay FREE");
        assertTrue(sql.contains("ADD COLUMN access_product_id VARCHAR(36) NULL"));
        assertTrue(sql.contains("ADD COLUMN access_expires_at DATETIME(6) NULL"), "DATETIME(6), never TIMESTAMP");
        assertTrue(sql.contains("ADD COLUMN kind VARCHAR(24) NOT NULL DEFAULT ''STANDARD''"), "existing products stay STANDARD - no backfill needed");
        assertTrue(sql.contains("ADD COLUMN invite_id VARCHAR(36) NULL"));
        assertFalse(sql.toUpperCase().contains("ALGORITHM=COPY"));
        assertFalse(sql.toUpperCase().contains("TIMESTAMP"), "new date columns are DATETIME(6)");
        int instant = sql.split("ALGORITHM=INSTANT", -1).length - 1;
        assertTrue(instant >= 6, "each of the six ADD COLUMNs is ALGORITHM=INSTANT, found " + instant);
    }

    @Test
    @DisplayName("V37 builds both class_members indexes ONLINE (INPLACE, LOCK=NONE): the per-class one from the spec and the sweeper's (state, access_expires_at)")
    void indexesAreOnline() throws Exception {
        String sql = statements();
        assertTrue(sql.contains("ix_class_members_access_expiry (class_id, state, access_expires_at)"));
        assertTrue(sql.contains("ix_class_members_expiry_sweep (state, access_expires_at)"));
        assertTrue(sql.contains("ALGORITHM=INPLACE, LOCK=NONE"));
    }

    @Test
    @DisplayName("V37 creates class_invites with a unique SHA-256 hash column, a class FK that cascades and a version column")
    void inviteTableShape() throws Exception {
        String sql = statements();
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS class_invites"));
        assertTrue(sql.contains("code_hash CHAR(64) NOT NULL"));
        assertTrue(sql.contains("UNIQUE KEY uk_class_invites_code_hash (code_hash)"));
        assertTrue(sql.contains("code_hint VARCHAR(8) NOT NULL"));
        assertTrue(sql.contains("used_count INT NOT NULL DEFAULT 0"));
        assertTrue(sql.contains("version BIGINT NOT NULL DEFAULT 0"));
        assertTrue(sql.contains("FOREIGN KEY (class_id) REFERENCES classrooms(id) ON DELETE CASCADE"));
        assertFalse(sql.contains("created_at DATETIME(6) NOT NULL DEFAULT"), "no zone-dependent CURRENT_TIMESTAMP default: the app writes created_at in UTC");
    }

    @Test
    @DisplayName("V37 links classrooms.access_product_id to products with ON DELETE RESTRICT")
    void accessProductForeignKeyRestricts() throws Exception {
        assertTrue(statements().contains("FOREIGN KEY (access_product_id) REFERENCES products(id) ON DELETE RESTRICT"));
    }

    @Test
    @DisplayName("V37 is replayable after a partially applied run: every ALTER is guarded by an information_schema check")
    void everyAlterIsGuarded() throws Exception {
        String sql = statements();
        int alters = sql.split("ALTER TABLE", -1).length - 1;
        int guards = sql.split("PREPARE migration_stmt FROM @ddl;", -1).length - 1;
        assertTrue(alters > 0 && alters == guards, alters + " ALTER TABLE statements but " + guards + " guarded PREPAREs");
        assertTrue(sql.contains("information_schema.columns"));
        assertTrue(sql.contains("information_schema.statistics"));
        assertTrue(sql.contains("information_schema.table_constraints"));
    }

    @Test
    @DisplayName("V37 changes no existing row: it has no UPDATE / DELETE / INSERT")
    void changesNoExistingRow() throws Exception {
        String sql = statements().toUpperCase();
        assertFalse(sql.contains("UPDATE "));
        assertFalse(sql.contains("DELETE FROM"));
        assertFalse(sql.contains("INSERT INTO"));
    }

    @Test
    @DisplayName("V37 documents its time-zone stance (R20-08) and must NOT carry the UTC-preflight opt-in marker anywhere in its first lines")
    void timeZoneNote() throws Exception {
        String header = script();
        assertTrue(header.contains("R20-08"));
        assertTrue(header.contains("DatabaseTimeZonePreflight"));
        assertFalse(header.toLowerCase().contains("requires-utc-server"), "it converts and compares no dates, so it runs on any server zone");
    }
}
