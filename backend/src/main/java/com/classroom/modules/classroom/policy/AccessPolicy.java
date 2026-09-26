package com.classroom.modules.classroom.policy;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.model.StaffAssignment;
import com.classroom.modules.classroom.model.StaffPermission;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.classroom.repository.StaffAssignmentRepository;
import com.classroom.modules.classroom.repository.StaffPermissionRepository;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AccessPolicy {

    private final ClassroomRepository classroomRepository;
    private final ClassMemberRepository memberRepository;
    private final StaffAssignmentRepository staffAssignmentRepository;
    private final StaffPermissionRepository staffPermissionRepository;

    public AccessPolicy(ClassroomRepository classroomRepository,
                        ClassMemberRepository memberRepository,
                        StaffAssignmentRepository staffAssignmentRepository,
                        StaffPermissionRepository staffPermissionRepository) {
        this.classroomRepository = classroomRepository;
        this.memberRepository = memberRepository;
        this.staffAssignmentRepository = staffAssignmentRepository;
        this.staffPermissionRepository = staffPermissionRepository;
    }

    public boolean isOwner(String userId, String classId) {
        if (userId == null || classId == null) return false;
        return classroomRepository.findById(classId)
                .map(c -> c.getOwnerId().equals(userId))
                .orElse(false);
    }

    public boolean isMember(String userId, String classId) {
        if (userId == null || classId == null) return false;
        if (isOwner(userId, classId)) return true;
        return memberRepository.findByClassIdAndUserId(classId, userId)
                .map(m -> "ACTIVE".equalsIgnoreCase(m.getState()))
                .orElse(false);
    }

    public boolean isActiveStaff(String userId, String classId) {
        if (userId == null || classId == null || !isMember(userId, classId)) return false;
        return staffAssignmentRepository.findByClassIdAndUserId(classId, userId)
                .map(a -> "ACTIVE".equalsIgnoreCase(a.getStatus()))
                .orElse(false);
    }

    public void enforceOwner(String userId, String classId) {
        if (!isOwner(userId, classId)) {
            throw new AppException(ErrorCode.FORBIDDEN, "Chỉ chủ lớp học (OWNER) mới có quyền thực hiện thao tác này");
        }
    }

    public void enforceMember(String userId, String classId) {
        if (!isMember(userId, classId)) {
            throw new AppException(ErrorCode.FORBIDDEN, "Bạn chưa là thành viên của lớp học này");
        }
    }

    /**
     * canManage: OWNER has full access to the class.
     * STAFF has access only if assigned with matching module, action, and resource scope.
     */
    public boolean canManage(String userId, String classId, String module, String action, String resourceScopeCourseId) {
        if (userId == null || classId == null) return false;
        if (isOwner(userId, classId)) return true;

        // Check active staff assignment
        StaffAssignment assignment = staffAssignmentRepository.findByClassIdAndUserId(classId, userId)
                .filter(a -> "ACTIVE".equalsIgnoreCase(a.getStatus()))
                .orElse(null);

        if (assignment == null || !isMember(userId, classId)) return false;

        // Staff can never manage other staff permissions or change class ownership
        if ("STAFF".equalsIgnoreCase(module) && !"VIEW".equalsIgnoreCase(action)) {
            return false;
        }

        List<StaffPermission> permissions = staffPermissionRepository.findByAssignmentId(assignment.getId());

        for (StaffPermission perm : permissions) {
            boolean moduleMatch = perm.getModule().equalsIgnoreCase(module) || "*".equals(perm.getModule());
            boolean actionMatch = perm.getAction().equalsIgnoreCase(action) || "*".equals(perm.getAction());
            boolean scopeMatch = perm.getScopeCourseId() == null
                    || (resourceScopeCourseId != null && perm.getScopeCourseId().equals(resourceScopeCourseId));

            if (moduleMatch && actionMatch && scopeMatch) {
                return true;
            }
        }

        return false;
    }

    public void enforceManage(String userId, String classId, String module, String action, String resourceScopeCourseId) {
        if (!canManage(userId, classId, module, action, resourceScopeCourseId)) {
            throw new AppException(ErrorCode.STAFF_PERMISSION_DENIED,
                    String.format("Không có quyền thực hiện %s trên %s của lớp học", action, module));
        }
    }

    /**
     * Checks whether a user may access exam answer keys.
     * Only OWNER or STAFF with an explicit non-wildcard EXAM:EDIT permission grant are allowed.
     * Wildcard grants (e.g. EXAM:* or *:*) are intentionally excluded because answer-key access
     * is a sensitive privilege that must be granted explicitly.
     */
    public boolean canAccessAnswerKey(String userId, String classId, String resourceScopeCourseId) {
        if (userId == null || classId == null) return false;
        // OWNER always has full access
        if (isOwner(userId, classId)) return true;

        // Check active staff assignment
        StaffAssignment assignment = staffAssignmentRepository.findByClassIdAndUserId(classId, userId)
                .filter(a -> "ACTIVE".equalsIgnoreCase(a.getStatus()))
                .orElse(null);

        if (assignment == null || !isMember(userId, classId)) return false;

        List<StaffPermission> permissions = staffPermissionRepository.findByAssignmentId(assignment.getId());

        // Require an explicit EXAM:EDIT grant — wildcards are NOT accepted for this sensitive path
        for (StaffPermission perm : permissions) {
            boolean isExactExamModule = "EXAM".equalsIgnoreCase(perm.getModule());
            boolean isExactEditAction = "EDIT".equalsIgnoreCase(perm.getAction());
            boolean scopeMatches = perm.getScopeCourseId() == null
                    || (resourceScopeCourseId != null && perm.getScopeCourseId().equals(resourceScopeCourseId));
            if (isExactExamModule && isExactEditAction && scopeMatches) {
                return true;
            }
        }
        return false;
    }

    /** Compatibility helper for class-wide checks; scoped grants cannot authorize an unknown resource. */
    public boolean canAccessAnswerKey(String userId, String classId) {
        return canAccessAnswerKey(userId, classId, null);
    }
}
