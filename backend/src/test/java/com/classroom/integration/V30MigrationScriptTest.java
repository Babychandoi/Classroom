package com.classroom.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R19-03: static guard on the V30 script (the behaviour on a real MySQL, including "existing values do not
 * shift", is in Round19CommerceIntegrationTest). MySQL converts a TIMESTAMP to DATETIME through the session time
 * zone, so the script must pin it to UTC before the first ALTER and put it back afterwards.
 */
class V30MigrationScriptTest {

    private static final String[][] FUTURE_DATE_COLUMNS = {
            {"entitlements", "starts_at"}, {"entitlements", "expires_at"},
            {"product_prices", "access_starts_at"}, {"order_items", "access_starts_at_snapshot"},
            {"exams", "schedule_start"}, {"exams", "schedule_end"}, {"exam_attempts", "ends_at"},
            {"revoked_tokens", "expires_at"}, {"refresh_tokens", "expires_at"},
            {"leaderboard_recalc_jobs", "next_attempt_at"}};

    private String script() throws Exception {
        return new String(new ClassPathResource("db/migration/V30__timestamp_to_datetime_beyond_2038.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /** The script without its `--` comment lines. */
    private String statements() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String line : script().split("\\R")) {
            if (!line.trim().startsWith("--")) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("V30 pins the session time zone to UTC before the first ALTER and restores it after the last one")
    void pinsTheSessionTimeZone() throws Exception {
        String sql = statements();
        int save = sql.indexOf("SET @v30_previous_time_zone = @@SESSION.time_zone");
        int pin = sql.indexOf("SET SESSION time_zone = '+00:00'");
        int firstAlter = sql.indexOf("ALTER TABLE");
        int lastAlter = sql.lastIndexOf("ALTER TABLE");
        int restore = sql.lastIndexOf("SET SESSION time_zone = @v30_previous_time_zone");
        assertTrue(save >= 0 && pin > save && firstAlter > pin, "the zone must be saved, then pinned to UTC, before the first ALTER");
        assertTrue(restore > lastAlter, "the zone must be restored after the last ALTER");
    }

    @Test
    @DisplayName("V30 converts every future-date column to DATETIME and leaves nothing as TIMESTAMP")
    void convertsTheFutureDateColumns() throws Exception {
        String sql = statements().toLowerCase(Locale.ROOT);
        assertFalse(Pattern.compile("(?<![a-z_])timestamp\\b").matcher(sql).find(),
                "no column may be (re)declared as TIMESTAMP in V30 (CURRENT_TIMESTAMP defaults are fine)");
        for (String[] col : FUTURE_DATE_COLUMNS) {
            Matcher m = Pattern.compile("alter table " + col[0] + "\\b.*?modify column " + col[1] + " datetime\\(\\d\\)", Pattern.DOTALL).matcher(sql);
            assertTrue(m.find(), col[0] + "." + col[1] + " must be MODIFYed to DATETIME(n) in V30");
        }
    }

    @Test
    @DisplayName("V30 keeps the NOT NULL / DEFAULT declarations of the columns it rewrites")
    void keepsNullabilityAndDefaults() throws Exception {
        String sql = statements();
        assertTrue(sql.contains("access_starts_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)"));
        assertTrue(sql.contains("access_starts_at_snapshot DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)"));
        assertTrue(sql.contains("schedule_start DATETIME(6) NULL"));
        assertTrue(sql.contains("schedule_end DATETIME(6) NULL"));
        assertTrue(sql.contains("next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)"));
    }
}
