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

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Database-backed regression test for concurrent exam publication (review finding 2).
 *
 * <p>Two exams publish for the same learner at the same time. Each publication runs in its own
 * transaction and queues a leaderboard recalculation. The learner's leaderboard total must end up
 * covering both exams; an inline recalculation inside each publishing transaction cannot see the
 * other's uncommitted attempt and would persist only one exam's reward.</p>
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("integration")
public class LeaderboardConcurrentPublicationIntegrationTest {

    private static final int REWARD_EXAM_ONE = 12;
    private static final int REWARD_EXAM_TWO = 7;

    @Autowired
    private LeaderboardService leaderboardService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String ownerId = "lb-owner-";
    private String userId;
    private String classId;
    private String examOneId;
    private String examTwoId;
    private String attemptOneId;
    private String attemptTwoId;

    @BeforeEach
    void seed() {
        userId = "lb-user-" + suffix;
        classId = "lb-class-" + suffix;
        examOneId = "lb-exam1-" + suffix;
        examTwoId = "lb-exam2-" + suffix;
        attemptOneId = "lb-att1-" + suffix;
        attemptTwoId = "lb-att2-" + suffix;

        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, 'x', 'LB Owner')",
                ownerId + suffix, "lb-owner-" + suffix + "@test.local");
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, 'x', 'LB Learner')",
                userId, "lb-user-" + suffix + "@test.local");
        jdbcTemplate.update(
                "INSERT INTO classrooms (id, owner_id, slug, title) VALUES (?, ?, ?, 'LB Class')",
                classId, ownerId + suffix, "lb-class-" + suffix);

        insertExam(examOneId, "LB Exam 1");
        insertExam(examTwoId, "LB Exam 2");

        // Reward rules: exam 1 awards 12 points from score 50, exam 2 awards 7 points from score 50.
        insertRewardRule(examOneId, REWARD_EXAM_ONE);
        insertRewardRule(examTwoId, REWARD_EXAM_TWO);

        insertSubmittedAttempt(attemptOneId, examOneId);
        insertSubmittedAttempt(attemptTwoId, examTwoId);
    }

    @AfterEach
    void cleanup() {
        // Keep financial and exam history protected by production RESTRICT constraints;
        // remove only this test's disposable fixture in dependency order.
        jdbcTemplate.update("DELETE FROM leaderboard_recalc_jobs WHERE class_id = ?", classId);
        jdbcTemplate.update("DELETE FROM leaderboard_entries WHERE class_id = ?", classId);
        jdbcTemplate.update("DELETE FROM exam_reward_rules WHERE class_id = ?", classId);
        jdbcTemplate.update("DELETE FROM exam_attempts WHERE class_id = ?", classId);
        jdbcTemplate.update("DELETE FROM exams WHERE class_id = ?", classId);
        jdbcTemplate.update("DELETE FROM classrooms WHERE id = ?", classId);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", userId, ownerId + suffix);
    }

    private void insertExam(String examId, String title) {
        jdbcTemplate.update(
                "INSERT INTO exams (id, class_id, title, duration_minutes, attempt_limit, audience_scope, status, pass_score) "
                        + "VALUES (?, ?, ?, 30, 1, 'ALL', 'PUBLISHED', 50)",
                examId, classId, title);
    }

    private void insertRewardRule(String examId, int points) {
        jdbcTemplate.update(
                "INSERT INTO exam_reward_rules (id, class_id, exam_id, min_exam_score, reward_points) VALUES (?, ?, ?, 50.00, ?)",
                UUID.randomUUID().toString(), classId, examId, points);
    }

    private void insertSubmittedAttempt(String attemptId, String examId) {
        jdbcTemplate.update(
                "INSERT INTO exam_attempts (id, exam_id, user_id, class_id, started_at, submitted_at, ends_at, score, total_points, status, is_preview, attempt_number) "
                        + "VALUES (?, ?, ?, ?, NOW(), NOW(), NOW(), 90.00, 100, 'SUBMITTED', FALSE, 1)",
                attemptId, examId, userId, classId);
    }

    @Test
    @DisplayName("Concurrent publication of two exams keeps the learner's leaderboard total complete")
    void concurrentPublicationsKeepLeaderboardTotalComplete() throws Exception {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        // Both publishing transactions must still be open (attempt rows uncommitted) when the other
        // one reaches its commit — this is exactly the interleaving that lost points before.
        CyclicBarrier barrier = new CyclicBarrier(2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> publish(txTemplate, barrier, attemptOneId));
            Future<?> second = pool.submit(() -> publish(txTemplate, barrier, attemptTwoId));
            first.get(60, TimeUnit.SECONDS);
            second.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }

        Integer total = jdbcTemplate.queryForObject(
                "SELECT total_points FROM leaderboard_entries WHERE class_id = ? AND user_id = ?",
                Integer.class, classId, userId);

        assertEquals(REWARD_EXAM_ONE + REWARD_EXAM_TWO, total,
                "Leaderboard total must include the reward of both concurrently published exams");
    }

    private void publish(TransactionTemplate txTemplate, CyclicBarrier barrier, String attemptId) {
        txTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    "UPDATE exam_attempts SET status = 'PUBLISHED', submitted_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(Instant.now()), attemptId);
            leaderboardService.scheduleRecalculation(classId, userId);
            try {
                barrier.await(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("Barrier failed", e);
            }
        });
    }
}
