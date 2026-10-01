package com.classroom.integration;

import com.classroom.common.AppException;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.RefreshTokenRepository;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.identity.service.RefreshTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R8-06/R9-01: RefreshTokenServiceTest exercises rotate()/reuse-detection against a mocked
 * repository, which cannot see whether a revocation actually survives the surrounding
 * @Transactional boundary, nor exercise real row-level locking across concurrent transactions. This
 * class runs against a real MySQL transaction manager (no mocks) for exactly those two properties:
 * revocation durability across a thrown exception, and exactly-one-winner under real concurrency.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("integration")
class RefreshTokenRotationIntegrationTest {

    @Autowired
    private RefreshTokenService refreshTokenService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    private User createActiveUser() {
        User user = new User();
        user.setEmail("refresh-rotation-" + System.nanoTime() + "@test.local");
        user.setPasswordHash("hashed");
        user.setFullName("Refresh Rotation Tester");
        user.setRole("USER");
        user.setStatus("ACTIVE");
        return userRepository.save(user);
    }

    @Test
    @DisplayName("R8-06/R9-01: reuse of a rotated-away token OUTSIDE the grace window durably revokes the rest of its family, surviving the AppException it throws")
    void reuseDetectionRevocationSurvivesTheThrownException() throws Exception {
        User user = createActiveUser();

        RefreshTokenService.IssuedToken gen1 = refreshTokenService.issue(user.getId());
        RefreshTokenService.RotationResult rotation = refreshTokenService.rotate(gen1.rawToken());
        String gen2RawToken = rotation.issued().rawToken();

        // Push gen1's rotatedAt outside the grace window so this exercises the genuine-reuse path,
        // not the R9-01 staggered-refresh continuation.
        backdateRotation(hashOf(gen1.rawToken()));

        // Reusing the already-rotated gen1 token, now outside the grace window, must 401 and revoke
        // the whole family.
        AppException ex = assertThrows(AppException.class, () -> refreshTokenService.rotate(gen1.rawToken()));
        assertEquals(com.classroom.common.ErrorCode.UNAUTHORIZED, ex.getErrorCode());

        // The revocation from the call above must have committed despite that call throwing -
        // the still-legitimate gen2 token (never itself reused) must now also be rejected.
        AppException ex2 = assertThrows(AppException.class, () -> refreshTokenService.rotate(gen2RawToken));
        assertEquals(com.classroom.common.ErrorCode.UNAUTHORIZED, ex2.getErrorCode());
    }

    @Test
    @DisplayName("R9-01: reuse WITHIN the grace window (staggered multi-tab refresh) continues the chain instead of revoking the family")
    void reuseWithinGraceWindowContinuesChainAgainstRealTransactionManager() {
        User user = createActiveUser();
        RefreshTokenService.IssuedToken gen1 = refreshTokenService.issue(user.getId());
        RefreshTokenService.RotationResult rotation = refreshTokenService.rotate(gen1.rawToken());

        // Tab B still had gen1's cookie queued when it fires, immediately after tab A's rotation.
        RefreshTokenService.RotationResult staggered = refreshTokenService.rotate(gen1.rawToken());
        assertEquals(user.getId(), staggered.userId());

        // The rest of the family (including the token rotate() first produced) is not revoked by
        // this - only continued past.
        assertDoesNotThrow(() -> refreshTokenService.rotate(rotation.issued().rawToken()));
    }

