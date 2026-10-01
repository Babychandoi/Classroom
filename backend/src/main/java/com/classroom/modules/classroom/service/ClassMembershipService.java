package com.classroom.modules.classroom.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.outbox.service.OutboxService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * D-19: the membership transitions that more than one entry point performs - a free join by id, a join by invite, a purchase settling, an
 * expiry - in ONE place, so they all mean the same thing and all emit the same outbox events.
 *
 * <p>Membership state machine (stored in {@code class_members.state}; {@code access_expires_at} is the end of paid access, NULL = never):</p>
 * <pre>
 *   (none)   --join FREE class / invite FREE-------------------------------&gt; ACTIVE   (expiry NULL)
 *   (none)   --purchase settles (PAID class)-------------------------------&gt; ACTIVE   (expiry = end of the entitlement chain, NULL = lifetime)
 *   ACTIVE   --access_expires_at passes (sweeper, or at once for every check)-&gt; EXPIRED
 *   ACTIVE   --refund leaves no paid time-------------------------------------&gt; EXPIRED
 *   EXPIRED  --purchase settles / renewal------------------------------------&gt; ACTIVE
 *   EXPIRED  --class became FREE and the person joins again-------------------&gt; ACTIVE   (expiry NULL)
 *   ACTIVE | EXPIRED --Studio remove--------------------------------------------&gt; REMOVED
 *   ACTIVE | EXPIRED --Studio block---------------------------------------------&gt; BLOCKED
 *   REMOVED  --join FREE class / invite / purchase settles---------------------&gt; ACTIVE
 *   BLOCKED  --Studio unblock----------------------------------------------------&gt; ACTIVE (EXPIRED when its paid access already lapsed)
 *   BLOCKED  --join / invite / purchase------------------------------------------&gt; refused (403)
 * </pre>
 *
 * <p>Every method here expects to run inside the caller's transaction and locks the member row FOR UPDATE first, so a settle, an invite
 * join, the sweeper and a Studio remove of the same person serialise on it.</p>
 */
@Service
public class ClassMembershipService {

    public static final String BLOCKED_MESSAGE = "Tài khoản thành viên này đã bị vô hiệu hóa trong lớp học";

    public enum Outcome {
        /** A new roster row was created. */
        CREATED,
        /** A REMOVED / EXPIRED row was made ACTIVE again. */
        REACTIVATED,
        /** The person already belonged - nothing changed (an invite use must not be consumed). */
        ALREADY_ACTIVE
    }

    public record JoinResult(ClassMember member, Outcome outcome) {}

    private final ClassMemberRepository memberRepository;
    private final OutboxService outboxService;

    public ClassMembershipService(ClassMemberRepository memberRepository, OutboxService outboxService) {
        this.memberRepository = memberRepository;
        this.outboxService = outboxService;
    }

    /** STATE of a stored value, with the legacy BANNED / any unknown value read as BLOCKED (the safe reading). */
    public static boolean isBlockedState(String state) {
        if (state == null) return true;
        String s = state.trim().toUpperCase(java.util.Locale.ROOT);
        return !s.equals("ACTIVE") && !s.equals("EXPIRED") && !s.equals("REMOVED");
    }

