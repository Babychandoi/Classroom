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

import java.time.Instant;
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

    /**
     * R4-06 / D-19: whether {@code classroom} is visible to {@code userId} (or an anonymous viewer when
     * {@code userId} is {@code null}). A class is open to everyone only when it is ACTIVE <em>and</em> PUBLIC; a non-ACTIVE
     * (draft/archived) or PRIVATE class is visible to its owner, its active staff, and to members - ACTIVE, or EXPIRED (a lapsed
     * paid member must still see the class to read the paywall and renew). A REMOVED or BLOCKED person, an outsider and a guest do not.
     *
     * <p>This is the single source of truth: the class listing, detail, "about", products, feed and every other class-scoped read agree
     * on it, and its SQL twins ({@code ClassroomRepository#findPubliclyVisible} / {@code findVisibleToUser}) are pinned to it by a
     * parity test. For a PRIVATE class the callers answer "no" as a 404 indistinguishable from a class that does not exist
     * ({@link #isHiddenPrivateClass}).</p>
     */
    public boolean isClassVisibleToUser(Classroom classroom, String userId) {
        if (classroom == null) return false;
        if ("ACTIVE".equalsIgnoreCase(classroom.getStatus()) && !classroom.isPrivate()) {
            return true;
        }
        if (userId == null) {
            return false;
        }
        if (classroom.getOwnerId().equals(userId)) {
            return true;
        }
        if (staffAssignmentRepository.findByClassIdAndUserId(classroom.getId(), userId)
                .map(s -> "ACTIVE".equalsIgnoreCase(s.getStatus())).orElse(false)) {
            return true;
        }
        Instant now = Instant.now();
        return memberRepository.findByClassIdAndUserId(classroom.getId(), userId)
                .map(m -> m.isActiveAt(now) || "EXPIRED".equalsIgnoreCase(m.effectiveState(now)))
                .orElse(false);
    }

    public boolean isClassVisibleToUser(String classId, String userId) {
        return classroomRepository.findById(classId)
                .map(c -> isClassVisibleToUser(c, userId))
                .orElse(false);
    }

    /**
     * D-19: the class exists but is PRIVATE and this viewer has no relation to it - every endpoint must then answer exactly as it does
     * for an id that does not exist (404, same message), so the answer never reveals that a private class is there.
     */
    public boolean isHiddenPrivateClass(Classroom classroom, String userId) {
        return classroom != null && classroom.isPrivate() && !isClassVisibleToUser(classroom, userId);
    }

    /**
     * D-19: for id-only callers (products, comments ...): true when the class does not exist <em>or</em> is a PRIVATE class hidden from
     * the viewer - the two cases that must be indistinguishable (404).
     */
    public boolean isMissingOrHiddenPrivateClass(String classId, String userId) {
        if (classId == null) return true;
        Classroom classroom = classroomRepository.findById(classId).orElse(null);
        return classroom == null || isHiddenPrivateClass(classroom, userId);
    }

    /**
     * R14-13 (decision D-11): an ARCHIVED class is frozen for NEW activity - no new orders and no new
     * exam attempts - while everything already granted stays readable and in-flight attempts can be
     * finished. Single source of truth for that rule.
     */
    public boolean isClassArchived(String classId) {
        if (classId == null) return false;
        return classroomRepository.findById(classId)
                .map(c -> "ARCHIVED".equalsIgnoreCase(c.getStatus()))
                .orElse(false);
    }

    /**
     * Whether the user currently belongs to the class: its owner, or a member whose row is ACTIVE <em>and</em> whose paid access (D-19
     * {@code access_expires_at}) has not lapsed. The date is checked here as well as by the sweeper, so a member is locked out the instant
     * the access ends, not up to one sweep interval later. Free-class members, staff and grandfathered members have no date and never lapse.
     */
    public boolean isMember(String userId, String classId) {
        if (userId == null || classId == null) return false;
        if (isOwner(userId, classId)) return true;
        Instant now = Instant.now();
        return memberRepository.findByClassIdAndUserId(classId, userId)
                .map(m -> m.isActiveAt(now))
                .orElse(false);
    }

    /**
     * D-19: the person is on the class roster but their paid access has lapsed (stored state EXPIRED, or ACTIVE with a passed
     * {@code access_expires_at} the sweeper has not flipped yet). Drives {@code MEMBERSHIP_EXPIRED} (403) and the paywall in the UI.
     */
    public boolean isMembershipExpired(String userId, String classId) {
        if (userId == null || classId == null) return false;
        Instant now = Instant.now();
        return memberRepository.findByClassIdAndUserId(classId, userId)
                .map(m -> "EXPIRED".equalsIgnoreCase(m.effectiveState(now)))
                .orElse(false);
    }

    /**
     * The exception for "this caller is not a member of the class". One place decides what the caller may learn:
     * <ul>
     *   <li>the class does not exist, or it is PRIVATE and hidden from the caller -> 404 (same answer for both, D-19);</li>
     *   <li>the caller's paid access has lapsed -> 403 {@code MEMBERSHIP_EXPIRED} (the UI shows the renew paywall);</li>
     *   <li>anything else -> the usual 403.</li>
     * </ul>
     */
    public AppException membershipDenied(String userId, String classId) {
        Classroom classroom = classId == null ? null : classroomRepository.findById(classId).orElse(null);
        if (classroom == null || isHiddenPrivateClass(classroom, userId)) {
            return new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy lớp học");
        }
        if (isMembershipExpired(userId, classId)) {
            return new AppException(ErrorCode.MEMBERSHIP_EXPIRED);
        }
        return new AppException(ErrorCode.FORBIDDEN, "Bạn chưa là thành viên của lớp học này");
    }

    /**
     * R16-07: whether {@code userId} has ever belonged to the class - the owner, or ANY
     * {@code class_members} row regardless of state (ACTIVE, REMOVED, BLOCKED). This is not an access
     * check (use {@link #isMember} for that); it only answers "does this person exist in this class's
     * roster", which is what lets class administrators keep seeing the identity of members they removed
     * or blocked while an outsider who never joined stays invisible to them.
     */
    public boolean hasMembershipRecord(String userId, String classId) {
        if (userId == null || classId == null) return false;
        if (isOwner(userId, classId)) return true;
        return memberRepository.existsByClassIdAndUserId(classId, userId);
    }

    /**
     * R20-03: batch form of {@link #hasMembershipRecord}: which of {@code userIds} have ever belonged to the class (the owner, or
     * any {@code class_members} row regardless of state) - ONE query for a whole listing instead of one per row.
     */
    public java.util.Set<String> usersWithMembershipRecord(String classId, java.util.Collection<String> userIds) {
        java.util.Set<String> result = new java.util.HashSet<>();
        if (classId == null || userIds == null || userIds.isEmpty()) return result;
        java.util.List<String> distinct = userIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) return result;
        result.addAll(memberRepository.findUserIdsByClassIdAndUserIdIn(classId, distinct));
        classroomRepository.findById(classId).ifPresent(c -> {
            if (distinct.contains(c.getOwnerId())) result.add(c.getOwnerId());
        });
        return result;
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
            throw membershipDenied(userId, classId);
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
