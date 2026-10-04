package com.classroom.modules.admin.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.admin.dto.AdminDtos.ClassCounts;
import com.classroom.modules.admin.dto.AdminDtos.ClassDetail;
import com.classroom.modules.admin.dto.AdminDtos.ClassRow;
import com.classroom.modules.admin.dto.AdminDtos.Page;
import com.classroom.modules.admin.dto.AdminDtos.Person;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.classroom.service.ClassCategories;
import com.classroom.modules.classroom.service.ClassroomService;
import com.classroom.modules.media.service.MediaService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * D-29: platform-admin class management - every class (PRIVATE and ARCHIVED included: an admin may know they exist), metadata and counts
 * only, plus suspend / restore.
 *
 * <p>A SUSPENDED class is hidden from everybody but its owner (the same 404 as a hidden PRIVATE class, {@code AccessPolicy}) and frozen
 * for every new activity and every write; the owner sees it read-only with the reason. The status it had (ACTIVE / ARCHIVED) is kept in
 * {@code status_before_suspend} and restored as it was. Nothing else is touched: members, staff, purchases and content stay as they are.</p>
 */
@Service
public class AdminClassService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_QUERY_LENGTH = 100;

    private static final Set<String> STATUSES = Set.of("ACTIVE", "ARCHIVED", AccessPolicy.STATUS_SUSPENDED);
    private static final Set<String> VISIBILITIES = Set.of(Classroom.VISIBILITY_PUBLIC, Classroom.VISIBILITY_PRIVATE);
    private static final Set<String> ACCESS_TYPES = Set.of(Classroom.ACCESS_FREE, Classroom.ACCESS_PAID);

    private static final String COLUMNS = "c.id, c.slug, c.title, c.owner_id, c.status, c.visibility, c.access_type, c.category, c.created_at,"
            + " c.cover_media_id, c.avatar_media_id, c.suspended_reason, c.suspended_at, u.full_name AS owner_name, u.email AS owner_email";
    private static final String FROM = " FROM classrooms c JOIN users u ON u.id = c.owner_id";

    private final NamedParameterJdbcTemplate jdbc;
    private final ClassroomRepository classroomRepository;
    private final AuditService auditService;
    private final AdminAuditService adminAuditService;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    @Lazy
    private MediaService mediaService;

    public AdminClassService(NamedParameterJdbcTemplate jdbc, ClassroomRepository classroomRepository, AuditService auditService,
                             AdminAuditService adminAuditService, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.classroomRepository = classroomRepository;
        this.auditService = auditService;
        this.adminAuditService = adminAuditService;
        this.objectMapper = objectMapper;
    }

    private record BaseRow(String id, String slug, String title, Person owner, String status, String visibility, String accessType,
                           String category, Instant createdAt, String coverMediaId, String avatarMediaId, String suspendedReason,
                           Instant suspendedAt) {
    }

    private static BaseRow base(ResultSet rs, int i) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp suspended = rs.getTimestamp("suspended_at");
        String visibility = Classroom.VISIBILITY_PRIVATE.equalsIgnoreCase(rs.getString("visibility"))
                ? Classroom.VISIBILITY_PRIVATE : Classroom.VISIBILITY_PUBLIC;
        String access = Classroom.ACCESS_PAID.equalsIgnoreCase(rs.getString("access_type")) ? Classroom.ACCESS_PAID : Classroom.ACCESS_FREE;
        return new BaseRow(rs.getString("id"), rs.getString("slug"), rs.getString("title"),
                new Person(rs.getString("owner_id"), rs.getString("owner_name"), rs.getString("owner_email")),
                rs.getString("status"), visibility, access, rs.getString("category"), created == null ? null : created.toInstant(),
                rs.getString("cover_media_id"), rs.getString("avatar_media_id"), rs.getString("suspended_reason"),
                suspended == null ? null : suspended.toInstant());
    }

    // -------------------------------------------------------------------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public Page<ClassRow> list(String q, String status, String visibility, String accessType, String category, String sort,
                               int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("now", Timestamp.from(Instant.now()));
        String pattern = likePattern(q, MAX_QUERY_LENGTH);
        if (pattern != null) {
            where.append(" AND (LOWER(c.title) LIKE :pattern OR LOWER(c.slug) LIKE :pattern OR LOWER(u.email) LIKE :pattern)");
            params.addValue("pattern", pattern);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND c.status = :status");
            params.addValue("status", oneOf(status, STATUSES, "Trạng thái chỉ có thể là ACTIVE, ARCHIVED hoặc SUSPENDED"));
        }
        if (visibility != null && !visibility.isBlank()) {
            String v = oneOf(visibility, VISIBILITIES, "Phạm vi chỉ có thể là PUBLIC hoặc PRIVATE");
            where.append(Classroom.VISIBILITY_PRIVATE.equals(v) ? " AND UPPER(c.visibility) = 'PRIVATE'" : " AND UPPER(c.visibility) <> 'PRIVATE'");
        }
        if (accessType != null && !accessType.isBlank()) {
            String a = oneOf(accessType, ACCESS_TYPES, "Hình thức chỉ có thể là FREE hoặc PAID");
            where.append(Classroom.ACCESS_PAID.equals(a) ? " AND UPPER(c.access_type) = 'PAID'" : " AND UPPER(c.access_type) <> 'PAID'");
        }
        if (category != null && !category.isBlank()) {
            where.append(" AND c.category = :category");
            params.addValue("category", ClassCategories.require(category));
        }
        String sortKey = sort == null || sort.isBlank() ? "newest" : sort.trim().toLowerCase(Locale.ROOT);
        String orderBy = switch (sortKey) {
            case "newest" -> " ORDER BY c.created_at DESC, c.id ASC";
            case "name" -> " ORDER BY c.title ASC, c.id ASC";
            // the same "current member" rule as ClassMemberRepository.ACTIVE_AT / ClassroomDto.memberCount
            case "members" -> " ORDER BY (SELECT COUNT(*) FROM class_members m WHERE m.class_id = c.id AND m.state = 'ACTIVE'"
                    + " AND (m.access_expires_at IS NULL OR m.access_expires_at > :now)) DESC, c.created_at DESC, c.id ASC";
            default -> throw new AppException(ErrorCode.BAD_REQUEST, "Cách sắp xếp chỉ có thể là newest, members hoặc name");
        };
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + FROM + where, params, Long.class);
        params.addValue("limit", safeSize).addValue("offset", (long) safePage * safeSize);
        List<BaseRow> rows = jdbc.query("SELECT " + COLUMNS + FROM + where + orderBy + " LIMIT :limit OFFSET :offset", params,
                AdminClassService::base);
        return Page.of(withCounts(rows), safePage, safeSize, total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public ClassDetail detail(String classId) {
        ClassRow row = row(classId);
        ClassCounts counts = jdbc.queryForObject("SELECT"
                        + " (SELECT COUNT(*) FROM courses WHERE class_id = :id) AS courses,"
                        + " (SELECT COUNT(*) FROM exams WHERE class_id = :id) AS exams,"
                        + " (SELECT COUNT(*) FROM blog_posts WHERE class_id = :id) AS blog,"
                        + " (SELECT COUNT(*) FROM class_events WHERE class_id = :id) AS events,"
                        + " (SELECT COUNT(*) FROM products WHERE class_id = :id) AS products,"
                        + " (SELECT COUNT(*) FROM orders WHERE class_id = :id AND status = 'PAID') AS paid",
                new MapSqlParameterSource("id", classId),
                (rs, i) -> new ClassCounts(rs.getLong("courses"), rs.getLong("exams"), rs.getLong("blog"), rs.getLong("events"),
                        rs.getLong("products"), rs.getLong("paid")));
        return ClassDetail.of(row, counts, adminAuditService.recentForClass(classId));
    }

    private ClassRow row(String classId) {
        List<BaseRow> rows = jdbc.query("SELECT " + COLUMNS + FROM + " WHERE c.id = :id", new MapSqlParameterSource("id", classId),
                AdminClassService::base);
        if (rows.isEmpty()) {
            throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy lớp học");
        }
        return withCounts(rows).get(0);
    }

    /** Member and pending-request counts with one grouped read each; cover / avatar URLs signed locally in one media lookup each. */
    private List<ClassRow> withCounts(List<BaseRow> rows) {
        if (rows.isEmpty()) return List.of();
        List<String> ids = rows.stream().map(BaseRow::id).toList();
        MapSqlParameterSource p = new MapSqlParameterSource("ids", ids).addValue("now", Timestamp.from(Instant.now()));
        Map<String, Long> members = new HashMap<>();
        Map<String, Long> pending = new HashMap<>();
        jdbc.query("SELECT class_id,"
                + " SUM(CASE WHEN state = 'ACTIVE' AND (access_expires_at IS NULL OR access_expires_at > :now) THEN 1 ELSE 0 END) AS active,"
                + " SUM(CASE WHEN state = 'PENDING' THEN 1 ELSE 0 END) AS pending"
                + " FROM class_members WHERE class_id IN (:ids) GROUP BY class_id", p, rs -> {
                    members.put(rs.getString("class_id"), rs.getLong("active"));
                    pending.put(rs.getString("class_id"), rs.getLong("pending"));
                });
        List<String> coverIds = rows.stream().map(BaseRow::coverMediaId).filter(Objects::nonNull).distinct().toList();
        List<String> avatarIds = rows.stream().map(BaseRow::avatarMediaId).filter(Objects::nonNull).distinct().toList();
        Map<String, String> covers = mediaService == null || coverIds.isEmpty() ? Map.of()
                : mediaService.presignedImageUrls(coverIds, ClassroomService.COVER_MEDIA_PURPOSE);
        Map<String, String> avatars = mediaService == null || avatarIds.isEmpty() ? Map.of()
                : mediaService.presignedImageUrls(avatarIds, ClassroomService.AVATAR_MEDIA_PURPOSE);
        List<ClassRow> result = new ArrayList<>(rows.size());
        for (BaseRow r : rows) {
            boolean suspended = AccessPolicy.STATUS_SUSPENDED.equalsIgnoreCase(r.status());
            result.add(new ClassRow(r.id(), r.slug(), r.title(), r.owner(), r.status(), r.visibility(), r.accessType(), r.category(),
                    members.getOrDefault(r.id(), 0L), pending.getOrDefault(r.id(), 0L), r.createdAt(),
                    r.coverMediaId() == null ? null : covers.get(r.coverMediaId()),
                    r.avatarMediaId() == null ? null : avatars.get(r.avatarMediaId()),
                    suspended ? r.suspendedReason() : null, suspended ? r.suspendedAt() : null));
        }
        return result;
    }

    // ------------------------------------------------------------------------------------------------------------------------- writes

    /** ACTIVE / ARCHIVED -> SUSPENDED (409 if already suspended). The class row is locked first, like every other status change. */
    @Transactional
    public ClassRow suspend(String classId, String adminId, String reason) {
        Classroom classroom = classroomRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy lớp học"));
        if (AccessPolicy.isSuspended(classroom)) {
            throw new AppException(ErrorCode.CONFLICT, "Lớp học đang bị tạm khóa");
        }
        String before = "ARCHIVED".equalsIgnoreCase(classroom.getStatus()) ? "ARCHIVED" : "ACTIVE";
        classroom.setStatusBeforeSuspend(before);
        classroom.setStatus(AccessPolicy.STATUS_SUSPENDED);
        classroom.setSuspendedReason(reason.trim());
        classroom.setSuspendedAt(Instant.now());
        classroomRepository.saveAndFlush(classroom);
        auditService.record(classId, adminId, "ADMIN_CLASS_SUSPEND", "CLASSROOM", classId,
                details(reason, "statusBefore", before, "statusAfter", AccessPolicy.STATUS_SUSPENDED));
        return row(classId);
    }

    /** SUSPENDED -> the status it had before (ACTIVE or ARCHIVED); 409 for a class that is not suspended. */
    @Transactional
    public ClassRow restore(String classId, String adminId, String reason) {
        Classroom classroom = classroomRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy lớp học"));
        if (!AccessPolicy.isSuspended(classroom)) {
            throw new AppException(ErrorCode.CONFLICT, "Lớp học không ở trạng thái tạm khóa");
        }
        String after = "ARCHIVED".equalsIgnoreCase(classroom.getStatusBeforeSuspend()) ? "ARCHIVED" : "ACTIVE";
        String suspendedReason = classroom.getSuspendedReason();
        classroom.setStatus(after);
        classroom.setStatusBeforeSuspend(null);
        classroom.setSuspendedReason(null);
        classroom.setSuspendedAt(null);
        classroomRepository.saveAndFlush(classroom);
        auditService.record(classId, adminId, "ADMIN_CLASS_RESTORE", "CLASSROOM", classId,
                details(reason, "statusBefore", AccessPolicy.STATUS_SUSPENDED, "statusAfter", after, "suspendedReason", suspendedReason));
        return row(classId);
    }

    private String details(String reason, Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("reason", reason.trim());
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------ helpers

    /** Lower-cased {@code %q%} with the user's LIKE wildcards neutralised (as GET /classes does); null when there is no search. */
    static String likePattern(String q, int maxLength) {
        if (q == null) return null;
        String needle = q.trim();
        if (needle.length() > maxLength) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Từ khóa tìm kiếm tối đa " + maxLength + " ký tự");
        }
        String cleaned = needle.toLowerCase(Locale.ROOT).replace('%', ' ').replace('_', ' ').replace('\\', ' ').trim();
        return cleaned.isEmpty() ? null : "%" + cleaned + "%";
    }

    /** The upper-cased value when it is one of {@code allowed}, else 400 with {@code message}. */
    static String oneOf(String raw, Set<String> allowed, String message) {
        String v = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(v)) {
            throw new AppException(ErrorCode.BAD_REQUEST, message);
        }
        return v;
    }
}
