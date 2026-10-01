package com.classroom.modules.exam.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.exam.model.AnswerOption;
import com.classroom.modules.exam.model.Exam;
import com.classroom.modules.exam.model.ExamAttempt;
import com.classroom.modules.exam.model.Question;
import com.classroom.modules.exam.policy.ExamAudiencePolicy;
import com.classroom.modules.exam.policy.ExamScoringPolicy;
import com.classroom.modules.exam.repository.AnswerOptionRepository;
import com.classroom.modules.exam.repository.AttemptAnswerRepository;
import com.classroom.modules.exam.repository.ExamAttemptRepository;
import com.classroom.modules.exam.repository.ExamRepository;
import com.classroom.modules.exam.repository.QuestionRepository;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.outbox.service.OutboxService;
import com.classroom.modules.ranking.service.LeaderboardService;
import com.classroom.modules.segment.repository.SegmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * R13-03: exam lifecycle transitions (update while DRAFT, question CRUD while DRAFT, publish ->
 * close -> archive state machine, staff attempt cancellation), scoped RBAC/IDOR, and rejection of
 * illegal transitions.
 */
@ExtendWith(MockitoExtension.class)
class ExamLifecycleTest {

    @Mock private ExamRepository examRepository;
    @Mock private QuestionRepository questionRepository;
    @Mock private AnswerOptionRepository optionRepository;
    @Mock private ExamAttemptRepository attemptRepository;
    @Mock private AttemptAnswerRepository attemptAnswerRepository;
    @Mock private ExamAudiencePolicy audiencePolicy;
    @Mock private ExamScoringPolicy scoringPolicy;
    @Mock private LeaderboardService leaderboardService;
    @Mock private AccessPolicy accessPolicy;
    @Mock private ObjectMapper objectMapper;
    @Mock private CourseRepository courseRepository;
    @Mock private SegmentRepository segmentRepository;
    @Mock private AuditService auditService;
    @Mock private OutboxService outboxService;

    @InjectMocks
    private ExamService examService;

    private Exam draftExam;
    private Exam publishedExam;

    @BeforeEach
    void setUp() {
        draftExam = new Exam("class-1", "Kỳ thi 1", "ALL", 60);
        draftExam.setId("exam-draft");
        draftExam.setStatus("DRAFT");

        publishedExam = new Exam("class-1", "Kỳ thi 2", "ALL", 60);
        publishedExam.setId("exam-published");
        publishedExam.setStatus("PUBLISHED");
    }

    @Test
    @DisplayName("updateExam succeeds while DRAFT for an authorized editor")
    void updateExamAllowedWhileDraft() {
        when(examRepository.findByIdForUpdate("exam-draft")).thenReturn(Optional.of(draftExam));
        when(examRepository.save(any(Exam.class))).thenAnswer(inv -> inv.getArgument(0));

        Exam patch = new Exam();
        patch.setTitle("Kỳ thi sửa");
        Exam result = examService.updateExam("exam-draft", patch, "owner-1");

        assertEquals("Kỳ thi sửa", result.getTitle());
    }