    @Test
    @DisplayName("R10-03: concurrent grace-window replays of the same rotated-away token - real MySQL row lock - family converges on exactly one live token, never forked")
    void concurrentGraceReplayOfSameOldTokenHasExactlyOneLiveToken() throws Exception {
        User user = createActiveUser();
        RefreshTokenService.IssuedToken gen1 = refreshTokenService.issue(user.getId());
        String familyId = refreshTokenRepository.findByTokenHash(hashOf(gen1.rawToken()))
                .orElseThrow().getFamilyId();

        // Rotate once so gen1 becomes a retired-by-rotation row, eligible for the grace-mint path.
        refreshTokenService.rotate(gen1.rawToken());

        // Several concurrent "tabs" all replay the same now-retired gen1 token within the grace
        // window - each hits handleReuseOfRetiredToken()'s grace-mint path racing for the same head.
        int threadCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();

        try {
            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        refreshTokenService.rotate(gen1.rawToken());
                        successes.incrementAndGet();
                    } catch (AppException e) {
                        failures.incrementAndGet();
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            for (Future<?> f : futures) f.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(threadCount, successes.get() + failures.get());
        // R19-07: the retired gen1 row has ONE grace re-mint. Of 8 concurrent legitimate-looking replays exactly one
        // gets a session; the other seven lose the race (401) - and losing a race must NOT revoke the family.
        assertEquals(1, successes.get(), "exactly one concurrent replay of a retired token may mint");
        assertEquals(threadCount - 1, failures.get());

        List<com.classroom.modules.identity.model.RefreshToken> family = refreshTokenRepository.findByFamilyId(familyId);
        long liveCount = family.stream().filter(t -> t.getRevokedAt() == null).count();
        assertEquals(1, liveCount, "the family must converge on exactly one live token under concurrent grace-mint replay, never forked");
        assertNotNull(refreshTokenRepository.findByTokenHash(hashOf(gen1.rawToken())).orElseThrow().getGraceMintedAt(),
                "the retired row must record that it spent its single re-mint");

        long distinctSuccessorTargets = family.stream()
                .map(com.classroom.modules.identity.model.RefreshToken::getReplacedByHash)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count();
        long rowsWithSuccessor = family.stream()
                .map(com.classroom.modules.identity.model.RefreshToken::getReplacedByHash)
                .filter(java.util.Objects::nonNull)
                .count();
        assertEquals(rowsWithSuccessor, distinctSuccessorTargets, "no successor token was produced by more than one grace-mint rotation (no fork)");
    }

    @Test
    @DisplayName("R9-01: concurrent rotation with the same token - real MySQL row lock - exactly one thread succeeds, family is not forked")
    void concurrentRotationWithSameTokenHasExactlyOneWinner() throws Exception {
        User user = createActiveUser();
        RefreshTokenService.IssuedToken issued = refreshTokenService.issue(user.getId());
        String familyId = refreshTokenRepository.findByTokenHash(hashOf(issued.rawToken()))
                .orElseThrow().getFamilyId();

        int threadCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();

        try {
            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        refreshTokenService.rotate(issued.rawToken());
                        successes.incrementAndGet();
                    } catch (AppException e) {
                        failures.incrementAndGet();
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            for (Future<?> f : futures) f.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // R9-01: the row lock (findByTokenHashForUpdate) plus the grace window together mean every
        // racer that reaches the row after the winner has committed sees it as "already rotated,
        // within grace" and is allowed to continue the SAME chain - it does not throw, but it also
        // must not fork the family. Whether a given racer "succeeds" or "fails" here is not the
        // interesting assertion (both are legitimate depending on ordering); what must always hold
        // is that the family ends up as a single, unforked chain with exactly one live token.
        assertEquals(threadCount, successes.get() + failures.get());

        List<com.classroom.modules.identity.model.RefreshToken> family = refreshTokenRepository.findByFamilyId(familyId);
        long liveCount = family.stream().filter(t -> t.getRevokedAt() == null).count();
        assertEquals(1, liveCount, "the family must converge on exactly one live token, never forked");

        // No two rows share the same replacedByHash (which would indicate two rows were both
        // "rotated into" the same successor - i.e. a fork).
        long distinctSuccessorTargets = family.stream()
                .map(com.classroom.modules.identity.model.RefreshToken::getReplacedByHash)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count();
        long rowsWithSuccessor = family.stream()
                .map(com.classroom.modules.identity.model.RefreshToken::getReplacedByHash)
                .filter(java.util.Objects::nonNull)
                .count();
        assertEquals(rowsWithSuccessor, distinctSuccessorTargets, "no successor token was produced by more than one rotation (no fork)");
    }

    @Test
    @DisplayName("R11-03: concurrently replaying TWO DIFFERENT retired tokens of the same family within grace converges on exactly one live token")
    void concurrentGraceReplayOfDifferentRetiredTokensHasExactlyOneLiveToken() throws Exception {
        User user = createActiveUser();
        RefreshTokenService.IssuedToken gen1 = refreshTokenService.issue(user.getId());
        String familyId = refreshTokenRepository.findByTokenHash(hashOf(gen1.rawToken()))
                .orElseThrow().getFamilyId();

        // Build a short chain: gen1 -> gen2 -> gen3 (current head). gen1 and gen2 are now both
        // retired-by-rotation rows within the grace window, each pointing (via replacedByHash,
        // followed transitively through findCurrentHead) at gen3 as the live tip.
        RefreshTokenService.RotationResult rotation1 = refreshTokenService.rotate(gen1.rawToken());
        String gen2RawToken = rotation1.issued().rawToken();
        RefreshTokenService.RotationResult rotation2 = refreshTokenService.rotate(gen2RawToken);
        String gen3RawToken = rotation2.issued().rawToken();

        // Several concurrent "tabs" replay EITHER gen1 or gen2 (two different already-retired rows
        // of the same family) at the same time - this is the R11-03 regression scenario: the
        // re-lock path must re-read each candidate head from the database under its lock rather
        // than trusting a persistence-context-cached (possibly stale) instance.
        int threadCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();

        try {
            for (int i = 0; i < threadCount; i++) {
                String rawToken = (i % 2 == 0) ? gen1.rawToken() : gen2RawToken;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        refreshTokenService.rotate(rawToken);
                        successes.incrementAndGet();
                    } catch (AppException e) {
                        failures.incrementAndGet();
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            for (Future<?> f : futures) f.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(threadCount, successes.get() + failures.get());
        // R19-07: two retired rows -> at most one re-mint each (a row whose head moved on may also simply lose).
        assertTrue(successes.get() >= 1 && successes.get() <= 2,
                "one grace re-mint per retired token, got " + successes.get() + " successes for 2 tokens");

        List<com.classroom.modules.identity.model.RefreshToken> family = refreshTokenRepository.findByFamilyId(familyId);
        long liveCount = family.stream().filter(t -> t.getRevokedAt() == null).count();
        assertEquals(1, liveCount, "the family must converge on exactly one live token, never forked, when two different retired tokens are replayed concurrently");

        long distinctSuccessorTargets = family.stream()
                .map(com.classroom.modules.identity.model.RefreshToken::getReplacedByHash)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count();
        long rowsWithSuccessor = family.stream()
                .map(com.classroom.modules.identity.model.RefreshToken::getReplacedByHash)
                .filter(java.util.Objects::nonNull)
                .count();
        assertEquals(rowsWithSuccessor, distinctSuccessorTargets, "no successor token was produced by more than one rotation (no fork)");

        // gen3 (the original head before this concurrent replay started) must still be part of the
        // single unforked chain - either still live, or itself rotated into the eventual live tip.
        assertTrue(refreshTokenRepository.findByTokenHash(hashOf(gen3RawToken)).isPresent());
    }

    /**
     * R12-01: logout (revokeByRawToken -> revokeFamily) racing a concurrent rotate() on the same
     * family used to read every row of the family unlocked, then saveAll() them; if a rotate() had
     * already bumped a row's @Version between that read and the save, Hibernate threw
     * ObjectOptimisticLockingFailureException - unmapped in GlobalExceptionHandler at the time,
     * surfacing as a 500 and rolling the whole transaction back, so the family was left unrevoked and
     * the logout endpoint's cookie was never cleared. The fix replaces that read-then-write sequence
     * with a single bulk UPDATE ... WHERE family_id = :f AND revoked_at IS NULL, which takes its own
     * row locks as it executes and evaluates its WHERE clause against the database at execution time
     * (so it also catches a child token the concurrent rotate() just committed, which the old
     * unlocked read could easily have missed). This test fires logout and rotate concurrently many
     * times against a real MySQL transaction manager and asserts the family always ends up fully
     * revoked and logout always completes (2xx), regardless of interleaving.
     */
    @Test
    @DisplayName("R12-01: logout racing rotate() on the same family - real MySQL - the whole family ends up revoked and logout never 500s")
    void logoutRacingRotateRevokesWholeFamilyAndLogoutNeverFails() throws Exception {
        User user = createActiveUser();
        RefreshTokenService.IssuedToken gen1 = refreshTokenService.issue(user.getId());
        String familyId = refreshTokenRepository.findByTokenHash(hashOf(gen1.rawToken()))
                .orElseThrow().getFamilyId();

        int threadCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger logoutFailures = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();

        try {
            for (int i = 0; i < threadCount; i++) {
                final boolean isLogoutThread = (i % 2 == 0);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        if (isLogoutThread) {
                            refreshTokenService.revokeByRawToken(gen1.rawToken());
                        } else {
                            refreshTokenService.rotate(gen1.rawToken());
                        }
                    } catch (AppException expected) {
                        // rotate() legitimately 401s once the family is revoked or a reuse is
                        // detected - that is not a failure of this test's guarantee.
                    } catch (RuntimeException unexpected) {
                        // Any other runtime exception (e.g. an unmapped optimistic-locking failure)
                        // is exactly the regression this test guards against.
                        logoutFailures.incrementAndGet();
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            for (Future<?> f : futures) f.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, logoutFailures.get(), "logout/rotate must never surface an unmapped exception when racing each other");

        // R13-01 regression check: assert immediately after the race, with NO extra trailing
        // revokeByRawToken() call. An extra clean-up call here would mask the bug this test guards
        // against - under the old plain-consistent-read revokeFamily loop (REPEATABLE READ), a
        // logout thread's revocation pass(es) could all share one snapshot taken before a racing
        // rotate() committed its child row, leaving that child unrevoked; a subsequent, separate
        // revokeByRawToken() call (a brand new transaction, hence a brand new snapshot) would then
        // trivially clean it up and hide the failure. Asserting right here, against only the
        // in-race revocations, is what makes this test actually fail against the old code.
        List<com.classroom.modules.identity.model.RefreshToken> family = refreshTokenRepository.findByFamilyId(familyId);
        assertFalse(family.isEmpty());
        assertTrue(family.stream().allMatch(t -> t.getRevokedAt() != null),
                "every token in the family must end up revoked after logout races rotate() to completion, with no separate clean-up call");
    }

    @Test
    @DisplayName("R19-07: sequential replays of a retired token mint exactly one session - real MySQL; the loser is refused without revoking the family")
    void retiredTokenReplayedInALoopMintsExactlyOneSession() throws Exception {
        User user = createActiveUser();
        RefreshTokenService.IssuedToken gen1 = refreshTokenService.issue(user.getId());
        String familyId = refreshTokenRepository.findByTokenHash(hashOf(gen1.rawToken())).orElseThrow().getFamilyId();
        refreshTokenService.rotate(gen1.rawToken());

        RefreshTokenService.RotationResult minted = refreshTokenService.rotate(gen1.rawToken()); // the single re-mint
        for (int i = 0; i < 10; i++) {
            AppException refused = assertThrows(AppException.class, () -> refreshTokenService.rotate(gen1.rawToken()));
            assertEquals(com.classroom.common.ErrorCode.UNAUTHORIZED, refused.getErrorCode());
        }

        List<com.classroom.modules.identity.model.RefreshToken> family = refreshTokenRepository.findByFamilyId(familyId);
        assertEquals(3, family.size(), "issue + rotation + ONE grace re-mint, however many replays followed");
        assertEquals(1, family.stream().filter(t -> t.getRevokedAt() == null).count(), "the racing/refused replays must not revoke the family");
        assertDoesNotThrow(() -> refreshTokenService.rotate(minted.issued().rawToken()), "the one legitimate re-mint keeps working");
    }

    @Test
    @DisplayName("R19-07: a second replay of a re-minted token AFTER the race tolerance is reuse - real MySQL - the whole family is revoked")
    void lateSecondReplayRevokesTheFamily() throws Exception {
        User user = createActiveUser();
        RefreshTokenService.IssuedToken gen1 = refreshTokenService.issue(user.getId());
        String familyId = refreshTokenRepository.findByTokenHash(hashOf(gen1.rawToken())).orElseThrow().getFamilyId();
        refreshTokenService.rotate(gen1.rawToken());
        RefreshTokenService.RotationResult minted = refreshTokenService.rotate(gen1.rawToken());

        // Rotated 40s ago (inside the default 60s grace window), re-minted 20s ago (outside the 10s race tolerance).
        var row = refreshTokenRepository.findByTokenHash(hashOf(gen1.rawToken())).orElseThrow();
        row.setRotatedAt(Instant.now().minusSeconds(40));
        row.setGraceMintedAt(Instant.now().minusSeconds(20));
        refreshTokenRepository.save(row);

        AppException ex = assertThrows(AppException.class, () -> refreshTokenService.rotate(gen1.rawToken()));
        assertEquals(com.classroom.common.ErrorCode.UNAUTHORIZED, ex.getErrorCode());

        assertTrue(refreshTokenRepository.findByFamilyId(familyId).stream().allMatch(t -> t.getRevokedAt() != null),
                "reuse of an already re-minted token burns the family");
        assertThrows(AppException.class, () -> refreshTokenService.rotate(minted.issued().rawToken()));
    }

    private String hashOf(String rawToken) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256").digest(rawToken.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }

    /** Pushes a row's rotatedAt far enough into the past to fall outside the service's grace window. */
    private void backdateRotation(String tokenHash) throws Exception {
        var row = refreshTokenRepository.findByTokenHash(tokenHash).orElseThrow();
        Field field = com.classroom.modules.identity.model.RefreshToken.class.getDeclaredField("rotatedAt");
        field.setAccessible(true);
        field.set(row, Instant.now().minusSeconds(3600));
        refreshTokenRepository.save(row);
    }
}
