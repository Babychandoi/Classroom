package com.classroom.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * R20-08: startup PREFLIGHT for migrations that assume a UTC database server.
 *
 * <p>V30 converts {@code TIMESTAMP} columns to {@code DATETIME(6)} under {@code SET SESSION time_zone = '+00:00'}. Its header promises that
 * existing values do not shift, but that only holds when the server's own zone is UTC. The application writes every instant through
 * a JDBC URL with {@code serverTimezone=UTC}, so on a server whose zone is, say, +07:00 the old {@code TIMESTAMP} values were stored as
 * (UTC minus 7 h) and read back as the same UTC wall-clock the application wrote; converting them under a pinned '+00:00' session then
 * exposes the stored instants unchanged - i.e. every converted date moves by the server offset. V30 is already applied on existing
 * databases and cannot be edited (its checksum is recorded), so the guard lives here: it runs BEFORE Flyway migrates, looks at the
 * pending migrations and, if one of them requires a UTC server and the server is not UTC, refuses to start with a clear message.
 *
 * <p>What "requires a UTC server": the version numbers in {@link #REQUIRES_UTC_VERSIONS} (V30) and any migration script that carries the
 * marker comment {@code requires-utc-server} in its first lines - the way future migrations opt in.
 *
 * <p>The zone is always logged at startup; a non-UTC zone is a warning even when nothing forces an abort (the JDBC URL says
 * {@code serverTimezone=UTC}, so the database server should agree). {@code app.db.allow-non-utc-migration=true} overrides the abort for an
 * operator who has verified the data.
 */
public final class DatabaseTimeZonePreflight {

    private static final Logger log = LoggerFactory.getLogger(DatabaseTimeZonePreflight.class);

    /** Pending versions that are only value-preserving on a UTC server. */
    static final Set<String> REQUIRES_UTC_VERSIONS = Set.of("30");
    /** Marker that any migration script can carry (in a comment near the top) to opt in to the same check. */
    static final String MARKER = "requires-utc-server";

    private static final Set<String> UTC_NAMES = Set.of("UTC", "GMT", "UCT", "Z", "ZULU", "UNIVERSAL", "ETC/UTC", "ETC/GMT",
            "ETC/UCT", "ETC/ZULU", "ETC/UNIVERSAL", "+00:00", "-00:00", "+0:00", "-0:00", "+00", "00:00", "+0");

    private DatabaseTimeZonePreflight() {
    }

    /** A migration that has not been applied yet. {@code requiresUtcMarker} is true when its script carries {@link #MARKER}. */
    public record PendingMigration(String version, String script, boolean requiresUtcMarker) {
        public boolean requiresUtc() {
            return requiresUtcMarker || (version != null && REQUIRES_UTC_VERSIONS.contains(version));
        }
    }

    /**
     * The server's time-zone configuration as MySQL reports it.
     *
     * @param globalZone  {@code @@global.time_zone} ("SYSTEM", "UTC", "+00:00", "Asia/Ho_Chi_Minh", ...)
     * @param sessionZone {@code @@session.time_zone} of the migration connection
     * @param systemZone  {@code @@system_time_zone} (what "SYSTEM" resolves to: "UTC", "ICT", "+07", ...)
     * @param offset      {@code TIMEDIFF(NOW(), UTC_TIMESTAMP)} of that session, "00:00:00" for UTC
     */
    public record ZoneInfo(String globalZone, String sessionZone, String systemZone, String offset) {
        @Override
        public String toString() {
            return "@@global.time_zone=" + globalZone + ", @@session.time_zone=" + sessionZone
                    + ", @@system_time_zone=" + systemZone + ", offset from UTC=" + offset;
        }
    }

    /** Reads the zone configuration through an open connection. */
    public static ZoneInfo read(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT @@global.time_zone, @@session.time_zone, @@system_time_zone, "
                     + "TIMEDIFF(NOW(), UTC_TIMESTAMP)")) {
            if (!rs.next()) {
                throw new SQLException("time zone query returned no row");
            }
            return new ZoneInfo(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4));
        }
    }

    /**
     * True when the server runs in UTC: the effective GLOBAL zone (what past writes were interpreted in - {@code SYSTEM} resolves to the
     * OS zone) is UTC, and the migration session is at offset zero. A zone that merely has offset 0 today (Europe/London in winter) is
     * NOT UTC.
     */
    public static boolean isUtc(ZoneInfo zone) {
        String effective = zone.globalZone() == null ? "" : zone.globalZone().trim();
        if ("SYSTEM".equalsIgnoreCase(effective)) {
            effective = zone.systemZone() == null ? "" : zone.systemZone().trim();
        }
        return isUtcName(effective) && isZeroOffset(zone.offset());
    }

    static boolean isUtcName(String name) {
        String normalized = name.trim().toUpperCase(Locale.ROOT);
        return UTC_NAMES.contains(normalized) || normalized.contains("UTC") || normalized.contains("COORDINATED UNIVERSAL");
    }

    static boolean isZeroOffset(String offset) {
        return offset != null && offset.trim().matches("^[-+]?0+:0+(:0+(\\.0+)?)?$");
    }

    /**
     * Logs the database zone and aborts the startup when a pending migration requires UTC on a non-UTC server.
     *
     * @throws IllegalStateException when the migration would silently corrupt dates and the operator did not override the check
     */
    public static void run(DataSource dataSource, Collection<PendingMigration> pending, boolean allowNonUtcMigration) {
        ZoneInfo zone;
        try (Connection connection = dataSource.getConnection()) {
            zone = read(connection);
        } catch (SQLException | RuntimeException e) {
            // Not MySQL (or not reachable yet - Flyway will report that itself): nothing to verify here.
            log.warn("Could not read the database time zone, skipping the UTC preflight: {}", e.getMessage());
            return;
        }
        boolean utc = isUtc(zone);
        if (utc) {
            log.info("Database time zone: {} (UTC, as the JDBC url's serverTimezone=UTC expects)", zone);
            return;
        }
        log.warn("Database time zone is NOT UTC: {}. The JDBC url says serverTimezone=UTC, so the database server should run in UTC too "
                + "(MySQL default-time-zone='+00:00'); DATETIME defaults such as CURRENT_TIMESTAMP(6) are evaluated in the server zone.", zone);

        List<PendingMigration> requiring = pending.stream().filter(PendingMigration::requiresUtc).toList();
        if (requiring.isEmpty()) {
            return;
        }
        if (allowNonUtcMigration) {
            log.warn("app.db.allow-non-utc-migration=true: running {} on a NON-UTC database server ({}). Converted date columns may shift by the "
                            + "server offset; verify the data afterwards.",
                    describe(requiring), zone);
            return;
        }
        throw new IllegalStateException(abortMessage(requiring, zone));
    }

    static String describe(List<PendingMigration> migrations) {
        return migrations.stream()
                .map(m -> m.script() != null ? m.script() : "V" + m.version())
                .reduce((a, b) -> a + ", " + b).orElse("");
    }

    static String abortMessage(List<PendingMigration> requiring, ZoneInfo zone) {
        String names = describe(requiring);
        return "\n"
                + "================================================================================================\n"
                + " KHÔNG THỂ KHỞI ĐỘNG / STARTUP ABORTED - máy chủ MySQL không chạy múi giờ UTC / MySQL server is not in UTC\n"
                + "================================================================================================\n"
                + " [VI] Các migration sắp chạy (" + names + ") giả định máy chủ MySQL dùng múi giờ UTC (V30 đổi TIMESTAMP -> DATETIME).\n"
                + "      CSDL hiện tại: " + zone + ".\n"
                + "      Chạy trên máy chủ không phải UTC sẽ LÀM LỆCH mọi giá trị ngày-giờ đã có (thời hạn quyền truy cập, hạn nộp bài thi,\n"
                + "      hạn token...) đúng bằng độ lệch múi giờ của máy chủ. Backend TỪ CHỐI khởi động để không làm hỏng dữ liệu.\n"
                + "      Cách xử lý: đặt múi giờ máy chủ MySQL về UTC (my.cnf: default-time-zone='+00:00'; hoặc SET GLOBAL time_zone='+00:00'\n"
                + "      rồi khởi động lại MySQL và backend). Chỉ khi đã tự kiểm tra dữ liệu mới đặt APP_DB_ALLOW_NON_UTC_MIGRATION=true\n"
                + "      (app.db.allow-non-utc-migration=true) để bỏ qua. Xem docs/RUNBOOK.md, mục 'Nâng cấp từ phiên bản cũ'.\n"
                + " [EN] The pending migration(s) (" + names + ") assume the MySQL server runs in UTC (V30 converts TIMESTAMP to DATETIME).\n"
                + "      Current database: " + zone + ".\n"
                + "      Running them on a non-UTC server would SHIFT every existing date-time value (entitlement periods, exam deadlines,\n"
                + "      token expiries...) by the server's offset, so the backend refuses to start rather than corrupt data.\n"
                + "      Fix: set the MySQL server time zone to UTC (my.cnf: default-time-zone='+00:00', or SET GLOBAL time_zone='+00:00' and\n"
                + "      restart MySQL and the backend). Only after verifying your data yourself, set APP_DB_ALLOW_NON_UTC_MIGRATION=true\n"
                + "      (app.db.allow-non-utc-migration=true) to override. See docs/RUNBOOK.md, section 'Nâng cấp từ phiên bản cũ'.\n"
                + "================================================================================================\n";
    }
}
