package com.classroom.modules.admin.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.admin.dto.AdminDtos.OwnedClass;
import com.classroom.modules.admin.dto.AdminDtos.Page;
import com.classroom.modules.admin.dto.AdminDtos.UserDetail;
import com.classroom.modules.admin.dto.AdminDtos.UserRow;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.identity.service.RefreshTokenService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Set;

/**
 * D-29: platform-admin user management - the list / detail (metadata and counts only) and the three writes: ban, unban, role.
 *
 * <p>Safety rules: an admin never bans or demotes themselves (400); the last ACTIVE platform admin is never banned or demoted (409) - checked
 * while holding row locks on every ACTIVE admin, so two admins demoting each other concurrently cannot leave the platform without one;
 * every write carries a reason (1..500) and writes an {@code ADMIN_*} audit row (details built with ObjectMapper). A ban revokes every
 * refresh-token family of the user in the same transaction; the access token stops working on the next request because the JWT filter
 * reads the account status from the database (D-26).</p>
 */
@Service
public class AdminUserService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int MAX_QUERY_LENGTH = 100;
    public static final int OWNED_CLASSES_LIMIT = 50;

    static final String ROLE_USER = "USER";
    static final String ROLE_ADMIN = "PLATFORM_ADMIN";
    private static final Set<String> STATUSES = Set.of("ACTIVE", "BANNED", "DELETED");
    private static final Set<String> ROLES = Set.of(ROLE_USER, ROLE_ADMIN);

    private final NamedParameterJdbcTemplate jdbc;
    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;
    private final AdminAuditService adminAuditService;
    private final ObjectMapper objectMapper;

    public AdminUserService(NamedParameterJdbcTemplate jdbc, UserRepository userRepository, RefreshTokenService refreshTokenService,
                            AuditService auditService, AdminAuditService adminAuditService, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.userRepository = userRepository;
        this.refreshTokenService = refreshTokenService;
        this.auditService = auditService;
        this.adminAuditService = adminAuditService;
        this.objectMapper = objectMapper;
    }

    private record BaseRow(String id, String email, String fullName, String avatarUrl, String role, String status, Instant createdAt) {
    }

    private static BaseRow base(ResultSet rs, int i) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        return new BaseRow(rs.getString("id"), rs.getString("email"), rs.getString("full_name"), rs.getString("avatar_url"),
                rs.getString("role"), rs.getString("status"), created == null ? null : created.toInstant());
    }

    // -------------------------------------------------------------------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public Page<UserRow> list(String q, String status, String role, String sort, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        String pattern = AdminClassService.likePattern(q, MAX_QUERY_LENGTH);
        if (pattern != null) {
            where.append(" AND (LOWER(u.email) LIKE :pattern OR LOWER(u.full_name) LIKE :pattern)");
            params.addValue("pattern", pattern);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND u.status = :status");
            params.addValue("status", AdminClassService.oneOf(status, STATUSES, "Trạng thái chỉ có thể là ACTIVE, BANNED hoặc DELETED"));
        }
        if (role != null && !role.isBlank()) {
            where.append(" AND u.role = :role");
            params.addValue("role", AdminClassService.oneOf(role, ROLES, "Vai trò chỉ có thể là USER hoặc PLATFORM_ADMIN"));
        }
        String sortKey = sort == null || sort.isBlank() ? "newest" : sort.trim().toLowerCase(Locale.ROOT);
        String orderBy = switch (sortKey) {
            case "newest" -> " ORDER BY u.created_at DESC, u.id ASC";
            case "oldest" -> " ORDER BY u.created_at ASC, u.id ASC";
            case "name" -> " ORDER BY u.full_name ASC, u.id ASC";
            default -> throw new AppException(ErrorCode.BAD_REQUEST, "Cách sắp xếp chỉ có thể là newest, oldest hoặc name");
        };
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM users u" + where, params, Long.class);
        params.addValue("limit", safeSize).addValue("offset", (long) safePage * safeSize);
        List<BaseRow> rows = jdbc.query("SELECT u.id, u.email, u.full_name, u.avatar_url, u.role, u.status, u.created_at FROM users u"
                + where + orderBy + " LIMIT :limit OFFSET :offset", params, AdminUserService::base);
        return Page.of(withCounts(rows), safePage, safeSize, total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public UserDetail detail(String userId) {
        UserRow row = row(userId);
        List<OwnedClass> owned = jdbc.query("SELECT id, slug, title, status FROM classrooms WHERE owner_id = :id"
                        + " ORDER BY created_at DESC, id ASC LIMIT " + OWNED_CLASSES_LIMIT,
                new MapSqlParameterSource("id", userId),
                (rs, i) -> new OwnedClass(rs.getString("id"), rs.getString("slug"), rs.getString("title"), rs.getString("status")));
        return UserDetail.of(row, owned, adminAuditService.recentForUser(userId));
    }

    private UserRow row(String userId) {
        List<BaseRow> rows = jdbc.query("SELECT u.id, u.email, u.full_name, u.avatar_url, u.role, u.status, u.created_at FROM users u"
                + " WHERE u.id = :id", new MapSqlParameterSource("id", userId), AdminUserService::base);
        if (rows.isEmpty()) {
            throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy người dùng");
        }
        return withCounts(rows).get(0);
    }

    /** Owned classes, ACTIVE memberships (owner rows excluded) and the last refresh-token issue for a whole page: three grouped reads. */
    private List<UserRow> withCounts(List<BaseRow> rows) {
        if (rows.isEmpty()) return List.of();
        List<String> ids = rows.stream().map(BaseRow::id).toList();
        MapSqlParameterSource p = new MapSqlParameterSource("ids", ids).addValue("now", Timestamp.from(Instant.now()));
        Map<String, Long> owned = new HashMap<>();
        jdbc.query("SELECT owner_id, COUNT(*) AS n FROM classrooms WHERE owner_id IN (:ids) GROUP BY owner_id", p,
                rs -> {
                    owned.put(rs.getString("owner_id"), rs.getLong("n"));
                });
        Map<String, Long> memberships = new HashMap<>();
        jdbc.query("SELECT user_id, COUNT(*) AS n FROM class_members WHERE user_id IN (:ids) AND state = 'ACTIVE' AND role <> 'OWNER'"
                + " AND (access_expires_at IS NULL OR access_expires_at > :now) GROUP BY user_id", p,
                rs -> {
                    memberships.put(rs.getString("user_id"), rs.getLong("n"));
                });
        Map<String, Instant> lastLogin = new HashMap<>();
        jdbc.query("SELECT user_id, MAX(created_at) AS last_at FROM refresh_tokens WHERE user_id IN (:ids) GROUP BY user_id", p,
                rs -> {
                    Timestamp t = rs.getTimestamp("last_at");
                    if (t != null) lastLogin.put(rs.getString("user_id"), t.toInstant());
                });
        List<UserRow> result = new ArrayList<>(rows.size());
        for (BaseRow r : rows) {
            result.add(new UserRow(r.id(), r.email(), r.fullName(), r.avatarUrl(), r.role(), r.status(), r.createdAt(),
                    owned.getOrDefault(r.id(), 0L), memberships.getOrDefault(r.id(), 0L), lastLogin.get(r.id())));
        }
        return result;
    }

    // ------------------------------------------------------------------------------------------------------------------------- writes

    @Transactional
    public UserRow ban(String targetId, String adminId, String reason) {
        if (targetId.equals(adminId)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Không thể tự khóa tài khoản của chính mình");
        }
        List<String> activeAdmins = lockActiveAdmins();
        User user = userRepository.findById(targetId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy người dùng"));
        if ("DELETED".equalsIgnoreCase(user.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "Tài khoản đã bị xóa; không thể khóa");
        }
        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "Tài khoản đã bị khóa");
        }
        if (ROLE_ADMIN.equalsIgnoreCase(user.getRole()) && activeAdmins.size() <= 1 && activeAdmins.contains(targetId)) {
            throw new AppException(ErrorCode.CONFLICT, "Không thể khóa quản trị nền tảng đang hoạt động cuối cùng");
        }
        String before = user.getStatus();
        user.setStatus("BANNED");
        userRepository.saveAndFlush(user);
        int revoked = refreshTokenService.revokeAllForUser(targetId);
        audit(adminId, "ADMIN_USER_BAN", targetId, details(reason, "statusBefore", before, "statusAfter", "BANNED", "revokedSessions", revoked));
        return row(targetId);
    }

    @Transactional
    public UserRow unban(String targetId, String adminId, String reason) {
        User user = userRepository.findById(targetId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy người dùng"));
        if (!"BANNED".equalsIgnoreCase(user.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "DELETED".equalsIgnoreCase(user.getStatus())
                    ? "Tài khoản đã bị xóa; không thể mở khóa" : "Tài khoản không ở trạng thái bị khóa");
        }
        user.setStatus("ACTIVE");
        userRepository.saveAndFlush(user);
        audit(adminId, "ADMIN_USER_UNBAN", targetId, details(reason, "statusBefore", "BANNED", "statusAfter", "ACTIVE"));
        return row(targetId);
    }

    @Transactional
    public UserRow changeRole(String targetId, String adminId, String requestedRole, String reason) {
        String role = AdminClassService.oneOf(requestedRole, ROLES, "Vai trò chỉ có thể là USER hoặc PLATFORM_ADMIN");
        if (targetId.equals(adminId)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Không thể tự thay đổi vai trò của chính mình");
        }
        List<String> activeAdmins = lockActiveAdmins();
        User user = userRepository.findById(targetId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy người dùng"));
        String before = user.getRole() == null ? ROLE_USER : user.getRole().toUpperCase(Locale.ROOT);
        if (role.equals(before)) {
            throw new AppException(ErrorCode.CONFLICT, "Người dùng đã có vai trò này");
        }
        if (ROLE_ADMIN.equals(role) && !"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new AppException(ErrorCode.CONFLICT, "Chỉ cấp quyền quản trị cho tài khoản đang hoạt động");
        }
        if (ROLE_ADMIN.equals(before) && activeAdmins.size() <= 1 && activeAdmins.contains(targetId)) {
            throw new AppException(ErrorCode.CONFLICT, "Không thể hạ quyền quản trị nền tảng đang hoạt động cuối cùng");
        }
        user.setRole(role);
        userRepository.saveAndFlush(user);
        audit(adminId, "ADMIN_USER_ROLE", targetId, details(reason, "roleBefore", before, "roleAfter", role));
        return row(targetId);
    }

    /**
     * Row locks (FOR UPDATE) on every ACTIVE platform admin, taken before the "last admin" check so concurrent bans / demotions of the
     * remaining admins serialise on them. The admin table is tiny.
     */
    private List<String> lockActiveAdmins() {
        return jdbc.queryForList("SELECT id FROM users WHERE role = 'PLATFORM_ADMIN' AND status = 'ACTIVE' ORDER BY id FOR UPDATE",
                new MapSqlParameterSource(), String.class);
    }

    private void audit(String adminId, String action, String targetId, String detailsJson) {
        auditService.record(null, adminId, action, "USER", targetId, detailsJson);
    }

    /** {@code {"reason": ..., k1: v1, ...}} serialised by ObjectMapper (never String.format). */
    String details(String reason, Object... pairs) {
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
}
