package com.classroom.seed;

import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.exam.model.AnswerOption;
import com.classroom.modules.exam.model.Exam;
import com.classroom.modules.exam.model.Question;
import com.classroom.modules.exam.repository.AnswerOptionRepository;
import com.classroom.modules.exam.repository.ExamRepository;
import com.classroom.modules.exam.repository.QuestionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R19-05: the demo seed published a COURSE exam with zero questions (bypassing publishExam's validation), and a PRO
 * exam whose question had no options. Fresh seeds must be valid, and databases seeded by the old version are repaired
 * idempotently by the next start.
 */
@SpringBootTest
@ActiveProfiles("test")
class DataSeedRunnerExamRepairTest {

    @Autowired private DataSeedRunner seedRunner;
    @Autowired private ClassroomRepository classroomRepository;
    @Autowired private ExamRepository examRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private AnswerOptionRepository optionRepository;

    private Classroom seededClass() {
        return classroomRepository.findBySlug("lop-toan-nang-cao").orElseThrow();
    }

    private void assertAnswerable(Question q) {
        List<AnswerOption> options = optionRepository.findByQuestionIdOrderByPositionAsc(q.getId());
        assertTrue(options.size() >= 2, "question '" + q.getQuestionText() + "' needs at least two options");
        assertTrue(options.stream().anyMatch(o -> o.getOptionKey().equalsIgnoreCase(q.getAnswerKey())),
                "answer key " + q.getAnswerKey() + " of '" + q.getQuestionText() + "' must match one of its options");
    }

    @Test
    @DisplayName("R19-05: every seeded PUBLISHED exam has at least one answerable multiple-choice/true-false question with a valid key")
    void seededExamsAreStartable() {
        List<Exam> exams = examRepository.findByClassIdOrderByCreatedAtDesc(seededClass().getId());
        assertFalse(exams.isEmpty());
        java.util.Set<String> seededTitles = java.util.Set.of("Kỳ Thi Kiểm Tra Giữa Kỳ (Mở cho tất cả)",
                DataSeedRunner.PRO_EXAM_TITLE, DataSeedRunner.COURSE_EXAM_TITLE);
        int checked = 0;
        for (Exam exam : exams) {
            // Other tests share this in-memory database; only the seed's own exams are the seed's responsibility.
            if (!seededTitles.contains(exam.getTitle()) || !"PUBLISHED".equals(exam.getStatus())) continue;
            checked++;
            List<Question> questions = questionRepository.findByExamIdOrderByPositionAsc(exam.getId());
            assertFalse(questions.isEmpty(), "seeded exam '" + exam.getTitle() + "' has no questions");
            questions.stream().filter(q -> !"ESSAY".equals(q.getType())).forEach(this::assertAnswerable);
        }
        assertTrue(checked >= 3, "the three seeded exams must be present, found " + checked);
    }

    @Test
    @DisplayName("R19-05: the repair adds the missing question to a question-less seeded exam, and running it again changes nothing")
    void repairIsIdempotent() {
        String classId = seededClass().getId();
        // What an old database looks like: a second copy of the seeded course exam with no question at all.
        Exam legacy = new Exam(classId, DataSeedRunner.COURSE_EXAM_TITLE, "ALL", 60);
        legacy = examRepository.save(legacy);
        try {
            assertEquals(0, questionRepository.countByExamId(legacy.getId()));

            seedRunner.run();

            List<Question> repaired = questionRepository.findByExamIdOrderByPositionAsc(legacy.getId());
            assertEquals(1, repaired.size(), "the repair adds the exam's question");
            assertAnswerable(repaired.get(0));

            seedRunner.run();
            seedRunner.run();

            assertEquals(1, questionRepository.countByExamId(legacy.getId()), "repeated runs must not add more questions");
            assertEquals(4, optionRepository.findByQuestionIdOrderByPositionAsc(repaired.get(0).getId()).size());
        } finally {
            for (Question q : questionRepository.findByExamIdOrderByPositionAsc(legacy.getId())) {
                optionRepository.deleteAll(optionRepository.findByQuestionIdOrderByPositionAsc(q.getId()));
                questionRepository.delete(q);
            }
            examRepository.delete(legacy);
        }
    }

    @Test
    @DisplayName("R19-05: the repair leaves unrelated (non-seeded) question-less exams alone and does not disturb healthy seeded exams")
    void repairOnlyTouchesBrokenSeededExams() {
        String classId = seededClass().getId();
        long seededQuestionsBefore = examRepository.findByClassIdOrderByCreatedAtDesc(classId).stream()
                .mapToLong(e -> questionRepository.countByExamId(e.getId())).sum();
        Exam custom = examRepository.save(new Exam(classId, "Kỳ thi tự tạo của giảng viên", "ALL", 30));
        try {
            seedRunner.run();

            assertEquals(0, questionRepository.countByExamId(custom.getId()), "a non-seeded exam must not be modified");
            long after = examRepository.findByClassIdOrderByCreatedAtDesc(classId).stream()
                    .filter(e -> !e.getId().equals(custom.getId()))
                    .mapToLong(e -> questionRepository.countByExamId(e.getId())).sum();
            assertEquals(seededQuestionsBefore, after, "healthy seeded exams keep exactly their questions");
        } finally {
            examRepository.delete(custom);
        }
    }
}
