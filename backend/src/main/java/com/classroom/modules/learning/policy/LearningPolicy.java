package com.classroom.modules.learning.policy;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.commerce.model.Entitlement;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.model.Lesson;
import com.classroom.modules.learning.model.Section;
import com.classroom.modules.learning.repository.SectionRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@Component
public class LearningPolicy {

    private final AccessPolicy accessPolicy;
    private final EntitlementRepository entitlementRepository;
    private final SectionRepository sectionRepository;

    public LearningPolicy(AccessPolicy accessPolicy, EntitlementRepository entitlementRepository,
                          SectionRepository sectionRepository) {
        this.accessPolicy = accessPolicy;
        this.entitlementRepository = entitlementRepository;
        this.sectionRepository = sectionRepository;
    }

    /**
     * R14-02: single source of truth for "this lesson is archived, so a learner must not see or act
     * on it". A lesson is hidden when the lesson itself is archived OR the section that contains it
     * is archived (archiving a section hides every lesson under it, exactly like the course-detail
     * listing already did). Users who manage the course content (OWNER, or staff holding COURSE:EDIT
     * for the course) are exempt so Studio can still open archived items to restore them.
     *
     * <p>The course-level part of the rule (a DRAFT course, or an ARCHIVED course for anyone who is
     * not staff/owner and has no lasting entitlement) is already enforced by {@link #canLearn}/
     * {@link #enforceLearn}, which every learner path calls before this check; this method only adds
     * the lesson/section level so every learner path (lesson detail, progress, Q&amp;A, assignment
     * submit, media download, journey) shares one definition instead of re-deriving it.</p>
     */
    public boolean isLessonHiddenFromLearner(Lesson lesson, Course course, String userId) {
        if (lesson == null) return false;
        boolean archived = lesson.isArchived() || isSectionArchived(lesson.getSectionId());
        if (!archived) return false;
        return userId == null || course == null
                || !accessPolicy.canManage(userId, course.getClassId(), "COURSE", "EDIT", course.getId());
    }

    private boolean isSectionArchived(String sectionId) {
        if (sectionId == null || sectionRepository == null) return false;
        return sectionRepository.findById(sectionId).map(Section::isArchived).orElse(false);
    }

    /**
     * R13-09 (Learn "hết hạn"): why the viewer does/doesn't have access to {@code course}, and (for
     * PURCHASE_REQUIRED) when a time-boxed entitlement expires. Distinguishes EXPIRED (once had an
     * entitlement for this course's product, now lapsed) from NOT_PURCHASED (never had one) so the
     * UI can render "Sản phẩm hết hạn ngày dd/MM/yyyy" only for the former, with a distinct message
     * (and no renew CTA) for a learner who never bought it at all.
     */
    public AccessReason resolveAccessReason(String userId, Course course) {
        if (userId != null && accessPolicy.isOwner(userId, course.getClassId())) {
            return new AccessReason("OWNER", null);
        }
        if (userId != null && accessPolicy.canManage(userId, course.getClassId(), "COURSE", "PREVIEW", course.getId())) {
            return new AccessReason("STAFF", null);
        }
        if ("FREE".equalsIgnoreCase(course.getAccessMode())) {
            return new AccessReason("FREE", null);
        }
        if (userId == null) {
            return new AccessReason("NOT_PURCHASED", null);
        }
        Instant now = Instant.now();
        List<Entitlement> entitlements = entitlementRepository.findByUserIdAndClassId(userId, course.getClassId()).stream()
                .filter(e -> course.getProductId() != null && course.getProductId().equals(e.getProductId()))
                .filter(e -> course.getId().equals(e.getTargetCourseId()) || e.getTargetCourseId() == null)
                .toList();
        if (entitlements.isEmpty()) {
            return new AccessReason("NOT_PURCHASED", null);
        }
        // R14-12: a REVOKED (refunded) entitlement no longer grants anything, so it must never
        // contribute the "latest expiry" shown to the learner - otherwise a refunded row with a later
        // expiry than the live one displays a wrong (or, when all are revoked, misleading) end date.
        List<Entitlement> live = entitlements.stream()
                .filter(e -> !"REVOKED".equalsIgnoreCase(e.getState()))
                .toList();
        Instant latestExpiry = live.stream()
                .map(Entitlement::getExpiresAt)
                .max(Comparator.naturalOrder())
                .orElse(null);
        boolean active = live.stream().anyMatch(e -> e.isCurrentlyActive(now));
        if (active) {
            return new AccessReason("OWNED", latestExpiry);
        }
        // R14-12: paid but not started yet (SCHEDULED / future-start entitlement) is neither
        // NOT_PURCHASED nor EXPIRED: the learner owns it and it begins later. OWNED_UPCOMING carries
        // the earliest upcoming start so the UI can say "Bắt đầu từ dd/MM/yyyy" instead of inviting a
        // second purchase.
        Instant upcomingStart = live.stream()
                .filter(e -> "ACTIVE".equalsIgnoreCase(e.getState()))
                .filter(e -> e.getStartsAt().isAfter(now) && e.getExpiresAt().isAfter(now))
                .map(Entitlement::getStartsAt)
                .min(Comparator.naturalOrder())
                .orElse(null);
        if (upcomingStart != null) {
            return new AccessReason("OWNED_UPCOMING", latestExpiry, upcomingStart);
        }
        // Every entitlement for this product has lapsed (expired or revoked) — distinguish REVOKED
        // (refund) from a plain time-based EXPIRED so the UI never invites a renewal after a refund.
        boolean anyExpiredByTime = live.stream()
                .anyMatch(e -> "EXPIRED".equalsIgnoreCase(e.getState()) || e.getExpiresAt().isBefore(now));
        if (anyExpiredByTime) {
            return new AccessReason("EXPIRED", latestExpiry);
        }
        return new AccessReason("NOT_PURCHASED", null);
    }

