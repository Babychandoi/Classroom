package com.classroom.modules.identity.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.identity.model.RefreshToken;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.RefreshTokenRepository;
import com.classroom.modules.identity.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * R8-06: refresh token issuance, rotation, reuse-detection (family revocation), and inactive-user
 * rejection. RefreshTokenRepository is mocked with a small backing map so rotate()'s
 * read-then-write sequence (find by hash -> mark revoked -> insert new row) is exercised
 * faithfully, the same way MediaSecurityTest mocks MinioClient/MediaAssetRepository.
 */
@ExtendWith(MockitoExtension.class)
public class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository repository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private EntityManager entityManager;
    @Mock
    private PlatformTransactionManager transactionManager;

    private RefreshTokenService service;
    private final Map<String, RefreshToken> byId = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        byId.clear();
        // R11-03: entityManager.refresh(...) is only meaningful against a real persistence context
        // (see RefreshTokenRotationIntegrationTest for that); here it is mocked as a no-op so the
        // grace-window re-lock path under test still exercises its own revokedAt/expiry checks
        // against this fake repository's backing map.
        // R12-01: rotate()/revokeByRawToken() now run their transactional body through a
        // TransactionTemplate (PROPAGATION_REQUIRES_NEW) instead of a plain @Transactional method,
        // so each retry attempt gets a genuinely fresh transaction (see RefreshTokenService's
        // constructor Javadoc). This fake PlatformTransactionManager has no real transactional
        // semantics (no commit/rollback side effects to verify here - that's what
        // RefreshTokenRotationIntegrationTest exercises against real MySQL) but faithfully invokes
        // the TransactionTemplate callback exactly once per attempt, which is all this class's own
        // logic (row locking, grace-window chain-following, family revocation) depends on.
        lenient().when(transactionManager.getTransaction(org.mockito.ArgumentMatchers.any(TransactionDefinition.class)))
                .thenAnswer(inv -> new SimpleTransactionStatus());
        service = new RefreshTokenService(repository, userRepository, entityManager, transactionManager, 30, 60);

        lenient().when(repository.save(any(RefreshToken.class))).thenAnswer(inv -> {
            RefreshToken token = inv.getArgument(0);
            byId.put(token.getId(), token);
            return token;
        });
        lenient().when(repository.saveAll(any())).thenAnswer(inv -> {
            List<RefreshToken> saved = new ArrayList<>();
            for (RefreshToken token : (Iterable<RefreshToken>) inv.getArgument(0)) {
                byId.put(token.getId(), token);
                saved.add(token);
            }
            return saved;
        });
        lenient().when(repository.findByTokenHash(anyString())).thenAnswer(inv -> {
            String hash = inv.getArgument(0);
            return byId.values().stream().filter(t -> t.getTokenHash().equals(hash)).findFirst();
        });
        // R9-01: rotate() now row-locks via findByTokenHashForUpdate instead of the plain find;
        // this mock backing store has no real locking, so it is faithfully modeled as the same
        // lookup.
        lenient().when(repository.findByTokenHashForUpdate(anyString())).thenAnswer(inv -> {
            String hash = inv.getArgument(0);
            return byId.values().stream().filter(t -> t.getTokenHash().equals(hash)).findFirst();
        });
        lenient().when(repository.findByFamilyId(anyString())).thenAnswer(inv -> {
            String familyId = inv.getArgument(0);
            List<RefreshToken> result = new ArrayList<>();
            for (RefreshToken t : byId.values()) if (t.getFamilyId().equals(familyId)) result.add(t);
            return result;
        });
        // R12-01: revokeFamily() now revokes one row at a time (see RefreshTokenRepository) instead
        // of findByFamilyId()+saveAll() (the original ObjectOptimisticLockingFailureException bug)
        // or a single wide multi-row bulk UPDATE (which deadlocked in MySQL under concurrency - see
        // RefreshTokenRotationIntegrationTest). Model both new repository methods against the same
        // backing map: findUnrevokedIdsByFamilyId() is a plain unlocked read of ids, and
        // revokeOneIfUnrevoked() revokes exactly one row per call, matching the real per-row-locked
        // JPQL UPDATE ... WHERE id = :id AND revoked_at IS NULL.
        lenient().when(repository.findUnrevokedIdsByFamilyId(anyString())).thenAnswer(inv -> {
            String familyId = inv.getArgument(0);
            List<String> ids = new ArrayList<>();
            for (RefreshToken t : byId.values()) {
                if (t.getFamilyId().equals(familyId) && t.getRevokedAt() == null) {
                    ids.add(t.getId());
                }
            }
            return ids;
        });
        lenient().when(repository.revokeOneIfUnrevoked(anyString(), any())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            Instant now = inv.getArgument(1);
            RefreshToken t = byId.get(id);
            if (t != null && t.getRevokedAt() == null) {
                t.setRevokedAt(now);
                return 1;
            }
            return 0;
        });
        lenient().doAnswer(inv -> {
            Instant now = inv.getArgument(0);
            byId.values().removeIf(t -> t.getExpiresAt().isBefore(now));
            return null;
        }).when(repository).deleteByExpiresAtBefore(any());
    }

    private User activeUser(String id) {
        User user = new User(id, id + "@test.local", "hashed", "Test User", "USER");
        user.setStatus("ACTIVE");
        return user;
    }

    private RefreshToken storedRow(String rawToken) {
        String hash = sha256(rawToken);
        return byId.values().stream().filter(t -> t.getTokenHash().equals(hash)).findFirst().orElse(null);
    }

    private static String sha256(String token) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("issue() persists a token whose hash, not the raw value, is stored")
    void issueStoresOnlyTheHash() {
        RefreshTokenService.IssuedToken issued = service.issue("user-1");

        assertNotNull(issued.rawToken());
        assertTrue(issued.expiresAt().isAfter(Instant.now()));
        assertEquals(1, byId.size());
        RefreshToken stored = byId.values().iterator().next();
        assertNotEquals(issued.rawToken(), stored.getTokenHash());
        assertEquals(64, stored.getTokenHash().length()); // SHA-256 hex digest
        assertEquals("user-1", stored.getUserId());
    }

    @Test
    @DisplayName("rotate() returns a new token, keeps the family, and revokes the presented one")
    void rotateRevokesOldTokenAndKeepsFamily() {
        when(userRepository.findById("user-1")).thenReturn(Optional.of(activeUser("user-1")));
        RefreshTokenService.IssuedToken first = service.issue("user-1");
        String firstFamily = byId.values().iterator().next().getFamilyId();

        RefreshTokenService.RotationResult result = service.rotate(first.rawToken());

        assertEquals("user-1", result.userId());
        assertNotEquals(first.rawToken(), result.issued().rawToken());
        assertEquals(2, byId.size());
        RefreshToken oldRow = storedRow(first.rawToken());
        assertNotNull(oldRow.getRevokedAt(), "the rotated-away token must be marked revoked");
        RefreshToken newRow = storedRow(result.issued().rawToken());
        assertNull(newRow.getRevokedAt());
        assertEquals(firstFamily, newRow.getFamilyId());
    }

    @Test
    @DisplayName("R9-01: reuse of a just-rotated token WITHIN the grace window continues the chain instead of revoking the family (staggered multi-tab refresh)")
    void reuseOfRotatedTokenWithinGraceWindowContinuesChain() {
        when(userRepository.findById("user-1")).thenReturn(Optional.of(activeUser("user-1")));
        RefreshTokenService.IssuedToken first = service.issue("user-1");
        String family = byId.values().iterator().next().getFamilyId();
        RefreshTokenService.RotationResult rotated = service.rotate(first.rawToken());

        // Tab B still had the old cookie queued and sends it right after tab A's rotation committed
        // - well within the grace window. This must succeed (a new token off the current head),
        // not revoke the family.
        RefreshTokenService.RotationResult secondReplay = service.rotate(first.rawToken());
        assertEquals("user-1", secondReplay.userId());
        assertEquals(family, storedRow(secondReplay.issued().rawToken()).getFamilyId());

        // Exactly one live (non-revoked) token remains for the family - the chain did not fork.
        long liveCount = byId.values().stream().filter(t -> family.equals(t.getFamilyId()) && t.getRevokedAt() == null).count();
        assertEquals(1, liveCount);

        // The first rotated-to token (now itself superseded by the grace-window continuation) was
        // also retired by rotation, so reusing it is itself still within the same grace window -
        // it too continues the chain rather than being treated as theft. The family is never
        // revoked by any of this; the chain simply keeps advancing by one link per stray replay.
        assertDoesNotThrow(() -> service.rotate(rotated.issued().rawToken()));
        // The token from the first grace-window continuation is still live throughout.
        assertDoesNotThrow(() -> service.rotate(secondReplay.issued().rawToken()));
    }

    @Test
    @DisplayName("R19-07: a retired token gets exactly ONE grace re-mint; an immediate second replay is refused (401), mints nothing and leaves the live token alone")
    void secondReplayOfTheSameRetiredTokenMintsNothingAndKeepsTheFamily() {
        when(userRepository.findById("user-1")).thenReturn(Optional.of(activeUser("user-1")));
        RefreshTokenService.IssuedToken first = service.issue("user-1");
        String family = byId.values().iterator().next().getFamilyId();
        service.rotate(first.rawToken());                                 // normal rotation: first is retired
        RefreshTokenService.RotationResult graceMint = service.rotate(first.rawToken()); // its single grace re-mint
        assertNotNull(storedRow(first.rawToken()).getGraceMintedAt(), "the retired row must record that it spent its re-mint");
        int rowsAfterGraceMint = byId.size();

        AppException ex = assertThrows(AppException.class, () -> service.rotate(first.rawToken()));

        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
        assertEquals(rowsAfterGraceMint, byId.size(), "the refused replay must not create a session");
        long live = byId.values().stream().filter(t -> family.equals(t.getFamilyId()) && t.getRevokedAt() == null).count();
        assertEquals(1, live, "a racing tab losing must not revoke the family");
        assertDoesNotThrow(() -> service.rotate(graceMint.issued().rawToken()), "the winner's token stays usable");
    }

    @Test
    @DisplayName("R19-07: replaying a stolen retired token in a loop is worth exactly one extra session")
    void replayLoopOfARetiredTokenMintsAtMostOneSession() {
        when(userRepository.findById("user-1")).thenReturn(Optional.of(activeUser("user-1")));
        RefreshTokenService.IssuedToken first = service.issue("user-1");
        service.rotate(first.rawToken());
        int rowsBeforeReplays = byId.size();

        int successes = 0;
        for (int i = 0; i < 25; i++) {
            try {
                service.rotate(first.rawToken());
                successes++;
            } catch (AppException expected) {
                assertEquals(ErrorCode.UNAUTHORIZED, expected.getErrorCode());
            }
        }

        assertEquals(1, successes, "only the first replay may mint");
        assertEquals(rowsBeforeReplays + 1, byId.size(), "and it minted exactly one row");
    }

    @Test
    @DisplayName("R19-07: a second replay after the race tolerance (but still inside the grace window) is reuse - the whole family is revoked")
    void lateSecondReplayIsReuseAndRevokesTheFamily() {
        when(userRepository.findById("user-1")).thenReturn(Optional.of(activeUser("user-1")));
        RefreshTokenService.IssuedToken first = service.issue("user-1");
        String family = byId.values().iterator().next().getFamilyId();
        service.rotate(first.rawToken());
        service.rotate(first.rawToken()); // the single grace re-mint
        RefreshToken retired = storedRow(first.rawToken());
        // Rotated 40s ago (inside the 60s window), re-minted 20s ago (outside the 10s race tolerance).
        retired.setRotatedAt(Instant.now().minusSeconds(40));
        retired.setGraceMintedAt(Instant.now().minusSeconds(20));

        AppException ex = assertThrows(AppException.class, () -> service.rotate(first.rawToken()));

        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
        long live = byId.values().stream().filter(t -> family.equals(t.getFamilyId()) && t.getRevokedAt() == null).count();
        assertEquals(0, live, "reuse of an already re-minted token must burn the family");
    }

    @Test
    @DisplayName("R19-07: every row of the chain has its own single re-mint (legitimate staggered refreshes keep working)")
    void eachRetiredRowInTheChainGetsItsOwnSingleReMint() {
        when(userRepository.findById("user-1")).thenReturn(Optional.of(activeUser("user-1")));
        RefreshTokenService.IssuedToken first = service.issue("user-1");
        RefreshTokenService.RotationResult second = service.rotate(first.rawToken());
        RefreshTokenService.RotationResult third = service.rotate(second.issued().rawToken());

        // Tabs holding the two older cookies each replay theirs once: both are legitimate and both succeed.
        assertDoesNotThrow(() -> service.rotate(first.rawToken()));
        assertDoesNotThrow(() -> service.rotate(second.issued().rawToken()));
        // ... but neither can be replayed a second time.
        assertThrows(AppException.class, () -> service.rotate(first.rawToken()));
        assertThrows(AppException.class, () -> service.rotate(second.issued().rawToken()));
        assertNotNull(third);
    }

    @Test
    @DisplayName("R10-04: reuse WITHIN the grace window is rejected and revokes the family when the owning user is no longer ACTIVE")
    void graceWindowReuseRejectsInactiveUserAndRevokesFamily() {
        when(userRepository.findById("user-1")).thenReturn(Optional.of(activeUser("user-1")));
        RefreshTokenService.IssuedToken first = service.issue("user-1");
        service.rotate(first.rawToken());

        // The user is suspended after the rotation that produced the current head.
        User suspended = activeUser("user-1");
        suspended.setStatus("SUSPENDED");
        reset(userRepository);
        when(userRepository.findById("user-1")).thenReturn(Optional.of(suspended));

        // Tab B replays the original (now rotated-away, but within grace) token - the grace-mint
        // path must still enforce the ACTIVE check rather than minting off an inactive user's head.
        AppException ex = assertThrows(AppException.class, () -> service.rotate(first.rawToken()));
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());

        // The whole family, including the still-live head produced by the first rotation, must now
        // be revoked.
        long liveCount = byId.values().stream()
                .filter(t -> t.getUserId().equals("user-1") && t.getRevokedAt() == null)
                .count();
        assertEquals(0, liveCount, "the family must be fully revoked, not left with a live token");
    }

    @Test
    @DisplayName("R9-01: reuse of a rotated-away token OUTSIDE the grace window revokes the entire family (genuine theft)")
    void reuseOfRotatedTokenOutsideGraceWindowRevokesFamily() {
        when(userRepository.findById("user-1")).thenReturn(Optional.of(activeUser("user-1")));
        RefreshTokenService.IssuedToken first = service.issue("user-1");
        RefreshTokenService.RotationResult rotated = service.rotate(first.rawToken());
        backdateRotation(storedRow(first.rawToken()));

        // Attacker replays the original, now-rotated-away token well after the grace window elapsed.
        AppException ex = assertThrows(AppException.class, () -> service.rotate(first.rawToken()));
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());

        // The legitimate rotated token must also now be revoked - the whole family is burned.
        AppException ex2 = assertThrows(AppException.class, () -> service.rotate(rotated.issued().rawToken()));
        assertEquals(ErrorCode.UNAUTHORIZED, ex2.getErrorCode());
    }

    @Test
    @DisplayName("R9-01: reuse of an explicitly logged-out (not rotated) token always revokes the family, even instantly")
    void reuseOfExplicitlyRevokedTokenAlwaysRevokesFamily() {
        RefreshTokenService.IssuedToken issued = service.issue("user-1");
        service.revokeByRawToken(issued.rawToken());

        // No grace window applies here: this row has revokedAt set but rotatedAt is null (logout,
        // not rotation), so any reuse is treated as reuse of a dead token, not a staggered refresh.
        AppException ex = assertThrows(AppException.class, () -> service.rotate(issued.rawToken()));
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
    }

    @Test
    @DisplayName("rotate() rejects an unknown token")
    void rotateRejectsUnknownToken() {
        AppException ex = assertThrows(AppException.class, () -> service.rotate("not-a-real-token"));
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
    }

    @Test
    @DisplayName("rotate() rejects a null/blank token without touching the repository")
    void rotateRejectsBlankToken() {
        assertThrows(AppException.class, () -> service.rotate(null));
        assertThrows(AppException.class, () -> service.rotate("  "));
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("rotate() rejects an expired token")
    void rotateRejectsExpiredToken() {
        RefreshTokenService.IssuedToken issued = service.issue("user-1");
        backdate(storedRow(issued.rawToken()));

        AppException ex = assertThrows(AppException.class, () -> service.rotate(issued.rawToken()));
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
    }

    @Test
    @DisplayName("rotate() rejects and revokes the family when the owning user is no longer ACTIVE")
    void rotateRejectsInactiveUserAndRevokesFamily() {
        User suspended = activeUser("user-1");
        suspended.setStatus("SUSPENDED");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(suspended));
        RefreshTokenService.IssuedToken issued = service.issue("user-1");

        AppException ex = assertThrows(AppException.class, () -> service.rotate(issued.rawToken()));
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
        assertNotNull(storedRow(issued.rawToken()).getRevokedAt());
    }

    @Test
    @DisplayName("rotate() rejects and revokes the family when the owning user no longer exists")
    void rotateRejectsDeletedUser() {
        when(userRepository.findById("user-1")).thenReturn(Optional.empty());
        RefreshTokenService.IssuedToken issued = service.issue("user-1");

        AppException ex = assertThrows(AppException.class, () -> service.rotate(issued.rawToken()));
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
        assertNotNull(storedRow(issued.rawToken()).getRevokedAt());
    }

    @Test
    @DisplayName("revokeByRawToken() (logout) revokes the whole family so a subsequent rotate() fails")
    void logoutRevokesFamily() {
        RefreshTokenService.IssuedToken issued = service.issue("user-1");

        service.revokeByRawToken(issued.rawToken());

        AppException ex = assertThrows(AppException.class, () -> service.rotate(issued.rawToken()));
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
    }

    @Test
    @DisplayName("R12-01: family revocation (logout, reuse-detection, inactive-user) revokes rows one at a time, never via read-all-then-saveAll()")
    void familyRevocationUsesPerRowUpdateNotReadThenSaveAll() {
        RefreshTokenService.IssuedToken issued = service.issue("user-1");

        service.revokeByRawToken(issued.rawToken());

        verify(repository, atLeastOnce()).revokeOneIfUnrevoked(anyString(), any());
        verify(repository, never()).saveAll(any());
        assertNotNull(storedRow(issued.rawToken()).getRevokedAt());
    }

    @Test
    @DisplayName("R12-01: revokeByRawToken() retries the whole operation when a per-row UPDATE loses a MySQL deadlock tie-break, and still succeeds")
    void revokeByRawTokenRetriesOnDeadlockAndEventuallySucceeds() {
        RefreshTokenService.IssuedToken issued = service.issue("user-1");

        // First attempt loses a deadlock tie-break (a real, if now rare, MySQL failure mode even
        // for same-granularity single-row locks racing each other - see
        // RefreshTokenRotationIntegrationTest for the real-MySQL version of this scenario); the
        // second attempt must actually perform the revocation against the fake backing store.
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(inv -> {
            if (calls.getAndIncrement() == 0) {
                throw new org.springframework.dao.CannotAcquireLockException("deadlock");
            }
            String id = inv.getArgument(0);
            Instant now = inv.getArgument(1);
            RefreshToken t = byId.get(id);
            if (t != null && t.getRevokedAt() == null) {
                t.setRevokedAt(now);
                return 1;
            }
            return 0;
        }).when(repository).revokeOneIfUnrevoked(anyString(), any());

        assertDoesNotThrow(() -> service.revokeByRawToken(issued.rawToken()));

        assertTrue(calls.get() >= 2, "the operation must be retried after the first deadlock loss");
        assertNotNull(storedRow(issued.rawToken()).getRevokedAt());
    }

    @Test
    @DisplayName("R12-01: revokeByRawToken() gives up and rethrows after exhausting deadlock retries")
    void revokeByRawTokenRethrowsAfterExhaustingRetries() {
        RefreshTokenService.IssuedToken issued = service.issue("user-1");
        doThrow(new org.springframework.dao.CannotAcquireLockException("deadlock"))
                .when(repository).revokeOneIfUnrevoked(anyString(), any());

        assertThrows(org.springframework.dao.CannotAcquireLockException.class,
                () -> service.revokeByRawToken(issued.rawToken()));
    }

    @Test
    @DisplayName("revokeByRawToken() is a no-op for an unknown or blank token (does not throw)")
    void logoutIsNoopForUnknownToken() {
        assertDoesNotThrow(() -> service.revokeByRawToken("unknown"));
        assertDoesNotThrow(() -> service.revokeByRawToken(null));
    }

    @Test
    @DisplayName("purgeExpiredTokens() delegates to the repository's expiry-bound delete")
    void purgeDelegatesToRepository() {
        service.purgeExpiredTokens();
        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteByExpiresAtBefore(captor.capture());
        assertTrue(captor.getValue().isBefore(Instant.now().plusSeconds(5)));
    }

    private static void backdate(RefreshToken row) {
        try {
            java.lang.reflect.Field field = RefreshToken.class.getDeclaredField("expiresAt");
            field.setAccessible(true);
            field.set(row, Instant.now().minus(1, ChronoUnit.DAYS));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Pushes a row's rotatedAt far enough into the past to fall outside the service's grace window. */
    private static void backdateRotation(RefreshToken row) {
        try {
            java.lang.reflect.Field field = RefreshToken.class.getDeclaredField("rotatedAt");
            field.setAccessible(true);
            field.set(row, Instant.now().minusSeconds(3600));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
