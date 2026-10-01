package com.classroom.modules.exam.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.commerce.policy.ProPolicy;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import com.classroom.modules.exam.dto.ExamAttemptDto;
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
import com.classroom.modules.identity.policy.ProfileVisibilityPolicy;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.outbox.service.OutboxService;
import com.classroom.modules.ranking.service.LeaderboardService;
import com.classroom.modules.segment.repository.SegmentRepository;
import com.classroom.modules.segment.service.SegmentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * R19-05: the seed used to publish an exam with zero questions (bypassing publishExam's validation). Starting it
 * created an attempt with an empty question snapshot: autosave, submit and resume then all answered 500 and the
 * attempt sat IN_PROGRESS until it timed out, burning the learner's try. startAttempt must refuse such an exam
 * BEFORE creating anything, and must not leave an already-stuck attempt behind.
 */
@ExtendWith(MockitoExtension.class)
class ExamStartWithoutQuestionsTest {

    private static final String CLASS_ID = "class-1";
    private static final String STUDENT = "student-1";
    private static final String OWNER = "owner-1";

    @Mock private ExamRepository examRepository;
    @Mock private QuestionRepository questionRepository;
    @Mock private AnswerOptionRepository optionRepository;
    @Mock private ExamAttemptRepository attemptRepository;
    @Mock private AttemptAnswerRepository attemptAnswerRepository;
    @Mock private AccessPolicy accessPolicy;
    @Mock private ProPolicy proPolicy;
    @Mock private EntitlementRepository entitlementRepository;
    @Mock private SegmentService segmentService;
    @Mock private CourseRepository courseRepository;
    @Mock private LeaderboardService leaderboardService;
    @Mock private SegmentRepository segmentRepository;
    @Mock private AuditService auditService;
    @Mock private OutboxService outboxService;
    @Mock private UserRepository userRepository;
    @Mock private ProfileVisibilityPolicy profileVisibilityPolicy;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ExamService examService;
    private Exam exam;

    @BeforeEach
    void setUp() {
        ExamAudiencePolicy audiencePolicy = new ExamAudiencePolicy(accessPolicy, proPolicy, entitlementRepository,
                attemptRepository, segmentService, courseRepository);
        examService = new ExamService(examRepository, questionRepository, optionRepository, attemptRepository,
                attemptAnswerRepository, audiencePolicy, new ExamScoringPolicy(), accessPolicy, leaderboardService,
                objectMapper, courseRepository, segmentRepository, auditService, outboxService, userRepository,
                profileVisibilityPolicy, null);

        exam = new Exam(CLASS_ID, "Kiểm Tra Cuối Khóa", "ALL", 60);
        exam.setId("exam-1");
        exam.setAttemptLimit(2);
        exam.setStatus("PUBLISHED");

        lenient().when(examRepository.findByIdForShare("exam-1")).thenReturn(Optional.of(exam));

        lenient().when(examRepository.findByIdForUpdate("exam-1")).thenReturn(Optional.of(exam));
        lenient().when(examRepository.findById("exam-1")).thenReturn(Optional.of(exam));
        lenient().when(attemptRepository.save(any(ExamAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(accessPolicy.isMember(STUDENT, CLASS_ID)).thenReturn(true);
        lenient().when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewFalseOrderByStartedAtDesc("exam-1", STUDENT, "IN_PROGRESS"))
                .thenReturn(Optional.empty());
    }

    private static Question question() {
        Question q = new Question("exam-1", "1 + 1 = ?", "MULTIPLE_CHOICE", 10, 1, "B");
        q.setId("q-1");
        return q;
    }

    @Test
    @DisplayName("R19-05: starting an exam that has no questions is refused with a Vietnamese 422 and creates NO attempt")
    void learnerStartIsRefusedAndNothingIsCreated() {
        when(questionRepository.findByExamIdOrderByPositionAsc("exam-1")).thenReturn(List.of());

        AppException ex = assertThrows(AppException.class, () -> examService.startAttempt("exam-1", STUDENT, false));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("chưa có câu hỏi"), ex.getMessage());
        verify(attemptRepository, never()).save(any());
    }

    @Test
    @DisplayName("R19-05: a staff preview of an exam without questions is refused the same way")
    void previewStartIsRefusedToo() {
        when(accessPolicy.isOwner(OWNER, CLASS_ID)).thenReturn(true);
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewTrueOrderByStartedAtDesc("exam-1", OWNER, "IN_PROGRESS"))
                .thenReturn(Optional.empty());
        when(questionRepository.findByExamIdOrderByPositionAsc("exam-1")).thenReturn(List.of());

        AppException ex = assertThrows(AppException.class, () -> examService.startAttempt("exam-1", OWNER, true));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, ex.getErrorCode());
        verify(attemptRepository, never()).save(any());
    }

    @Test
    @DisplayName("R19-05: an exam with a question still starts normally")
    void examWithQuestionsStillStarts() {
        when(questionRepository.findByExamIdOrderByPositionAsc("exam-1")).thenReturn(List.of(question()));
        when(attemptRepository.countAttemptsTowardLimit("exam-1", STUDENT)).thenReturn(0L);

        ExamAttemptDto dto = examService.startAttempt("exam-1", STUDENT, false);

        assertEquals("IN_PROGRESS", dto.getStatus());
        assertEquals(1, dto.getQuestions().size());
    }

    @Test
    @DisplayName("R19-05: an attempt already stranded with an empty snapshot is cancelled (not counted) and replaced once the exam has questions")
    void strandedAttemptIsRetiredAndReplaced() {
        ExamAttempt stranded = new ExamAttempt("exam-1", STUDENT, CLASS_ID, Instant.now().plusSeconds(1800), false);
        stranded.setId("att-stranded");
        stranded.setAttemptNumber(1);
        stranded.setAudienceEligibleAtStart(true);
        stranded.setQuestionSnapshotJson("[]");
        stranded.setGradingSnapshotJson("[]");
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewFalseOrderByStartedAtDesc("exam-1", STUDENT, "IN_PROGRESS"))
                .thenReturn(Optional.of(stranded));
        // The exam was repaired since (the seed repair added its question).
        when(questionRepository.findByExamIdOrderByPositionAsc("exam-1")).thenReturn(List.of(question()));
        // CANCELLED attempts do not count toward the limit; attempt number 1 is taken, so the next is 2.
        when(attemptRepository.countAttemptsTowardLimit("exam-1", STUDENT)).thenReturn(0L);
        when(attemptRepository.findMaxAttemptNumber("exam-1", STUDENT)).thenReturn(1);

        ExamAttemptDto dto = examService.startAttempt("exam-1", STUDENT, false);

        assertEquals("CANCELLED", stranded.getStatus());
        assertNotNull(stranded.getCancelReason());
        assertNotEquals("att-stranded", dto.getId());
        assertEquals("IN_PROGRESS", dto.getStatus());
        assertEquals(1, dto.getQuestions().size());
        ArgumentCaptor<ExamAttempt> saved = ArgumentCaptor.forClass(ExamAttempt.class);
        verify(attemptRepository, atLeast(2)).save(saved.capture());
        assertTrue(saved.getAllValues().stream().anyMatch(a -> a.getAttemptNumber() == 2 && "IN_PROGRESS".equals(a.getStatus())));
    }

    @Test
    @DisplayName("R19-05: with the exam still empty the stranded attempt yields the 422 (never a 500) and no new attempt")
    void strandedAttemptOnStillEmptyExamGives422() {
        ExamAttempt stranded = new ExamAttempt("exam-1", STUDENT, CLASS_ID, Instant.now().plusSeconds(1800), false);
        stranded.setId("att-stranded");
        stranded.setAudienceEligibleAtStart(true);
        stranded.setQuestionSnapshotJson("[]");
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewFalseOrderByStartedAtDesc("exam-1", STUDENT, "IN_PROGRESS"))
                .thenReturn(Optional.of(stranded));
        when(questionRepository.findByExamIdOrderByPositionAsc("exam-1")).thenReturn(List.of());

        AppException ex = assertThrows(AppException.class, () -> examService.startAttempt("exam-1", STUDENT, false));

        assertEquals(ErrorCode.UNPROCESSABLE_ENTITY, ex.getErrorCode());
        ArgumentCaptor<ExamAttempt> saved = ArgumentCaptor.forClass(ExamAttempt.class);
        verify(attemptRepository, atMostOnce()).save(saved.capture());
        assertTrue(saved.getAllValues().stream().allMatch(a -> a == stranded), "no NEW attempt may be created");
    }

    @Test
    @DisplayName("R19-05: publishing an exam with no questions is refused (the rule the seed used to bypass)")
    void publishRequiresAtLeastOneQuestion() {
        exam.setStatus("DRAFT");
        when(questionRepository.findByExamIdOrderByPositionAsc("exam-1")).thenReturn(List.of());

        AppException ex = assertThrows(AppException.class, () -> examService.publishExam("exam-1", OWNER));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("chưa có câu hỏi"), ex.getMessage());
        verify(examRepository, never()).save(any());
    }
}
