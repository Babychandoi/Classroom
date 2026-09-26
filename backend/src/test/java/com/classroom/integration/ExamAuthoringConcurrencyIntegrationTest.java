package com.classroom.integration;

import com.classroom.common.AppException;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.exam.model.Exam;
import com.classroom.modules.exam.model.Question;
import com.classroom.modules.exam.repository.ExamRepository;
import com.classroom.modules.exam.repository.QuestionRepository;
import com.classroom.modules.exam.service.ExamService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@SpringBootTest
@ActiveProfiles("integration")
class ExamAuthoringConcurrencyIntegrationTest {
    @Autowired private ClassroomRepository classroomRepository;
    @Autowired private ExamRepository examRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private ExamService examService;

    @Test
    @DisplayName("Concurrent question additions serialize total-points validation with publication")
    void concurrentAuthoringCannotChangePublishedExamOrExceedPointLimit() throws Exception {
        var classroom = classroomRepository.findBySlug("lop-toan-nang-cao").orElseThrow();
        String ownerId = classroom.getOwnerId();
        Exam exam = new Exam();
        exam.setTitle("Authoring race " + System.nanoTime());
        exam.setAudienceScope("ALL");
        exam.setDurationMinutes(30);
        exam = examService.createExam(classroom.getId(), exam, ownerId);
        final String examId = exam.getId();

        int workers = 3;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        // First add a valid question so the publication request can succeed if it obtains the lock
        // after this question is committed.
        examService.addQuestion(examId, essayQuestion(1, "Initial"), List.of(), ownerId);
        results.add(executor.submit(() -> {
            ready.countDown();
            start.await();
            try {
                examService.addQuestion(examId, essayQuestion(60_000, "Concurrent A"), List.of(), ownerId);
                return true;
            } catch (AppException rejectedAfterPublish) {
                return false;
            }
        }));
        results.add(executor.submit(() -> {
            ready.countDown();
            start.await();
            try {
                examService.addQuestion(examId, essayQuestion(60_000, "Concurrent B"), List.of(), ownerId);
                return true;
            } catch (AppException rejectedAfterPublishOrLimit) {
                return false;
            }
        }));
        results.add(executor.submit(() -> {
            ready.countDown();
            start.await();
            try {
                examService.publishExam(examId, ownerId);
                return true;
            } catch (AppException publishValidationFailure) {
                return false;
            }
        }));
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        for (Future<Boolean> result : results) result.get(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        Exam persisted = examRepository.findById(examId).orElseThrow();
        List<Question> publishedQuestions = questionRepository.findByExamIdOrderByPositionAsc(examId);
        long total = publishedQuestions.stream().mapToLong(Question::getPoints).sum();
        assertEquals("PUBLISHED", persisted.getStatus());
        assertTrue(total <= ExamService.MAX_EXAM_TOTAL_POINTS);
        int stableCount = publishedQuestions.size();
        assertThrows(AppException.class,
                () -> examService.addQuestion(examId, essayQuestion(1, "Late"), List.of(), ownerId));
        assertEquals(stableCount, questionRepository.countByExamId(examId));
    }

    private Question essayQuestion(int points, String text) {
        Question question = new Question();
        question.setQuestionText(text);
        question.setType("ESSAY");
        question.setPoints(points);
        return question;
    }
}
