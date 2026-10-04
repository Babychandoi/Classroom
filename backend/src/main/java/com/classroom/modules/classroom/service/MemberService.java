package com.classroom.modules.classroom.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.audit.model.AuditEvent;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.dto.ClassMemberDto;
import com.classroom.modules.classroom.dto.ClassMemberPageDto;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.repository.StaffAssignmentRepository;
import com.classroom.modules.classroom.repository.StaffPermissionRepository;
import com.classroom.modules.commerce.policy.ProPolicy;
import com.classroom.modules.exam.model.ExamAttempt;
import com.classroom.modules.exam.repository.ExamAttemptRepository;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.outbox.service.OutboxService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * R13-02: Studio member management (FR-14 / sitemap /studio/classes/:id/members).
 *
 * <p>Gated on the MEMBER:* module (already used read-only by ProfileVisibilityPolicy/UserService
 * for MEMBER:VIEW - see AccessPolicy.canManage callers there); this class adds the write actions
 * (MEMBER:EDIT) an owner or a staff member explicitly granted that permission may perform:
 * remove ("kick"), block, unblock. The class OWNER can never be removed/blocked - ownership
 * transfer is a separate, not-yet-specified flow. Removing/blocking a STAFF member is OWNER-only
 * and nobody may target themselves here (R14-01, decision D-12).</p>
 */
@Service
public class MemberService {

    private final ClassMemberRepository memberRepository;
    private final StaffAssignmentRepository staffAssignmentRepository;
    private final StaffPermissionRepository staffPermissionRepository;
    private final UserRepository userRepository;
    private final ExamAttemptRepository examAttemptRepository;
    private final AccessPolicy accessPolicy;
    private final ProPolicy proPolicy;
    private final AuditService auditService;
    private final OutboxService outboxService;
    /** D-28: optional (hand-built unit-test instances): the class row an approval checks (still FREE, not archived). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.classroom.modules.classroom.repository.ClassroomRepository classroomRepository;

    public MemberService(ClassMemberRepository memberRepository,
                          StaffAssignmentRepository staffAssignmentRepository,
                          StaffPermissionRepository staffPermissionRepository,
                          UserRepository userRepository,
                          ExamAttemptRepository examAttemptRepository,
                          AccessPolicy accessPolicy,
                          ProPolicy proPolicy,
                          AuditService auditService,
                          OutboxService outboxService) {
        this.memberRepository = memberRepository;
        this.staffAssignmentRepository = staffAssignmentRepository;
        this.staffPermissionRepository = staffPermissionRepository;
        this.userRepository = userRepository;
        this.examAttemptRepository = examAttemptRepository;
        this.accessPolicy = accessPolicy;
        this.proPolicy = proPolicy;
        this.auditService = auditService;
        this.outboxService = outboxService;
    }

    public static final int STUDIO_MEMBERS_DEFAULT_SIZE = 50;
    public static final int STUDIO_MEMBERS_MAX_SIZE = 200;
    private static final Set<String> STATE_FILTERS = Set.of("ACTIVE", "EXPIRED", "REMOVED", "BLOCKED", "BANNED", "PENDING");

    /** Convenience form without paging parameters: the first page at the default size. */
    @Transactional(readOnly = true)
    public ClassMemberPageDto getStudioMembers(String classId, String currentUserId) {
        return getStudioMembers(classId, currentUserId, null, null, null, null, null);
    }

