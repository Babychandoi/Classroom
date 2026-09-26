package com.classroom.modules.community.repository;

import com.classroom.modules.community.model.DocumentAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentAssetRepository extends JpaRepository<DocumentAsset, String> {
    List<DocumentAsset> findByClassIdOrderByCreatedAtDesc(String classId);
    List<DocumentAsset> findByMediaAssetId(String mediaAssetId);
}