    /**
     * Joins {@code userId} to a class that costs nothing to join (a FREE class, or a PAID one whose buyer is already covered): creates
     * the row, or brings a REMOVED / EXPIRED / lapsed row back to ACTIVE with no expiry. A BLOCKED person is refused (403) - only a Studio
     * unblock restores them.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public JoinResult joinFree(Classroom classroom, String userId) {
        String classId = classroom.getId();
        Instant now = Instant.now();
        Optional<ClassMember> existing = memberRepository.findByClassIdAndUserIdForUpdate(classId, userId);
        if (existing.isEmpty()) {
            if (classroom.isPaid()) {
                throw new AppException(ErrorCode.PAYMENT_REQUIRED);
            }
            ClassMember member = new ClassMember(classId, userId, "STUDENT");
            memberRepository.save(member);
            emitJoined(classId, userId, member.getRole());
            return new JoinResult(member, Outcome.CREATED);
        }
        ClassMember member = existing.get();
        if (member.isActiveAt(now)) {
            return new JoinResult(member, Outcome.ALREADY_ACTIVE);
        }
        if (isBlockedState(member.getState())) {
            throw new AppException(ErrorCode.FORBIDDEN, BLOCKED_MESSAGE);
        }
        if (classroom.isPaid()) {
            // Fail closed: joining for free is only for FREE classes; callers send a PAID class's non-members to checkout first.
            throw new AppException(ErrorCode.PAYMENT_REQUIRED);
        }
        // REMOVED, EXPIRED, or ACTIVE-but-lapsed in a FREE class: nothing to pay, so the person is simply active again with no expiry.
        member.setState("ACTIVE");
        member.setAccessExpiresAt(null);
        memberRepository.save(member);
        emitJoined(classId, userId, member.getRole());
        return new JoinResult(member, Outcome.REACTIVATED);
    }

    /**
     * A class-access purchase settled: make the buyer an ACTIVE member whose access runs to {@code accessExpiresAt} (NULL = lifetime).
     * Owner and staff keep NULL (they never expire). A BLOCKED buyer keeps the BLOCKED row (the purchase is recorded; an operator
     * decides) and {@code false} is returned. Returns true when the buyer is an ACTIVE member afterwards.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean grantPaidAccess(String classId, String userId, Instant accessExpiresAt) {
        Instant now = Instant.now();
        Optional<ClassMember> existing = memberRepository.findByClassIdAndUserIdForUpdate(classId, userId);
        if (existing.isEmpty()) {
            ClassMember member = new ClassMember(classId, userId, "STUDENT");
            member.setAccessExpiresAt(accessExpiresAt);
            memberRepository.save(member);
            emitJoined(classId, userId, member.getRole());
            return true;
        }
        ClassMember member = existing.get();
        if (isBlockedState(member.getState())) {
            return false;
        }
        boolean wasActive = member.isActiveAt(now);
        boolean privileged = "OWNER".equalsIgnoreCase(member.getRole()) || "STAFF".equalsIgnoreCase(member.getRole());
        if (!privileged) {
            // A member who is perpetual already (grandfathered / lifetime) keeps it: a finite purchase must never shorten their access.
            boolean keepsPerpetual = wasActive && member.getAccessExpiresAt() == null;
            if (!keepsPerpetual) {
                member.setAccessExpiresAt(accessExpiresAt);
            }
        }
        member.setState("ACTIVE");
        memberRepository.save(member);
        if (!wasActive) {
            emitJoined(classId, userId, member.getRole());
        }
        return true;
    }

    /**
     * A refund (or anything else that removes paid time) left the buyer with access until {@code accessExpiresAt}, or none at all
     * ({@code hasAccess == false}): recompute the row from the entitlement chain. A chain that still runs only moves the date; no chain
     * at all flips an ACTIVE non-staff row to EXPIRED immediately and emits MEMBER_EXPIRED. REMOVED / BLOCKED rows are left alone.
     *
     * <p>{@code lostLifetime}: the refunded purchase was the LIFETIME one. A member with no expiry date is perpetual either because they
     * were grandfathered (they never needed the purchase, so refunding it takes nothing from them) or because the lifetime purchase itself
     * is what made them so (refund ends it) - the caller, which can see the refunded entitlements, says which.</p>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reconcileAfterRefund(String classId, String userId, boolean hasAccess, Instant accessExpiresAt, boolean lostLifetime) {
        Instant now = Instant.now();
        Optional<ClassMember> existing = memberRepository.findByClassIdAndUserIdForUpdate(classId, userId);
        if (existing.isEmpty()) return;
        ClassMember member = existing.get();
        boolean privileged = "OWNER".equalsIgnoreCase(member.getRole()) || "STAFF".equalsIgnoreCase(member.getRole());
        if (privileged) return;
        String effective = member.effectiveState(now);
        if (!"ACTIVE".equalsIgnoreCase(effective) && !"EXPIRED".equalsIgnoreCase(effective)) return; // REMOVED / BLOCKED
        if (hasAccess) {
            boolean wasActive = member.isActiveAt(now);
            member.setAccessExpiresAt(accessExpiresAt);
            member.setState("ACTIVE");
            memberRepository.save(member);
            if (!wasActive) emitJoined(classId, userId, member.getRole());
            return;
        }
        if (member.getAccessExpiresAt() == null && member.isActiveAt(now) && !lostLifetime) {
            // A perpetual (grandfathered) member who also bought a finite term and got refunded loses nothing: they never needed the purchase.
            return;
        }
        boolean wasActive = member.isActiveAt(now);
        member.setState("EXPIRED");
        member.setAccessExpiresAt(now);
        memberRepository.save(member);
        if (wasActive) emitExpired(classId, userId, now);
    }

    /**
     * Sweeper step for one locked row: ACTIVE -&gt; EXPIRED, with the MEMBER_EXPIRED outbox event. Returns false (and changes nothing)
     * for a row that is no longer a lapsed ACTIVE one.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean expire(ClassMember member, Instant now) {
        if (!"ACTIVE".equalsIgnoreCase(member.getState()) || member.getAccessExpiresAt() == null
                || member.getAccessExpiresAt().isAfter(now)) {
            return false;
        }
        if ("OWNER".equalsIgnoreCase(member.getRole()) || "STAFF".equalsIgnoreCase(member.getRole())) {
            // Defensive: staff / owner never expire (the date is cleared when they are assigned); repair the row instead of locking them out.
            member.setAccessExpiresAt(null);
            memberRepository.save(member);
            return false;
        }
        member.setState("EXPIRED");
        memberRepository.save(member);
        emitExpired(member.getClassId(), member.getUserId(), member.getAccessExpiresAt());
        return true;
    }

    /** MEMBER_JOINED: the Neo4j membership edge (re)appears; idempotent (MERGE). */
    public void emitJoined(String classId, String userId, String role) {
        outboxService.recordEvent("CLASSROOM", classId, "MEMBER_JOINED", Map.of(
                "userId", userId,
                "classId", classId,
                "role", role == null ? "STUDENT" : role
        ));
    }

    /** MEMBER_EXPIRED: the Neo4j membership edge disappears (like MEMBER_REMOVED); the Mongo document is one history row. */
    public void emitExpired(String classId, String userId, Instant expiredAt) {
        outboxService.recordEvent("CLASSROOM", classId, "MEMBER_EXPIRED", Map.of(
                "userId", userId,
                "classId", classId,
                "expiredAt", expiredAt.toString()
        ));
    }
}