    /**
     * R20-03: one page of the Studio roster (default 50, max 200 per page) with server-side search / state / role filters.
     *
     * <p>Every per-row lookup is batched for the page: the users ({@code findAllById}, 1 statement) and PRO status (owner check +
     * ONE entitlement query for the page's members). The old listing did a {@code findById} plus 2-3 policy queries per member of
     * the WHOLE class: 6007 statements and 3 s for a 2000-member class. The listing now costs a fixed handful of statements
     * (permission, page, count, users, entitlements) regardless of the size of the class.</p>
     *
     * @param page  0-based page index
     * @param query case-insensitive text matched against the member's name or e-mail
     * @param state ACTIVE / REMOVED / BLOCKED / BANNED, or null / blank / ALL for any
     * @param role  exact role (OWNER / STAFF / STUDENT ...), or null / blank / ALL for any
     */
    @Transactional(readOnly = true)
    public ClassMemberPageDto getStudioMembers(String classId, String currentUserId, Integer page, Integer size,
                                               String query, String state, String role) {
        accessPolicy.enforceManage(currentUserId, classId, "MEMBER", "VIEW", null);

        int pageIndex = Math.max(page == null ? 0 : page, 0);
        int pageSize = Math.min(Math.max(size == null ? STUDIO_MEMBERS_DEFAULT_SIZE : size, 1), STUDIO_MEMBERS_MAX_SIZE);
        String stateFilter = state == null || state.isBlank() || "ALL".equalsIgnoreCase(state.trim())
                ? "" : state.trim().toUpperCase(Locale.ROOT);
        if (!stateFilter.isEmpty() && !STATE_FILTERS.contains(stateFilter)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Trạng thái thành viên không hợp lệ: " + state);
        }
        String roleFilter = role == null || role.isBlank() || "ALL".equalsIgnoreCase(role.trim())
                ? "" : role.trim().toUpperCase(Locale.ROOT);
        // LIKE wildcards typed by the user are plain text here.
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT).replace('%', ' ').replace('_', ' ').replace('\\', ' ').trim();
        String pattern = needle.isEmpty() ? "%" : "%" + needle + "%";

        Page<ClassMember> result = memberRepository.searchStudioRoster(classId, stateFilter, roleFilter, pattern,
                PageRequest.of(pageIndex, pageSize));
        List<ClassMember> rows = result.getContent();

        List<String> userIds = rows.stream().map(ClassMember::getUserId).distinct().toList();
        Map<String, User> usersById = new HashMap<>();
        if (!userIds.isEmpty()) {
            userRepository.findAllById(userIds).forEach(u -> usersById.put(u.getId(), u));
        }
        Instant now = Instant.now();
        List<String> activeIds = rows.stream()
                .filter(m -> m.isActiveAt(now))
                .map(ClassMember::getUserId).distinct().toList();
        Set<String> proUsers = proPolicy.proUserIds(classId, userIds, activeIds);

