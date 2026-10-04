package com.classroom.modules.admin.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.admin.dto.AdminDtos.AuditRow;
import com.classroom.modules.admin.dto.AdminDtos.Page;
import com.classroom.modules.admin.dto.AdminDtos.Person;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * D-29: the platform-wide audit log ({@code GET /admin/audit}) and the "recent audit" blocks of the user / class detail pages. A page is
 * rendered with a fixed number of statements: the rows, one COUNT, one lookup of the actors and one of the class titles.
 */
@Service
public class AdminAuditService {

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;
    public static final int RECENT_LIMIT = 20;

    private static final String COLUMNS = "a.id, a.created_at, a.action, a.target_type, a.target_id, a.class_id, a.actor_id, a.details_json";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AdminAuditService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** A raw audit row before the actor / class lookups. */
    record RawRow(String id, Instant createdAt, String action, String targetType, String targetId, String classId, String actorId,
                  String detailsJson) {
    }

    private static RawRow raw(ResultSet rs, int i) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        return new RawRow(rs.getString("id"), created == null ? null : created.toInstant(), rs.getString("action"),
                rs.getString("target_type"), rs.getString("target_id"), rs.getString("class_id"), rs.getString("actor_id"),
                rs.getString("details_json"));
    }

    @Transactional(readOnly = true)
    public Page<AuditRow> search(String action, String actorId, String classId, String from, String to, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (action != null && !action.isBlank()) {
            if (action.trim().length() > 64) throw new AppException(ErrorCode.BAD_REQUEST, "Mã hành động tối đa 64 ký tự");
            where.append(" AND a.action = :action");
            params.addValue("action", action.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (actorId != null && !actorId.isBlank()) {
            where.append(" AND a.actor_id = :actorId");
            params.addValue("actorId", actorId.trim());
        }
        if (classId != null && !classId.isBlank()) {
            where.append(" AND a.class_id = :classId");
            params.addValue("classId", classId.trim());
        }
        Instant fromInstant = parseBound(from, false);
        Instant toInstant = parseBound(to, true);
        if (fromInstant != null) {
            where.append(" AND a.created_at >= :from");
            params.addValue("from", Timestamp.from(fromInstant));
        }
        if (toInstant != null) {
            where.append(" AND a.created_at < :to");
            params.addValue("to", Timestamp.from(toInstant));
        }
        if (fromInstant != null && toInstant != null && !fromInstant.isBefore(toInstant)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Khoảng thời gian không hợp lệ: 'from' phải trước 'to'");
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM audit_events a" + where, params, Long.class);
        params.addValue("limit", safeSize).addValue("offset", (long) safePage * safeSize);
        List<RawRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM audit_events a" + where
                + " ORDER BY a.created_at DESC, a.id DESC LIMIT :limit OFFSET :offset", params, AdminAuditService::raw);
        return Page.of(map(rows), safePage, safeSize, total == null ? 0 : total);
    }

    /** The last {@link #RECENT_LIMIT} rows of one class (newest first). */
    @Transactional(readOnly = true)
    public List<AuditRow> recentForClass(String classId) {
        List<RawRow> rows = jdbc.query("SELECT " + COLUMNS + " FROM audit_events a WHERE a.class_id = :classId"
                        + " ORDER BY a.created_at DESC, a.id DESC LIMIT " + RECENT_LIMIT,
                new MapSqlParameterSource("classId", classId), AdminAuditService::raw);
        return map(rows);
    }

    /**
     * The last {@link #RECENT_LIMIT} rows where the user is the actor or the target. Two index range reads (actor, target) merged here
     * instead of an OR that would scan the table.
     */
    @Transactional(readOnly = true)
    public List<AuditRow> recentForUser(String userId) {
        MapSqlParameterSource p = new MapSqlParameterSource("userId", userId);
        Map<String, RawRow> merged = new LinkedHashMap<>();
        for (String column : List.of("actor_id", "target_id")) {
            for (RawRow r : jdbc.query("SELECT " + COLUMNS + " FROM audit_events a WHERE a." + column + " = :userId"
                    + " ORDER BY a.created_at DESC, a.id DESC LIMIT " + RECENT_LIMIT, p, AdminAuditService::raw)) {
                merged.putIfAbsent(r.id(), r);
            }
        }
        List<RawRow> rows = new ArrayList<>(merged.values());
        rows.sort(Comparator.comparing(RawRow::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(RawRow::id, Comparator.reverseOrder()));
        return map(rows.size() > RECENT_LIMIT ? rows.subList(0, RECENT_LIMIT) : rows);
    }

    /** Resolves actors and class titles for a list of rows with one statement each. */
    private List<AuditRow> map(List<RawRow> rows) {
        if (rows.isEmpty()) return List.of();
        Map<String, Person> actors = people(rows.stream().map(RawRow::actorId).filter(Objects::nonNull).toList());
        Map<String, String> titles = classTitles(rows.stream().map(RawRow::classId).filter(Objects::nonNull).toList());
        List<AuditRow> result = new ArrayList<>(rows.size());
        for (RawRow r : rows) {
            result.add(new AuditRow(r.id(), r.createdAt(), r.action(), r.targetType(), r.targetId(), r.classId(),
                    r.classId() == null ? null : titles.get(r.classId()),
                    r.actorId() == null ? null : actors.getOrDefault(r.actorId(), new Person(r.actorId(), null, null)),
                    details(r.detailsJson())));
        }
        return result;
    }

    Map<String, Person> people(Collection<String> ids) {
        Set<String> distinct = new HashSet<>(ids);
        Map<String, Person> result = new HashMap<>();
        if (distinct.isEmpty()) return result;
        jdbc.query("SELECT id, full_name, email FROM users WHERE id IN (:ids)", new MapSqlParameterSource("ids", distinct),
                rs -> {
                    result.put(rs.getString("id"), new Person(rs.getString("id"), rs.getString("full_name"), rs.getString("email")));
                });
        return result;
    }

    private Map<String, String> classTitles(Collection<String> ids) {
        Set<String> distinct = new HashSet<>(ids);
        Map<String, String> result = new HashMap<>();
        if (distinct.isEmpty()) return result;
        jdbc.query("SELECT id, title FROM classrooms WHERE id IN (:ids)", new MapSqlParameterSource("ids", distinct),
                rs -> {
                    result.put(rs.getString("id"), rs.getString("title"));
                });
        return result;
    }

    /** {@code details_json} as an object; older rows written with String.format that do not parse come back as {@code {"raw": "..."}}. */
    private JsonNode details(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (Exception notJson) {
            return objectMapper.createObjectNode().put("raw", json);
        }
    }

    /** ISO-8601 instant, or a {@code yyyy-MM-dd} UTC date ({@code to} then means the end of that day, exclusive). */
    static Instant parseBound(String value, boolean upper) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        try {
            if (v.length() == 10) {
                LocalDate day = LocalDate.parse(v);
                return (upper ? day.plusDays(1) : day).atStartOfDay(ZoneOffset.UTC).toInstant();
            }
            return Instant.parse(v);
        } catch (DateTimeParseException e) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Thời gian phải theo ISO-8601 (ví dụ 2026-10-01 hoặc 2026-10-01T00:00:00Z)");
        }
    }
}
