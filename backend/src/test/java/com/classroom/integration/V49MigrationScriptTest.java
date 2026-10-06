package com.classroom.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-32: V49 adds the lesson components. The DDL is MySQL-only (guarded information_schema / PREPARE), so a static review pins its shape; the
 * DATA MIGRATION statements (sections 3-6, plain SQL) are executed here against an in-memory H2 (MySQL mode) on hand-built old-model data, twice,
 * to prove the conversion and its idempotence. Two MySQL-only functions are mapped for H2: UTC_TIMESTAMP(6) -> CURRENT_TIMESTAMP(6).
 */
class V49MigrationScriptTest {

    private String script() throws Exception {
        return new String(new ClassPathResource("db/migration/V49__lesson_components.sql").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private String statementsText() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String line : script().split("\\R")) {
            if (!line.trim().startsWith("--")) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("V49 shape: guarded INSTANT columns, attachments table with CASCADE lesson FK, RESTRICT media FK and unique media, DATETIME(6) without default")
    void shape() throws Exception {
        String sql = statementsText();
        assertTrue(sql.contains("ADD COLUMN has_assignment BOOLEAN NOT NULL DEFAULT FALSE, ALGORITHM=INSTANT"));
        assertTrue(sql.contains("ADD COLUMN assignment_instructions MEDIUMTEXT NULL, ALGORITHM=INSTANT"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS lesson_attachments"));
        assertTrue(sql.contains("CONSTRAINT uk_lesson_attachment_media UNIQUE (media_asset_id)"));
        assertTrue(sql.contains("FOREIGN KEY (lesson_id) REFERENCES lessons(id) ON DELETE CASCADE"));
        assertTrue(sql.contains("FOREIGN KEY (media_asset_id) REFERENCES media_assets(id) ON DELETE RESTRICT"));
        assertTrue(sql.contains("created_at DATETIME(6) NOT NULL,"), "no zone-dependent default");
        assertFalse(sql.toUpperCase().contains("CURRENT_TIMESTAMP"), "only UTC_TIMESTAMP(6), which does not depend on the session zone");
        assertFalse(sql.toUpperCase().contains("ALGORITHM=COPY"));
        assertEquals(2, sql.split("information_schema.columns", -1).length - 1, "each ADD COLUMN is guarded");
        assertTrue(sql.contains("SET @ddl = IF("));
    }

    /** The plain-SQL data migration: everything after the lesson_attachments table, one statement per element. */
    private List<String> dataStatements() throws Exception {
        String sql = statementsText();
        String data = sql.substring(sql.indexOf("INSERT INTO lesson_attachments"));
        List<String> out = new ArrayList<>();
        for (String stmt : data.split(";")) {
            String t = stmt.trim();
            if (!t.isEmpty()) out.add(t.replace("UTC_TIMESTAMP(6)", "CURRENT_TIMESTAMP(6)"));
        }
        assertEquals(4, out.size(), "insert, clear, assignment, type");
        return out;
    }

    private static void exec(Statement st, String sql) throws Exception {
        st.execute(sql);
    }

    private static String one(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next(), sql);
            return rs.getString(1);
        }
    }

    @Test
    @DisplayName("data migration: DOCUMENT files -> attachments, ASSIGNMENT content -> instructions, type recomputed; video/audio kept; replay changes nothing")
    void dataMigration() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:v49_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
             Statement st = c.createStatement()) {
            exec(st, "CREATE TABLE media_assets (id VARCHAR(36) PRIMARY KEY, mime_type VARCHAR(128) NOT NULL)");
            exec(st, "CREATE TABLE lessons (id VARCHAR(36) PRIMARY KEY, title VARCHAR(255) NOT NULL, type VARCHAR(32) NOT NULL, content_text CLOB,"
                    + " media_asset_id VARCHAR(36), video_provider VARCHAR(16), video_ref VARCHAR(128),"
                    + " has_assignment BOOLEAN NOT NULL DEFAULT FALSE, assignment_instructions CLOB)");
            exec(st, "CREATE TABLE lesson_attachments (id VARCHAR(36) PRIMARY KEY, lesson_id VARCHAR(36) NOT NULL, media_asset_id VARCHAR(36) NOT NULL UNIQUE,"
                    + " title VARCHAR(200) NOT NULL, position INT NOT NULL DEFAULT 0, created_at TIMESTAMP(6) NOT NULL)");
            exec(st, "INSERT INTO media_assets VALUES ('m-pdf','application/pdf'), ('m-mp4','video/mp4'), ('m-mp3','Audio/MPEG'), ('m-zip','application/zip'), ('m-shared','application/pdf')");
            exec(st, "INSERT INTO lessons (id, title, type, content_text, media_asset_id, video_provider, video_ref) VALUES"
                    + " ('doc',   'Phieu bai tap', 'DOCUMENT',   'Mo ta',   'm-pdf', NULL, NULL),"
                    + " ('vid',   'Video',         'VIDEO',      'Loi',     'm-mp4', NULL, NULL),"
                    + " ('aud',   'Audio',         'VIDEO',      NULL,      'm-mp3', NULL, NULL),"
                    + " ('link',  'Link',          'VIDEO',      'Chep',    NULL, 'YOUTUBE', 'dQw4w9WgXcQ'),"
                    + " ('txt',   'Chu',           'TEXT',       'Noi dung',NULL, NULL, NULL),"
                    + " ('emptyv','Video rong',    'VIDEO',      NULL,      NULL, NULL, NULL),"
                    + " ('asg',   'Bai tap',       'ASSIGNMENT', 'De bai',  NULL, NULL, NULL),"
                    + " ('asgdoc','Bai tap + file','ASSIGNMENT', 'De 2',    'm-zip', NULL, NULL),"
                    + " ('sh1',   'Dung chung 1',  'DOCUMENT',   NULL,      'm-shared', NULL, NULL),"
                    + " ('sh2',   'Dung chung 2',  'DOCUMENT',   NULL,      'm-shared', NULL, NULL)");

            for (int run = 1; run <= 2; run++) {
                for (String sql : dataStatements()) exec(st, sql);
                String again = "run " + run;

                assertEquals("DOCUMENT", one(st, "SELECT type FROM lessons WHERE id='doc'"), again);
                assertNull(one(st, "SELECT media_asset_id FROM lessons WHERE id='doc'"), again);
                assertEquals("Phieu bai tap", one(st, "SELECT title FROM lesson_attachments WHERE lesson_id='doc'"), again);
                assertEquals("m-pdf", one(st, "SELECT media_asset_id FROM lesson_attachments WHERE lesson_id='doc'"), again);
                assertEquals("Mo ta", one(st, "SELECT content_text FROM lessons WHERE id='doc'"), "content stays on a document lesson");

                assertEquals("m-mp4", one(st, "SELECT media_asset_id FROM lessons WHERE id='vid'"), "video files stay as the video: " + again);
                assertEquals("VIDEO", one(st, "SELECT type FROM lessons WHERE id='vid'"));
                assertEquals("m-mp3", one(st, "SELECT media_asset_id FROM lessons WHERE id='aud'"), "audio too (mime compared case-insensitively)");
                assertEquals("VIDEO", one(st, "SELECT type FROM lessons WHERE id='link'"), "an external link is a video");
                assertEquals("TEXT", one(st, "SELECT type FROM lessons WHERE id='txt'"));
                assertEquals("TEXT", one(st, "SELECT type FROM lessons WHERE id='emptyv'"), "priority rule: no video at all -> TEXT");

                assertEquals("ASSIGNMENT", one(st, "SELECT type FROM lessons WHERE id='asg'"));
                assertEquals("De bai", one(st, "SELECT assignment_instructions FROM lessons WHERE id='asg'"), again);
                assertNull(one(st, "SELECT content_text FROM lessons WHERE id='asg'"), again);
                assertEquals("TRUE", one(st, "SELECT CAST(has_assignment AS VARCHAR(5)) FROM lessons WHERE id='asg'").toUpperCase());
                assertEquals("De 2", one(st, "SELECT assignment_instructions FROM lessons WHERE id='asgdoc'"));
                assertEquals("m-zip", one(st, "SELECT media_asset_id FROM lesson_attachments WHERE lesson_id='asgdoc'"), "an assignment's file becomes a document");
                assertNull(one(st, "SELECT media_asset_id FROM lessons WHERE id='asgdoc'"));
                assertEquals("ASSIGNMENT", one(st, "SELECT type FROM lessons WHERE id='asgdoc'"), "assignment outranks documents");

                // a legacy file shared by two lessons: exactly one attachment (lowest id), the other lesson keeps the reference (fails closed later)
                assertEquals("1", one(st, "SELECT COUNT(*) FROM lesson_attachments WHERE media_asset_id='m-shared'"));
                assertEquals("sh1", one(st, "SELECT lesson_id FROM lesson_attachments WHERE media_asset_id='m-shared'"));
                assertEquals("m-shared", one(st, "SELECT media_asset_id FROM lessons WHERE id='sh2'"));

                assertEquals("3", one(st, "SELECT COUNT(*) FROM lesson_attachments"), "doc, asgdoc, sh1; a replay adds none: " + again);
            }
        }
    }
}
