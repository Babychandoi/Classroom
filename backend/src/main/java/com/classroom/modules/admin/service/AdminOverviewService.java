package com.classroom.modules.admin.service;

import com.classroom.modules.admin.dto.AdminDtos;
import com.classroom.modules.admin.dto.AdminDtos.DayCount;
import com.classroom.modules.admin.dto.AdminDtos.Overview;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * D-29: {@code GET /admin/overview} - platform-wide counters. Each block is ONE aggregate statement (COUNT / SUM over an index or a small
 * table, conditional sums for the breakdowns); nothing is loaded row by row. The signup histogram is one statement of
 * per-day conditional sums over the last 30 UTC days.
 */
@Service
public class AdminOverviewService {

    static final int HISTOGRAM_DAYS = 30;

    private final NamedParameterJdbcTemplate jdbc;

    public AdminOverviewService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);
        Timestamp days7 = Timestamp.from(now.minus(Duration.ofDays(7)));
        Timestamp days30 = Timestamp.from(now.minus(Duration.ofDays(30)));
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("now", nowTs).addValue("d7", days7).addValue("d30", days30);

        AdminDtos.Users users = jdbc.queryForObject("SELECT COUNT(*) AS total,"
                + " COALESCE(SUM(CASE WHEN status = 'ACTIVE' THEN 1 ELSE 0 END), 0) AS active,"
                + " COALESCE(SUM(CASE WHEN status = 'BANNED' THEN 1 ELSE 0 END), 0) AS banned,"
                + " COALESCE(SUM(CASE WHEN status = 'DELETED' THEN 1 ELSE 0 END), 0) AS deleted,"
                + " COALESCE(SUM(CASE WHEN role = 'PLATFORM_ADMIN' AND status = 'ACTIVE' THEN 1 ELSE 0 END), 0) AS admins,"
                + " COALESCE(SUM(CASE WHEN created_at >= :d7 THEN 1 ELSE 0 END), 0) AS new7,"
                + " COALESCE(SUM(CASE WHEN created_at >= :d30 THEN 1 ELSE 0 END), 0) AS new30"
                + " FROM users", p, (rs, i) -> new AdminDtos.Users(rs.getLong("total"), rs.getLong("active"), rs.getLong("banned"),
                rs.getLong("deleted"), rs.getLong("admins"), rs.getLong("new7"), rs.getLong("new30")));

        AdminDtos.Classes classes = jdbc.queryForObject("SELECT COUNT(*) AS total,"
                + " COALESCE(SUM(CASE WHEN status = 'ACTIVE' THEN 1 ELSE 0 END), 0) AS active,"
                + " COALESCE(SUM(CASE WHEN status = 'ARCHIVED' THEN 1 ELSE 0 END), 0) AS archived,"
                + " COALESCE(SUM(CASE WHEN status = 'SUSPENDED' THEN 1 ELSE 0 END), 0) AS suspended,"
                + " COALESCE(SUM(CASE WHEN UPPER(visibility) = 'PRIVATE' THEN 0 ELSE 1 END), 0) AS pub,"
                + " COALESCE(SUM(CASE WHEN UPPER(visibility) = 'PRIVATE' THEN 1 ELSE 0 END), 0) AS priv,"
                + " COALESCE(SUM(CASE WHEN UPPER(access_type) = 'PAID' THEN 1 ELSE 0 END), 0) AS paid,"
                + " COALESCE(SUM(CASE WHEN created_at >= :d7 THEN 1 ELSE 0 END), 0) AS new7"
                + " FROM classrooms", p, (rs, i) -> new AdminDtos.Classes(rs.getLong("total"), rs.getLong("active"), rs.getLong("archived"),
                rs.getLong("suspended"), rs.getLong("pub"), rs.getLong("priv"), rs.getLong("paid"), rs.getLong("new7")));

        // Memberships = the people in classes (owner rows excluded), counted with the "current member" rule (ACTIVE, access not lapsed).
        AdminDtos.Members members = jdbc.queryForObject("SELECT"
                + " COALESCE(SUM(CASE WHEN state = 'ACTIVE' AND role <> 'OWNER' AND (access_expires_at IS NULL OR access_expires_at > :now)"
                + " THEN 1 ELSE 0 END), 0) AS active,"
                + " COALESCE(SUM(CASE WHEN state = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending"
                + " FROM class_members", p, (rs, i) -> new AdminDtos.Members(rs.getLong("active"), rs.getLong("pending")));

        AdminDtos.Content content = jdbc.queryForObject("SELECT"
                + " (SELECT COUNT(*) FROM courses) AS courses,"
                + " (SELECT COUNT(*) FROM exams WHERE status IN ('PUBLISHED', 'OPEN', 'CLOSED')) AS exams,"
                + " (SELECT COUNT(*) FROM blog_posts WHERE status = 'PUBLISHED') AS blog,"
                + " (SELECT COUNT(*) FROM class_events WHERE status = 'SCHEDULED' AND ends_at >= :now) AS events", p,
                (rs, i) -> new AdminDtos.Content(rs.getLong("courses"), rs.getLong("exams"), rs.getLong("blog"), rs.getLong("events")));

        // Revenue = PAID orders paid in the last 30 days; a REFUNDED order is no longer PAID, so refunds are excluded by construction.
        AdminDtos.Commerce commerce = jdbc.queryForObject("SELECT"
                + " (SELECT COUNT(*) FROM orders WHERE status = 'PAID' AND paid_at >= :d30) AS paid,"
                + " (SELECT COALESCE(SUM(total_amount), 0) FROM orders WHERE status = 'PAID' AND paid_at >= :d30) AS revenue,"
                + " (SELECT COUNT(*) FROM orders WHERE status = 'PENDING') AS pending", p,
                (rs, i) -> {
                    BigDecimal revenue = rs.getBigDecimal("revenue");
                    return new AdminDtos.Commerce(rs.getLong("paid"), revenue == null ? BigDecimal.ZERO : revenue, "VND", rs.getLong("pending"));
                });

        Long openPrivacy = jdbc.queryForObject("SELECT COUNT(*) FROM privacy_requests WHERE status NOT IN ('COMPLETED', 'REJECTED')",
                p, Long.class);

        AdminDtos.Outbox outbox = jdbc.queryForObject("SELECT"
                + " (SELECT COUNT(*) FROM outbox_events WHERE status IN ('PENDING', 'PROCESSING', 'FAILED')) AS pending,"
                + " (SELECT COUNT(*) FROM outbox_events WHERE status = 'DEAD_LETTER') AS dead", p,
                (rs, i) -> new AdminDtos.Outbox(rs.getLong("pending"), rs.getLong("dead")));

        return new Overview(users, classes, members, content, commerce, new AdminDtos.Privacy(openPrivacy == null ? 0 : openPrivacy),
                outbox, signupsByDay(now));
    }

    /**
     * Signups per UTC day for the last 30 days (today included), oldest first, days without signups as 0. One statement: a conditional sum
     * per day over the {@code created_at >= first day} index range. The day boundaries are computed here in UTC and bound as instants, so
     * the answer never depends on the database session time zone or on how a driver turns a timestamp into a date.
     */
    List<DayCount> signupsByDay(Instant now) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate first = today.minusDays(HISTOGRAM_DAYS - 1L);
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < HISTOGRAM_DAYS; i++) {
            if (i > 0) sql.append(", ");
            sql.append("COALESCE(SUM(CASE WHEN created_at >= :b").append(i).append(" AND created_at < :b").append(i + 1)
                    .append(" THEN 1 ELSE 0 END), 0) AS d").append(i);
            p.addValue("b" + i, Timestamp.from(first.plusDays(i).atStartOfDay(ZoneOffset.UTC).toInstant()));
        }
        p.addValue("b" + HISTOGRAM_DAYS, Timestamp.from(first.plusDays(HISTOGRAM_DAYS).atStartOfDay(ZoneOffset.UTC).toInstant()));
        sql.append(" FROM users WHERE created_at >= :b0");
        List<DayCount> result = new ArrayList<>(HISTOGRAM_DAYS);
        jdbc.query(sql.toString(), p, rs -> {
            for (int i = 0; i < HISTOGRAM_DAYS; i++) {
                result.add(new DayCount(first.plusDays(i).toString(), rs.getLong("d" + i)));
            }
        });
        if (result.isEmpty()) { // no row at all cannot happen for an aggregate, but stay total
            for (int i = 0; i < HISTOGRAM_DAYS; i++) result.add(new DayCount(first.plusDays(i).toString(), 0));
        }
        return result;
    }
}
