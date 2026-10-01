package com.classroom.modules.classroom.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.outbox.service.OutboxService;
import com.classroom.modules.classroom.dto.StaffAssignmentDto;
import com.classroom.modules.classroom.dto.StaffPermissionDto;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.StaffAssignment;
import com.classroom.modules.classroom.model.StaffPermission;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.repository.StaffAssignmentRepository;
import com.classroom.modules.classroom.repository.StaffPermissionRepository;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class StaffService {

    /**
     * R7-01: modules whose access is ever evaluated per-course (AccessPolicy.canManage /
     * enforceManage called with a non-null resourceScopeCourseId somewhere in the codebase).
     * Every other module (SEGMENT, STORE, STUDIO, FEED, DOCUMENT, ABOUT, MEDIA, MEMBER, AUDIT, ...)
     * is only ever checked with a null (class-wide) scope server-side, so a course-scoped grant on
     * one of them can never actually authorize anything there — assignStaff rejects such grants
     * up front instead of silently persisting an inert (and misleading) permission.
     */
    private static final java.util.Set<String> COURSE_SCOPABLE_MODULES = java.util.Set.of("COURSE", "EXAM");

    private final StaffAssignmentRepository staffAssignmentRepository;
    private final StaffPermissionRepository staffPermissionRepository;
    private final ClassMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final AccessPolicy accessPolicy;
    private final AuditService auditService;
    private final OutboxService outboxService;
    private final com.classroom.modules.learning.repository.CourseRepository courseRepository;

    public StaffService(StaffAssignmentRepository staffAssignmentRepository,
                        StaffPermissionRepository staffPermissionRepository,
                        ClassMemberRepository memberRepository,
                        UserRepository userRepository,
                        AccessPolicy accessPolicy,
                        AuditService auditService) {
        this(staffAssignmentRepository, staffPermissionRepository, memberRepository, userRepository,
                accessPolicy, auditService, null, null);
    }

    public StaffService(StaffAssignmentRepository staffAssignmentRepository,
                        StaffPermissionRepository staffPermissionRepository,
                        ClassMemberRepository memberRepository,
                        UserRepository userRepository,
                        AccessPolicy accessPolicy,
                       AuditService auditService,
                       OutboxService outboxService) {
        this(staffAssignmentRepository, staffPermissionRepository, memberRepository, userRepository,
                accessPolicy, auditService, outboxService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public StaffService(StaffAssignmentRepository staffAssignmentRepository,
                        StaffPermissionRepository staffPermissionRepository,
                        ClassMemberRepository memberRepository,
                        UserRepository userRepository,
                        AccessPolicy accessPolicy,
                        AuditService auditService,
                        OutboxService outboxService,
                        com.classroom.modules.learning.repository.CourseRepository courseRepository) {
        this.staffAssignmentRepository = staffAssignmentRepository;
        this.staffPermissionRepository = staffPermissionRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.accessPolicy = accessPolicy;
        this.auditService = auditService;
        this.outboxService = outboxService;
        this.courseRepository = courseRepository;
    }

    @Transactional(readOnly = true)
    public List<StaffAssignmentDto> getClassStaff(String classId, String currentUserId) {
        accessPolicy.enforceManage(currentUserId, classId, "STAFF", "VIEW", null);

        return staffAssignmentRepository.findByClassId(classId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public StaffAssignmentDto assignStaff(String classId, String targetUserId, List<StaffPermissionDto> permissions, String currentUserId) {
        accessPolicy.enforceOwner(currentUserId, classId);

        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy người dùng mục tiêu"));

        // R3-07 (part 2): the class owner already holds full authority; assigning themselves as
        // STAFF is meaningless and would let an owner accidentally scope down their own access.
        if (accessPolicy.isOwner(targetUserId, classId)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Chủ lớp học không thể tự thêm mình làm nhân viên");
        }

        // R4-01: assignStaff must never auto-create membership — the target must already be an
        // ACTIVE member of the class (i.e. they consented to join). Silently creating an ACTIVE
        // STAFF membership for an arbitrary user would grant them class access, and combined with
        // the class-admin privacy override, would let the owner read that user's PRIVATE profile
        // without consent.
        ClassMember m = memberRepository.findByClassIdAndUserId(classId, targetUserId)
                .orElseThrow(() -> new AppException(ErrorCode.BAD_REQUEST, "Người dùng chưa là thành viên của lớp học này"));
        if (!m.isActiveAt(java.time.Instant.now())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Không thể cấp nhân sự cho thành viên chưa hoạt động trong lớp");
        }
        m.setRole("STAFF");
        m.setAccessExpiresAt(null); // D-19: staff never need (or lose) paid access - assigning them ends the term
        memberRepository.save(m);
        if (outboxService != null) outboxService.recordEvent("CLASSROOM", classId, "MEMBER_JOINED", java.util.Map.of("classId", classId, "userId", targetUserId));

        StaffAssignment assignment = staffAssignmentRepository.findByClassIdAndUserId(classId, targetUserId)
                .orElseGet(() -> new StaffAssignment(classId, targetUserId));
        assignment.setStatus("ACTIVE");
        StaffAssignment savedAssignment = staffAssignmentRepository.save(assignment);

        // Update permissions. The bulk delete below is flushed immediately (see
        // StaffPermissionRepository#deleteByAssignmentId) so it always runs before the inserts
        // that follow, avoiding a uk_staff_permission collision from Hibernate's default
        // insert-before-delete flush ordering. Requested permissions are also deduped by their
        // natural key so a caller submitting the same grant twice cannot violate the constraint.
        staffPermissionRepository.deleteByAssignmentId(savedAssignment.getId());
        if (permissions != null) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (StaffPermissionDto pDto : permissions) {
                if (pDto.getScopeCourseId() != null && !pDto.getScopeCourseId().isBlank()) {
                    var scopedCourse = courseRepository.findById(pDto.getScopeCourseId())
                            .orElseThrow(() -> new AppException(ErrorCode.BAD_REQUEST, "Khóa học phạm vi không tồn tại"));
                    if (!classId.equals(scopedCourse.getClassId())) {
                        throw new AppException(ErrorCode.BAD_REQUEST, "Khóa học phạm vi không thuộc lớp này");
                    }
                }
                if (pDto.getModule() == null || pDto.getAction() == null) {
                    throw new AppException(ErrorCode.BAD_REQUEST, "Module và hành động của quyền không được để trống");
                }
                String module = pDto.getModule().toUpperCase();
                String action = pDto.getAction().toUpperCase();
                String scopeCourseId = pDto.getScopeCourseId() == null || pDto.getScopeCourseId().isBlank() ? null : pDto.getScopeCourseId();
                // R7-01: reject a course-scoped grant for a module that is never checked with a
                // course scope server-side — it would look assigned in the UI but never actually
                // authorize anything, and would silently produce an inert row in the DB.
                if (scopeCourseId != null && !COURSE_SCOPABLE_MODULES.contains(module)) {
                    throw new AppException(ErrorCode.BAD_REQUEST,
                            "Module " + module + " không hỗ trợ phân quyền theo phạm vi khóa học");
                }
                String naturalKey = module + "|" + action + "|" + scopeCourseId;
                if (!seen.add(naturalKey)) {
                    continue;
                }
                StaffPermission p = new StaffPermission(
                        savedAssignment.getId(),
                        module,
                        action,
                        scopeCourseId
                );
                staffPermissionRepository.save(p);
            }
        }

        // Finding 7: Record transactional audit event for staff assignment / permissions
        auditService.record(
                classId,
                currentUserId,
                "STAFF_PERMISSION_ASSIGN",
                "STAFF_ASSIGNMENT",
                savedAssignment.getId(),
                String.format("{\"targetUserId\":\"%s\",\"permissionCount\":%d}",
                        targetUserId, permissions != null ? permissions.size() : 0)
        );

        return toDto(savedAssignment);
    }

    @Transactional
    public void removeStaff(String classId, String targetUserId, String currentUserId) {
        accessPolicy.enforceOwner(currentUserId, classId);

        boolean assignmentRemoved = staffAssignmentRepository.findByClassIdAndUserId(classId, targetUserId)
                .map(assignment -> {
                    staffPermissionRepository.deleteByAssignmentId(assignment.getId());
                    staffAssignmentRepository.delete(assignment);
                    return true;
                }).orElse(false);

        memberRepository.findByClassIdAndUserId(classId, targetUserId).ifPresent(m -> {
            m.setRole("STUDENT");
            memberRepository.save(m);
        });
        // R3-07 (part 3): only emit when a STAFF assignment actually existed and was removed.
        // A no-op removal (the target was never staff) must not re-emit MEMBER_JOINED.
        // Removing a STAFF assignment changes the role but leaves the user an active class
        // member; retain the MEMBER_OF edge in the graph projection.
        if (assignmentRemoved && outboxService != null) {
            outboxService.recordEvent("CLASSROOM", classId, "MEMBER_JOINED", java.util.Map.of("classId", classId, "userId", targetUserId));
        }

        // Finding 7: Record transactional audit event for staff removal
        auditService.record(
                classId,
                currentUserId,
                "STAFF_REMOVE",
                "STAFF_ASSIGNMENT",
                targetUserId,
                String.format("{\"targetUserId\":\"%s\"}", targetUserId)
        );
    }

    private StaffAssignmentDto toDto(StaffAssignment assignment) {
        StaffAssignmentDto dto = new StaffAssignmentDto();
        dto.setId(assignment.getId());
        dto.setClassId(assignment.getClassId());
        dto.setUserId(assignment.getUserId());
        dto.setStatus(assignment.getStatus());
        dto.setAssignedAt(assignment.getAssignedAt());

        userRepository.findById(assignment.getUserId()).ifPresent(u -> {
            dto.setUserEmail(u.getEmail());
            dto.setUserFullName(u.getFullName());
        });

        List<StaffPermissionDto> perms = staffPermissionRepository.findByAssignmentId(assignment.getId()).stream()
                .map(p -> new StaffPermissionDto(p.getModule(), p.getAction(), p.getScopeCourseId()))
                .toList();
        dto.setPermissions(perms);

        return dto;
    }
}