    /**
     * @param startsAt only set for OWNED_UPCOMING: when the earliest not-yet-started entitlement begins.
     */
    public record AccessReason(String reason, Instant expiresAt, Instant startsAt) {
        public AccessReason(String reason, Instant expiresAt) {
            this(reason, expiresAt, null);
        }
    }

    public boolean canLearn(String userId, Course course) {
        if (userId == null || course == null) return false;

        // 1. OWNER has full access to all courses in the class, including DRAFT
        if (accessPolicy.isOwner(userId, course.getClassId())) {
            return true;
        }

        // 2. STAFF needs an explicit COURSE:PREVIEW grant to read learner content.
        //    Authoring rights (COURSE:EDIT) manage a course but never grant learning access to
        //    paid content; management rights must not bypass the entitlement check.
        if (accessPolicy.canManage(userId, course.getClassId(), "COURSE", "PREVIEW", course.getId())) {
            return true;
        }

        // Draft content is not learner-visible. Archived purchased content remains available
        // according to the entitlement policy below.
        if (!"PUBLISHED".equalsIgnoreCase(course.getStatus())
                && !("ARCHIVED".equalsIgnoreCase(course.getStatus())
                && "PURCHASE_REQUIRED".equalsIgnoreCase(course.getAccessMode()))) return false;

        // 3. User must at least be a member of the classroom
        if (!accessPolicy.isMember(userId, course.getClassId())) {
            return false;
        }

        // 4. FREE access mode: any active member can learn
        if ("FREE".equalsIgnoreCase(course.getAccessMode())) {
            return true;
        }

        // 5. PURCHASE_REQUIRED access mode: check active entitlement
        Instant now = Instant.now();
        return entitlementRepository.hasCourseAccess(
                userId,
                course.getClassId(),
                course.getId(),
                course.getProductId(),
                now
        );
    }

    /**
     * Whether the user may see an unpublished (DRAFT) course at all, including its metadata,
     * section names and lesson titles. Restricted to the class OWNER and STAFF holding an
     * explicit COURSE:PREVIEW or COURSE:EDIT grant for that course.
     */
    public boolean canViewUnpublished(String userId, Course course) {
        if (userId == null || course == null) return false;
        return accessPolicy.isOwner(userId, course.getClassId())
                || accessPolicy.canManage(userId, course.getClassId(), "COURSE", "PREVIEW", course.getId())
                || accessPolicy.canManage(userId, course.getClassId(), "COURSE", "EDIT", course.getId());
    }

    /** True when the course is visible to the user in learner-facing listings and detail views. */
    public boolean canViewCourse(String userId, Course course) {
        if (userId == null || course == null) return false;
        if ("DRAFT".equalsIgnoreCase(course.getStatus())
                || ("ARCHIVED".equalsIgnoreCase(course.getStatus())
                && "FREE".equalsIgnoreCase(course.getAccessMode()))) {
            return canViewUnpublished(userId, course);
        }
        if ("ARCHIVED".equalsIgnoreCase(course.getStatus())) {
            return canLearn(userId, course);
        }
        return accessPolicy.isMember(userId, course.getClassId());
    }

    public void enforceLearn(String userId, Course course) {
        if (!canLearn(userId, course)) {
            if (accessPolicy.isMembershipExpired(userId, course.getClassId())) {
                throw new AppException(ErrorCode.MEMBERSHIP_EXPIRED); // D-19: lapsed paid access of the class itself
            }
            throw new AppException(ErrorCode.COURSE_ACCESS_REQUIRED, "Bạn cần mua khóa học này để truy cập nội dung bài giảng");
        }
    }
}
