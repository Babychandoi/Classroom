package com.classroom.modules.media.repository;

import com.classroom.modules.media.model.MediaAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

@Repository
public interface MediaAssetRepository extends JpaRepository<MediaAsset, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MediaAsset m where m.id = :id")
    Optional<MediaAsset> findByIdForUpdate(@Param("id") String id);
    Optional<MediaAsset> findByObjectKey(String objectKey);
    List<MediaAsset> findByClassId(String classId);
    List<MediaAsset> findByStatusAndCreatedAtBefore(String status, Instant cutoff);
}
