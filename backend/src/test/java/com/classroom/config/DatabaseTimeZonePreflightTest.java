package com.classroom.config;

import com.classroom.config.DatabaseTimeZonePreflight.PendingMigration;
import com.classroom.config.DatabaseTimeZonePreflight.ZoneInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R20-08: V30 (TIMESTAMP -> DATETIME under a pinned '+00:00' session) shifts every value by the server offset when MySQL does not run
 * in UTC. The preflight must stop the startup BEFORE that migration, with a message an operator can act on, unless overridden.
 */
class DatabaseTimeZonePreflightTest {

    private static final PendingMigration V30 = new PendingMigration("30", "V30__timestamp_to_datetime_beyond_2038.sql", false);
    private static final PendingMigration V36 = new PendingMigration("36", "V36__outbox_indexes_failure_kind_and_retention.sql", false);

    private static ZoneInfo zone(String global, String session, String system, String offset) {
        return new ZoneInfo(global, session, system, offset);
    }

    /** A DataSource whose connection answers the preflight query with the given row. */
    private static DataSource dataSourceReporting(ZoneInfo zone) throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getString(1)).thenReturn(zone.globalZone());
        when(rs.getString(2)).thenReturn(zone.sessionZone());
        when(rs.getString(3)).thenReturn(zone.systemZone());
        when(rs.getString(4)).thenReturn(zone.offset());
        return dataSource;
    }

    // ---------------------------------------------------------------------------------------------------- UTC detection

    @Test
    @DisplayName("UTC servers are recognised however MySQL spells it: UTC, +00:00, SYSTEM resolving to UTC")
    void utcSpellings() {
        assertTrue(DatabaseTimeZonePreflight.isUtc(zone("UTC", "UTC", "UTC", "00:00:00")));
        assertTrue(DatabaseTimeZonePreflight.isUtc(zone("+00:00", "+00:00", "UTC", "00:00:00")));
        assertTrue(DatabaseTimeZonePreflight.isUtc(zone("SYSTEM", "SYSTEM", "UTC", "00:00:00")), "the mysql docker image: SYSTEM = UTC");
        assertTrue(DatabaseTimeZonePreflight.isUtc(zone("SYSTEM", "SYSTEM", "Coordinated Universal Time", "00:00:00")));
        assertTrue(DatabaseTimeZonePreflight.isUtc(zone("Etc/UTC", "Etc/UTC", "UTC", "00:00:00")));
        assertTrue(DatabaseTimeZonePreflight.isUtc(zone("GMT", "GMT", "UTC", "00:00:00")));
    }

    @Test
    @DisplayName("a non-UTC server is recognised: fixed offsets, named zones, SYSTEM resolving to a local zone, and zones that are merely at offset 0 today")
    void nonUtcServers() {
        assertFalse(DatabaseTimeZonePreflight.isUtc(zone("+07:00", "+07:00", "UTC", "07:00:00")));
        assertFalse(DatabaseTimeZonePreflight.isUtc(zone("-05:00", "-05:00", "UTC", "-05:00:00")));
        assertFalse(DatabaseTimeZonePreflight.isUtc(zone("Asia/Ho_Chi_Minh", "Asia/Ho_Chi_Minh", "UTC", "07:00:00")));
        assertFalse(DatabaseTimeZonePreflight.isUtc(zone("SYSTEM", "SYSTEM", "ICT", "07:00:00")));
        assertFalse(DatabaseTimeZonePreflight.isUtc(zone("SYSTEM", "SYSTEM", "+07", "07:00:00")));
        assertFalse(DatabaseTimeZonePreflight.isUtc(zone("Europe/London", "Europe/London", "UTC", "00:00:00")),
                "London is at offset 0 in winter only - not UTC");
        assertFalse(DatabaseTimeZonePreflight.isUtc(zone("UTC", "+07:00", "UTC", "07:00:00")),
                "a UTC global zone but a session pinned elsewhere must not pass");
        assertFalse(DatabaseTimeZonePreflight.isUtc(zone(null, null, null, null)));
    }

    // -------------------------------------------------------------------------------------------------------- the abort

    @Test
    @DisplayName("pending V30 on a +07:00 server ABORTS the startup with a Vietnamese + English message naming the fix and the override")
    void abortsWhenV30IsPendingOnANonUtcServer() throws Exception {
        DataSource dataSource = dataSourceReporting(zone("+07:00", "+07:00", "UTC", "07:00:00"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> DatabaseTimeZonePreflight.run(dataSource, List.of(V30, V36), false));

        String message = failure.getMessage();
        assertTrue(message.contains("KHÔNG THỂ KHỞI ĐỘNG"), "Vietnamese headline");
        assertTrue(message.contains("STARTUP ABORTED"), "English headline");
        assertTrue(message.contains("V30__timestamp_to_datetime_beyond_2038.sql"), "names the migration");
        assertTrue(message.contains("@@global.time_zone=+07:00"), "shows what the server reported");
        assertTrue(message.contains("default-time-zone='+00:00'"), "tells how to fix it");
        assertTrue(message.contains("APP_DB_ALLOW_NON_UTC_MIGRATION=true"), "tells how to override");
        assertTrue(message.contains("Nâng cấp từ phiên bản cũ"), "points to the runbook section");
        assertFalse(message.contains("V36__outbox"), "only the migrations that need UTC are named");
    }

    @Test
    @DisplayName("the explicit override app.db.allow-non-utc-migration=true lets the migration proceed (with a warning)")
    void overrideAllowsTheMigration() throws Exception {
        DataSource dataSource = dataSourceReporting(zone("+07:00", "+07:00", "UTC", "07:00:00"));

        assertDoesNotThrow(() -> DatabaseTimeZonePreflight.run(dataSource, List.of(V30), true));
    }

    @Test
    @DisplayName("UTC server: V30 proceeds")
    void utcServerProceeds() throws Exception {
        DataSource dataSource = dataSourceReporting(zone("SYSTEM", "SYSTEM", "UTC", "00:00:00"));

        assertDoesNotThrow(() -> DatabaseTimeZonePreflight.run(dataSource, List.of(V30, V36), false));
    }

    @Test
    @DisplayName("non-UTC server but no pending migration needs UTC (V30 already applied): warn only, do not abort")
    void nonUtcWithoutPendingV30OnlyWarns() throws Exception {
        DataSource dataSource = dataSourceReporting(zone("+07:00", "+07:00", "UTC", "07:00:00"));

        assertDoesNotThrow(() -> DatabaseTimeZonePreflight.run(dataSource, List.of(V36), false));
        assertDoesNotThrow(() -> DatabaseTimeZonePreflight.run(dataSource, List.of(), false));
    }

    @Test
    @DisplayName("any migration carrying the requires-utc-server marker triggers the same abort, not just V30")
    void markerOptsAFutureMigrationIn() throws Exception {
        DataSource dataSource = dataSourceReporting(zone("+07:00", "+07:00", "UTC", "07:00:00"));
        PendingMigration future = new PendingMigration("40", "V40__convert_more_timestamps.sql", true);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> DatabaseTimeZonePreflight.run(dataSource, List.of(future), false));

        assertTrue(failure.getMessage().contains("V40__convert_more_timestamps.sql"));
    }

    @Test
    @DisplayName("a database that cannot be read (not MySQL / not reachable) does not block startup - Flyway reports its own error")
    void unreadableDatabaseDoesNotBlock() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("Communications link failure"));
        assertDoesNotThrow(() -> DatabaseTimeZonePreflight.run(dataSource, List.of(V30), false));

        DataSource h2Like = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(h2Like.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenThrow(new SQLException("Unknown system variable 'global.time_zone'"));
        assertDoesNotThrow(() -> DatabaseTimeZonePreflight.run(h2Like, List.of(V30), false));
        verify(connection).close();
    }

    @Test
    @DisplayName("V30 is the version that requires UTC, and PendingMigration tells so")
    void v30RequiresUtc() {
        assertEquals(java.util.Set.of("30"), DatabaseTimeZonePreflight.REQUIRES_UTC_VERSIONS);
        assertTrue(V30.requiresUtcMarker() || DatabaseTimeZonePreflight.REQUIRES_UTC_VERSIONS.contains(V30.version()));
    }
}
