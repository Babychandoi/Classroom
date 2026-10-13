package com.classroom.modules.assessment.service;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

/** A data copy includes the student's own work without revealing unpublished grading or exam content. */
@Component
public class AssessmentPrivacyExport {
    private final JdbcTemplate jdbc;
    public AssessmentPrivacyExport(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public boolean append(Map<String,Object> result, String userId, int offset) {
        // The assessment module is absent in the legacy JPA-only test schema.
        Boolean available = jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            for (String table : List.of("assessment_attempts", "ASSESSMENT_ATTEMPTS")) {
                try (var tables = connection.getMetaData().getTables(connection.getCatalog(), null, table, new String[]{"TABLE"})) {
                    if (tables.next()) return true;
                }
            }
            return false;
        });
        if (!Boolean.TRUE.equals(available)) {
            for (String key : List.of("assessmentAttempts", "assessmentAnswers", "assessmentUploads")) result.put(key,List.of());
            return false;
        }
        String visible = "(t.status='RETURNED' OR (t.status<>'IN_PROGRESS' AND (s.result_visibility='IMMEDIATE' OR "
                + "(s.result_visibility='AFTER_DUE' AND s.due_at IS NOT NULL AND s.due_at<=CURRENT_TIMESTAMP))))";
        var queries = Map.of(
                "assessmentAttempts", "SELECT t.id,t.assignment_id,t.class_id,t.attempt_no,t.status,t.started_at,t.deadline_at,t.submitted_at,t.late,t.max_score,CASE WHEN " + visible + " THEN t.total_score ELSE NULL END AS total_score,CASE WHEN " + visible + " THEN t.teacher_comment ELSE NULL END AS teacher_comment FROM assessment_attempts t JOIN assessment_assignments s ON s.id=t.assignment_id WHERE t.user_id=? ORDER BY t.id",
                "assessmentAnswers", "SELECT a.id,a.attempt_id,a.item_id,a.response_json,a.recording_asset_id,a.updated_at,CASE WHEN " + visible + " THEN a.final_points ELSE NULL END AS final_points,CASE WHEN " + visible + " THEN a.teacher_comment ELSE NULL END AS teacher_comment FROM assessment_attempt_answers a JOIN assessment_attempts t ON t.id=a.attempt_id JOIN assessment_assignments s ON s.id=t.assignment_id WHERE t.user_id=? ORDER BY a.id",
                "assessmentUploads", "SELECT u.asset_id,u.attempt_id,u.item_id,u.duration_ms,u.completed,u.created_at,m.original_filename,m.mime_type,m.size_bytes FROM assessment_attempt_uploads u JOIN assessment_attempts t ON t.id=u.attempt_id JOIN media_assets m ON m.id=u.asset_id WHERE t.user_id=? ORDER BY u.asset_id");
        boolean more = false;
        for (var query : queries.entrySet()) {
            var rows = jdbc.queryForList(query.getValue() + " LIMIT 1001 OFFSET ?", userId, offset);
            more |= rows.size()>1000;
            result.put(query.getKey(),rows.size()>1000?rows.subList(0,1000):rows);
        }
        return more;
    }
}
