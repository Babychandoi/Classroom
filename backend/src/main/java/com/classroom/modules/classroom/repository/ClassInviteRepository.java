package com.classroom.modules.classroom.repository;

import com.classroom.modules.classroom.model.ClassInvite;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClassInviteRepository extends JpaRepository<ClassInvite, String> {

    /** Lookup by the SHA-256 of a presented code (unique index). Read-only preview path. */
    Optional<ClassInvite> findByCodeHash(String codeHash);

    /**
     * The join path: the invite row is locked FOR UPDATE before {@code usedCount} is read and incremented, so N concurrent joins of an invite
     * with {@code maxUses = k} produce exactly k successes (the others see the new count once the lock is released). Lock order on the join
     * path is invite -> class_members row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ClassInvite i where i.codeHash = :codeHash")
    Optional<ClassInvite> findByCodeHashForUpdate(@Param("codeHash") String codeHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ClassInvite i where i.id = :id and i.classId = :classId")
    Optional<ClassInvite> findByIdAndClassIdForUpdate(@Param("id") String id, @Param("classId") String classId);

    List<ClassInvite> findByClassIdOrderByCreatedAtDesc(String classId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ClassInvite i where i.id = :id")
    Optional<ClassInvite> findByIdForUpdate(@Param("id") String id);
}
