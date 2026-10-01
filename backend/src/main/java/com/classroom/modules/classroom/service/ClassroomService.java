package com.classroom.modules.classroom.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.dto.ClassAccessProductDto;
import com.classroom.modules.classroom.dto.ClassMemberDto;
import com.classroom.modules.classroom.dto.ClassroomDto;
import com.classroom.modules.classroom.dto.CreateClassroomRequest;
import com.classroom.modules.classroom.dto.UpdateClassAccessRequest;
import com.classroom.modules.classroom.dto.UpdateClassroomRequest;
import com.classroom.modules.classroom.dto.UpdateClassroomStatusRequest;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.model.ClassAbout;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.model.StaffAssignment;
import com.classroom.modules.classroom.model.StaffPermission;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ClassroomService {

    /**
     * R7-01: mirrors StaffService.COURSE_SCOPABLE_MODULES. assignStaff now rejects new
     * course-scoped grants for any module outside this set, but pre-existing DB rows created
     * before that guard may still carry an inert scoped grant (e.g. STORE scoped to a course) —
     * AccessPolicy.canManage never checks those modules with a non-null course scope, so such a
     * grant can never authorize anything. Excluding it here keeps the API contract (and the
     * frontend's per-course picker / nav gating) consistent with what the server will actually
     * enforce, instead of advertising a permission that silently does nothing.
     */
    private static final java.util.Set<String> COURSE_SCOPABLE_MODULES = java.util.Set.of("COURSE", "EXAM");

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
    private final AuditService auditService;
    private final ClassMembershipService membershipService;
    private final ClassAccessService accessService;

    /** Kept for callers (and unit tests) that build the service by hand: membership work is real, the paid-access read model is absent (accessProduct stays null). */
    public ClassroomService(ClassroomRepository classroomRepository,
                            ClassMemberRepository memberRepository,
                            ClassAboutRepository aboutRepository,
                            StaffAssignmentRepository staffAssignmentRepository,
                            StaffPermissionRepository staffPermissionRepository,
                            UserRepository userRepository,
                            OutboxService outboxService,
                            AccessPolicy accessPolicy,
                            ProfileVisibilityPolicy profileVisibilityPolicy,
                            ProPolicy proPolicy,
                            AuditService auditService) {
        this(classroomRepository, memberRepository, aboutRepository, staffAssignmentRepository, staffPermissionRepository,
                userRepository, outboxService, accessPolicy, profileVisibilityPolicy, proPolicy, auditService,
                new ClassMembershipService(memberRepository, outboxService), null);
    }

    @Autowired
    public ClassroomService(ClassroomRepository classroomRepository,
                            ClassMemberRepository memberRepository,
                            ClassAboutRepository aboutRepository,
                            StaffAssignmentRepository staffAssignmentRepository,
                            StaffPermissionRepository staffPermissionRepository,
                            UserRepository userRepository,
                            OutboxService outboxService,
                            AccessPolicy accessPolicy,
                            ProfileVisibilityPolicy profileVisibilityPolicy,
                            ProPolicy proPolicy,
                            AuditService auditService,
                            ClassMembershipService membershipService,
                            ClassAccessService accessService) {
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
        this.auditService = auditService;
        this.membershipService = membershipService;
        this.accessService = accessService;
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
        classroom.setVisibility(normalizeVisibility(req.getVisibility(), Classroom.VISIBILITY_PUBLIC));

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

    /** R3-08: cap on page size for the paginated listing. */
    public static final int MAX_PAGE_SIZE = 100;

    /**
     * R16-08: page size used when the caller sends no {@code size}. The listing used to be genuinely
     * unbounded (findAll + a handful of queries per class) although the controller's comment claimed a
     * default cap; now it is real. A client that wants more pages asks for {@code page=1,2,...}.
     */
    public static final int DEFAULT_PAGE_SIZE = 50;

    @Transactional(readOnly = true)
    public List<ClassroomDto> getAllClassrooms(String currentUserId) {
        return getAllClassrooms(currentUserId, 0, DEFAULT_PAGE_SIZE);
    }

    /**
     * R16-08: one page of the classes visible to {@code currentUserId} (newest first, id as a stable
     * tie-break). Visibility is applied in the query, so a page is never short because of rows filtered
     * out afterwards, and the page is rendered with a fixed number of batched queries (owners, member
     * counts, the caller's memberships / staff grants / PRO entitlements) instead of several per class.
     */
    @Transactional(readOnly = true)
    public List<ClassroomDto> getAllClassrooms(String currentUserId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.ASC, "id")));
        List<Classroom> pageOfClasses = currentUserId == null
                ? classroomRepository.findPubliclyVisible(pageable)
                : classroomRepository.findVisibleToUser(currentUserId, pageable);
        return toDtos(pageOfClasses, currentUserId);
    }

    @Transactional(readOnly = true)
    public ClassroomDto getBySlug(String slug, String currentUserId) {
        Classroom classroom = classroomRepository.findBySlug(slug)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy lớp học với slug: " + slug));
        if (accessPolicy.isHiddenPrivateClass(classroom, currentUserId)) {
            // D-19: a PRIVATE class does not exist for a viewer with no relation to it - the same 404 (and message) as an unknown slug.
            throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy lớp học với slug: " + slug);
        }
        if (!accessPolicy.isClassVisibleToUser(classroom, currentUserId)) {
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
        if (accessPolicy.isHiddenPrivateClass(classroom, currentUserId)) {
            throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy lớp học với id: " + id);
        }
        if (!accessPolicy.isClassVisibleToUser(classroom, currentUserId)) {
            if (currentUserId == null) {
                throw new AppException(ErrorCode.UNAUTHORIZED, "Yêu cầu đăng nhập để xem thông tin lớp học này");
            }
            throw new AppException(ErrorCode.FORBIDDEN, "Bạn không có quyền truy cập thông tin lớp học không công khai này");
        }
        return toDto(classroom, currentUserId);
    }

    /**
     * R13-02: PUT /classes/{id} (Studio "Cài đặt lớp"). Gated on CLASS:EDIT (canManage), which the
     * OWNER always satisfies and a staff member only satisfies if explicitly granted that module —
     * mirrors how "about" edits are gated on ABOUT:EDIT rather than being OWNER-only. Slug and
     * status are intentionally untouched here (see UpdateClassroomRequest's Javadoc and
     * updateClassroomStatus below).
     */
    @Transactional
    public ClassroomDto updateClassroom(String classId, UpdateClassroomRequest req, String currentUserId) {
        accessPolicy.enforceManage(currentUserId, classId, "CLASS", "EDIT", null);
        Classroom classroom = classroomRepository.findById(classId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy lớp học"));

        String visibilityBefore = classroom.isPrivate() ? Classroom.VISIBILITY_PRIVATE : Classroom.VISIBILITY_PUBLIC;
        classroom.setTitle(req.getTitle().trim());
        classroom.setDescription(req.getDescription());
        classroom.setCoverImageUrl(req.getCoverImageUrl());
        // D-19: absent = unchanged. PUBLIC -> PRIVATE keeps every existing member (they simply stop being discoverable by others);
        // PRIVATE -> PUBLIC lists the class and lets anyone join (invites stay valid but are no longer needed).
        classroom.setVisibility(normalizeVisibility(req.getVisibility(), visibilityBefore));
        Classroom saved = classroomRepository.save(classroom);

        auditService.record(classId, currentUserId, "CLASS_SETTINGS_UPDATE", "CLASSROOM", classId,
                String.format("{\"title\":\"%s\",\"visibilityBefore\":\"%s\",\"visibilityAfter\":\"%s\"}",
                        saved.getTitle().replace("\"", "'"), visibilityBefore, saved.isPrivate() ? Classroom.VISIBILITY_PRIVATE : Classroom.VISIBILITY_PUBLIC));

        return toDto(saved, currentUserId);
    }

    /**
     * R13-02: archive/unarchive (FR-14's Studio "Cài đặt lớp"). Unlike a plain settings edit, a
     * status transition changes whether the class (and everything under it) is visible to anyone
     * but its own OWNER/staff/members at all (see AccessPolicy.isClassVisibleToUser) — a much wider
     * blast radius than editing title/description, so this is OWNER-only rather than delegable via
     * CLASS:EDIT, matching the task's "OWNER-only for archive" call.
     *
     * <p>State machine: ACTIVE <-> ARCHIVED only (enforced by UpdateClassroomStatusRequest's
     * pattern before this method even runs). Archiving does not touch membership, staff
     * assignments or purchased entitlements — it only flips visibility/joinability, mirroring how
     * Course/Product ARCHIVED already behaves elsewhere in this codebase (content and access
     * already granted survive archiving; only new self-service join is blocked, per
     * joinClassroom's existing ACTIVE-only guard). Since R14-13 (decision D-11) an ARCHIVED class is
     * also frozen for NEW orders and NEW exam attempts - see AccessPolicy.isClassArchived.</p>
     */
    @Transactional
    public ClassroomDto updateClassroomStatus(String classId, UpdateClassroomStatusRequest req, String currentUserId) {
        accessPolicy.enforceOwner(currentUserId, classId);
        Classroom classroom = classroomRepository.findById(classId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy lớp học"));

        String target = req.getStatus().toUpperCase();
        if (target.equals(classroom.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Lớp học đã ở trạng thái này");
        }

        classroom.setStatus(target);
        Classroom saved = classroomRepository.save(classroom);

        auditService.record(classId, currentUserId,
                "ARCHIVED".equals(target) ? "CLASS_ARCHIVE" : "CLASS_UNARCHIVE",
                "CLASSROOM", classId, String.format("{\"status\":\"%s\"}", target));

        return toDto(saved, currentUserId);
    }

    /**
     * D-19: PUT /classes/{id}/access - FREE / PAID and the price. Money is involved, so it is NOT part of the CLASS:EDIT settings call:
     * the owner, or staff holding both STORE:EDIT and CLASS:EDIT (checked in {@link ClassAccessService#changeAccess}). See there for the
     * conversion rules (grandfathering on FREE -> PAID, nobody loses anything on PAID -> FREE).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ClassroomDto updateClassAccess(String classId, UpdateClassAccessRequest req, String currentUserId) {
        if (accessService == null) {
            throw new IllegalStateException("ClassAccessService is required to change a class access type");
        }
        Classroom saved = accessService.changeAccess(classId, req, currentUserId);
        return toDto(saved, currentUserId);
    }

    /** PUBLIC / PRIVATE from a request value (case-insensitive); absent or blank keeps {@code fallback}. */
    private static String normalizeVisibility(String requested, String fallback) {
        if (requested == null || requested.isBlank()) {
            return fallback;
        }
        return Classroom.VISIBILITY_PRIVATE.equalsIgnoreCase(requested.trim())
                ? Classroom.VISIBILITY_PRIVATE : Classroom.VISIBILITY_PUBLIC;
    }

    /**
     * POST /classes/{id}/join. D-19 rules, in order:
     * <ol>
     *   <li>unknown id, or a PRIVATE class this caller cannot see -&gt; 404 (indistinguishable);</li>
     *   <li>a class that is not ACTIVE takes no new members (403, D-11);</li>
     *   <li>someone who already belongs simply gets the class back (idempotent, also the owner and staff);</li>
     *   <li>a PAID class: BLOCKED -&gt; 403; everybody else -&gt; 402 PAYMENT_REQUIRED with the class-access product (checkout);</li>
     *   <li>a PRIVATE class -&gt; 403 INVITE_REQUIRED (only reachable for a caller who can see the class but is not in it);</li>
     *   <li>a FREE public class: REMOVED (and lapsed-EXPIRED) people rejoin, BLOCKED people cannot (403, D-12), anyone else becomes an
     *   ACTIVE member.</li>
     * </ol>
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ClassroomDto joinClassroom(String classId, String userId) {
        Classroom classroom = classroomRepository.findById(classId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy lớp học"));
        if (accessPolicy.isHiddenPrivateClass(classroom, userId)) {
            throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy lớp học");
        }

        // There is no approval flow: only active classes accept joins.
        if (!"ACTIVE".equalsIgnoreCase(classroom.getStatus())) {
            throw new AppException(ErrorCode.FORBIDDEN, "Lớp học hiện không mở đăng ký thành viên");
        }

        Instant now = Instant.now();
        Optional<ClassMember> existing = memberRepository.findByClassIdAndUserId(classId, userId);
        boolean belongs = classroom.getOwnerId().equals(userId) || existing.map(m -> m.isActiveAt(now)).orElse(false);
        if (belongs) {
            return toDto(classroom, userId);
        }
        if (existing.isPresent() && ClassMembershipService.isBlockedState(existing.get().getState())) {
            throw new AppException(ErrorCode.FORBIDDEN, ClassMembershipService.BLOCKED_MESSAGE);
        }
        if (classroom.isPaid()) {
            // Paid time survives removal: a REMOVED person who still holds a running purchase is let back in without paying again.
            if (accessService != null && accessService.restoreIfStillPaid(classroom, userId, now)) {
                return toDto(classroom, userId);
            }
            throw accessService != null ? accessService.paymentRequired(classroom) : new AppException(ErrorCode.PAYMENT_REQUIRED);
        }
        if (classroom.isPrivate()) {
            throw new AppException(ErrorCode.INVITE_REQUIRED);
        }
        membershipService.joinFree(classroom, userId);
        return toDto(classroom, userId);
    }

    @Transactional(readOnly = true)
    public List<ClassMemberDto> getClassMembers(String classId, String currentUserId) {
        accessPolicy.enforceMember(currentUserId, classId);
        // R16-08: the viewer-level privacy facts (is the viewer a class administrator? a class peer?) are
        // resolved once for the whole listing, not once per row and per field.
        ProfileVisibilityPolicy.ViewerContext viewer = profileVisibilityPolicy.viewerContext(currentUserId, classId);
        // R15-04: a REMOVED/BLOCKED member no longer belongs to the class, so an ordinary member must
        // not even learn that they exist (their state is a moderation decision). Only the class
        // administrators (OWNER, or STAFF holding MEMBER:VIEW — the same gate as Studio's member
        // management) see every state.
        boolean seesAllStates = viewer.isClassAdministrator();
        Instant now = Instant.now();
        List<ClassMember> rows = memberRepository.findByClassId(classId).stream()
                .filter(m -> seesAllStates || m.isActiveAt(now))
                .toList();

        // R16-08: one findAllById for the whole roster instead of a findById per member.
        Map<String, User> usersById = new HashMap<>();
        if (!rows.isEmpty()) {
            userRepository.findAllById(rows.stream().map(ClassMember::getUserId).distinct().toList())
                    .forEach(u -> usersById.put(u.getId(), u));
        }

        List<ClassMemberDto> result = new ArrayList<>(rows.size());
        for (ClassMember m : rows) {
            ClassMemberDto dto = new ClassMemberDto();
            dto.setId(m.getId());
            dto.setRole(m.getRole());
            dto.setState(m.effectiveState(now));
            dto.setJoinedAt(m.getJoinedAt());
            User u = usersById.get(m.getUserId());
            if (u != null) {
                // Finding 1: the member listing must honour the same visibility rule as the
                // profile endpoint, otherwise it leaks what that endpoint withheld. The row itself is
                // the target's membership record, so the class-admin override (R16-07: any state) applies.
                boolean identityVisible = viewer.isIdentityVisible(u, () -> true);
                if (identityVisible) {
                    dto.setUserId(m.getUserId());
                }
                dto.setUserFullName(viewer.displayName(u, identityVisible));
                dto.setUserAvatarUrl(viewer.avatarUrl(u, identityVisible));
            }
            result.add(dto);
        }
        return result;
    }

    public ClassroomDto toDto(Classroom classroom, String currentUserId) {
        return buildDto(classroom, currentUserId, new DirectLookups());
    }

    /** R16-08: DTOs for a page of classes using batched lookups (see {@link BatchLookups}). */
    private List<ClassroomDto> toDtos(List<Classroom> classrooms, String currentUserId) {
        if (classrooms.isEmpty()) {
            return List.of();
        }
        BatchLookups lookups = new BatchLookups(classrooms, currentUserId);
        return classrooms.stream().map(c -> buildDto(c, currentUserId, lookups)).toList();
    }

    /**
     * Normalises a stored membership state to the five values the API exposes (ACTIVE, EXPIRED, REMOVED, BLOCKED, NONE). Anything that is not
     * ACTIVE or REMOVED (BLOCKED, the legacy BANNED, or an unknown value) is treated as BLOCKED - the
     * safe reading: never offer a self-service rejoin for a state we do not understand.
     */
    static String memberStateOf(Optional<ClassMember> member) {
        if (member.isEmpty()) {
            return ClassroomDto.MEMBER_STATE_NONE;
        }
        // D-19: the effective state - a stored ACTIVE whose paid access has lapsed is already EXPIRED, swept or not.
        String state = member.get().effectiveState(Instant.now());
        state = state == null ? "" : state.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (state) {
            case "ACTIVE" -> ClassroomDto.MEMBER_STATE_ACTIVE;
            case "EXPIRED" -> ClassroomDto.MEMBER_STATE_EXPIRED;
            case "REMOVED" -> ClassroomDto.MEMBER_STATE_REMOVED;
            default -> ClassroomDto.MEMBER_STATE_BLOCKED;
        };
    }

    private ClassroomDto buildDto(Classroom classroom, String currentUserId, DtoLookups lookups) {
        ClassroomDto dto = new ClassroomDto();
        dto.setId(classroom.getId());
        dto.setOwnerId(classroom.getOwnerId());
        dto.setSlug(classroom.getSlug());
        dto.setTitle(classroom.getTitle());
        dto.setDescription(classroom.getDescription());
        dto.setCoverImageUrl(classroom.getCoverImageUrl());
        dto.setStatus(classroom.getStatus());
        dto.setVisibility(classroom.isPrivate() ? Classroom.VISIBILITY_PRIVATE : Classroom.VISIBILITY_PUBLIC);
        dto.setAccessType(classroom.isPaid() ? Classroom.ACCESS_PAID : Classroom.ACCESS_FREE);
        dto.setAccessProduct(lookups.accessProduct(classroom));
        dto.setCreatedAt(classroom.getCreatedAt());

        // R15-04: only ACTIVE members count - REMOVED/BLOCKED rows are not members any more.
        dto.setMemberCount(lookups.activeMemberCount(classroom.getId()));

        String ownerName = lookups.ownerName(classroom.getOwnerId());
        if (ownerName != null) {
            dto.setOwnerName(ownerName);
        }

        if (currentUserId != null) {
            boolean isOwner = classroom.getOwnerId().equals(currentUserId);
            dto.setOwner(isOwner);

            if (isOwner) {
                dto.setUserRole("OWNER");
                dto.setMember(true);
                dto.setMemberState(ClassroomDto.MEMBER_STATE_ACTIVE);
            } else {
                Optional<ClassMember> memberOpt = lookups.membership(classroom.getId(), currentUserId);
                String memberState = memberStateOf(memberOpt);
                dto.setMemberState(memberState);
                if (ClassroomDto.MEMBER_STATE_ACTIVE.equals(memberState)) {
                    // R16-01: only an ACTIVE row makes the caller a member. A REMOVED/BLOCKED row used to
                    // yield isMember=true / userRole=STUDENT here, so the UI showed a removed or blocked
                    // person the full member experience while every server-side check (AccessPolicy.
                    // isMember) refused them.
                    dto.setMember(true);
                    memberOpt.ifPresent(m -> dto.setAccessExpiresAt(m.getAccessExpiresAt()));
                    Optional<StaffAssignment> staffAssignment = lookups.staffAssignment(classroom.getId(), currentUserId)
                            .filter(s -> "ACTIVE".equalsIgnoreCase(s.getStatus()));
                    dto.setUserRole(staffAssignment.isPresent() ? "STAFF" : memberOpt.get().getRole());
                    staffAssignment.ifPresent(assignment -> {
                        // R5-07: studioPermissions feeds hasStudioPermission() on the frontend, which
                        // gates class-wide actions (e.g. "show the PUBLIC/PRO composer option"). A
                        // course-scoped grant (scopeCourseId != null) only authorizes actions on that
                        // one course server-side — AccessPolicy.canManage requires the caller to pass
                        // the matching resourceScopeCourseId — so surfacing it here as a bare
                        // "MODULE:ACTION" string would make the frontend believe the staff member has
                        // that permission class-wide. Only unscoped (whole-class) grants are emitted.
                        //
                        // R6-01: scoped grants are emitted separately in studioScopedPermissions so a
                        // course-scoped-only staff member is not locked out of Studio nav/routes and
                        // can still act on the specific course(s) they were granted — without ever
                        // being treated as class-wide via studioPermissions.
                        List<StaffPermission> perms = lookups.staffPermissions(assignment.getId());
                        dto.setStudioPermissions(perms.stream()
                                .filter(p -> p.getScopeCourseId() == null)
                                .map(p -> p.getModule().toUpperCase() + ":" + p.getAction().toUpperCase())
                                .distinct().toList());
                        dto.setStudioScopedPermissions(perms.stream()
                                .filter(p -> p.getScopeCourseId() != null
                                        && COURSE_SCOPABLE_MODULES.contains(p.getModule().toUpperCase()))
                                .map(p -> new ClassroomDto.StudioScopedPermission(
                                        p.getModule().toUpperCase(), p.getAction().toUpperCase(), p.getScopeCourseId()))
                                .toList());
                    });
                } else {
                    // Never joined, EXPIRED, REMOVED or BLOCKED: not a member. memberState tells the UI which
                    // of the four it is (join / renew / rejoin / blocked notice).
                    dto.setMember(false);
                    dto.setUserRole("GUEST");
                    if (ClassroomDto.MEMBER_STATE_EXPIRED.equals(memberState)) {
                        memberOpt.ifPresent(m -> dto.setAccessExpiresAt(m.getAccessExpiresAt()));
                    }
                }
            }

            // PRO status is derived from ProPolicy (OWNER, or member with an active
            // PRO entitlement) so the API contract matches the enforcement decisions.
            dto.setPro(lookups.isPro(currentUserId, classroom));
        } else {
            dto.setUserRole("GUEST");
        }

        return dto;
    }

    /**
     * The reads {@link #buildDto} needs beyond the classroom row. {@link DirectLookups} answers each one
     * with its own query (right for a single class); {@link BatchLookups} preloads them for a whole page
     * of classes so the listing needs a fixed number of queries.
     */
    private interface DtoLookups {
        String ownerName(String ownerId);

        long activeMemberCount(String classId);

        Optional<ClassMember> membership(String classId, String userId);

        Optional<StaffAssignment> staffAssignment(String classId, String userId);

        List<StaffPermission> staffPermissions(String assignmentId);

        boolean isPro(String userId, Classroom classroom);

        ClassAccessProductDto accessProduct(Classroom classroom);
    }

    private final class DirectLookups implements DtoLookups {
        @Override
        public String ownerName(String ownerId) {
            return userRepository.findById(ownerId).map(User::getFullName).orElse(null);
        }

        @Override
        public long activeMemberCount(String classId) {
            return memberRepository.countByClassIdAndState(classId, "ACTIVE");
        }

        @Override
        public Optional<ClassMember> membership(String classId, String userId) {
            return memberRepository.findByClassIdAndUserId(classId, userId);
        }

        @Override
        public Optional<StaffAssignment> staffAssignment(String classId, String userId) {
            return staffAssignmentRepository.findByClassIdAndUserId(classId, userId);
        }

        @Override
        public List<StaffPermission> staffPermissions(String assignmentId) {
            return staffPermissionRepository.findByAssignmentId(assignmentId);
        }

        @Override
        public boolean isPro(String userId, Classroom classroom) {
            return proPolicy.isPro(userId, classroom.getId());
        }

        @Override
        public ClassAccessProductDto accessProduct(Classroom classroom) {
            return accessService == null ? null : accessService.accessProductOf(classroom);
        }
    }

    private final class BatchLookups implements DtoLookups {
        private final Map<String, String> ownerNames = new HashMap<>();
        private final Map<String, Long> activeCounts = new HashMap<>();
        private final Map<String, ClassMember> memberships = new HashMap<>();
        private final Map<String, StaffAssignment> assignments = new HashMap<>();
        private final Map<String, List<StaffPermission>> permissionsByAssignment = new HashMap<>();
        private final java.util.Set<String> proClassIds;
        private final Map<String, ClassAccessProductDto> accessProducts;

        BatchLookups(List<Classroom> classrooms, String currentUserId) {
            List<String> classIds = classrooms.stream().map(Classroom::getId).toList();
            List<String> ownerIds = classrooms.stream().map(Classroom::getOwnerId).distinct().toList();

            userRepository.findAllById(ownerIds).forEach(u -> ownerNames.put(u.getId(), u.getFullName()));
            for (Object[] row : memberRepository.countActiveByClassIds(classIds)) {
                activeCounts.put((String) row[0], ((Number) row[1]).longValue());
            }

            java.util.Set<String> owned = new HashSet<>();
            java.util.Set<String> activeMemberOf = new HashSet<>();
            if (currentUserId != null) {
                classrooms.stream().filter(c -> c.getOwnerId().equals(currentUserId)).forEach(c -> owned.add(c.getId()));
                memberRepository.findByUserIdAndClassIdIn(currentUserId, classIds)
                        .forEach(m -> memberships.put(m.getClassId(), m));
                Instant now = Instant.now();
                memberships.forEach((classId, m) -> {
                    if (m.isActiveAt(now)) {
                        activeMemberOf.add(classId);
                    }
                });
                staffAssignmentRepository.findByUserIdAndClassIdIn(currentUserId, classIds)
                        .forEach(a -> assignments.put(a.getClassId(), a));
                List<String> activeAssignmentIds = assignments.values().stream()
                        .filter(a -> "ACTIVE".equalsIgnoreCase(a.getStatus()))
                        .map(StaffAssignment::getId).toList();
                if (!activeAssignmentIds.isEmpty()) {
                    staffPermissionRepository.findByAssignmentIdIn(activeAssignmentIds)
                            .forEach(p -> permissionsByAssignment
                                    .computeIfAbsent(p.getAssignmentId(), k -> new ArrayList<>()).add(p));
                }
            }
            this.proClassIds = currentUserId == null
                    ? java.util.Set.of()
                    : proPolicy.proClassIds(currentUserId, owned, activeMemberOf);
            this.accessProducts = accessService == null ? Map.of() : accessService.accessProductsOf(classrooms);
        }

        @Override
        public String ownerName(String ownerId) {
            return ownerNames.get(ownerId);
        }

        @Override
        public long activeMemberCount(String classId) {
            return activeCounts.getOrDefault(classId, 0L);
        }

        @Override
        public Optional<ClassMember> membership(String classId, String userId) {
            return Optional.ofNullable(memberships.get(classId));
        }

        @Override
        public Optional<StaffAssignment> staffAssignment(String classId, String userId) {
            return Optional.ofNullable(assignments.get(classId));
        }

        @Override
        public List<StaffPermission> staffPermissions(String assignmentId) {
            return permissionsByAssignment.getOrDefault(assignmentId, List.of());
        }

        @Override
        public boolean isPro(String userId, Classroom classroom) {
            return proClassIds.contains(classroom.getId());
        }

        @Override
        public ClassAccessProductDto accessProduct(Classroom classroom) {
            return accessProducts.get(classroom.getId());
        }
    }
}
