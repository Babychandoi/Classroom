package com.classroom.modules.ranking.repository;

import com.classroom.modules.ranking.model.LeaderboardEntry;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LeaderboardEntryRepository extends JpaRepository<LeaderboardEntry, String> {
    Optional<LeaderboardEntry> findByClassIdAndUserId(String classId, String userId);
    List<LeaderboardEntry> findByClassIdOrderByTotalPointsDesc(String classId);

    /**
     * Locks the learner's leaderboard row so that only one recalculation for a given
     * (classId, userId) runs at a time. This is always the first lock a recalculation takes,
     * which gives a consistent lock ordering and keeps concurrent recalculations deadlock-free.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM LeaderboardEntry e WHERE e.classId = :classId AND e.userId = :userId")
    Optional<LeaderboardEntry> lockByClassIdAndUserId(@Param("classId") String classId, @Param("userId") String userId);
}
