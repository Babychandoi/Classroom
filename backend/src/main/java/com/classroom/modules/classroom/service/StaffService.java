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
import java.util.Optional;

@Service
public class StaffService {

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

        // Ensure user is member
        Optional<ClassMember> memberOpt = memberRepository.findByClassIdAndUserId(classId, targetUserId);
        if (memberOpt.isEmpty()) {
            memberRepository.save(new ClassMember(classId, targetUserId, "STAFF"));
        } else {
            ClassMember m = memberOpt.get();
            if (!"ACTIVE".equalsIgnoreCase(m.getState())) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Không thể cấp nhân sự cho thành viên chưa hoạt động trong lớp");
            }
            m.setRole("STAFF");
            memberRepository.save(m);
        }
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
                String module = pDto.getModule().toUpperCase();
                String action = pDto.getAction().toUpperCase();
                String scopeCourseId = pDto.getScopeCourseId() == null || pDto.getScopeCourseId().isBlank() ? null : pDto.getScopeCourseId();
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

        staffAssignmentRepository.findByClassIdAndUserId(classId, targetUserId).ifPresent(assignment -> {
            staffPermissionRepository.deleteByAssignmentId(assignment.getId());
            staffAssignmentRepository.delete(assignment);
        });

        memberRepository.findByClassIdAndUserId(classId, targetUserId).ifPresent(m -> {
            m.setRole("STUDENT");
            memberRepository.save(m);
        });
        // Removing a STAFF assignment changes the role but leaves the user an active class
        // member; retain the MEMBER_OF edge in the graph projection.
        if (outboxService != null) outboxService.recordEvent("CLASSROOM", classId, "MEMBER_JOINED", java.util.Map.of("classId", classId, "userId", targetUserId));

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
