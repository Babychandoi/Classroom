package com.classroom.modules.admin.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * D-29: the wire shapes of {@code /api/v1/admin/**} (docs/API.md section 12). Platform admins see class / user METADATA and aggregate
 * counts only - none of these records carries content bodies, answer keys, messages, payment secrets or password hashes.
 */
public final class AdminDtos {

    private AdminDtos() {
    }

    /** Body of every admin write: the mandatory reason, recorded in the audit row (and shown to the owner of a suspended class). */
    public record ReasonRequest(
            @NotBlank(message = "Cần ghi lý do (1..500 ký tự)")
            @Size(max = 500, message = "Lý do tối đa 500 ký tự") String reason) {
    }

    public record RoleRequest(
            @NotBlank(message = "Cần chọn vai trò USER hoặc PLATFORM_ADMIN") String role,
            @NotBlank(message = "Cần ghi lý do (1..500 ký tự)")
            @Size(max = 500, message = "Lý do tối đa 500 ký tự") String reason) {
    }

    public record Page<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        public static <T> Page<T> of(List<T> content, int page, int size, long total) {
            return new Page<>(content, page, size, total, size == 0 ? 0 : (int) ((total + size - 1) / size));
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------- overview

    public record Overview(Users users, Classes classes, Members members, Content content, Commerce commerce, Privacy privacy,
                           Outbox outbox, List<DayCount> signupsByDay) {
    }

    public record Users(long total, long active, long banned, long deleted, long admins, long newLast7Days, long newLast30Days) {
    }

    public record Classes(long total, long active, long archived, long suspended, @com.fasterxml.jackson.annotation.JsonProperty("public") long publicCount,
                          @com.fasterxml.jackson.annotation.JsonProperty("private") long privateCount, long paid, long newLast7Days) {
    }

    public record Members(long activeMemberships, long pendingRequests) {
    }

    public record Content(long courses, long publishedExams, long blogPostsPublished, long upcomingEvents) {
    }

    public record Commerce(long paidOrdersLast30Days, BigDecimal revenueLast30Days, String currency, long pendingOrders) {
    }

    public record Privacy(long openRequests) {
    }

    public record Outbox(long pending, long deadLetter) {
    }

    public record DayCount(String date, long count) {
    }

    // ---------------------------------------------------------------------------------------------------------------------------- users

    public record UserRow(String id, String email, String fullName, String avatarUrl, String role, String status, Instant createdAt,
                          long ownedClassCount, long membershipCount, Instant lastLoginAt) {
    }

    public record OwnedClass(String id, String slug, String title, String status) {
    }

    public record UserDetail(String id, String email, String fullName, String avatarUrl, String role, String status, Instant createdAt,
                             long ownedClassCount, long membershipCount, Instant lastLoginAt,
                             List<OwnedClass> ownedClasses, List<AuditRow> recentAudit) {
        public static UserDetail of(UserRow row, List<OwnedClass> ownedClasses, List<AuditRow> recentAudit) {
            return new UserDetail(row.id(), row.email(), row.fullName(), row.avatarUrl(), row.role(), row.status(), row.createdAt(),
                    row.ownedClassCount(), row.membershipCount(), row.lastLoginAt(), ownedClasses, recentAudit);
        }
    }

    // -------------------------------------------------------------------------------------------------------------------------- classes

    public record Person(String id, String fullName, String email) {
    }

    public record ClassRow(String id, String slug, String title, Person owner, String status, String visibility, String accessType,
                           String category, long memberCount, long pendingRequestCount, Instant createdAt, String coverUrl, String avatarUrl,
                           String suspendedReason, Instant suspendedAt) {
    }

    public record ClassCounts(long courses, long exams, long blogPosts, long events, long products, long paidOrders) {
    }

    public record ClassDetail(String id, String slug, String title, Person owner, String status, String visibility, String accessType,
                              String category, long memberCount, long pendingRequestCount, Instant createdAt, String coverUrl,
                              String avatarUrl, String suspendedReason, Instant suspendedAt, ClassCounts counts,
                              List<AuditRow> recentAudit) {
        public static ClassDetail of(ClassRow r, ClassCounts counts, List<AuditRow> recentAudit) {
            return new ClassDetail(r.id(), r.slug(), r.title(), r.owner(), r.status(), r.visibility(), r.accessType(), r.category(),
                    r.memberCount(), r.pendingRequestCount(), r.createdAt(), r.coverUrl(), r.avatarUrl(), r.suspendedReason(),
                    r.suspendedAt(), counts, recentAudit);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------------- audit

    public record AuditRow(String id, Instant createdAt, String action, String targetType, String targetId, String classId,
                           String classTitle, Person actor, JsonNode details) {
    }
}
