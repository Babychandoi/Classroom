package com.classroom.modules.identity.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.identity.model.RefreshToken;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.RefreshTokenRepository;
import com.classroom.modules.identity.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * R8-06: issues, rotates and revokes HttpOnly-cookie refresh tokens.
 *
 * <p>Only a SHA-256 digest of each raw token is ever persisted (see RefreshToken), so a database
 * read alone can never mint a usable session. Each successful refresh rotates the token: the old
 * row is marked revoked and a new row (same family) is inserted. If a token that is no longer the
 * live member of its family is presented again (reuse of an already-rotated, or already-revoked,
 * token), that can only happen if the token leaked, so the entire family is revoked - forcing the
 * legitimate holder to log in again, but also invalidating whatever the attacker holds.</p>
 *
 * <p>R9-01: two legitimate scenarios previously looked identical to theft and forked/killed the
 * family:</p>
 * <ul>
 *   <li><b>Concurrent refresh</b> (two tabs racing the same cookie): both read the row as active
 *   and both tried to rotate it. {@link RefreshTokenRepository#findByTokenHashForUpdate} now
 *   row-locks the token for the transaction, so only the first racer's rotation commits; the
 *   second sees the row already rotated once it acquires the lock.</li>
 *   <li><b>Staggered refresh</b> (tab B still sends the old cookie a moment after tab A's rotation
 *   already committed and updated the shared cookie jar): rotate() now recognizes a reused row
 *   that was retired by <i>rotation</i> (rotatedAt set) within a short grace window and mints a new
 *   child off the family's current head instead of revoking the family - exactly one live token per
 *   family always results, and the legitimate multi-tab user is never logged out. Reuse of a row
 *   outside the grace window, or of a row that was revoked outright (logout / prior reuse
 *   detection), still revokes the whole family.</li>
 * </ul>
 */
@Service
public class RefreshTokenService {
    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository repository;
    private final UserRepository userRepository;
    private final EntityManager entityManager;
    private final long ttlDays;
    private final long graceWindowSeconds;
    private final TransactionTemplate requiresNewTransaction;

    public RefreshTokenService(
            RefreshTokenRepository repository,
            UserRepository userRepository,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager,
            @Value("${app.jwt.refresh-token-ttl-days:30}") long ttlDays,
            @Value("${app.jwt.refresh-token-grace-window-seconds:60}") long graceWindowSeconds) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.entityManager = entityManager;
        this.ttlDays = ttlDays;
        this.graceWindowSeconds = graceWindowSeconds;
        // R12-01: rotate()/revokeByRawToken() retry the WHOLE operation on a MySQL deadlock (see
        // withDeadlockRetry below), and each retry must run in a genuinely fresh transaction - a
        // MySQL deadlock rolls back the entire transaction that lost the tie-break, so continuing to
        // reuse it is not possible. @Transactional on a method this class calls via plain
        // this.xxx()/self-invocation would silently NOT apply at all (Spring's proxy only intercepts
        // calls that arrive through the proxy, never internal calls) - using TransactionTemplate
        // with PROPAGATION_REQUIRES_NEW here starts a real, independent transaction on each
        // execute(...) call, regardless of how this method itself was invoked. noRollbackFor
        // semantics (see rotateInTransaction's Javadoc) are reproduced manually: the callback catches
        // AppException, lets the transaction commit normally (a caught exception does not roll back
        // a TransactionTemplate callback), and the exception is rethrown by the caller after
        // execute() returns.
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // R13-01: revokeFamily's per-pass findUnrevokedIdsByFamilyId is a plain consistent read.
        // Under MySQL's default REPEATABLE READ, every read within one transaction after the first
        // reuses that first read's snapshot - so a second/third pass of the same "bounded retry"
        // loop would see the EXACT SAME snapshot as pass one, never observing a child row a
        // concurrent rotate() committed after this transaction's snapshot was taken (e.g. logout
        // racing rotate(): rotate()'s REQUIRES_NEW transaction commits a brand new child row for the
        // family in between this transaction's snapshot and its later passes). READ_COMMITTED makes
        // each statement take its own fresh read, so a later pass genuinely re-queries the database
        // and catches any row committed since the previous pass.
        this.requiresNewTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public record IssuedToken(String rawToken, Instant expiresAt) {}

    /** Issues the first token of a brand new rotation family (login/register). */
    @Transactional
    public IssuedToken issue(String userId) {
        return issue(userId, UUID.randomUUID().toString());
    }

    private IssuedToken issue(String userId, String familyId) {
        String rawToken = generateRawToken();
        Instant expiresAt = Instant.now().plus(ttlDays, ChronoUnit.DAYS);
        repository.save(new RefreshToken(userId, hash(rawToken), familyId, expiresAt));
        return new IssuedToken(rawToken, expiresAt);
    }

    public record RotationResult(String userId, IssuedToken issued) {}

    /**
     * R12-01: defense in depth. {@link #revokeFamily} now revokes one row at a time (by primary key)
     * specifically to avoid the wide-lock deadlocks a single multi-row bulk UPDATE produced (see its
     * Javadoc), but two single-row locks can still theoretically deadlock if two transactions each
     * hold one row the other needs next, in opposite acquisition order. If that ever happens, MySQL
     * reports it as CannotAcquireLockException; this retries the ENTIRE call a bounded number of
     * times rather than trying to recover the doomed transaction - a MySQL deadlock rolls back the
     * whole transaction that lost the deadlock-detection tie-break, so retrying only the failed
     * statement inside that same transaction cannot work. A fresh call runs
     * {@link #requiresNewTransaction}.execute(...) again, which starts a genuinely new, independent
     * transaction each time. Every code path this wraps is naturally idempotent under retry:
     * rotate()/revokeByRawToken() re-read current state from the database on each attempt, not
     * blindly reapplying a stale write.
     */
    private static final int DEADLOCK_RETRY_ATTEMPTS = 3;

    /**
     * R19-07: after a retired token's single grace re-mint, another replay of that same token arriving within this
     * many seconds is read as the losing side of a multi-tab race (refused, family kept); a later one is read as
     * reuse of a stolen token (family revoked). Deliberately independent of the grace window: it only has to cover
     * concurrent requests, not a user's staggered clicks.
     */
    static final long GRACE_REPLAY_RACE_SECONDS = 10;

    private <T> T withDeadlockRetry(java.util.function.Supplier<T> action) {
        CannotAcquireLockException lastFailure = null;
        for (int attempt = 1; attempt <= DEADLOCK_RETRY_ATTEMPTS; attempt++) {
            try {
                return action.get();
            } catch (CannotAcquireLockException deadlock) {
                lastFailure = deadlock;
                log.warn("Refresh token operation lost a MySQL deadlock tie-break (attempt {}/{}); retrying",
                        attempt, DEADLOCK_RETRY_ATTEMPTS);
            }
        }
        throw lastFailure;
    }

    /**
     * Validates the presented raw refresh token, rotates it, and returns the new token plus the
     * owning user id. Throws AppException(UNAUTHORIZED) for any missing/invalid/expired/revoked
     * token, a token reused after rotation (reuse-detection: also revokes the family), or a user
     * that is no longer ACTIVE.
     */
    public RotationResult rotate(String rawToken) {
        return withDeadlockRetry(() -> {
            java.util.concurrent.atomic.AtomicReference<AppException> thrown = new java.util.concurrent.atomic.AtomicReference<>();
            RotationResult result = requiresNewTransaction.execute(status -> {
                try {
                    return rotateInTransaction(rawToken);
                } catch (AppException ex) {
                    // noRollbackFor = AppException.class equivalent: reuse-detection and the
                    // inactive-user path both revoke the family and THEN signal rejection to the
                    // caller. Letting the callback return normally (instead of rethrowing here)
                    // lets this REQUIRES_NEW transaction commit that revocation; the exception is
                    // captured and rethrown below, once the transaction has already committed.
                    thrown.set(ex);
                    return null;
                }
            });
            AppException ex = thrown.get();
            if (ex != null) {
                throw ex;
            }
            return result;
        });
    }

    private RotationResult rotateInTransaction(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "Không tìm thấy phiên đăng nhập");
        }
        String tokenHash = hash(rawToken.trim());
        // R9-01: row-locked for the rest of this transaction. When two requests race the same
        // cookie, the second blocks here until the first commits (or rolls back), so it never reads
        // this row as "still active" the way an unlocked read could.
        RefreshToken current = repository.findByTokenHashForUpdate(tokenHash).orElse(null);
        if (current == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "Phiên đăng nhập không hợp lệ");
        }

        Instant now = Instant.now();
        if (current.getRevokedAt() != null) {
            return handleReuseOfRetiredToken(current, now);
        }
        if (!current.getExpiresAt().isAfter(now)) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "Phiên đăng nhập đã hết hạn");
        }

        User user = userRepository.findById(current.getUserId()).orElse(null);
        if (user == null || !"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            revokeFamily(current.getFamilyId(), now);
            throw new AppException(ErrorCode.UNAUTHORIZED, "Tài khoản chưa kích hoạt hoặc đã bị khóa");
        }

        IssuedToken issued = issue(current.getUserId(), current.getFamilyId());
        current.setRevokedAt(now);
        current.setRotatedAt(now);
        current.setReplacedByHash(hash(issued.rawToken()));
        repository.save(current);
        return new RotationResult(current.getUserId(), issued);
    }

    /**
     * R9-01: a row that is no longer the live member of its family was presented again. Two very
     * different situations look identical from this row alone, so tell them apart by whether the
     * row was retired by rotation (as opposed to logout / an earlier reuse-detection) and, if so,
     * how long ago:
     *
     * <ul>
     *   <li>Retired by rotation, within the grace window -> most likely a staggered multi-tab
     *   refresh (tab B still had the old cookie queued when tab A's rotation already committed).
     *   Mint a new child off the family's <i>current</i> head (which may itself already have moved
     *   past {@code current}'s immediate successor if several staggered refreshes queued up) and
     *   retire that head the same way - exactly one extra rotation happens, the chain never forks,
     *   and the legitimate user is never logged out.</li>
     *   <li>Otherwise (no rotatedAt at all - i.e. an explicit logout or a previous reuse-detection
     *   revocation - or a rotation-retirement outside the grace window) -> this can only be a
     *   genuinely stolen, already-superseded token being replayed. Fail closed: revoke the whole
     *   family.</li>
     * </ul>
     *
     * <p>R19-07: the grace re-mint is a ONE-SHOT per retired row ({@code graceMintedAt}). A retired token replayed a
     * second time is refused with 401; if that happens within {@link #GRACE_REPLAY_RACE_SECONDS} of the first
     * re-mint it is a racing tab (family untouched), otherwise it is reuse and the whole family is revoked. This
     * bounds what a stolen retired token is worth to exactly one extra session (the trade-off is recorded in
     * docs/DECISIONS.md D-17).</p>
     *
     * <p>R10-03: {@link #findCurrentHead} itself only reads (no row lock), so between locating the
     * head and writing to it, another concurrent grace-mint could have raced in and rotated that
     * same head first. Re-fetch the head row via {@link RefreshTokenRepository#findByTokenHashForUpdate}
     * (row-locking it for this transaction) and re-check {@code revokedAt == null} immediately
     * before minting; if it turns out to have moved on already, follow {@code replacedByHash} again
     * to the new head and retry, bounded the same way {@link #findCurrentHead} is.</p>
     *
     * <p>R11-03: the re-fetch above used to be ineffective when the candidate head was already a
     * managed entity in this transaction's persistence context (e.g. the very row
     * {@link #findCurrentHead} just read a moment earlier, unlocked) - Hibernate's first-level
     * cache handed back that SAME cached instance instead of re-reading the row, so the
     * {@code revokedAt} re-check could observe a value that was never actually refreshed from the
     * database; only the row lock itself (blocking until a concurrent writer committed) had any
     * real effect, and only while nothing else in this transaction had touched the row first. Each
     * candidate is now explicitly detached before the locking re-fetch, forcing a genuine read.</p>
     */
    private RotationResult handleReuseOfRetiredToken(RefreshToken current, Instant now) {
        boolean withinGrace = current.getRotatedAt() != null
                && !current.getRotatedAt().isBefore(now.minusSeconds(graceWindowSeconds));
        if (withinGrace && current.getGraceMintedAt() != null) {
            // R19-07: this retired row has already produced its one grace re-mint. Without this bound every replay
            // of the same retired token minted another session for as long as the grace window lasted. The
            // decision reads graceMintedAt under the row lock findByTokenHashForUpdate took, and the mint below
            // writes it in the same transaction, so among N concurrent replays exactly one gets a session.
            boolean stillRacing = !current.getGraceMintedAt().isBefore(now.minusSeconds(GRACE_REPLAY_RACE_SECONDS));
            if (stillRacing) {
                // Same instant as the winner's mint: this is the losing side of a multi-tab race (the winner's
                // Set-Cookie has already reached the shared cookie jar), so refuse WITHOUT revoking the family -
                // the legitimate live token must survive its own tabs racing.
                log.info("Refresh token replay lost the grace re-mint race for family {}; refused without revoking",
                        current.getFamilyId());
                throw new AppException(ErrorCode.UNAUTHORIZED, "Phiên đăng nhập vừa được làm mới ở thẻ khác; vui lòng thử lại");
            }
            // Later than a race can explain: a second use of the same retired token is what theft looks like.
            log.warn("Refresh token grace re-mint already used for family {}; treating the replay as reuse",
                    current.getFamilyId());
        } else if (withinGrace) {
            RefreshToken head = findCurrentHead(current);
            for (int hops = 0; head != null && hops < 100; hops++) {
                // R11-03: findByTokenHashForUpdate() issues a locking SELECT, but if `head` (or the
                // row at this hash) is already a managed entity in this transaction's persistence
                // context - e.g. the very row findCurrentHead() just read a moment ago, still
                // unlocked - Hibernate returns that SAME cached instance instead of re-reading the
                // row, so the revokedAt re-check below could observe a value that was never actually
                // refreshed from the database. The row lock itself (blocking until a concurrent
                // writer commits or rolls back) still applies, but the subsequent read from the
                // Java object does not reflect what the lock just waited for.
                //
                // Detach the candidate row from the persistence context FIRST (if it is even
                // managed - it may not be, e.g. the very first hop's `head` came from
                // findCurrentHead's own read), so the locking query below always performs a genuine
                // fresh read rather than handing back a cached instance. With @Version now on this
                // entity, locking an already-managed row whose version was bumped by a transaction
                // we just waited on would otherwise make Hibernate throw
                // ObjectOptimisticLockingFailureException directly out of the locking query itself -
                // before any application code runs - which Spring's exception translation can mark
                // the whole transaction rollback-only for, even if caught locally. Detaching first
                // avoids ever taking that path.
                entityManager.detach(head);
                RefreshToken lockedHead = repository.findByTokenHashForUpdate(head.getTokenHash()).orElse(null);
                if (lockedHead == null) {
                    head = null;
                    break;
                }
                if (lockedHead.getRevokedAt() != null) {
                    // Someone else rotated this head between our unlocked read and acquiring the
                    // lock. Follow the chain to whatever the new head is and re-check it.
                    if (lockedHead.getReplacedByHash() == null) {
                        head = null;
                        break;
                    }
                    head = repository.findByTokenHash(lockedHead.getReplacedByHash()).orElse(null);
                    continue;
                }
                head = lockedHead;
                break;
            }
            if (head != null && head.getRevokedAt() == null && head.getExpiresAt().isAfter(now)) {
                // R10-04: the grace-mint path previously skipped the ACTIVE user check that the
                // normal rotate() path enforces - apply the same check here before minting, and
                // fail closed (revoking the family) the same way rotate() does for an inactive user.
                User user = userRepository.findById(head.getUserId()).orElse(null);
                if (user == null || !"ACTIVE".equalsIgnoreCase(user.getStatus())) {
                    log.warn("Refresh token grace-mint rejected for family {}: owning user is not ACTIVE",
                            current.getFamilyId());
                    revokeFamily(current.getFamilyId(), now);
                    throw new AppException(ErrorCode.UNAUTHORIZED, "Tài khoản chưa kích hoạt hoặc đã bị khóa");
                }
                log.info("Refresh token reused within grace window for family {}; continuing chain without revoking family",
                        current.getFamilyId());
                IssuedToken issued = issue(head.getUserId(), head.getFamilyId());
                head.setRevokedAt(now);
                head.setRotatedAt(now);
                head.setReplacedByHash(hash(issued.rawToken()));
                repository.save(head);
                // R19-07: the retired token has now spent its single grace re-mint (same transaction, same lock).
                current.setGraceMintedAt(now);
                repository.save(current);
                return new RotationResult(head.getUserId(), issued);
            }
            // The chain's current head is itself unusable (expired/missing) - fall through to
            // revoking below rather than minting off nothing.
        }
        log.warn("Refresh token reuse detected for family {}; revoking family", current.getFamilyId());
        revokeFamily(current.getFamilyId(), now);
        throw new AppException(ErrorCode.UNAUTHORIZED, "Phiên đăng nhập đã bị thu hồi do phát hiện sử dụng lại token");
    }

    /** Follows replacedByHash forward from a retired row to the family's still-active tip, if any. */
    private RefreshToken findCurrentHead(RefreshToken from) {
        RefreshToken node = from;
        // A family's chain length is bounded by how many times it has been rotated; this is only
        // ever walked a handful of hops (successive staggered refreshes), never unbounded.
        for (int hops = 0; hops < 100 && node.getReplacedByHash() != null; hops++) {
            RefreshToken next = repository.findByTokenHash(node.getReplacedByHash()).orElse(null);
            if (next == null) return null;
            if (next.getRevokedAt() == null) return next;
            node = next;
        }
        return node.getRevokedAt() == null ? node : null;
    }

    /** Revokes every token in the family that owns this raw token (logout). No-op if unknown. */
    public void revokeByRawToken(String rawToken) {
        withDeadlockRetry(() -> {
            requiresNewTransaction.executeWithoutResult(status -> revokeByRawTokenInTransaction(rawToken));
            return null;
        });
    }

    private void revokeByRawTokenInTransaction(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        Optional<RefreshToken> current = repository.findByTokenHash(hash(rawToken.trim()));
        current.ifPresent(t -> revokeFamily(t.getFamilyId(), Instant.now()));
    }

    /**
     * R12-01: revokes an entire family one row at a time (see
     * {@link RefreshTokenRepository#revokeOneIfUnrevoked}) instead of the previous
     * findByFamilyId() + per-row mutate + saveAll() sequence. That sequence loaded every row of the
     * family unlocked, so a logout racing a concurrent rotate() could load a row whose @Version the
     * rotation had already bumped; saveAll()'s subsequent UPDATE ... WHERE id=? AND version=? then
     * matched no rows, so Hibernate threw ObjectOptimisticLockingFailureException - unmapped,
     * surfacing as a 500, and rolling back the whole revocation.
     *
     * <p>An earlier version of this fix used a single multi-row bulk UPDATE ... WHERE family_id = :f
     * over the whole family. That resolved the optimistic-lock crash but introduced real MySQL
     * deadlocks (SQLState 40001) under concurrency: the bulk statement's range scan over the family's
     * rows (via the family_id index) could lock those rows in a different order than the fine-grained
     * single-row SELECT ... FOR UPDATE locks {@link #handleReuseOfRetiredToken}'s grace-mint hop loop
     * takes via {@link RefreshTokenRepository#findByTokenHashForUpdate} on the SAME rows - two
     * concurrent transactions each holding one lock the other needs next is a textbook deadlock.
     * Revoking one row at a time, by primary key, keeps every lock this class ever takes at the same
     * granularity (a single row via a unique/primary-key index), which is what the rest of this class
     * already relies on to avoid exactly this class of deadlock.</p>
     *
     * <p>The id list is read unlocked, then each id is revoked with its own tiny transaction-scoped
     * statement; a second pass re-reads for any row a concurrent rotation inserted (with
     * revoked_at still null) between the read and this method's own updates, bounded the same way
     * {@link #findCurrentHead}'s hop loop is, so a child minted concurrently by another transaction
     * is still caught rather than left unrevoked.</p>
     *
     * <p>R13-01: that re-read only actually observes a concurrently-committed child row because
     * {@link #requiresNewTransaction} now runs at READ_COMMITTED (see its constructor Javadoc).
     * Under the previous default (MySQL REPEATABLE READ), every read after the first within one
     * transaction reuses that first read's snapshot, so passes 2-5 here were reading the exact same
     * snapshot as pass 1 and could never see a row a concurrent rotate() committed afterwards -
     * defeating the whole point of looping. READ_COMMITTED makes each pass's
     * findUnrevokedIdsByFamilyId a genuinely fresh read.</p>
     */
    private static final int REVOKE_FAMILY_MAX_PASSES = 20;

    private void revokeFamily(String familyId, Instant now) {
        for (int pass = 0; pass < REVOKE_FAMILY_MAX_PASSES; pass++) {
            List<String> unrevokedIds = repository.findUnrevokedIdsByFamilyId(familyId);
            if (unrevokedIds.isEmpty()) {
                return;
            }
            for (String id : unrevokedIds) {
                repository.revokeOneIfUnrevoked(id, now);
            }
        }
    }

    /**
     * D-29: a platform-admin ban logs the user out everywhere - every refresh-token family of the user is revoked, row by row exactly like
     * {@link #revokeFamily} (same lock granularity), inside the caller's transaction so the ban and the revocation commit together. Access
     * tokens need nothing: the JWT filter reads the account status on every request (D-26). Returns the number of families revoked.
     */
    @Transactional
    public int revokeAllForUser(String userId) {
        if (userId == null) return 0;
        Instant now = Instant.now();
        List<String> families = repository.findUnrevokedFamilyIdsByUserId(userId);
        for (String familyId : families) {
            revokeFamily(familyId, now);
        }
        return families.size();
    }

    /** R8-06: expired rows are otherwise never removed, growing the table forever. */
    @Scheduled(fixedDelayString = "${classroom.security.refresh-token-purge-delay-ms:3600000}")
    @Transactional
    public void purgeExpiredTokens() {
        repository.deleteByExpiresAtBefore(Instant.now());
    }

    private static String generateRawToken() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
