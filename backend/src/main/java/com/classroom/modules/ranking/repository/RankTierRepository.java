package com.classroom.modules.ranking.repository;

import com.classroom.modules.ranking.model.RankTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RankTierRepository extends JpaRepository<RankTier, String> {
    List<RankTier> findByClassIdOrderByMinPointsAsc(String classId);
    void deleteByClassId(String classId);
}
