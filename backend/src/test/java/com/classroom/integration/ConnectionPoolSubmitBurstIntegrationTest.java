package com.classroom.integration;

import com.classroom.modules.exam.dto.ExamAttemptDto;
import com.classroom.modules.exam.dto.SubmitAttemptRequest;
import com.classroom.modules.exam.service.ExamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R20-01 regression on a REAL MySQL with a deliberately tiny connection pool.
 *
 * <p>Production incident (load test): 200 students pressing "Nộp bài" together froze the whole backend for 30-60 s and ~95% of
 * the submits failed with 500. Root cause: the leaderboard recalculation ran inside the request's {@code afterCommit} hook while
 * Spring still held the request's connection, and opened a {@code REQUIRES_NEW} transaction - and a second one for the
 * leaderboard row - so every submitting request held one pooled connection while waiting for another: with N concurrent submits
 * and a 10-connection pool, a pool deadlock until the connection timeout.</p>
 *
 * <p>This test uses {@code maximumPoolSize=3} (a third of what the incident needed to deadlock at 4-10 submitters) and 12
 * concurrent submitters, each with their own attempt. With the old code the first three submits would each hold a connection
 * and wait for a nested one, the pool would be exhausted and every submit would fail after the 5 s connection timeout. Now
 * the after-commit hook only hands off to a small worker pool, so all 12 submits succeed within a few seconds, no request ever
 * waits on the pool timeout, a concurrent database probe keeps working, and the leaderboard totals converge (eventual
 * consistency, as before).</p>
 */
@Tag("integration")
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.datasource.hikari.connection-timeout=5000",
        "classroom.leaderboard.recalc.workers=2"
})
@ActiveProfiles("integration")
public class ConnectionPoolSubmitBurstIntegrationTest {

    private static final int SUBMITTERS = 12;
    private static final int REWARD_POINTS = 20;