        List<ClassMemberDto> members = rows.stream().map(m -> {
            ClassMemberDto dto = new ClassMemberDto();
            dto.setId(m.getId());
            dto.setUserId(m.getUserId());
            dto.setRole(m.getRole());
            dto.setState(m.effectiveState(now));
            dto.setJoinedAt(m.getJoinedAt());
            dto.setAccessExpiresAt(m.getAccessExpiresAt());
            if (ClassMembershipService.isPendingState(m.getState())) dto.setRequestedAt(m.getJoinedAt());
            User u = usersById.get(m.getUserId());
            if (u != null) {
                dto.setUserFullName(u.getFullName());
                dto.setUserAvatarUrl(u.getAvatarUrl());
                dto.setUserEmail(u.getEmail());
            }
            dto.setPro(proUsers.contains(m.getUserId()));
            return dto;
        }).toList();
        return new ClassMemberPageDto(members, result.getTotalElements(), pageIndex, pageSize, result.hasNext());
    }

    /**
     * R13-02: remove ("kick") a member. Cross-module effects, per FR-14's Studio members
     * management and the state machines this module must respect:
     * <ul>
     *   <li>the member row moves to REMOVED (not deleted - joinedAt/history is preserved for audit,
     *   mirroring how every other soft-state model in this codebase (Course/Product/Exam status)
     *   never hard-deletes);</li>
     *   <li>an active STAFF assignment (and its permissions) is revoked - a removed member cannot
     *   retain staff authority over the class they no longer belong to;</li>
     *   <li>any IN_PROGRESS exam attempt of theirs in this class is CANCELLED - they can no longer
     *   act on it, and it must not silently auto-submit/auto-grade after their access ends;</li>
     *   <li>purchased entitlements (Entitlement rows) are left untouched - a paid purchase survives
     *   removal from the class per the commerce module's "quyền truy cập đã bán" invariant
     *   (LLD.md commerce policy section), it just cannot be exercised while REMOVED since
     *   AccessPolicy.isMember() (which every content/exam/commerce check is built on) requires
     *   an ACTIVE class_members row;</li>
     *   <li>MEMBER_REMOVED is emitted on the outbox (OutboxWorker already special-cases this event
     *   type for the Neo4j membership-graph projection).</li>
     * </ul>
     */
    @Transactional
    public ClassMemberDto removeMember(String classId, String targetUserId, String currentUserId) {
        accessPolicy.enforceManage(currentUserId, classId, "MEMBER", "EDIT", null);
        ClassMember member = requireRemovableMember(classId, targetUserId, currentUserId);

        member.setState("REMOVED");
        member.setRole("STUDENT");
        memberRepository.save(member);

        revokeStaffAssignment(classId, targetUserId);
        cancelInProgressAttempts(classId, targetUserId);

        outboxService.recordEvent("CLASSROOM", classId, "MEMBER_REMOVED", Map.of(
                "userId", targetUserId,
                "classId", classId
        ));

        auditService.record(classId, currentUserId, "MEMBER_REMOVE", "CLASS_MEMBER", member.getId(),
                String.format("{\"targetUserId\":\"%s\"}", targetUserId));

        return toDto(classId, member);
    }

    /**
     * R13-02: block a member. Same immediate cross-module effects as removal (loses member-only
     * access immediately, staff assignment revoked, IN_PROGRESS attempts cancelled) but a distinct
     * terminal state: BLOCKED members can never rejoin on their own (see ClassroomService.
     * joinClassroom), unlike REMOVED members who may request to rejoin.
     */
    @Transactional
    public ClassMemberDto blockMember(String classId, String targetUserId, String currentUserId) {
        accessPolicy.enforceManage(currentUserId, classId, "MEMBER", "EDIT", null);
        ClassMember member = requireRemovableMember(classId, targetUserId, currentUserId);

        member.setState("BLOCKED");
        member.setRole("STUDENT");
        memberRepository.save(member);

        revokeStaffAssignment(classId, targetUserId);
        cancelInProgressAttempts(classId, targetUserId);

        outboxService.recordEvent("CLASSROOM", classId, "MEMBER_REMOVED", Map.of(
                "userId", targetUserId,
                "classId", classId
        ));

        auditService.record(classId, currentUserId, "MEMBER_BLOCK", "CLASS_MEMBER", member.getId(),
                String.format("{\"targetUserId\":\"%s\"}", targetUserId));

        return toDto(classId, member);
    }

    /**
     * R13-02: restores a BLOCKED (or REMOVED) member back to ACTIVE without them having to rejoin.
     *
     * <p>R19-10: a restriction the OWNER imposed can only be lifted by the OWNER. Blocking or removing a staff
     * member is already owner-only ({@link #requireRemovableMember}), and doing either resets the row (role STUDENT,
     * staff assignment deleted), so nothing on the row says "this used to be staff"; the audit trail is what
     * remembers who acted. Without this rule a delegate holding only MEMBER:EDIT could undo the owner's block (or
     * bring a removed staff member back into the class) that they were never allowed to impose themselves.</p>
     */
    @Transactional
    public ClassMemberDto unblockMember(String classId, String targetUserId, String currentUserId) {
        accessPolicy.enforceManage(currentUserId, classId, "MEMBER", "EDIT", null);
        ClassMember member = memberRepository.findByClassIdAndUserId(classId, targetUserId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy thành viên trong lớp học"));
        if ("ACTIVE".equalsIgnoreCase(member.getState())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Thành viên này đang hoạt động, không cần mở khóa");
        }
        if ("EXPIRED".equalsIgnoreCase(member.getState())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Thành viên này không bị chặn hay xóa; quyền truy cập đã hết hạn và cần được gia hạn");
        }
        if (ClassMembershipService.isPendingState(member.getState())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Đây là yêu cầu tham gia đang chờ duyệt; hãy dùng Duyệt hoặc Từ chối");
        }
        if (!accessPolicy.isOwner(currentUserId, classId) && restrictionRequiresOwner(classId, member)) {
            throw new AppException(ErrorCode.FORBIDDEN,
                    "Chỉ chủ lớp học (OWNER) mới được mở khóa thành viên do chủ lớp chặn/xóa hoặc thành viên vốn là nhân sự");
        }

        // D-19: unblocking gives back what the person had. Paid access that lapsed while they were blocked is not revived: the row then
        // comes back EXPIRED (they renew through checkout) and no membership edge is projected.
        boolean lapsed = member.getAccessExpiresAt() != null && !member.getAccessExpiresAt().isAfter(Instant.now());
        member.setState(lapsed ? "EXPIRED" : "ACTIVE");
        memberRepository.save(member);

        if (!lapsed) {
            outboxService.recordEvent("CLASSROOM", classId, "MEMBER_JOINED", Map.of(
                    "userId", targetUserId,
                    "classId", classId,
                    "role", member.getRole()
            ));
        }

        auditService.record(classId, currentUserId, "MEMBER_UNBLOCK", "CLASS_MEMBER", member.getId(),
                String.format("{\"targetUserId\":\"%s\"}", targetUserId));

        return toDto(classId, member);
    }

    /**
     * D-28: approve a PENDING join request -&gt; ACTIVE member (no expiry), with the same MEMBER_JOINED outbox event as a normal join and a
     * MEMBER_APPROVE audit record. Needs MEMBER:EDIT. Refused (409) when the class is no longer ACTIVE (D-11) or has become PAID (the
     * person must buy access instead - approval never grants paid access).
     */
    @Transactional
    public ClassMemberDto approveRequest(String classId, String targetUserId, String currentUserId) {
        accessPolicy.enforceManage(currentUserId, classId, "MEMBER", "EDIT", null);
        ClassMember member = requirePending(classId, targetUserId);
        if (classroomRepository != null) {
            var classroom = classroomRepository.findById(classId)
                    .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy lớp học"));
            if (!"ACTIVE".equalsIgnoreCase(classroom.getStatus())) {
                throw new AppException(ErrorCode.CONFLICT, "Lớp học hiện không mở đăng ký thành viên");
            }
            if (classroom.isPaid()) {
                throw new AppException(ErrorCode.CONFLICT, "Lớp học đã chuyển sang trả phí; người này cần mua quyền truy cập");
            }
        }
        Instant requestedAt = member.getJoinedAt();
        member.setState("ACTIVE");
        member.setAccessExpiresAt(null);
        member.setJoinedAt(Instant.now());
        memberRepository.save(member);
        outboxService.recordEvent("CLASSROOM", classId, "MEMBER_JOINED", Map.of(
                "userId", targetUserId,
                "classId", classId,
                "role", member.getRole()
        ));
        auditService.record(classId, currentUserId, "MEMBER_APPROVE", "CLASS_MEMBER", member.getId(),
                String.format("{\"targetUserId\":\"%s\",\"requestedAt\":\"%s\"}", targetUserId, requestedAt));
        return toDto(classId, member);
    }

    /**
     * D-28: reject a PENDING join request: the row is deleted (the person sees memberState NONE and may ask again later) and a
     * MEMBER_REJECT audit record keeps the decision. Needs MEMBER:EDIT. Returns the rejected row as it was (state PENDING).
     */
    @Transactional
    public ClassMemberDto rejectRequest(String classId, String targetUserId, String currentUserId) {
        accessPolicy.enforceManage(currentUserId, classId, "MEMBER", "EDIT", null);
        ClassMember member = requirePending(classId, targetUserId);
        ClassMemberDto dto = toDto(classId, member);
        dto.setRequestedAt(member.getJoinedAt());
        memberRepository.delete(member);
        auditService.record(classId, currentUserId, "MEMBER_REJECT", "CLASS_MEMBER", member.getId(),
                String.format("{\"targetUserId\":\"%s\"}", targetUserId));
        return dto;
    }

    private ClassMember requirePending(String classId, String targetUserId) {
        ClassMember member = memberRepository.findByClassIdAndUserIdForUpdate(classId, targetUserId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy yêu cầu tham gia"));
        if (!ClassMembershipService.isPendingState(member.getState())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Người này không có yêu cầu tham gia đang chờ duyệt");
        }
        return member;
    }

    /**
     * R14-01: resolves the member a Studio "remove"/"block" action targets and enforces the
     * target-specific guards on top of the caller's MEMBER:EDIT grant:
     * <ul>
     *   <li>the class OWNER can never be removed/blocked;</li>
     *   <li>a caller can never remove/block themselves through this surface (a non-owner staff
     *   member using it on their own row would strip their own assignment through a path that is
     *   not the owner-controlled staff lifecycle);</li>
     *   <li>a target that is STAFF (member role STAFF or any staff assignment row) can only be
     *   removed/blocked by the OWNER - exactly the rule StaffService.removeStaff already enforces.
     *   Without it a delegate holding only MEMBER:EDIT could revoke another staff member's
     *   assignment and permissions (revokeStaffAssignment) and so bypass the owner-only staff
     *   lifecycle.</li>
     * </ul>
     */
    private ClassMember requireRemovableMember(String classId, String targetUserId, String currentUserId) {
        if (accessPolicy.isOwner(targetUserId, classId)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Không thể xóa hoặc chặn chủ lớp học (OWNER)");
        }
        if (targetUserId != null && targetUserId.equals(currentUserId)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Bạn không thể tự xóa hoặc tự chặn chính mình khỏi lớp học");
        }
        ClassMember member = memberRepository.findByClassIdAndUserId(classId, targetUserId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy thành viên trong lớp học"));
        // D-19: a member whose paid access lapsed (EXPIRED) is still on the roster and can be removed or blocked - in particular a BLOCKED
        // person must be refused the purchase, so blocking an expired member has to work.
        if (!"ACTIVE".equalsIgnoreCase(member.getState()) && !"EXPIRED".equalsIgnoreCase(member.getState())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Thành viên này không ở trạng thái hoạt động");
        }
        boolean targetIsStaff = "STAFF".equalsIgnoreCase(member.getRole())
                || staffAssignmentRepository.findByClassIdAndUserId(classId, targetUserId).isPresent();
        if (targetIsStaff) {
            accessPolicy.enforceOwner(currentUserId, classId);
        }
        return member;
    }

    /**
     * R19-10: true when the latest block/removal of {@code member} was done by the class owner, or cannot be
     * attributed to anyone else. No audit record at all (a row that predates the audit trail, or was edited outside
     * this service) fails closed to owner-only: a delegate can only lift a restriction that provably came from a delegate.
     */
    private boolean restrictionRequiresOwner(String classId, ClassMember member) {
        Optional<AuditEvent> last = auditService.findLatestEvent(classId, "CLASS_MEMBER", member.getId(),
                List.of("MEMBER_BLOCK", "MEMBER_REMOVE"));
        if (last == null || last.isEmpty()) {
            return true;
        }
        String actorId = last.get().getActorId();
        return actorId == null || accessPolicy.isOwner(actorId, classId);
    }

    private void revokeStaffAssignment(String classId, String targetUserId) {
        staffAssignmentRepository.findByClassIdAndUserId(classId, targetUserId).ifPresent(assignment -> {
            staffPermissionRepository.deleteByAssignmentId(assignment.getId());
            staffAssignmentRepository.delete(assignment);
        });
    }

    private void cancelInProgressAttempts(String classId, String targetUserId) {
        // R19-01(c): locking read (see ExamAttemptRepository#findByClassIdAndUserIdAndStatusForUpdate) - a learner
        // submitting at the same moment must win, not be overwritten with a stale IN_PROGRESS -> CANCELLED.
        List<ExamAttempt> inProgress = examAttemptRepository.findByClassIdAndUserIdAndStatusForUpdate(classId, targetUserId, "IN_PROGRESS");
        for (ExamAttempt attempt : inProgress) {
            attempt.setStatus("CANCELLED");
            examAttemptRepository.save(attempt);
        }
    }

    private ClassMemberDto toDto(String classId, ClassMember m) {
        ClassMemberDto dto = new ClassMemberDto();
        dto.setId(m.getId());
        dto.setUserId(m.getUserId());
        dto.setRole(m.getRole());
        dto.setState(m.effectiveState(Instant.now()));
        dto.setJoinedAt(m.getJoinedAt());
        dto.setAccessExpiresAt(m.getAccessExpiresAt());
        userRepository.findById(m.getUserId()).ifPresent(u -> {
            dto.setUserFullName(u.getFullName());
            dto.setUserAvatarUrl(u.getAvatarUrl());
            dto.setUserEmail(u.getEmail());
        });
        dto.setPro(proPolicy.isPro(m.getUserId(), classId));
        return dto;
    }
}
