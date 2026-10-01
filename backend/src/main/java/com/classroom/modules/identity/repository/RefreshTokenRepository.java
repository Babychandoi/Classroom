package com.classroom.modules.identity.repository;

import com.classroom.modules.identity.model.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * R9-01: row-locks the presented token for the duration of the rotate() transaction so two
     * concurrent refreshes racing the same cookie (e.g. two tabs' bootstrap firing at once) cannot
     * both read the row as "still active" and both rotate it - only the first to acquire the lock
     * proceeds; the second sees the already-rotated row once it gets the lock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    List<RefreshToken> findByFamilyId(String familyId);

    /**
     * R12-01: the id (not the full entity) of every currently-unrevoked row of a family - a plain,
     * unlocked read used only to discover which rows {@link #revokeOneIfUnrevoked} needs to visit.
     * Locking happens per-row (below), never as a single wide statement over the whole family.
     */
    @Query("select t.id from RefreshToken t where t.familyId = :familyId and t.revokedAt is null")
    List<String> findUnrevokedIdsByFamilyId(@Param("familyId") String familyId);

    /**
     * R12-01: revokes exactly one row (by primary key), the same fine-grained lock granularity as
     * {@link #findByTokenHashForUpdate} (a single-row lock via a unique index), used in a loop by
     * {@link com.classroom.modules.identity.service.RefreshTokenService#revokeFamily} to revoke a
     * whole family one row at a time instead of the previous findByFamilyId() + saveAll() sequence.
     *
     * <p>That previous sequence read every row of the family unlocked, so a logout racing a
     * concurrent rotate() could load a row whose @Version the rotation had already bumped;
     * saveAll()'s subsequent UPDATE ... WHERE id = ? AND version = ? then matched zero rows and
     * Hibernate threw ObjectOptimisticLockingFailureException - unmapped, surfacing as a 500, and
     * rolling back the whole revocation. This statement has no version predicate at all (a one-way
     * monotonic revoke needs no optimistic check: revoking an already-revoked row is a harmless
     * no-op, matched by nothing here) and updates one row per call, so it never takes a wider lock
     * than a single primary-key row - the same granularity every other locking query in this
     * repository already uses ({@link #findByTokenHashForUpdate}), which is what avoids the MySQL
     * deadlocks a single multi-row bulk UPDATE over the whole family produced when raced against
     * those same single-row locks taken elsewhere in the same family's rotation chain (see R11-03's
     * grace-mint hop loop).</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now, t.version = t.version + 1 "
            + "where t.id = :id and t.revokedAt is null")
    int revokeOneIfUnrevoked(@Param("id") String id, @Param("now") Instant now);

    void deleteByExpiresAtBefore(Instant now);
}
