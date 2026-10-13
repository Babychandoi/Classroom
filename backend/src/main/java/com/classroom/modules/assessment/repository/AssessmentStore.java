package com.classroom.modules.assessment.repository;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** SQL stays parameterized here; table/column names are fixed by the module, never supplied by HTTP. */
@Repository
public class AssessmentStore {
    private final JdbcTemplate jdbc;
    public AssessmentStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<Row> rows(String sql, Object... args) {
        return jdbc.queryForList(sql, args).stream().map(Row::new).toList();
    }
    public Optional<Row> first(String sql, Object... args) { return rows(sql, args).stream().findFirst(); }
    public Row require(String sql, Object... args) {
        return first(sql, args).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài tập"));
    }
    public int write(String sql, Object... args) { return jdbc.update(sql, args); }
    public long count(String sql, Object... args) { return Optional.ofNullable(jdbc.queryForObject(sql, Long.class, args)).orElse(0L); }
    public Row assessment(String id, boolean lock) {
        return require("SELECT * FROM assessments WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    }
    public Row assignment(String id, boolean lock) {
        return require("SELECT * FROM assessment_assignments WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    }
    public Row attempt(String id, boolean lock) {
        return require("SELECT * FROM assessment_attempts WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    }
    public Row version(String id) { return require("SELECT * FROM assessment_versions WHERE id=?", id); }
    public void lockStudent(String id) { require("SELECT id FROM users WHERE id=? FOR UPDATE", id); }
    public static Timestamp time(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    public record Row(Map<String, Object> data) {
        public String text(String key) { Object v=data.get(key); return v==null ? null : v.toString(); }
        public int number(String key) { Object v=data.get(key); return v==null ? 0 : ((Number)v).intValue(); }
        public long longNumber(String key) { Object v=data.get(key); return v==null ? 0 : ((Number)v).longValue(); }
        public boolean flag(String key) { Object v=data.get(key); return Boolean.TRUE.equals(v) || v instanceof Number n && n.intValue()!=0; }
        public BigDecimal decimal(String key) { Object v=data.get(key); return v==null ? null : new BigDecimal(v.toString()); }
        public Instant instant(String key) {
            Object v=data.get(key); if(v==null) return null;
            if(v instanceof Timestamp t) return t.toInstant();
            if(v instanceof java.time.LocalDateTime t) return t.toInstant(java.time.ZoneOffset.UTC);
            return Instant.parse(v.toString());
        }
    }
}
