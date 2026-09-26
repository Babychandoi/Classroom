package com.classroom.modules.classroom.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.dto.ClassMemberDto;
import com.classroom.modules.classroom.dto.ClassroomDto;
import com.classroom.modules.classroom.dto.CreateClassroomRequest;
import com.classroom.modules.classroom.model.ClassAbout;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.ClassAboutRepository;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.classroom.repository.StaffAssignmentRepository;
import com.classroom.modules.classroom.repository.StaffPermissionRepository;
import com.classroom.modules.commerce.policy.ProPolicy;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.policy.ProfileVisibilityPolicy;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.outbox.service.OutboxService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ClassroomService {

    private final ClassroomRepository classroomRepository;
    private final ClassMemberRepository memberRepository;
    private final ClassAboutRepository aboutRepository;
    private final StaffAssignmentRepository staffAssignmentRepository;
    private final StaffPermissionRepository staffPermissionRepository;
    private final UserRepository userRepository;
    private final OutboxService outboxService;
    private final AccessPolicy accessPolicy;
    private final ProfileVisibilityPolicy profileVisibilityPolicy;
    private final ProPolicy proPolicy;

    public ClassroomService(ClassroomRepository classroomRepository,
                            ClassMemberRepository memberRepository,
                            ClassAboutRepository aboutRepository,
                            StaffAssignmentRepository staffAssignmentRepository,
                            StaffPermissionRepository staffPermissionRepository,
                            UserRepository userRepository,
                            OutboxService outboxService,
                            AccessPolicy accessPolicy,
                            ProfileVisibilityPolicy profileVisibilityPolicy,
                            ProPolicy proPolicy) {
        this.classroomRepository = classroomRepository;
        this.memberRepository = memberRepository;
        this.aboutRepository = aboutRepository;
        this.staffAssignmentRepository = staffAssignmentRepository;
        this.staffPermissionRepository = staffPermissionRepository;
        this.userRepository = userRepository;
        this.outboxService = outboxService;
        this.accessPolicy = accessPolicy;
        this.profileVisibilityPolicy = profileVisibilityPolicy;
        this.proPolicy = proPolicy;
    }

    @Transactional
    public ClassroomDto createClassroom(String ownerId, CreateClassroomRequest req) {
        String slug = req.getSlug().toLowerCase().trim();
        if (classroomRepository.existsBySlug(slug)) {
            throw new AppException(ErrorCode.CONFLICT, "Đường dẫn slug này đã được sử dụng");
        }

        Classroom classroom = new Classroom();
        classroom.setOwnerId(ownerId);
        classroom.setSlug(slug);
        classroom.setTitle(req.getTitle().trim());
        classroom.setDescription(req.getDescription());
        classroom.setCoverImageUrl(req.getCoverImageUrl());
        classroom.setStatus("ACTIVE");

        Classroom saved = classroomRepository.save(classroom);

        // Add owner as a member
        ClassMember ownerMember = new ClassMember(saved.getId(), ownerId, "OWNER");
        memberRepository.save(ownerMember);

        // Finding 8: Emit outbox event for owner membership
        outboxService.recordEvent("CLASSROOM", saved.getId(), "MEMBER_JOINED", Map.of(
                "userId", ownerId,
                "classId", saved.getId(),
                "role", "OWNER"
        ));

        // Create default about page
        ClassAbout about = new ClassAbout(
                saved.getId(),
                "## Chào mừng bạn đến với " + saved.getTitle() + "!\n\nLớp học trực tuyến chất lượng cao.",
                "1. Tôn trọng giảng viên và bạn học.\n2. Không chia sẻ tài liệu ra ngoài."
        );
        aboutRepository.save(about);

        return toDto(saved, ownerId);
    }

    @Transactional(readOnly = true)
    public List<ClassroomDto> getAllClassrooms(String currentUserId) {
        return classroomRepository.findAll().stream()
                .filter(c -> isVisibleToUser(c, currentUserId))
                .map(c -> toDto(c, currentUserId))
                .toList();
    }

    @Transactional(readOnly = true)
    public ClassroomDto getBySlug(String slug, String currentUserId) {
        Classroom classroom = classroomRepository.findBySlug(slug)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy lớp học với slug: " + slug));
        if (!isVisibleToUser(classroom, currentUserId)) {
            if (currentUserId == null) {
                throw new AppException(ErrorCode.UNAUTHORIZED, "Yêu cầu đăng nhập để xem thông tin lớp học này");
            }
            throw new AppException(ErrorCode.FORBIDDEN, "Bạn không có quyền truy cập thông tin lớp học không công khai này");
        }
        return toDto(classroom, currentUserId);
    }

    @Transactional(readOnly = true)
    public ClassroomDto getById(String id, String currentUserId) {
        Classroom classroom = classroomRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy lớp học với id: " + id));
        if (!isVisibleToUser(classroom, currentUserId)) {
            if (currentUserId == null) {
                throw new AppException(ErrorCode.UNAUTHORIZED, "Yêu cầu đăng nhập để xem thông tin lớp học này");
            }
            throw new AppException(ErrorCode.FORBIDDEN, "Bạn không có quyền truy cập thông tin lớp học không công khai này");
        }
        return toDto(classroom, currentUserId);
    }

    private boolean isVisibleToUser(Classroom classroom, String userId) {
        if ("ACTIVE".equalsIgnoreCase(classroom.getStatus())) {
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
        return accessPolicy.isMember(userId, classroom.getId());
    }

    @Transactional
    public ClassroomDto joinClassroom(String classId, String userId) {
        Classroom classroom = classroomRepository.findById(classId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy lớp học"));

        // There is no invite/approval flow yet: only active, publicly joinable classes accept joins.
        if (!"ACTIVE".equalsIgnoreCase(classroom.getStatus())) {
            throw new AppException(ErrorCode.FORBIDDEN, "Lớp học hiện không mở đăng ký thành viên");
        }

        Optional<ClassMember> existing = memberRepository.findByClassIdAndUserId(classId, userId);
        if (existing.isEmpty()) {
            ClassMember member = new ClassMember(classId, userId, "STUDENT");
            memberRepository.save(member);

            // Finding 8: Emit outbox event for student membership
            outboxService.recordEvent("CLASSROOM", classId, "MEMBER_JOINED", Map.of(
                    "userId", userId,
                    "classId", classId,
                    "role", "STUDENT"
            ));
        } else if (!"ACTIVE".equalsIgnoreCase(existing.get().getState())) {
            // Join is self-service, not an administrative appeal path. Never let a
            // suspended/removed member restore their own access.
            throw new AppException(ErrorCode.FORBIDDEN, "Tài khoản thành viên này đã bị vô hiệu hóa trong lớp học");
        }
        return toDto(classroom, userId);
    }

    @Transactional(readOnly = true)
    public List<ClassMemberDto> getClassMembers(String classId, String currentUserId) {
        accessPolicy.enforceMember(currentUserId, classId);
        return memberRepository.findByClassId(classId).stream()
                .map(m -> {
                    ClassMemberDto dto = new ClassMemberDto();
                    dto.setId(m.getId());
                    dto.setRole(m.getRole());
                    dto.setState(m.getState());
                    dto.setJoinedAt(m.getJoinedAt());
                    // Finding 1: the member listing must honour the same visibility rule as the
                    // profile endpoint, otherwise it leaks what that endpoint withheld.
                    userRepository.findById(m.getUserId()).ifPresent(u -> {
                        if (profileVisibilityPolicy.isIdentityVisible(u, currentUserId, classId)) {
                            dto.setUserId(m.getUserId());
                        }
                        dto.setUserFullName(profileVisibilityPolicy.displayName(u, currentUserId, classId));
                        dto.setUserAvatarUrl(profileVisibilityPolicy.avatarUrl(u, currentUserId, classId));
                    });
                    return dto;
                })
                .toList();
    }

    public ClassroomDto toDto(Classroom classroom, String currentUserId) {
        ClassroomDto dto = new ClassroomDto();
        dto.setId(classroom.getId());
        dto.setOwnerId(classroom.getOwnerId());
        dto.setSlug(classroom.getSlug());
        dto.setTitle(classroom.getTitle());
        dto.setDescription(classroom.getDescription());
        dto.setCoverImageUrl(classroom.getCoverImageUrl());
        dto.setStatus(classroom.getStatus());
        dto.setCreatedAt(classroom.getCreatedAt());

        long count = memberRepository.countByClassId(classroom.getId());
        dto.setMemberCount(count);

        userRepository.findById(classroom.getOwnerId()).ifPresent(u -> dto.setOwnerName(u.getFullName()));

        if (currentUserId != null) {
            boolean isOwner = classroom.getOwnerId().equals(currentUserId);
            dto.setOwner(isOwner);

            if (isOwner) {
                dto.setUserRole("OWNER");
                dto.setMember(true);
            } else {
                Optional<ClassMember> memberOpt = memberRepository.findByClassIdAndUserId(classroom.getId(), currentUserId);
                if (memberOpt.isPresent()) {
                    dto.setMember(true);
                    boolean isStaff = staffAssignmentRepository.findByClassIdAndUserId(classroom.getId(), currentUserId)
                            .map(s -> "ACTIVE".equalsIgnoreCase(s.getStatus()))
                            .orElse(false);
                    dto.setUserRole(isStaff ? "STAFF" : memberOpt.get().getRole());
                    if (isStaff) {
                        staffAssignmentRepository.findByClassIdAndUserId(classroom.getId(), currentUserId)
                                .ifPresent(assignment -> dto.setStudioPermissions(staffPermissionRepository.findByAssignmentId(assignment.getId()).stream()
                                        .map(p -> p.getModule().toUpperCase() + ":" + p.getAction().toUpperCase())
                                        .distinct().toList()));
                    }
                } else {
                    dto.setMember(false);
                    dto.setUserRole("GUEST");
                }
            }

            // PRO status is derived from ProPolicy (OWNER, or member with an active
            // PRO entitlement) so the API contract matches the enforcement decisions.
            dto.setPro(proPolicy.isPro(currentUserId, classroom.getId()));
        } else {
            dto.setUserRole("GUEST");
        }

        return dto;
    }
}
