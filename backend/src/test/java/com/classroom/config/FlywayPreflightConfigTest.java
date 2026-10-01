package com.classroom.config;

import com.classroom.config.DatabaseTimeZonePreflight.PendingMigration;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** R20-08: the Flyway hook that runs the UTC preflight before migrating, and the marker scan of pending scripts. */
class FlywayPreflightConfigTest {

    private static final String FIXTURES = "classpath:db/preflight-fixtures";

    @Test
    @DisplayName("a script carrying the requires-utc-server marker in its header is detected; an ordinary script and a missing one are not")
    void markerScan() {
        assertTrue(FlywayPreflightConfig.scriptCarriesMarker(FIXTURES, "V999__future_utc_dependent_migration.sql"));
        assertFalse(FlywayPreflightConfig.scriptCarriesMarker(FIXTURES, "V998__ordinary_migration.sql"));
        assertFalse(FlywayPreflightConfig.scriptCarriesMarker(FIXTURES, "V997__does_not_exist.sql"));
        assertFalse(FlywayPreflightConfig.scriptCarriesMarker(FIXTURES, null));
        assertFalse(FlywayPreflightConfig.scriptCarriesMarker("filesystem:/opt/migrations", "V1__x.sql"), "only classpath locations are scanned");
    }

    @Test
    @DisplayName("the real V30 script does not carry the marker (it cannot be edited) - it is covered by the hard-coded version instead")
    void v30IsCoveredByVersionNotByMarker() {
        assertFalse(FlywayPreflightConfig.scriptCarriesMarker("classpath:db/migration", "V30__timestamp_to_datetime_beyond_2038.sql"));
        assertTrue(DatabaseTimeZonePreflight.REQUIRES_UTC_VERSIONS.contains("30"));
    }

    private static Flyway flywayWithPending(DataSource dataSource, String version, String script) {
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService infoService = mock(MigrationInfoService.class);
        MigrationInfo info = mock(MigrationInfo.class);
        when(info.getVersion()).thenReturn(MigrationVersion.fromVersion(version));
        when(info.getScript()).thenReturn(script);
        when(infoService.pending()).thenReturn(new MigrationInfo[]{info});
        when(flyway.info()).thenReturn(infoService);
        Configuration configuration = mock(Configuration.class);
        when(configuration.getDataSource()).thenReturn(dataSource);
        when(flyway.getConfiguration()).thenReturn(configuration);
        return flyway;
    }

    private static DataSource dataSourceReporting(String global, String offset) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getString(1)).thenReturn(global);
        when(rs.getString(2)).thenReturn(global);
        when(rs.getString(3)).thenReturn("UTC");
        when(rs.getString(4)).thenReturn(offset);
        return dataSource;
    }

    @Test
    @DisplayName("the strategy aborts BEFORE flyway.migrate() when V30 is pending on a non-UTC server")
    void strategyAbortsBeforeMigrating() throws Exception {
        Flyway flyway = flywayWithPending(dataSourceReporting("+07:00", "07:00:00"), "30", "V30__timestamp_to_datetime_beyond_2038.sql");
        FlywayMigrationStrategy strategy = new FlywayPreflightConfig().utcPreflightMigrationStrategy(false, "classpath:db/migration");

        assertThrows(IllegalStateException.class, () -> strategy.migrate(flyway));

        verify(flyway, never()).migrate();
    }

    @Test
    @DisplayName("the strategy migrates normally on a UTC server, and with the explicit override on a non-UTC one")
    void strategyMigratesWhenAllowed() throws Exception {
        Flyway utc = flywayWithPending(dataSourceReporting("UTC", "00:00:00"), "30", "V30__timestamp_to_datetime_beyond_2038.sql");
        new FlywayPreflightConfig().utcPreflightMigrationStrategy(false, "classpath:db/migration").migrate(utc);
        verify(utc).migrate();

        Flyway overridden = flywayWithPending(dataSourceReporting("+07:00", "07:00:00"), "30", "V30__timestamp_to_datetime_beyond_2038.sql");
        new FlywayPreflightConfig().utcPreflightMigrationStrategy(true, "classpath:db/migration").migrate(overridden);
        verify(overridden).migrate();
    }

    @Test
    @DisplayName("PendingMigration.requiresUtc: by version (V30) or by marker")
    void pendingMigrationRule() {
        assertTrue(new PendingMigration("30", "V30__x.sql", false).requiresUtc());
        assertTrue(new PendingMigration("77", "V77__x.sql", true).requiresUtc());
        assertFalse(new PendingMigration("36", "V36__x.sql", false).requiresUtc());
    }
}
