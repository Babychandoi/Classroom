package com.classroom.modules.ranking.repository;

import com.classroom.modules.ranking.model.LeaderboardEntry;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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
    /**
     * R20-01: creates the learner's row unless it already exists, on the caller's own connection and transaction.
     * {@code ON DUPLICATE KEY UPDATE id = id} turns the lost race against a concurrent creator (unique key
     * {@code uk_lb_class_user}) into a no-op, so no nested {@code REQUIRES_NEW} transaction (a second pooled
     * connection) and no constraint violation that would poison the surrounding transaction is needed.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO leaderboard_entries (id, class_id, user_id, total_points, current_tier, last_calculated_at) "
            + "VALUES (:id, :classId, :userId, 0, :tier, CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE id = id", nativeQuery = true)
    int insertIfAbsent(@Param("id") String id, @Param("classId") String classId,
                       @Param("userId") String userId, @Param("tier") String tier);
}
