package com.classroom.modules.ranking.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Durable record that a learner's leaderboard total still needs recalculating.
 *
 * <p>The row is written inside the transaction that publishes (or corrects) a score, so it
 * commits atomically with the attempt. The post-commit recalculation deletes it on success;
 * anything else — a transient failure, or the process dying before the post-commit hook ran —
 * leaves the row behind for {@code LeaderboardService#sweepPendingRecalculations} to retry.</p>
 *
 * <p>One row is written per publication rather than one upserted row per learner. A shared row
 * would have to be locked by every publishing transaction, so two exams publishing for the same
 * learner at the same time would block on each other until one committed. Independent rows let a
 * recalculation delete exactly the rows it observed before it read the attempts, which is what
 * keeps a job armed by a publication it could not see.</p>
 */
@Entity
@Table(name = "leaderboard_recalc_jobs",
        indexes = @Index(name = "idx_lb_recalc_class_user", columnList = "class_id, user_id"))
public class LeaderboardRecalcJob {

    @Id
    @Column(length = 36)
    private String id = UUID.randomUUID().toString();

    @Column(name = "class_id", nullable = false, length = 36)
    private String classId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    protected LeaderboardRecalcJob() {}

    public LeaderboardRecalcJob(String classId, String userId) {
        this.classId = classId;
        this.userId = userId;
    }

    public String getId() { return id; }
    public String getClassId() { return classId; }
    public String getUserId() { return userId; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
}
