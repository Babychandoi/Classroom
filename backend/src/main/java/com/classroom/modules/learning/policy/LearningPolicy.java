package com.classroom.modules.learning.policy;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import com.classroom.modules.learning.model.Course;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class LearningPolicy {

    private final AccessPolicy accessPolicy;
    private final EntitlementRepository entitlementRepository;

    public LearningPolicy(AccessPolicy accessPolicy, EntitlementRepository entitlementRepository) {
        this.accessPolicy = accessPolicy;
        this.entitlementRepository = entitlementRepository;
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
            throw new AppException(ErrorCode.COURSE_ACCESS_REQUIRED, "Bạn cần mua khóa học này để truy cập nội dung bài giảng");
        }
    }
}
