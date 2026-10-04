package com.classroom.modules.identity.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.identity.repository.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.time.Instant;
import java.sql.Timestamp;

@Service
public class PrivacyService {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final PasswordEncoder passwords;
    @org.springframework.beans.factory.annotation.Autowired
    private com.classroom.modules.audit.service.AuditService audit;
    @org.springframework.beans.factory.annotation.Autowired
    private com.classroom.modules.outbox.service.OutboxService outbox;
    public PrivacyService(JdbcTemplate jdbc, UserRepository users, PasswordEncoder passwords) { this.jdbc = jdbc; this.users = users; this.passwords = passwords; }
    private void verify(String userId, String password) {
        var user = users.findById(userId).orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED, "Phiên đăng nhập không hợp lệ"));
        if (password == null || !passwords.matches(password, user.getPasswordHash())) throw new AppException(ErrorCode.UNAUTHORIZED, "Mật khẩu xác nhận không đúng");
    }
    @Transactional(readOnly = true)
    public Map<String, Object> export(String userId, String password, int offset) {
        verify(userId, password);
        var result = new LinkedHashMap<String, Object>();
        result.put("exportedAt", Instant.now()); result.put("offset", offset);
        result.put("profile", jdbc.queryForMap("SELECT id,email,full_name,avatar_url,bio,profile_visibility,created_at,updated_at FROM users WHERE id=?", userId));
        boolean more = false;
        for (var entry : Map.ofEntries(
                Map.entry("memberships", "SELECT * FROM class_members WHERE user_id=? ORDER BY id"),
                Map.entry("orders", "SELECT id,order_number,class_id,status,total_amount,currency,paid_at,created_at FROM orders WHERE buyer_id=? ORDER BY id"),
                Map.entry("entitlements", "SELECT * FROM entitlements WHERE user_id=? ORDER BY id"),
                Map.entry("examAttempts", "SELECT id,exam_id,class_id,started_at,submitted_at,ends_at,status,total_points,CASE WHEN status='PUBLISHED' THEN score ELSE NULL END AS score FROM exam_attempts WHERE user_id=? ORDER BY id"),
                Map.entry("examAnswers", "SELECT a.id,a.attempt_id,a.question_id,a.student_answer,CASE WHEN e.status='PUBLISHED' THEN a.points_awarded ELSE NULL END AS points_awarded,CASE WHEN e.status='PUBLISHED' THEN a.teacher_feedback ELSE NULL END AS teacher_feedback FROM attempt_answers a JOIN exam_attempts e ON e.id=a.attempt_id WHERE e.user_id=? ORDER BY a.id"),
                Map.entry("lessonProgress", "SELECT * FROM lesson_progress WHERE user_id=? ORDER BY id"),
                Map.entry("lessonQuestions", "SELECT * FROM lesson_questions WHERE user_id=? ORDER BY id"),
                Map.entry("lessonAnswers", "SELECT * FROM lesson_answers WHERE user_id=? ORDER BY id"),
                Map.entry("assignments", "SELECT * FROM assignment_submissions WHERE user_id=? ORDER BY id"),
                Map.entry("posts", "SELECT * FROM posts WHERE author_id=? ORDER BY id"),
                Map.entry("comments", "SELECT * FROM comments WHERE author_id=? ORDER BY id"),
                // D-27: blog posts written, events created or hosted, and own event registrations.
                Map.entry("blogPosts", "SELECT * FROM blog_posts WHERE author_id=? ORDER BY id"),
                Map.entry("events", "SELECT * FROM class_events WHERE ? IN (created_by, host_user_id) ORDER BY id"),
                Map.entry("eventRegistrations", "SELECT r.id,r.event_id,e.class_id,e.title,e.starts_at,e.ends_at,r.registered_at FROM class_event_registrations r JOIN class_events e ON e.id=r.event_id WHERE r.user_id=? ORDER BY r.id")
        ).entrySet()) {
            var rows = jdbc.queryForList(entry.getValue() + " LIMIT 1001 OFFSET ?", userId, offset);
            more |= rows.size() > 1000;
            result.put(entry.getKey(), rows.size() > 1000 ? rows.subList(0, 1000) : rows);
        }
        result.put("hasMore", more); result.put("nextOffset", more ? offset + 1000 : null);
        return result;
    }
    @Transactional
    public Map<String, Object> requestDeletion(String userId, String password, String reason) {
        verify(userId, password);
        // Serialize requests for this user, including first creation.
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", String.class, userId);
        var existing = jdbc.queryForList("SELECT * FROM privacy_requests WHERE user_id=?", userId);
        if (!existing.isEmpty() && Set.of("PENDING", "ON_HOLD", "COMPLETED").contains(existing.get(0).get("status"))) return existing.get(0);
        if (!existing.isEmpty()) jdbc.update("DELETE FROM privacy_requests WHERE user_id=?", userId);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO privacy_requests (user_id,id,status,reason,created_at,updated_at) VALUES (?,?,'PENDING',?,?,?)", userId, UUID.randomUUID().toString(), reason, now, now);
        return jdbc.queryForMap("SELECT * FROM privacy_requests WHERE user_id=?", userId);
    }
    /**
     * D-27: a closed account frees its seats in events that have not ended. Same lock order as EventService.register (event row FOR UPDATE,
     * then the registration row), so registered_count stays equal to the number of registration rows. Registrations of past events stay
     * as attendance history attached to the anonymised user.
     */
    private void releaseUpcomingEventRegistrations(String userId) {
        List<String> eventIds = jdbc.queryForList("SELECT r.event_id FROM class_event_registrations r JOIN class_events e ON e.id=r.event_id"
                + " WHERE r.user_id=? AND e.ends_at>=? ORDER BY r.event_id", String.class, userId, Timestamp.from(Instant.now()));
        for (String eventId : eventIds) {
            jdbc.queryForList("SELECT id FROM class_events WHERE id=? FOR UPDATE", eventId);
            int deleted = jdbc.update("DELETE FROM class_event_registrations WHERE event_id=? AND user_id=?", eventId, userId);
            if (deleted > 0) {
                jdbc.update("UPDATE class_events SET registered_count=GREATEST(registered_count-?,0) WHERE id=?", deleted, eventId);
            }
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> requests(String userId, boolean admin) {
        return admin ? jdbc.queryForList("SELECT * FROM privacy_requests ORDER BY created_at LIMIT 1000")
                : jdbc.queryForList("SELECT * FROM privacy_requests WHERE user_id=?", userId);
    }
    @Transactional
    public Map<String, Object> resolve(String userId, String adminId, String status, String resolution) {
        if (!Set.of("ON_HOLD", "REJECTED", "COMPLETED").contains(status) || resolution == null || resolution.isBlank()) throw new AppException(ErrorCode.BAD_REQUEST, "Chọn kết quả xử lý và ghi rõ lý do/căn cứ lưu trữ");
        var rows = jdbc.queryForList("SELECT * FROM privacy_requests WHERE user_id=? FOR UPDATE", userId);
        if (rows.isEmpty()) throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy yêu cầu");
        if ("COMPLETED".equals(rows.get(0).get("status"))) return rows.get(0);
        if ("COMPLETED".equals(status)) {
            Integer owned = jdbc.queryForObject("SELECT COUNT(*) FROM classrooms WHERE owner_id=? AND status='ACTIVE'", Integer.class, userId);
            if (owned != null && owned > 0) throw new AppException(ErrorCode.CONFLICT, "Cần chuyển quyền sở hữu hoặc lưu trữ lớp đang hoạt động trước khi xóa tài khoản");
            jdbc.update("UPDATE users SET email=?,full_name='Tài khoản đã xóa',avatar_url=NULL,bio=NULL,profile_visibility='PRIVATE',status='DELETED',password_hash=?,updated_at=? WHERE id=?",
                    "deleted+" + userId + "@invalid.local", passwords.encode(UUID.randomUUID().toString()), Timestamp.from(Instant.now()), userId);
            jdbc.update("DELETE FROM refresh_tokens WHERE user_id=?", userId);
            var memberships = jdbc.queryForList("SELECT class_id FROM class_members WHERE user_id=? AND state='ACTIVE'", userId);
            for (var member : memberships) outbox.recordEvent("Class", member.get("class_id").toString(), "MEMBER_REMOVED", Map.of("userId", userId, "classId", member.get("class_id")));
            // D-28: a pending join request is not a membership - it simply disappears (no REMOVED row left behind).
            jdbc.update("DELETE FROM class_members WHERE user_id=? AND state='PENDING'", userId);
            jdbc.update("UPDATE class_members SET state='REMOVED' WHERE user_id=?", userId);
            jdbc.update("UPDATE staff_assignments SET status='INACTIVE' WHERE user_id=?", userId);
            jdbc.update("UPDATE posts SET title='Nội dung đã xóa',content_markdown='Nội dung đã xóa theo yêu cầu của tác giả',status='ARCHIVED' WHERE author_id=?", userId);
            jdbc.update("UPDATE comments SET content='Bình luận đã xóa theo yêu cầu của tác giả' WHERE author_id=?", userId);
            jdbc.update("UPDATE lesson_questions SET author_name='Tài khoản đã xóa',question_text='Câu hỏi đã xóa theo yêu cầu' WHERE user_id=?", userId);
            jdbc.update("UPDATE lesson_answers SET author_name='Tài khoản đã xóa',answer_text='Nội dung đã xóa theo yêu cầu' WHERE user_id=?", userId);
            jdbc.update("UPDATE privacy_requests SET reason=NULL WHERE user_id=?", userId);
            releaseUpcomingEventRegistrations(userId);
            // D-27: blog posts and events the user authored / hosts stay as class content; their byline / host follows the anonymised
            // user row above (docs/DATA_POLICY.md).
        }
        jdbc.update("UPDATE privacy_requests SET status=?,resolution=?,resolved_by=?,updated_at=? WHERE user_id=?", status, resolution, adminId, Timestamp.from(Instant.now()), userId);
        try { audit.record(null, adminId, "PRIVACY_REQUEST_RESOLVE", "USER", userId, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("status", status, "resolution", resolution))); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
        return jdbc.queryForMap("SELECT * FROM privacy_requests WHERE user_id=?", userId);
    }
}
