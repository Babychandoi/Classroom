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

    /** D-27: optional collaborators (absent in the hand-built unit-test instances): cover URLs and upcoming-event counts. */
    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.classroom.modules.media.service.MediaService mediaService;
    @Autowired(required = false)
    private com.classroom.modules.event.repository.ClassEventRepository eventRepository;

    public static final String COVER_MEDIA_PURPOSE = "CLASS_COVER";
    public static final String AVATAR_MEDIA_PURPOSE = "CLASS_AVATAR";
    public static final String SORT_NEWEST = "newest";
    public static final String SORT_POPULAR = "popular";
    public static final int MAX_QUERY_LENGTH = 100;

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
        String slug = resolveSlug(req.getSlug(), req.getTitle());

        Classroom classroom = new Classroom();
        classroom.setOwnerId(ownerId);
        classroom.setSlug(slug);
        classroom.setTitle(req.getTitle().trim());
        classroom.setDescription(req.getDescription());
        classroom.setCoverImageUrl(req.getCoverImageUrl());
        classroom.setStatus("ACTIVE");
        classroom.setVisibility(normalizeVisibility(req.getVisibility(), Classroom.VISIBILITY_PUBLIC));
        // D-28: category / approval / focal points. Cover and avatar media need the class id, so they are attached by a PUT afterwards.
        classroom.setCategory(req.getCategory() == null || req.getCategory().isBlank() ? null : ClassCategories.require(req.getCategory()));
        classroom.setRequireApproval(Boolean.TRUE.equals(req.getRequireApproval()));
        classroom.setCoverPosition(ObjectPositions.require(req.getCoverPosition(), "bìa"));
        classroom.setAvatarPosition(ObjectPositions.require(req.getAvatarPosition(), "đại diện"));

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

    /**
     * D-28: an explicit slug keeps the old rules (lower-cased, 3..100 characters, 409 when taken). Without one the slug is derived from the
     * title ({@link SlugGenerator}); a taken one gets "-2" .. "-6", then a short random suffix. A concurrent create that still collides
     * is stopped by the unique index (DataIntegrityViolation -&gt; 409).
     */
    private String resolveSlug(String rawSlug, String title) {
        if (rawSlug != null && !rawSlug.isBlank()) {
            String slug = rawSlug.toLowerCase(java.util.Locale.ROOT).trim();
            if (slug.length() < 3 || slug.length() > 100) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Slug từ 3 đến 100 ký tự");
            }
            if (classroomRepository.existsBySlug(slug)) {
                throw new AppException(ErrorCode.CONFLICT, "Đường dẫn slug này đã được sử dụng");
            }
            return slug;
        }
        String base = SlugGenerator.fromTitle(title);
        if (!classroomRepository.existsBySlug(base)) return base;
        for (int n = 2; n <= 6; n++) {
            String candidate = SlugGenerator.withSuffix(base, String.valueOf(n));
            if (!classroomRepository.existsBySlug(candidate)) return candidate;
        }
        for (int attempt = 0; attempt < 10; attempt++) {
            String candidate = SlugGenerator.withSuffix(base, java.util.UUID.randomUUID().toString().substring(0, 6));
            if (!classroomRepository.existsBySlug(candidate)) return candidate;
        }
        throw new AppException(ErrorCode.CONFLICT, "Không tạo được đường dẫn cho lớp học; hãy nhập slug khác");
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
        return getAllClassrooms(currentUserId, page, size, null, null);
    }

    /**
     * D-27: the listing with an optional search ({@code q}: case-insensitive match on title / description, trimmed, at most 100 characters)
     * and {@code sort} ({@code newest} - the default, unchanged - or {@code popular}: current active member count, highest first). The
     * visibility rule is the same query-side rule as without them, so a search never reveals a class the caller may not see.
     */
    @Transactional(readOnly = true)
    public List<ClassroomDto> getAllClassrooms(String currentUserId, int page, int size, String q, String sort) {
        return getAllClassrooms(currentUserId, page, size, q, sort, null);
    }

    /** D-28: plus an optional {@code category} filter (exact, one of ClassCategories.ALL; 400 otherwise). */
    @Transactional(readOnly = true)
    public List<ClassroomDto> getAllClassrooms(String currentUserId, int page, int size, String q, String sort, String category) {
        String categoryFilter = category == null || category.isBlank() ? "" : ClassCategories.require(category);
        String needle = q == null ? "" : q.trim();
        if (needle.length() > MAX_QUERY_LENGTH) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Từ khóa tìm kiếm tối đa " + MAX_QUERY_LENGTH + " ký tự");
        }
        String sortKey = sort == null || sort.isBlank() ? SORT_NEWEST : sort.trim().toLowerCase(java.util.Locale.ROOT);
        if (!SORT_NEWEST.equals(sortKey) && !SORT_POPULAR.equals(sortKey)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Cách sắp xếp chỉ có thể là newest hoặc popular");
        }
        if (needle.isEmpty() && SORT_NEWEST.equals(sortKey) && categoryFilter.isEmpty()) {
            return listNewest(currentUserId, page, size);
        }
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        // LIKE wildcards typed by the user are plain text here (same normalisation as the Studio roster search).
        String cleaned = needle.toLowerCase(java.util.Locale.ROOT).replace('%', ' ').replace('_', ' ').replace('\\', ' ').trim();
        String pattern = cleaned.isEmpty() ? "%" : "%" + cleaned + "%";
        List<Classroom> pageOfClasses;
        if (SORT_POPULAR.equals(sortKey)) {
            Pageable unsorted = PageRequest.of(safePage, safeSize);
            Instant now = Instant.now();
            pageOfClasses = currentUserId == null
                    ? classroomRepository.searchPubliclyVisibleByPopularity(pattern, categoryFilter, now, unsorted)
                    : classroomRepository.searchVisibleToUserByPopularity(currentUserId, pattern, categoryFilter, now, unsorted);
        } else {
            Pageable pageable = PageRequest.of(safePage, safeSize,
                    Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.ASC, "id")));
            pageOfClasses = currentUserId == null
                    ? classroomRepository.searchPubliclyVisible(pattern, categoryFilter, pageable)
                    : classroomRepository.searchVisibleToUser(currentUserId, pattern, categoryFilter, pageable);
        }
        return toDtos(pageOfClasses, currentUserId);
    }

    private List<ClassroomDto> listNewest(String currentUserId, int page, int size) {
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
        // D-27: absent / null = unchanged, "" = no uploaded cover, otherwise an UPLOADED CLASS_COVER image of this class.
        String coverBefore = classroom.getCoverMediaId();
        if (req.getCoverMediaId() != null) {
            String cover = req.getCoverMediaId().trim();
            if (cover.isEmpty()) {
                classroom.setCoverMediaId(null);
            } else if (!cover.equals(coverBefore)) {
                if (mediaService == null) {
                    throw new IllegalStateException("MediaService is required to attach a class cover");
                }
                classroom.setCoverMediaId(mediaService.requireAttachableImage(cover, classId, COVER_MEDIA_PURPOSE));
            }
        }
        // D-28: avatar (same semantics as the cover), category, focal points, approval - absent = unchanged.
        if (req.getAvatarMediaId() != null) {
            String avatar = req.getAvatarMediaId().trim();
            if (avatar.isEmpty()) {
                classroom.setAvatarMediaId(null);
            } else if (!avatar.equals(classroom.getAvatarMediaId())) {
                if (mediaService == null) {
                    throw new IllegalStateException("MediaService is required to attach a class avatar");
                }
                classroom.setAvatarMediaId(mediaService.requireAttachableImage(avatar, classId, AVATAR_MEDIA_PURPOSE));
            }
        }
        if (req.getCategory() != null) {
            classroom.setCategory(req.getCategory().isBlank() ? null : ClassCategories.require(req.getCategory()));
        }
        if (req.getCoverPosition() != null) {
            classroom.setCoverPosition(ObjectPositions.require(req.getCoverPosition(), "bìa"));
        }
        if (req.getAvatarPosition() != null) {
            classroom.setAvatarPosition(ObjectPositions.require(req.getAvatarPosition(), "đại diện"));
        }
        boolean approvalBefore = classroom.isRequireApproval();
        if (req.getRequireApproval() != null) {
            // Turning approval OFF does not approve anybody: PENDING requests stay until the Studio handles them.
            classroom.setRequireApproval(req.getRequireApproval());
        }
        // D-19: absent = unchanged. PUBLIC -> PRIVATE keeps every existing member (they simply stop being discoverable by others);
        // PRIVATE -> PUBLIC lists the class and lets anyone join (invites stay valid but are no longer needed).
        classroom.setVisibility(normalizeVisibility(req.getVisibility(), visibilityBefore));
        Classroom saved = classroomRepository.save(classroom);

        auditService.record(classId, currentUserId, "CLASS_SETTINGS_UPDATE", "CLASSROOM", classId,
                String.format("{\"title\":\"%s\",\"visibilityBefore\":\"%s\",\"visibilityAfter\":\"%s\"}",
                        saved.getTitle().replace("\"", "'"), visibilityBefore, saved.isPrivate() ? Classroom.VISIBILITY_PRIVATE : Classroom.VISIBILITY_PUBLIC));
        if (approvalBefore != saved.isRequireApproval()) {
            auditService.record(classId, currentUserId, "CLASS_APPROVAL_SETTING", "CLASSROOM", classId,
                    String.format("{\"requireApprovalBefore\":%s,\"requireApprovalAfter\":%s}", approvalBefore, saved.isRequireApproval()));
        }

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

        // Only active classes accept joins (or join requests, D-28).
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
        if (classroom.isRequireApproval()) {
            // D-28: the join becomes a request (state PENDING, not a member) until the Studio approves or rejects it.
            membershipService.requestToJoin(classroom, userId);
            return toDto(classroom, userId);
        }
        membershipService.joinFree(classroom, userId);
        return toDto(classroom, userId);
    }

    /**
     * D-28: DELETE /classes/{id}/join-request - the caller withdraws their PENDING request (the row is deleted: memberState becomes NONE
     * and they may ask again). Idempotent: no pending request is a no-op. A caller who holds a pending request may always withdraw it, even
     * if the class has since become hidden from them; the answer is then {@code null} (no class data), otherwise the class.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ClassroomDto withdrawJoinRequest(String classId, String userId) {
        Classroom classroom = classroomRepository.findById(classId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy lớp học"));
        boolean withdrawn = membershipService.withdrawRequest(classId, userId);
        if (!accessPolicy.isClassVisibleToUser(classroom, userId)) {
            if (withdrawn) {
                return null;
            }
            if (classroom.isPrivate()) {
                throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy lớp học");
            }
            throw new AppException(ErrorCode.FORBIDDEN, "Bạn không có quyền truy cập thông tin lớp học không công khai này");
        }
        if (withdrawn) {
            auditService.record(classId, userId, "MEMBER_REQUEST_WITHDRAW", "CLASSROOM", classId,
                    String.format("{\"userId\":\"%s\"}", userId));
        }
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
            case "PENDING" -> ClassroomDto.MEMBER_STATE_PENDING;
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
        // D-27: the uploaded cover is signed only here, i.e. for a caller who already passed the class-visibility check (detail
        // endpoints) or whose listing query only returned visible classes.
        dto.setCoverMediaId(classroom.getCoverMediaId());
        dto.setCoverUrl(lookups.coverUrl(classroom));
        dto.setUpcomingEventCount(lookups.upcomingEventCount(classroom.getId()));
        // D-28: the avatar is signed under the same rule as the cover.
        dto.setCategory(classroom.getCategory());
        dto.setAvatarMediaId(classroom.getAvatarMediaId());
        dto.setAvatarUrl(lookups.avatarUrl(classroom));
        dto.setCoverPosition(classroom.getCoverPosition());
        dto.setAvatarPosition(classroom.getAvatarPosition());
        dto.setRequireApproval(classroom.isRequireApproval());
        dto.setPendingRequestCount(currentUserId == null ? 0 : lookups.pendingRequestCount(classroom, currentUserId));
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
        dto.setOwnerAvatarUrl(lookups.ownerAvatarUrl(classroom.getOwnerId()));

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
                    // Never joined, PENDING (D-28), EXPIRED, REMOVED or BLOCKED: not a member. memberState tells the UI which
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
    /** Mirrors the grant match of AccessPolicy.canManage for MEMBER:VIEW (module / action wildcards, whole-class scope only). */
    static boolean grantsMemberView(StaffPermission p) {
        boolean module = "MEMBER".equalsIgnoreCase(p.getModule()) || "*".equals(p.getModule());
        boolean action = "VIEW".equalsIgnoreCase(p.getAction()) || "*".equals(p.getAction());
        return module && action && p.getScopeCourseId() == null;
    }

    private interface DtoLookups {
        String ownerName(String ownerId);

        String ownerAvatarUrl(String ownerId);

        long upcomingEventCount(String classId);

        String coverUrl(Classroom classroom);

        String avatarUrl(Classroom classroom);

        /** PENDING join requests, only for a caller holding MEMBER:VIEW (0 otherwise). */
        long pendingRequestCount(Classroom classroom, String userId);

        long activeMemberCount(String classId);

        Optional<ClassMember> membership(String classId, String userId);

        Optional<StaffAssignment> staffAssignment(String classId, String userId);

        List<StaffPermission> staffPermissions(String assignmentId);

        boolean isPro(String userId, Classroom classroom);

        ClassAccessProductDto accessProduct(Classroom classroom);
    }

    private final class DirectLookups implements DtoLookups {
        private Optional<User> owner;

        private Optional<User> owner(String ownerId) {
            if (owner == null) {
                owner = userRepository.findById(ownerId);
            }
            return owner;
        }

        @Override
        public String ownerName(String ownerId) {
            return owner(ownerId).map(User::getFullName).orElse(null);
        }

        @Override
        public String ownerAvatarUrl(String ownerId) {
            return owner(ownerId).map(User::getAvatarUrl).orElse(null);
        }

        @Override
        public long upcomingEventCount(String classId) {
            return eventRepository == null ? 0 : eventRepository.countUpcomingByClassId(classId, Instant.now());
        }

        @Override
        public String coverUrl(Classroom classroom) {
            return mediaService == null || classroom.getCoverMediaId() == null ? null
                    : mediaService.presignedImageUrl(classroom.getCoverMediaId(), COVER_MEDIA_PURPOSE);
        }

        @Override
        public String avatarUrl(Classroom classroom) {
            return mediaService == null || classroom.getAvatarMediaId() == null ? null
                    : mediaService.presignedImageUrl(classroom.getAvatarMediaId(), AVATAR_MEDIA_PURPOSE);
        }

        @Override
        public long pendingRequestCount(Classroom classroom, String userId) {
            if (!accessPolicy.canManage(userId, classroom.getId(), "MEMBER", "VIEW", null)) return 0;
            return memberRepository.countRawByClassIdAndState(classroom.getId(), ClassMembershipService.STATE_PENDING);
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
        private final Map<String, String> ownerAvatars = new HashMap<>();
        private final Map<String, Long> upcomingEvents = new HashMap<>();
        private final Map<String, String> coverUrls;
        private final Map<String, String> avatarUrls;
        private final Map<String, Long> pendingCounts = new HashMap<>();
        private final Map<String, Long> activeCounts = new HashMap<>();
        private final Map<String, ClassMember> memberships = new HashMap<>();
        private final Map<String, StaffAssignment> assignments = new HashMap<>();
        private final Map<String, List<StaffPermission>> permissionsByAssignment = new HashMap<>();
        private final java.util.Set<String> proClassIds;
        private final Map<String, ClassAccessProductDto> accessProducts;

        BatchLookups(List<Classroom> classrooms, String currentUserId) {
            List<String> classIds = classrooms.stream().map(Classroom::getId).toList();
            List<String> ownerIds = classrooms.stream().map(Classroom::getOwnerId).distinct().toList();

            userRepository.findAllById(ownerIds).forEach(u -> {
                ownerNames.put(u.getId(), u.getFullName());
                if (u.getAvatarUrl() != null) ownerAvatars.put(u.getId(), u.getAvatarUrl());
            });
            if (eventRepository != null) {
                for (Object[] row : eventRepository.countUpcomingByClassIds(classIds, Instant.now())) {
                    upcomingEvents.put((String) row[0], ((Number) row[1]).longValue());
                }
            }
            List<String> coverIds = classrooms.stream().map(Classroom::getCoverMediaId).filter(java.util.Objects::nonNull).distinct().toList();
            this.coverUrls = mediaService == null || coverIds.isEmpty() ? Map.of()
                    : mediaService.presignedImageUrls(coverIds, COVER_MEDIA_PURPOSE);
            List<String> avatarIds = classrooms.stream().map(Classroom::getAvatarMediaId).filter(java.util.Objects::nonNull).distinct().toList();
            this.avatarUrls = mediaService == null || avatarIds.isEmpty() ? Map.of()
                    : mediaService.presignedImageUrls(avatarIds, AVATAR_MEDIA_PURPOSE);
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
            // D-28: pendingRequestCount for the classes the caller administers (owner, or ACTIVE staff + active member holding MEMBER:VIEW,
            // wildcards included - the same rule as AccessPolicy.canManage) - ONE grouped query, from the grants loaded above.
            if (currentUserId != null) {
                java.util.Set<String> administered = new HashSet<>(owned);
                assignments.forEach((classId, a) -> {
                    if ("ACTIVE".equalsIgnoreCase(a.getStatus()) && activeMemberOf.contains(classId)
                            && permissionsByAssignment.getOrDefault(a.getId(), List.of()).stream().anyMatch(ClassroomService::grantsMemberView)) {
                        administered.add(classId);
                    }
                });
                if (!administered.isEmpty()) {
                    for (Object[] row : memberRepository.countRawByClassIdsAndState(administered, ClassMembershipService.STATE_PENDING)) {
                        pendingCounts.put((String) row[0], ((Number) row[1]).longValue());
                    }
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
        public String ownerAvatarUrl(String ownerId) {
            return ownerAvatars.get(ownerId);
        }

        @Override
        public long upcomingEventCount(String classId) {
            return upcomingEvents.getOrDefault(classId, 0L);
        }

        @Override
        public String coverUrl(Classroom classroom) {
            return classroom.getCoverMediaId() == null ? null : coverUrls.get(classroom.getCoverMediaId());
        }

        @Override
        public String avatarUrl(Classroom classroom) {
            return classroom.getAvatarMediaId() == null ? null : avatarUrls.get(classroom.getAvatarMediaId());
        }

        @Override
        public long pendingRequestCount(Classroom classroom, String userId) {
            return pendingCounts.getOrDefault(classroom.getId(), 0L);
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
