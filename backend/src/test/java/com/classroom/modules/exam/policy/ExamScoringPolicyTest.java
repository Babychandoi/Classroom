package com.classroom.modules.exam.policy;

import com.classroom.modules.exam.model.AttemptAnswer;
import com.classroom.modules.exam.model.ExamAttempt;
import com.classroom.modules.exam.model.Question;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ExamScoringPolicyTest {

    private final ExamScoringPolicy scoringPolicy = new ExamScoringPolicy();

    @Test
    @DisplayName("UT-05: Auto-grades multiple choice questions accurately and computes percentage score")
    void testAutoGradingMultipleChoice() {
        ExamAttempt attempt = new ExamAttempt("exam-1", "user-1", "class-1", Instant.now().plusSeconds(3600), false);

        Question q1 = new Question("exam-1", "2 + 2 = ?", "MULTIPLE_CHOICE", 10, 1, "B");
        q1.setId("q-1");

        Question q2 = new Question("exam-1", "Capital of France?", "MULTIPLE_CHOICE", 10, 2, "A");
        q2.setId("q-2");

        List<Question> questions = List.of(q1, q2);

        // Student answered q1 correctly ('B'), q2 incorrectly ('C')
        AttemptAnswer a1 = new AttemptAnswer(attempt.getId(), "q-1", "B");
        AttemptAnswer a2 = new AttemptAnswer(attempt.getId(), "q-2", "C");
        List<AttemptAnswer> answers = List.of(a1, a2);

        scoringPolicy.autoGradeAttempt(attempt, questions, answers);

        assertEquals(new BigDecimal("10"), a1.getPointsAwarded());
        assertEquals(BigDecimal.ZERO, a2.getPointsAwarded());
        assertEquals(20, attempt.getTotalPoints());
        assertEquals(new BigDecimal("50.00"), attempt.getScore());
        assertEquals("PUBLISHED", attempt.getStatus());
    }

    @Test
    @DisplayName("Essay question leaves attempt in GRADING status until teacher grades it")
    void testEssayPendingStatus() {
        ExamAttempt attempt = new ExamAttempt("exam-1", "user-1", "class-1", Instant.now().plusSeconds(3600), false);

        Question q1 = new Question("exam-1", "Write essay about math", "ESSAY", 20, 1, null);
        q1.setId("q-essay");

        AttemptAnswer a1 = new AttemptAnswer(attempt.getId(), "q-essay", "My essay content...");

        scoringPolicy.autoGradeAttempt(attempt, List.of(q1), List.of(a1));

        assertEquals("GRADING", attempt.getStatus());
        assertNull(a1.getPointsAwarded());
    }

    @Test
    @DisplayName("Finding 2: Essay score exceeding question points throws AppException")
    void testEssayScoreExceedingMaxThrowsException() {
        ExamAttempt attempt = new ExamAttempt("exam-1", "user-1", "class-1", Instant.now().plusSeconds(3600), false);

        Question q1 = new Question("exam-1", "Essay question", "ESSAY", 15, 1, null);
        q1.setId("q-essay");

        AttemptAnswer a1 = new AttemptAnswer(attempt.getId(), "q-essay", "Answer");
        a1.setPointsAwarded(new BigDecimal("20")); // Max is 15

        assertThrows(com.classroom.common.AppException.class, () ->
                scoringPolicy.autoGradeAttempt(attempt, List.of(q1), List.of(a1)));
    }

    @Test
    @DisplayName("Finding 2: Negative essay score throws AppException")
    void testNegativeEssayScoreThrowsException() {
        ExamAttempt attempt = new ExamAttempt("exam-1", "user-1", "class-1", Instant.now().plusSeconds(3600), false);

        Question q1 = new Question("exam-1", "Essay question", "ESSAY", 15, 1, null);
        q1.setId("q-essay");

        AttemptAnswer a1 = new AttemptAnswer(attempt.getId(), "q-essay", "Answer");
        a1.setPointsAwarded(new BigDecimal("-5"));

        assertThrows(com.classroom.common.AppException.class, () ->
                scoringPolicy.autoGradeAttempt(attempt, List.of(q1), List.of(a1)));
    }

    @Test
    @DisplayName("Finding 2: Valid essay score transitions attempt to PUBLISHED with correct total")
    void testValidEssayScorePublishesAttempt() {
        ExamAttempt attempt = new ExamAttempt("exam-1", "user-1", "class-1", Instant.now().plusSeconds(3600), false);

        Question q1 = new Question("exam-1", "Essay question", "ESSAY", 20, 1, null);
        q1.setId("q-essay");

        AttemptAnswer a1 = new AttemptAnswer(attempt.getId(), "q-essay", "Answer");
        a1.setPointsAwarded(new BigDecimal("18"));

        scoringPolicy.autoGradeAttempt(attempt, List.of(q1), List.of(a1));

        assertEquals("PUBLISHED", attempt.getStatus());
        assertEquals(new BigDecimal("90.00"), attempt.getScore());
    }
}
