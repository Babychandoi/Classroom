package com.classroom.integration;

import com.classroom.common.AppException;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R20-06 on a real MySQL: starting an attempt no longer takes the exam row FOR UPDATE (which serialised 200 simultaneous starts),
 * yet the per-learner guarantees must stay exact, and autosave must never leave two answer rows for one question.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("integration")
public class ExamStartAndAnswerConcurrencyIntegrationTest {

    @Autowired private ExamService examService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private String ownerId;
    private String classId;
    private String examId;
    private final List<String> questionIds = new ArrayList<>();
    private final List<String> learnerIds = new ArrayList<>();

    @BeforeEach
    void seed() {
        ownerId = "st-owner-" + suffix;
        classId = "st-class-" + suffix;
        examId = "st-exam-" + suffix;
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, 'x', 'Start Owner')",
                ownerId, ownerId + "@test.local");
        jdbcTemplate.update("INSERT INTO classrooms (id, owner_id, slug, title) VALUES (?, ?, ?, 'Start Class')",
                classId, ownerId, classId);
        // attempt_limit = 1 by default; individual tests raise it
        jdbcTemplate.update("INSERT INTO exams (id, class_id, title, duration_minutes, attempt_limit, audience_scope, status, pass_score) "
                + "VALUES (?, ?, 'Start Exam', 30, 1, 'ALL', 'PUBLISHED', 50)", examId, classId);
        for (int i = 0; i < 3; i++) {
            String qid = "st-q" + i + "-" + suffix;
            questionIds.add(qid);
            jdbcTemplate.update("INSERT INTO questions (id, exam_id, question_text, type, points, position, answer_key) "
                    + "VALUES (?, ?, ?, 'MULTIPLE_CHOICE', 10, ?, 'A')", qid, examId, "Cau " + i, i + 1);
            for (String key : List.of("A", "B")) {
                jdbcTemplate.update("INSERT INTO answer_options (id, question_id, option_key, option_text, position) VALUES (?, ?, ?, ?, ?)",
                        UUID.randomUUID().toString(), qid, key, "Lua chon " + key, "A".equals(key) ? 1 : 2);
            }
        }
    }

    private String newLearner(String tag) {
        String id = "st-user-" + tag + "-" + suffix;
        learnerIds.add(id);
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, 'x', ?)", id, id + "@test.local", "Learner " + tag);
        jdbcTemplate.update("INSERT INTO class_members (id, class_id, user_id, state, role) VALUES (?, ?, ?, 'ACTIVE', 'STUDENT')",
                UUID.randomUUID().toString(), classId, id);
        return id;
    }

    @AfterEach
    void cleanup() {
        Eventually.await(30_000, () -> jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM leaderboard_recalc_jobs WHERE class_id = ?", Integer.class, classId) == 0);
        // Leave no PENDING outbox rows behind: other integration tests inspect the head of the global pending queue.
        jdbcTemplate.update("DELETE FROM outbox_events WHERE aggregate_type = 'EXAM' AND aggregate_id IN (SELECT id FROM exam_attempts WHERE exam_id = ?)", examId);
        jdbcTemplate.update("DELETE FROM attempt_answers WHERE attempt_id IN (SELECT id FROM exam_attempts WHERE exam_id = ?)", examId);
        jdbcTemplate.update("DELETE FROM leaderboard_recalc_jobs WHERE class_id = ?", classId);
        jdbcTemplate.update("DELETE FROM leaderboard_entries WHERE class_id = ?", classId);
        jdbcTemplate.update("DELETE FROM exam_attempts WHERE exam_id = ?", examId);
        jdbcTemplate.update("DELETE FROM exam_user_locks WHERE exam_id = ?", examId);
        jdbcTemplate.update("DELETE FROM exam_reward_rules WHERE class_id = ?", classId);
        for (String q : questionIds) jdbcTemplate.update("DELETE FROM answer_options WHERE question_id = ?", q);
        jdbcTemplate.update("DELETE FROM questions WHERE exam_id = ?", examId);
        jdbcTemplate.update("DELETE FROM exams WHERE class_id = ?", classId);
        jdbcTemplate.update("DELETE FROM class_members WHERE class_id = ?", classId);
        jdbcTemplate.update("DELETE FROM classrooms WHERE id = ?", classId);
        for (String u : learnerIds) jdbcTemplate.update("DELETE FROM users WHERE id = ?", u);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", ownerId);
    }

    /** Runs {@code n} tasks at the same instant and returns their outcomes (value or exception). */
    private <T> List<Object> race(int n, java.util.function.IntFunction<Callable<T>> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Callable<T> work = task.apply(i);
            futures.add(pool.submit(() -> {
                go.await();
                try {
                    return work.call();
                } catch (Exception e) {
                    return e;
                }
            }));
        }
        go.countDown();
        List<Object> out = new ArrayList<>();
        for (Future<Object> f : futures) out.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdownNow();
        return out;
    }

    @Test
    @DisplayName("8 simultaneous starts by the SAME learner (double click, two tabs) create exactly one attempt and all get it back")
    void sameLearnerRacingStartsCreateOneAttempt() throws Exception {
        String learner = newLearner("dbl");
        List<Object> results = race(8, i -> () -> examService.startAttempt(examId, learner, false));

        Set<String> ids = new HashSet<>();
        for (Object r : results) {
            assertTrue(r instanceof ExamAttemptDto, "every racing start must succeed (resume the running attempt), got " + r);
            ids.add(((ExamAttemptDto) r).getId());
        }
        assertEquals(1, ids.size(), "all requests must see the same attempt");
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM exam_attempts WHERE exam_id = ? AND user_id = ?",
                Integer.class, examId, learner));
    }

    @Test
    @DisplayName("attempt limit stays exact: with the limit reached, 8 simultaneous starts are all refused and no attempt is added")
    void attemptLimitIsNotExceededByRacingStarts() throws Exception {
        String learner = newLearner("limit");
        ExamAttemptDto first = examService.startAttempt(examId, learner, false);
        SubmitAttemptRequest submit = new SubmitAttemptRequest();
        submit.setAnswers(Map.of(questionIds.get(0), "A"));
        examService.submitAttempt(first.getId(), learner, submit);

        List<Object> results = race(8, i -> () -> examService.startAttempt(examId, learner, false));

        for (Object r : results) {
            assertTrue(r instanceof AppException, "limit reached: a new attempt must be refused, got " + r);
        }
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM exam_attempts WHERE exam_id = ? AND user_id = ?",
                Integer.class, examId, learner));
    }

    @Test
    @DisplayName("limit 3 and a cancelled first try: racing starts never exceed the limit and never reuse an attempt number")
    void limitAndAttemptNumbersSurviveRaces() throws Exception {
        jdbcTemplate.update("UPDATE exams SET attempt_limit = 3 WHERE id = ?", examId);
        String learner = newLearner("num");
        ExamAttemptDto first = examService.startAttempt(examId, learner, false);
        jdbcTemplate.update("UPDATE exam_attempts SET status = 'CANCELLED' WHERE id = ?", first.getId());

        List<Object> results = race(8, i -> () -> examService.startAttempt(examId, learner, false));

        long ok = results.stream().filter(r -> r instanceof ExamAttemptDto).count();
        assertEquals(8, ok, "everyone resumes or is served by the single new attempt");
        List<Integer> numbers = jdbcTemplate.queryForList(
                "SELECT attempt_number FROM exam_attempts WHERE exam_id = ? AND user_id = ? ORDER BY attempt_number", Integer.class, examId, learner);
        assertEquals(List.of(1, 2), numbers, "the cancelled try keeps number 1, the racing starts produce exactly one number 2");
    }

    @Test
    @DisplayName("40 DIFFERENT learners start at the same time: nobody waits on anybody else's lock and everyone gets exactly one attempt")
    void differentLearnersStartInParallel() throws Exception {
        List<String> learners = new ArrayList<>();
        for (int i = 0; i < 40; i++) learners.add(newLearner("p" + i));

        long started = System.nanoTime();
        List<Object> results = race(40, i -> () -> examService.startAttempt(examId, learners.get(i), false));
        long wallMs = (System.nanoTime() - started) / 1_000_000;

        for (Object r : results) assertTrue(r instanceof ExamAttemptDto, "start must succeed, got " + r);
        assertEquals(40, jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT user_id) FROM exam_attempts WHERE exam_id = ?", Integer.class, examId));
        assertEquals(40, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM exam_attempts WHERE exam_id = ?", Integer.class, examId));
        assertTrue(wallMs < 20_000, "40 parallel starts took " + wallMs + " ms");
    }

    @Test
    @DisplayName("autosave races (overlapping question sets) and the submit never create two rows for one question; nothing is lost")
    void answerUpsertsNeverDuplicate() throws Exception {
        String learner = newLearner("ans");
        String attemptId = examService.startAttempt(examId, learner, false).getId();

        // 12 concurrent autosaves, each carrying a different (overlapping) subset with its own values
        List<Object> saves = race(12, i -> () -> {
            Map<String, String> answers = new HashMap<>();
            answers.put(questionIds.get(i % 3), i % 2 == 0 ? "A" : "B");
            answers.put(questionIds.get((i + 1) % 3), "A");
            return examService.saveAnswers(attemptId, learner, answers);
        });
        for (Object r : saves) assertTrue(r instanceof ExamAttemptDto, "autosave must succeed, got " + r);

        // the submit carries the final full map and must win
        SubmitAttemptRequest submit = new SubmitAttemptRequest();
        submit.setAnswers(Map.of(questionIds.get(0), "A", questionIds.get(1), "A", questionIds.get(2), "B"));
        ExamAttemptDto submitted = examService.submitAttempt(attemptId, learner, submit);
        assertEquals(3, submitted.getAnswers().size());

        List<Map<String, Object>> duplicates = jdbcTemplate.queryForList(
                "SELECT question_id, COUNT(*) c FROM attempt_answers WHERE attempt_id = ? GROUP BY question_id HAVING c > 1", attemptId);
        assertTrue(duplicates.isEmpty(), "duplicate answer rows: " + duplicates);
        Map<String, String> stored = new HashMap<>();
        jdbcTemplate.query("SELECT question_id, student_answer FROM attempt_answers WHERE attempt_id = ?",
                rs -> { stored.put(rs.getString(1), rs.getString(2)); }, attemptId);
        assertEquals(Map.of(questionIds.get(0), "A", questionIds.get(1), "A", questionIds.get(2), "B"), stored);
    }

    @Test
    @DisplayName("V32: the unique key exists, and a second row for the same (attempt, question) is rejected by the database")
    void uniqueKeyRejectsDuplicateAnswerRows() {
        Integer keys = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() "
                        + "AND table_name = 'attempt_answers' AND index_name = 'uq_aa_attempt_question' AND non_unique = 0", Integer.class);
        assertEquals(2, keys, "uq_aa_attempt_question must be a unique index over (attempt_id, question_id)");
    }

    @Test
    @DisplayName("V32 de-duplication keeps the graded row, otherwise the highest id (script run on a key-less scratch copy)")
    void migrationKeepsGradedRowThenHighestId() throws Exception {
        String script = new String(new ClassPathResource("db/migration/V32__attempt_answers_unique_key.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (String line : script.split("\\R")) {
            if (!line.trim().startsWith("--")) sb.append(line).append('\n');
        }
        String withoutComments = sb.toString();
        String deleteStatement = withoutComments.substring(withoutComments.indexOf("DELETE aa"), withoutComments.indexOf("SET @ddl")).trim();
        deleteStatement = deleteStatement.substring(0, deleteStatement.lastIndexOf(';'));

        String probe = "attempt_answers_v32_probe";
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + probe);
        try {
            // no keys, no foreign keys: duplicates are insertable, exactly like the pre-V32 table
            jdbcTemplate.execute("CREATE TABLE " + probe + " AS SELECT * FROM attempt_answers WHERE 1 = 0");
            insertProbe(probe, "a-1", "att", "q1", "plain-low", null, null);
            insertProbe(probe, "a-9", "att", "q1", "plain-high", null, null);               // duplicate pair: highest id wins ...
            insertProbe(probe, "a-5", "att", "q2", "plain", null, null);
            insertProbe(probe, "a-6", "att", "q2", "graded-low-id", "grader", "7.50");        // ... unless one row was graded
            insertProbe(probe, "a-3", "att", "q3", "only-one", null, null);                   // untouched
            insertProbe(probe, "a-2", "att2", "q1", "other-attempt", null, null);             // same question, other attempt: untouched

            jdbcTemplate.execute(deleteStatement.replace("attempt_answers", probe));

            Map<String, String> survivors = new HashMap<>();
            jdbcTemplate.query("SELECT id, student_answer FROM " + probe, rs -> { survivors.put(rs.getString(1), rs.getString(2)); });
            assertEquals(Map.of("a-9", "plain-high", "a-6", "graded-low-id", "a-3", "only-one", "a-2", "other-attempt"), survivors);
        } finally {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + probe);
        }
    }

    private void insertProbe(String table, String id, String attempt, String question, String answer, String gradedBy, String points) {
        jdbcTemplate.update("INSERT INTO " + table + " (id, attempt_id, question_id, student_answer, graded_by, points_awarded) VALUES (?, ?, ?, ?, ?, ?)",
                id, attempt, question, answer, gradedBy, points);
    }
}
