package com.classroom.modules.classroom.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.model.ClassInvite;
import com.classroom.modules.classroom.repository.ClassInviteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * D-19: the only code that changes {@code class_invites.used_count}. A use is always taken under the invite row's FOR UPDATE lock, so N
 * concurrent joins of an invite with {@code maxUses = k} produce exactly k successes.
 *
 * <p>Kept apart from {@link ClassInviteService} (which builds class DTOs and so depends on the classroom service) so that the commerce
 * side - a purchase that reserves a use for a PRIVATE paid class - can use it without a dependency cycle.</p>
 *
 * <p>Every method requires the caller's transaction (MANDATORY) and assumes READ_COMMITTED, like the other lock-first money/membership
 * paths: the FOR UPDATE read returns the latest committed {@code used_count} whatever was read earlier in the transaction.</p>
 */
@Service
public class ClassInviteLedger {

    /** The one answer for every way a code can be unusable (unknown, malformed, revoked, expired, used up, other class, archived class). */
    public static final String NOT_FOUND_MESSAGE = "Không tìm thấy lớp học";

    private final ClassInviteRepository inviteRepository;

    public ClassInviteLedger(ClassInviteRepository inviteRepository) {
        this.inviteRepository = inviteRepository;
    }

    public static AppException invalidInvite() {
        return new AppException(ErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE);
    }

    /**
     * Locks the invite named by {@code rawCode} and returns it only if it is usable right now. The presented and the stored hash are
     * compared in constant time even though the lookup already matched them. Every failure is the same 404.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ClassInvite lockUsable(String rawCode, Instant now) {
        ClassInvite invite = lockByCode(rawCode).orElseThrow(ClassInviteLedger::invalidInvite);
        if (!invite.isUsableAt(now)) {
            throw invalidInvite();
        }
        return invite;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ClassInvite> lockByCode(String rawCode) {
        if (!InviteCodes.isWellFormed(rawCode)) {
            return Optional.empty();
        }
        String presentedHash = InviteCodes.hash(rawCode);
        return inviteRepository.findByCodeHashForUpdate(presentedHash)
                .filter(invite -> InviteCodes.constantTimeEquals(invite.getCodeHash(), presentedHash));
    }

    /** Takes one use (caller holds the lock from {@link #lockUsable}). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(ClassInvite invite) {
        invite.setUsedCount(invite.getUsedCount() + 1);
        inviteRepository.save(invite);
    }

    /**
     * Reserves one use of the invite for a purchase at the checkout of {@code classId} (a PRIVATE paid class). The invite must belong to
     * that class and be usable; the use is taken immediately so the cap holds under concurrency, and is given back by
     * {@link #release} if the order is cancelled or fails.
     *
     * @return the invite id to store on the order
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String reserveForPurchase(String rawCode, String classId, Instant now) {
        ClassInvite invite = lockUsable(rawCode, now);
        if (!classId.equals(invite.getClassId())) {
            throw invalidInvite();
        }
        consume(invite);
        return invite.getId();
    }

    /** Gives back a reserved use (never below zero). A deleted / unknown invite is ignored. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(String inviteId) {
        if (inviteId == null) return;
        inviteRepository.findByIdForUpdate(inviteId).ifPresent(invite -> {
            if (invite.getUsedCount() > 0) {
                invite.setUsedCount(invite.getUsedCount() - 1);
                inviteRepository.save(invite);
            }
        });
    }
}
