package com.classroom.integration;

import com.classroom.modules.ranking.service.LeaderboardService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Database-backed regression test for the leaderboard recalculation job's arming window.
 *
 * <p>The job used to be written in its own transaction, before the publishing transaction
 * committed. The sweeper could therefore service that job against the still-old committed score
 * and delete it; if the publisher then died before its post-commit recalculation ran, nothing was
 * left to fold the published score into the total and it stayed missing until someone rebuilt the
 * board by hand. These tests pin both halves of the fix: the job is invisible until the score
 * commits, and once committed the sweeper alone is enough to repair the total.</p>
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("integration")
public class LeaderboardJobArmingIntegrationTest {

    private static final int REWARD_POINTS = 21;

    @Autowired
    private LeaderboardService leaderboardService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private String ownerId;
    private String userId;
    private String classId;
    private String examId;
    private String attemptId;

    @BeforeEach
    void seed() {
        ownerId = "arm-owner-" + suffix;
        userId = "arm-user-" + suffix;
        classId = "arm-class-" + suffix;
        examId = "arm-exam-" + suffix;
        attemptId = "arm-att-" + suffix;

        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, 'x', 'Arm Owner')",
                ownerId, ownerId + "@test.local");
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, 'x', 'Arm Learner')",
                userId, userId + "@test.local");
        jdbcTemplate.update("INSERT INTO classrooms (id, owner_id, slug, title) VALUES (?, ?, ?, 'Arm Class')",
                classId, ownerId, classId);
        jdbcTemplate.update("INSERT INTO exams (id, class_id, title, duration_minutes, attempt_limit, audience_scope, status, pass_score) "
                + "VALUES (?, ?, 'Arm Exam', 30, 1, 'ALL', 'PUBLISHED', 50)", examId, classId);
        jdbcTemplate.update("INSERT INTO exam_reward_rules (id, class_id, exam_id, min_exam_score, reward_points) VALUES (?, ?, ?, 50.00, ?)",
                UUID.randomUUID().toString(), classId, examId, REWARD_POINTS);
        jdbcTemplate.update("INSERT INTO exam_attempts (id, exam_id, user_id, class_id, started_at, submitted_at, ends_at, score, total_points, status, is_preview, attempt_number) "
                + "VALUES (?, ?, ?, ?, NOW(), NOW(), NOW(), 90.00, 100, 'SUBMITTED', FALSE, 1)", attemptId, examId, userId, classId);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM leaderboard_recalc_jobs WHERE class_id = ?", classId);
        jdbcTemplate.update("DELETE FROM leaderboard_entries WHERE class_id = ?", classId);
        jdbcTemplate.update("DELETE FROM exam_reward_rules WHERE class_id = ?", classId);
        jdbcTemplate.update("DELETE FROM exam_attempts WHERE class_id = ?", classId);
        jdbcTemplate.update("DELETE FROM exams WHERE class_id = ?", classId);
        jdbcTemplate.update("DELETE FROM classrooms WHERE id = ?", classId);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", userId, ownerId);
    }

    @Test
    @DisplayName("The recalculation job is invisible to other connections until the publishing transaction commits")
    void testJobIsNotVisibleBeforeTheScoreCommits() {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        Integer jobsSeenByAnotherConnection = txTemplate.execute(status -> {
            jdbcTemplate.update("UPDATE exam_attempts SET status = 'PUBLISHED' WHERE id = ?", attemptId);
            leaderboardService.scheduleRecalculation(classId, userId);

            // A sweeper on another connection must not be able to see this job yet: servicing it
            // now would recalculate against the still-uncommitted score and then delete it.
            return onAnotherConnection(() -> jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM leaderboard_recalc_jobs WHERE class_id = ? AND user_id = ?",
                    Integer.class, classId, userId));
        });

        assertEquals(0, jobsSeenByAnotherConnection,
                "The job must become visible with the score, never before it");

        // After the commit the post-commit recalculation has serviced and cleared it.
        Integer jobsAfterCommit = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM leaderboard_recalc_jobs WHERE class_id = ? AND user_id = ?",
                Integer.class, classId, userId);
        assertEquals(0, jobsAfterCommit, "A serviced job must be cleared");

        Integer total = jdbcTemplate.queryForObject(
                "SELECT total_points FROM leaderboard_entries WHERE class_id = ? AND user_id = ?",
                Integer.class, classId, userId);
        assertEquals(REWARD_POINTS, total);
    }

    @Test
    @DisplayName("The sweeper alone repairs the total when the publisher dies before its post-commit recalculation")
    void testSweeperRepairsTotalWhenPostCommitRecalculationNeverRan() {
        // Commit the published score together with its job row exactly as a publishing transaction
        // does, but never run the post-commit recalculation: this is the crash window.
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("UPDATE exam_attempts SET status = 'PUBLISHED' WHERE id = ?", attemptId);
            jdbcTemplate.update("INSERT INTO leaderboard_recalc_jobs (id, class_id, user_id) VALUES (?, ?, ?)",
                    UUID.randomUUID().toString(), classId, userId);
        });

        leaderboardService.sweepPendingRecalculations();

        Integer total = jdbcTemplate.queryForObject(
                "SELECT total_points FROM leaderboard_entries WHERE class_id = ? AND user_id = ?",
                Integer.class, classId, userId);
        assertEquals(REWARD_POINTS, total,
                "The surviving job must let the sweeper fold the published score into the total");

        Integer remainingJobs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM leaderboard_recalc_jobs WHERE class_id = ? AND user_id = ?",
                Integer.class, classId, userId);
        assertEquals(0, remainingJobs, "A serviced job must be cleared");
    }

    /** Runs a query on a thread with no bound transaction, so it uses a separate connection. */
    private <T> T onAnotherConnection(Callable<T> work) {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            return pool.submit(work).get(30, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException("Query on a second connection failed", ex);
        } finally {
            pool.shutdownNow();
        }
    }
}