    @Test
    @DisplayName("updateExam is rejected once the exam has been published (post-publish config edits disallowed)")
    void updateExamRejectedAfterPublish() {
        when(examRepository.findByIdForUpdate("exam-published")).thenReturn(Optional.of(publishedExam));

        Exam patch = new Exam();
        patch.setTitle("x");
        AppException ex = assertThrows(AppException.class, () -> examService.updateExam("exam-published", patch, "owner-1"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    @DisplayName("updateExam is forbidden for a caller without EXAM:EDIT (IDOR/authz check)")
    void updateExamForbiddenWithoutPermission() {
        when(examRepository.findByIdForUpdate("exam-draft")).thenReturn(Optional.of(draftExam));
        doThrow(new AppException(ErrorCode.STAFF_PERMISSION_DENIED, "denied"))
                .when(accessPolicy).enforceManage("intruder", "class-1", "EXAM", "EDIT", null);

        Exam patch = new Exam();
        patch.setTitle("x");
        assertThrows(AppException.class, () -> examService.updateExam("exam-draft", patch, "intruder"));
    }

    @Test
    @DisplayName("closeExam transitions PUBLISHED -> CLOSED for an authorized publisher")
    void closeExamTransitionsFromPublished() {
        when(examRepository.findByIdForUpdate("exam-published")).thenReturn(Optional.of(publishedExam));
        when(examRepository.save(any(Exam.class))).thenAnswer(inv -> inv.getArgument(0));

        Exam result = examService.closeExam("exam-published", "owner-1");
        assertEquals("CLOSED", result.getStatus());
        assertNotNull(result.getClosedAt());
    }

    @Test
    @DisplayName("closeExam rejects an illegal transition from DRAFT (400)")
    void closeExamRejectsDraft() {
        when(examRepository.findByIdForUpdate("exam-draft")).thenReturn(Optional.of(draftExam));

        AppException ex = assertThrows(AppException.class, () -> examService.closeExam("exam-draft", "owner-1"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    @DisplayName("archiveExam requires CLOSED first; rejects PUBLISHED -> ARCHIVED directly")
    void archiveExamRequiresClosedFirst() {
        when(examRepository.findByIdForUpdate("exam-published")).thenReturn(Optional.of(publishedExam));

        AppException ex = assertThrows(AppException.class, () -> examService.archiveExam("exam-published", "owner-1"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    @DisplayName("archiveExam succeeds from CLOSED")
    void archiveExamSucceedsFromClosed() {
        publishedExam.setStatus("CLOSED");
        when(examRepository.findByIdForUpdate("exam-published")).thenReturn(Optional.of(publishedExam));
        when(examRepository.save(any(Exam.class))).thenAnswer(inv -> inv.getArgument(0));

        Exam result = examService.archiveExam("exam-published", "owner-1");
        assertEquals("ARCHIVED", result.getStatus());
    }

    @Test
    @DisplayName("deleteQuestion is rejected once the exam is published")
    void deleteQuestionRejectedAfterPublish() {
        Question q = new Question("exam-published", "Q", "ESSAY", 10, 1, null);
        q.setId("q-1");
        when(questionRepository.findById("q-1")).thenReturn(Optional.of(q));
        when(questionRepository.existsById("q-1")).thenReturn(true);
        when(examRepository.findByIdForUpdate("exam-published")).thenReturn(Optional.of(publishedExam));

        AppException ex = assertThrows(AppException.class, () -> examService.deleteQuestion("q-1", "owner-1"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    @DisplayName("deleteQuestion succeeds while DRAFT for an authorized editor")
    void deleteQuestionSucceedsWhileDraft() {
        Question q = new Question("exam-draft", "Q", "ESSAY", 10, 1, null);
        q.setId("q-1");
        when(questionRepository.findById("q-1")).thenReturn(Optional.of(q));
        when(questionRepository.existsById("q-1")).thenReturn(true);
        when(examRepository.findByIdForUpdate("exam-draft")).thenReturn(Optional.of(draftExam));

        examService.deleteQuestion("q-1", "owner-1");
        verify(questionRepository).delete(q);
    }

    @Test
    @DisplayName("cancelAttempt requires EXAM:GRADE and refuses to cancel an already-PUBLISHED attempt")
    void cancelAttemptRefusesPublishedAttempt() {
        ExamAttempt attempt = new ExamAttempt("exam-published", "student-1", "class-1", java.time.Instant.now(), false);
        attempt.setId("att-1");
        attempt.setStatus("PUBLISHED");
        when(attemptRepository.findByIdForUpdate("att-1")).thenReturn(Optional.of(attempt));
        when(examRepository.findById("exam-published")).thenReturn(Optional.of(publishedExam));

        AppException ex = assertThrows(AppException.class, () -> examService.cancelAttempt("att-1", "staff-1", "gian lận"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    @DisplayName("cancelAttempt succeeds for an IN_PROGRESS attempt and is forbidden without EXAM:GRADE")
    void cancelAttemptSucceedsAndIsScoped() {
        ExamAttempt attempt = new ExamAttempt("exam-published", "student-1", "class-1", java.time.Instant.now().plusSeconds(600), false);
        attempt.setId("att-1");
        attempt.setStatus("IN_PROGRESS");
        when(attemptRepository.findByIdForUpdate("att-1")).thenReturn(Optional.of(attempt));
        when(examRepository.findById("exam-published")).thenReturn(Optional.of(publishedExam));
        when(attemptRepository.save(any(ExamAttempt.class))).thenAnswer(inv -> inv.getArgument(0));

        var dto = examService.cancelAttempt("att-1", "staff-1", "gian lận");
        assertEquals("CANCELLED", dto.getStatus());

        // Cross-class / unauthorized staff must be rejected (IDOR/authz)
        attempt.setStatus("IN_PROGRESS");
        doThrow(new AppException(ErrorCode.STAFF_PERMISSION_DENIED, "denied"))
                .when(accessPolicy).enforceManage("intruder", "class-1", "EXAM", "GRADE", null);
        assertThrows(AppException.class, () -> examService.cancelAttempt("att-1", "intruder", "x"));
    }

    // ----- R16-06: the schedule can be cleared (null start/end = unscheduled) while DRAFT -----

    @Test
    @DisplayName("R16-06: updateExam with null scheduleStart/scheduleEnd clears a previously set schedule")
    void updateExamClearsSchedule() {
        draftExam.setScheduleStart(java.time.Instant.parse("2026-10-01T08:00:00Z"));
        draftExam.setScheduleEnd(java.time.Instant.parse("2026-10-01T10:00:00Z"));
        when(examRepository.findByIdForUpdate("exam-draft")).thenReturn(Optional.of(draftExam));
        when(examRepository.save(any(Exam.class))).thenAnswer(inv -> inv.getArgument(0));

        Exam patch = new Exam();
        patch.setTitle("Kỳ thi 1");
        // scheduleStart / scheduleEnd deliberately left null: "unscheduled"
        Exam result = examService.updateExam("exam-draft", patch, "owner-1");

        assertNull(result.getScheduleStart());
        assertNull(result.getScheduleEnd());
    }

    @Test
    @DisplayName("R16-06: updateExam replaces a schedule with the new one and still validates end > start")
    void updateExamReplacesAndValidatesSchedule() {
        draftExam.setScheduleStart(java.time.Instant.parse("2026-10-01T08:00:00Z"));
        draftExam.setScheduleEnd(java.time.Instant.parse("2026-10-01T10:00:00Z"));
        when(examRepository.findByIdForUpdate("exam-draft")).thenReturn(Optional.of(draftExam));
        when(examRepository.save(any(Exam.class))).thenAnswer(inv -> inv.getArgument(0));

        Exam replace = new Exam();
        replace.setScheduleStart(java.time.Instant.parse("2026-11-01T08:00:00Z"));
        replace.setScheduleEnd(java.time.Instant.parse("2026-11-01T09:00:00Z"));
        Exam result = examService.updateExam("exam-draft", replace, "owner-1");
        assertEquals(java.time.Instant.parse("2026-11-01T08:00:00Z"), result.getScheduleStart());
        assertEquals(java.time.Instant.parse("2026-11-01T09:00:00Z"), result.getScheduleEnd());

        Exam inverted = new Exam();
        inverted.setScheduleStart(java.time.Instant.parse("2026-11-02T10:00:00Z"));
        inverted.setScheduleEnd(java.time.Instant.parse("2026-11-02T09:00:00Z"));
        AppException ex = assertThrows(AppException.class, () -> examService.updateExam("exam-draft", inverted, "owner-1"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    // ----- R16-10: reorder lists must be exact permutations -----

    private Question question(String id, int position) {
        Question q = new Question("exam-draft", "Câu " + id, "MULTIPLE_CHOICE", 10, position, "A");
        q.setId(id);
        return q;
    }

    @Test
    @DisplayName("R16-10: reorderQuestions rejects a duplicated id even when the list length matches")
    void reorderQuestionsRejectsDuplicateIds() {
        when(examRepository.findByIdForUpdate("exam-draft")).thenReturn(Optional.of(draftExam));
        when(questionRepository.findByExamIdOrderByPositionAsc("exam-draft"))
                .thenReturn(List.of(question("q1", 0), question("q2", 1)));

        AppException ex = assertThrows(AppException.class,
                () -> examService.reorderQuestions("exam-draft", List.of("q1", "q1"), "owner-1"));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verify(questionRepository, never()).save(any());
    }

    @Test
    @DisplayName("R16-10: reorderQuestions accepts a genuine permutation and renumbers positions")
    void reorderQuestionsAcceptsPermutation() {
        Question q1 = question("q1", 0);
        Question q2 = question("q2", 1);
        when(examRepository.findByIdForUpdate("exam-draft")).thenReturn(Optional.of(draftExam));
        when(questionRepository.findByExamIdOrderByPositionAsc("exam-draft")).thenReturn(List.of(q1, q2));

        examService.reorderQuestions("exam-draft", List.of("q2", "q1"), "owner-1");

        assertEquals(0, q2.getPosition());
        assertEquals(1, q1.getPosition());
    }}
