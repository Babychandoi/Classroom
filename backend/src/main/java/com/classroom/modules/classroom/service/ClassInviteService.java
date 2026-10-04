package com.classroom.modules.classroom.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.dto.ClassAccessProductDto;
import com.classroom.modules.classroom.dto.ClassInviteDto;
import com.classroom.modules.classroom.dto.CreateInviteRequest;
import com.classroom.modules.classroom.dto.InvitePreviewDto;
import com.classroom.modules.classroom.model.ClassInvite;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.ClassInviteRepository;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.classroom.repository.StaffAssignmentRepository;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * D-19: invite links of a class - create / list / revoke for the people who manage members, and the two public entry points a holder of a
 * code uses (preview, join).
 *
 * <ul>
 *   <li><b>Who manages</b>: the OWNER, or staff holding {@code MEMBER:EDIT} (the same grant that removes / blocks members).</li>
 *   <li><b>The code</b> is shown exactly once, in the response of the create call. Only its SHA-256 and its last four characters are kept
 *   ({@link InviteCodes}).</li>
 *   <li><b>One answer for every invalid code</b>: unknown, malformed, revoked, expired, used up, and a code of an archived class are all
 *   the same 404 ({@link ClassInviteLedger#invalidInvite()}), so the endpoints are no oracle for which codes exist or why one stopped
 *   working; the per-IP rate limit (AuthRateLimitFilter) makes enumeration infeasible on top of the 192-bit code space.</li>
 *   <li><b>Join</b> locks the invite row first (READ_COMMITTED), then the member row; a use is consumed only when somebody actually
 *   joins (a person who already belongs, the owner and staff consume nothing). Owner and staff never need an invite.</li>
 * </ul>
 */
@Service
public class ClassInviteService {

    public static final int MAX_USES_LIMIT = 100_000;
    /** An invite may live at most this long; "never expires" is the absence of an {@code expiresAt}. */
    public static final int MAX_LIFETIME_DAYS = 3650;

    private final ClassInviteRepository inviteRepository;
    private final ClassroomRepository classroomRepository;
    private final ClassMemberRepository memberRepository;
    private final StaffAssignmentRepository staffAssignmentRepository;
    private final UserRepository userRepository;
    private final AccessPolicy accessPolicy;
    private final AuditService auditService;
    private final ClassInviteLedger inviteLedger;
    private final ClassMembershipService membershipService;
    private final ClassAccessService accessService;

    public ClassInviteService(ClassInviteRepository inviteRepository,
                              ClassroomRepository classroomRepository,
                              ClassMemberRepository memberRepository,
                              StaffAssignmentRepository staffAssignmentRepository,
                              UserRepository userRepository,
                              AccessPolicy accessPolicy,
                              AuditService auditService,
                              ClassInviteLedger inviteLedger,
                              ClassMembershipService membershipService,
                              ClassAccessService accessService) {
        this.inviteRepository = inviteRepository;
        this.classroomRepository = classroomRepository;
        this.memberRepository = memberRepository;
        this.staffAssignmentRepository = staffAssignmentRepository;
        this.userRepository = userRepository;
        this.accessPolicy = accessPolicy;
        this.auditService = auditService;
        this.inviteLedger = inviteLedger;
        this.membershipService = membershipService;
        this.accessService = accessService;
    }

    // ------------------------------------------------------------------------------------------------------------------ management

    /** Creates an invite and returns it WITH its code - the only time the full code is ever available. */
    @Transactional
    public ClassInviteDto create(String classId, CreateInviteRequest request, String actorId) {
        accessPolicy.enforceManage(actorId, classId, "MEMBER", "EDIT", null);
        Classroom classroom = classroomRepository.findById(classId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, ClassInviteLedger.NOT_FOUND_MESSAGE));
        if (!"ACTIVE".equalsIgnoreCase(classroom.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Lớp học đã được lưu trữ; không thể tạo mã mời mới");
        }
        Instant now = Instant.now();
        Instant expiresAt = request == null ? null : request.getExpiresAt();
        Integer maxUses = request == null ? null : request.getMaxUses();
        if (expiresAt != null) {
            if (!expiresAt.isAfter(now)) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Thời điểm hết hạn của mã mời phải ở tương lai");
            }
            if (expiresAt.isAfter(now.plus(MAX_LIFETIME_DAYS, ChronoUnit.DAYS))) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Mã mời chỉ có hiệu lực tối đa " + MAX_LIFETIME_DAYS + " ngày");
            }
        }
        if (maxUses != null && (maxUses < 1 || maxUses > MAX_USES_LIMIT)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Số lượt dùng tối đa phải từ 1 đến " + MAX_USES_LIMIT);
        }

        String code = InviteCodes.generate();
        ClassInvite invite = new ClassInvite(classId, InviteCodes.hash(code), InviteCodes.hint(code), actorId, expiresAt, maxUses);
        invite = inviteRepository.save(invite);

        auditService.record(classId, actorId, "CLASS_INVITE_CREATE", "CLASS_INVITE", invite.getId(),
                String.format(Locale.ROOT, "{\"codeHint\":\"%s\",\"expiresAt\":%s,\"maxUses\":%s}",
                        invite.getCodeHint(), expiresAt == null ? "null" : "\"" + expiresAt + "\"", maxUses == null ? "null" : maxUses));

        ClassInviteDto dto = toDto(invite, now);
        dto.setCode(code);
        return dto;
    }

    /** The class's invites, newest first: id, dates, limits, usage, status and the last four characters of the code - never the code. */
    @Transactional(readOnly = true)
    public List<ClassInviteDto> list(String classId, String actorId) {
        accessPolicy.enforceManageRead(actorId, classId, "MEMBER", "EDIT", null);
        Instant now = Instant.now();
        return inviteRepository.findByClassIdOrderByCreatedAtDesc(classId).stream().map(i -> toDto(i, now)).toList();
    }

    /** Revokes an invite (kept in the list as REVOKED). Revoking an already revoked invite is a no-op and is not audited twice. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ClassInviteDto revoke(String classId, String inviteId, String actorId) {
        accessPolicy.enforceManage(actorId, classId, "MEMBER", "EDIT", null);
        ClassInvite invite = inviteRepository.findByIdAndClassIdForUpdate(inviteId, classId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy mã mời"));
        Instant now = Instant.now();
        if (invite.getRevokedAt() == null) {
            invite.setRevokedAt(now);
            inviteRepository.save(invite);
            auditService.record(classId, actorId, "CLASS_INVITE_REVOKE", "CLASS_INVITE", invite.getId(),
                    String.format(Locale.ROOT, "{\"codeHint\":\"%s\",\"usedCount\":%d}", invite.getCodeHint(), invite.getUsedCount()));
        }
        return toDto(invite, now);
    }

    private static ClassInviteDto toDto(ClassInvite invite, Instant now) {
        ClassInviteDto dto = new ClassInviteDto();
        dto.setId(invite.getId());
        dto.setCodeHint(invite.getCodeHint());
        dto.setCreatedAt(invite.getCreatedAt());
        dto.setExpiresAt(invite.getExpiresAt());
        dto.setMaxUses(invite.getMaxUses());
        dto.setUsedCount(invite.getUsedCount());
        dto.setStatus(invite.statusAt(now));
        dto.setCreatedBy(invite.getCreatedBy());
        return dto;
    }

    // ---------------------------------------------------------------------------------------------------------------- public holder side

    /** GET /classes/invites/{code}: the class card for a valid code, the same 404 for every invalid one. No login needed. */
    @Transactional(readOnly = true)
    public InvitePreviewDto preview(String rawCode) {
        if (!InviteCodes.isWellFormed(rawCode)) {
            throw ClassInviteLedger.invalidInvite();
        }
        String presentedHash = InviteCodes.hash(rawCode);
        ClassInvite invite = inviteRepository.findByCodeHash(presentedHash)
                .filter(i -> InviteCodes.constantTimeEquals(i.getCodeHash(), presentedHash))
                .filter(i -> i.isUsableAt(Instant.now()))
                .orElseThrow(ClassInviteLedger::invalidInvite);
        Classroom classroom = classroomRepository.findById(invite.getClassId())
                .filter(c -> "ACTIVE".equalsIgnoreCase(c.getStatus()))
                .orElseThrow(ClassInviteLedger::invalidInvite);

        InvitePreviewDto dto = new InvitePreviewDto();
        dto.setClassId(classroom.getId());
        dto.setSlug(classroom.getSlug());
        dto.setTitle(classroom.getTitle());
        dto.setDescription(classroom.getDescription());
        dto.setCoverImageUrl(classroom.getCoverImageUrl());
        dto.setAccessType(classroom.isPaid() ? Classroom.ACCESS_PAID : Classroom.ACCESS_FREE);
        if (classroom.isPaid()) {
            ClassAccessProductDto product = accessService.accessProductOf(classroom);
            if (product != null) {
                dto.setPrice(product.getPrice());
                dto.setCurrency(product.getCurrency());
                dto.setDurationDays(product.getDurationDays());
                dto.setLifetime(product.isLifetime());
            }
        }
        dto.setOwnerName(userRepository.findById(classroom.getOwnerId()).map(User::getFullName).orElse(null));
        return dto;
    }

    /**
     * POST /classes/invites/{code}/join (login required). FREE class: the caller becomes an ACTIVE member (a REMOVED person may rejoin; a
     * BLOCKED one is refused, 403) and one use of the invite is consumed. PAID class: a person who is not covered gets PAYMENT_REQUIRED (402)
     * with the class-access product. The owner, staff and people who already belong consume nothing.
     *
     * @return the class, for the caller to render
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Classroom join(String rawCode, String userId) {
        Instant now = Instant.now();
        ClassInvite invite = inviteLedger.lockUsable(rawCode, now);
        Classroom classroom = classroomRepository.findById(invite.getClassId())
                .filter(c -> "ACTIVE".equalsIgnoreCase(c.getStatus()))
                .orElseThrow(ClassInviteLedger::invalidInvite);

        if (accessPolicy.isOwner(userId, classroom.getId())
                || staffAssignmentRepository.findByClassIdAndUserId(classroom.getId(), userId)
                .map(a -> "ACTIVE".equalsIgnoreCase(a.getStatus())).orElse(false)) {
            return classroom;
        }
        Optional<ClassMember> existing = memberRepository.findByClassIdAndUserId(classroom.getId(), userId);
        if (existing.isPresent() && existing.get().isActiveAt(now)) {
            return classroom; // already belongs: the invite is not needed and not used up
        }
        if (existing.isPresent() && ClassMembershipService.isBlockedState(existing.get().getState())) {
            throw new AppException(ErrorCode.FORBIDDEN, ClassMembershipService.BLOCKED_MESSAGE);
        }
        if (classroom.isPaid()) {
            if (accessService.restoreIfStillPaid(classroom, userId, now)) {
                // A REMOVED person who still holds a running purchase comes back through the invite without paying again.
                inviteLedger.consume(invite);
                auditService.record(classroom.getId(), userId, "CLASS_INVITE_JOIN", "CLASS_INVITE", invite.getId(),
                        String.format(Locale.ROOT, "{\"codeHint\":\"%s\",\"outcome\":\"RESTORED\"}", invite.getCodeHint()));
                return classroom;
            }
            throw accessService.paymentRequired(classroom);
        }
        ClassMembershipService.JoinResult result = membershipService.joinFree(classroom, userId);
        if (result.outcome() != ClassMembershipService.Outcome.ALREADY_ACTIVE) {
            inviteLedger.consume(invite);
            auditService.record(classroom.getId(), userId, "CLASS_INVITE_JOIN", "CLASS_INVITE", invite.getId(),
                    String.format(Locale.ROOT, "{\"codeHint\":\"%s\",\"outcome\":\"%s\"}", invite.getCodeHint(), result.outcome()));
        }
        return classroom;
    }
}