    @Autowired private ExamService examService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private String ownerId;
    private String classId;
    private String examId;
    private String questionId;
    private final List<String> userIds = new ArrayList<>();
    private final List<String> attemptIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        ownerId = "pool-owner-" + suffix;
        classId = "pool-class-" + suffix;
        examId = "pool-exam-" + suffix;
        questionId = "pool-q-" + suffix;
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, 'x', 'Pool Owner')",
                ownerId, ownerId + "@test.local");
        jdbcTemplate.update("INSERT INTO classrooms (id, owner_id, slug, title) VALUES (?, ?, ?, 'Pool Class')",
                classId, ownerId, classId);
        jdbcTemplate.update("INSERT INTO exams (id, class_id, title, duration_minutes, attempt_limit, audience_scope, status, pass_score) "
                + "VALUES (?, ?, 'Pool Exam', 30, 1, 'ALL', 'PUBLISHED', 50)", examId, classId);
        jdbcTemplate.update("INSERT INTO questions (id, exam_id, question_text, type, points, position, answer_key) "
                + "VALUES (?, ?, 'Chon A', 'MULTIPLE_CHOICE', 10, 1, 'A')", questionId, examId);
        for (String key : List.of("A", "B")) {
            jdbcTemplate.update("INSERT INTO answer_options (id, question_id, option_key, option_text, position) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID().toString(), questionId, key, "Lua chon " + key, "A".equals(key) ? 1 : 2);
        }
        jdbcTemplate.update("INSERT INTO exam_reward_rules (id, class_id, exam_id, min_exam_score, reward_points) VALUES (?, ?, ?, 50.00, ?)",
                UUID.randomUUID().toString(), classId, examId, REWARD_POINTS);

        for (int i = 0; i < SUBMITTERS; i++) {
            String userId = "pool-user-" + i + "-" + suffix;
            userIds.add(userId);
            jdbcTemplate.update("INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, 'x', ?)",
                    userId, userId + "@test.local", "Pool Learner " + i);
            jdbcTemplate.update("INSERT INTO class_members (id, class_id, user_id, state, role) VALUES (?, ?, ?, 'ACTIVE', 'STUDENT')",
                    UUID.randomUUID().toString(), classId, userId);
        }
        // Every learner starts their own attempt through the real service (sequential: the burst under test is the submit).
        for (String userId : userIds) {
            ExamAttemptDto attempt = examService.startAttempt(examId, userId, false);
            attemptIds.add(attempt.getId());
        }
    }

    @AfterEach
    void cleanup() {
        // The recalculation workers may still be running: wait until nothing is outstanding before deleting their rows.
        Eventually.await(30_000, () -> jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM leaderboard_recalc_jobs WHERE class_id = ?", Integer.class, classId) == 0);
        // Leave no PENDING outbox rows behind: other integration tests inspect the head of the global pending queue.
        jdbcTemplate.update("DELETE FROM outbox_events WHERE aggregate_type = 'EXAM' AND aggregate_id IN (SELECT id FROM exam_attempts WHERE class_id = ?)", classId);
        for (String attemptId : attemptIds) {
            jdbcTemplate.update("DELETE FROM attempt_answers WHERE attempt_id = ?", attemptId);
        }
        jdbcTemplate.update("DELETE FROM leaderboard_recalc_jobs WHERE class_id = ?", classId);
        jdbcTemplate.update("DELETE FROM leaderboard_entries WHERE class_id = ?", classId);
        jdbcTemplate.update("DELETE FROM exam_attempts WHERE class_id = ?", classId);
        jdbcTemplate.update("DELETE FROM exam_user_locks WHERE exam_id = ?", examId);
        jdbcTemplate.update("DELETE FROM exam_reward_rules WHERE class_id = ?", classId);
        jdbcTemplate.update("DELETE FROM answer_options WHERE question_id = ?", questionId);
        jdbcTemplate.update("DELETE FROM questions WHERE exam_id = ?", examId);
        jdbcTemplate.update("DELETE FROM exams WHERE class_id = ?", classId);
        jdbcTemplate.update("DELETE FROM class_members WHERE class_id = ?", classId);
        jdbcTemplate.update("DELETE FROM classrooms WHERE id = ?", classId);
        for (String userId : userIds) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", ownerId);
    }

    @Test
    @DisplayName("12 simultaneous submits on a 3-connection pool all succeed quickly, the pool never times out, the leaderboard converges")
    void simultaneousSubmitsDoNotExhaustTheConnectionPool() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(SUBMITTERS + 1);
        CountDownLatch go = new CountDownLatch(1);

        // A database probe on its own thread during the burst (what /health/readiness does): it must keep getting connections.
        AtomicBoolean burstDone = new AtomicBoolean(false);
        AtomicLong slowestProbeMs = new AtomicLong();
        AtomicLong probeFailures = new AtomicLong();
        Future<?> probe = pool.submit(() -> {
            try {
                go.await();
            } catch (InterruptedException e) {
                return;
            }
            while (!burstDone.get()) {
                long t0 = System.nanoTime();
                try {
                    jdbcTemplate.queryForObject("SELECT 1", Integer.class);
                } catch (RuntimeException e) {
                    probeFailures.incrementAndGet();
                }
                slowestProbeMs.accumulateAndGet((System.nanoTime() - t0) / 1_000_000, Math::max);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });

        List<Future<ExamAttemptDto>> submits = new ArrayList<>();
        for (int i = 0; i < SUBMITTERS; i++) {
            final int index = i;
            Callable<ExamAttemptDto> submit = () -> {
                go.await();
                SubmitAttemptRequest request = new SubmitAttemptRequest();
                // even learners answer correctly (A), odd ones wrongly (B)
                request.setAnswers(Map.of(questionId, index % 2 == 0 ? "A" : "B"));
                return examService.submitAttempt(attemptIds.get(index), userIds.get(index), request);
            };
            submits.add(pool.submit(submit));
        }

        long started = System.nanoTime();
        go.countDown();
        List<ExamAttemptDto> results = new ArrayList<>();
        for (Future<ExamAttemptDto> f : submits) {
            results.add(f.get(60, TimeUnit.SECONDS)); // an exception here would be the pool-timeout 500 of the incident
        }
        long wallMs = (System.nanoTime() - started) / 1_000_000;
        burstDone.set(true);
        probe.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        for (ExamAttemptDto dto : results) {
            assertEquals("PUBLISHED", dto.getStatus());
        }
        // 3 connections, 12 short transactions: seconds at most. The connection timeout is 5 s, so a request that had to wait on
        // an exhausted pool would already have failed above; a deadlock would take 5 s per request.
        assertTrue(wallMs < 15_000, "12 concurrent submits took " + wallMs + " ms");
        assertEquals(0, probeFailures.get(), "the database probe must keep getting connections during the burst");
        assertTrue(slowestProbeMs.get() < 5_000, "slowest probe " + slowestProbeMs.get() + " ms");

        // Eventual consistency: every learner's leaderboard total converges to its reward, all jobs are serviced.
        boolean converged = Eventually.await(30_000, () -> {
            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM leaderboard_entries WHERE class_id = ?", Integer.class, classId);
            Integer wrong = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM leaderboard_entries e WHERE e.class_id = ? AND e.total_points <> "
                            + "(CASE WHEN CAST(SUBSTRING_INDEX(SUBSTRING_INDEX(e.user_id, '-', 3), '-', -1) AS UNSIGNED) % 2 = 0 THEN ? ELSE 0 END)",
                    Integer.class, classId, REWARD_POINTS);
            Integer jobs = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM leaderboard_recalc_jobs WHERE class_id = ?", Integer.class, classId);
            return rows != null && rows == SUBMITTERS && wrong != null && wrong == 0 && jobs != null && jobs == 0;
        });
        assertTrue(converged, "leaderboard totals must converge shortly after the burst");
        assertFalse(userIds.isEmpty());
    }
}
