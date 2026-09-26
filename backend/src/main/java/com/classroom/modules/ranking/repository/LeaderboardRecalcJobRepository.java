package com.classroom.modules.ranking.repository;

import com.classroom.modules.ranking.model.LeaderboardRecalcJob;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface LeaderboardRecalcJobRepository extends JpaRepository<LeaderboardRecalcJob, String> {

    /** Every outstanding arming for a learner. There is one row per publication, not per learner. */
    List<LeaderboardRecalcJob> findByClassIdAndUserId(String classId, String userId);

    @Query("select j from LeaderboardRecalcJob j where j.nextAttemptAt <= :now order by j.nextAttemptAt asc")
    List<LeaderboardRecalcJob> findDueJobs(@Param("now") Instant now, Pageable pageable);

    /**
     * Clears exactly the armings a recalculation observed before it read the learner's attempts.
     * A publication that committed after that read is not in this set, so its job survives for
     * {@code LeaderboardService#sweepPendingRecalculations} instead of being dropped unserviced.
     */
    @Modifying
    @Query("delete from LeaderboardRecalcJob j where j.id in :ids")
    int deleteByIdIn(@Param("ids") Collection<String> ids);
}
