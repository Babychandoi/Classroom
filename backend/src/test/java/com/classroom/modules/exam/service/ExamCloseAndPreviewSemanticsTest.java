package com.classroom.modules.exam.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.commerce.policy.ProPolicy;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import com.classroom.modules.exam.dto.ExamAttemptDto;
import com.classroom.modules.exam.dto.SubmitAttemptRequest;
import com.classroom.modules.exam.model.AttemptAnswer;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * R14-05 / R14-14 / R14-15: ExamService wired with the REAL ExamAudiencePolicy and ExamScoringPolicy
 * (only repositories and unrelated collaborators are mocked), so the close semantics, attempt
 * counting and preview reuse are exercised through the same policy code production uses.
 */
@ExtendWith(MockitoExtension.class)
class ExamCloseAndPreviewSemanticsTest {

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
    private Question q1;

    @BeforeEach
    void setUp() {
        ExamAudiencePolicy audiencePolicy = new ExamAudiencePolicy(accessPolicy, proPolicy, entitlementRepository,
                attemptRepository, segmentService, courseRepository);
        examService = new ExamService(examRepository, questionRepository, optionRepository, attemptRepository,
                attemptAnswerRepository, audiencePolicy, new ExamScoringPolicy(), accessPolicy, leaderboardService,
                objectMapper, courseRepository, segmentRepository, auditService, outboxService, userRepository,
                profileVisibilityPolicy, null);

        exam = new Exam(CLASS_ID, "Kỳ thi giữa kỳ", "ALL", 60);
        exam.setId("exam-1");
        exam.setAttemptLimit(1);
        exam.setStatus("PUBLISHED");

        q1 = new Question("exam-1", "1 + 1 = ?", "MULTIPLE_CHOICE", 10, 1, "B");
        q1.setId("q-1");

        lenient().when(examRepository.findByIdForShare("exam-1")).thenReturn(Optional.of(exam));

        lenient().when(examRepository.findByIdForUpdate("exam-1")).thenReturn(Optional.of(exam));
        lenient().when(examRepository.findById("exam-1")).thenReturn(Optional.of(exam));
        lenient().when(questionRepository.findByExamIdOrderByPositionAsc("exam-1")).thenReturn(List.of(q1));
        lenient().when(attemptRepository.save(any(ExamAttempt.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void closeExamNow(Instant closedAt) {
        exam.setStatus("CLOSED");
        exam.setClosedAt(closedAt);
    }

    /** An IN_PROGRESS learner attempt with real snapshots, started at {@code startedAt}. */
    private ExamAttempt runningAttempt(Instant startedAt, Instant endsAt) throws Exception {
        ExamAttempt attempt = new ExamAttempt("exam-1", STUDENT, CLASS_ID, endsAt, false);
        attempt.setId("att-1");
        attempt.setStartedAt(startedAt);
        attempt.setAttemptNumber(1);
        attempt.setAudienceEligibleAtStart(true);
        attempt.setQuestionSnapshotJson("[{\"id\":\"q-1\",\"examId\":\"exam-1\",\"questionText\":\"1 + 1 = ?\","
                + "\"type\":\"MULTIPLE_CHOICE\",\"points\":10,\"position\":1}]");
        attempt.setGradingSnapshotJson(objectMapper.writeValueAsString(List.of(q1)));
        return attempt;
    }

    // ----- R14-05 -----

    @Test
    @DisplayName("R14-05: start-attempt resumes an IN_PROGRESS attempt after the exam was CLOSED (was 422 EXAM_NOT_OPEN)")
    void resumeAfterCloseReturnsTheRunningAttempt() throws Exception {
        Instant now = Instant.now();
        ExamAttempt running = runningAttempt(now.minus(20, ChronoUnit.MINUTES), now.plus(40, ChronoUnit.MINUTES));
        closeExamNow(now.minus(5, ChronoUnit.MINUTES));
        when(accessPolicy.isMember(STUDENT, CLASS_ID)).thenReturn(true);
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewFalseOrderByStartedAtDesc("exam-1", STUDENT, "IN_PROGRESS"))
                .thenReturn(Optional.of(running));

        ExamAttemptDto dto = examService.startAttempt("exam-1", STUDENT, false, true);

        assertEquals("att-1", dto.getId());
        assertEquals("IN_PROGRESS", dto.getStatus());
        assertEquals(1, dto.getQuestions().size());
        verify(attemptRepository, never()).save(any());
    }

    @Test
    @DisplayName("R14-05: a NEW attempt is still refused on a CLOSED exam (EXAM_NOT_OPEN)")
    void newAttemptStillBlockedAfterClose() {
        closeExamNow(Instant.now().minus(5, ChronoUnit.MINUTES));
        when(accessPolicy.isMember(STUDENT, CLASS_ID)).thenReturn(true);
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewFalseOrderByStartedAtDesc("exam-1", STUDENT, "IN_PROGRESS"))
                .thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () -> examService.startAttempt("exam-1", STUDENT, false));
        assertEquals(ErrorCode.EXAM_NOT_OPEN, ex.getErrorCode());
        verify(attemptRepository, never()).save(any());
    }

    @Test
    @DisplayName("R14-05: an attempt that started AFTER closedAt is not resumable on the closed exam")
    void attemptStartedAfterCloseNotResumable() throws Exception {
        Instant now = Instant.now();
        closeExamNow(now.minus(10, ChronoUnit.MINUTES));
        ExamAttempt late = runningAttempt(now.minus(2, ChronoUnit.MINUTES), now.plus(40, ChronoUnit.MINUTES));
        when(accessPolicy.isMember(STUDENT, CLASS_ID)).thenReturn(true);
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewFalseOrderByStartedAtDesc("exam-1", STUDENT, "IN_PROGRESS"))
                .thenReturn(Optional.of(late));

        AppException ex = assertThrows(AppException.class, () -> examService.startAttempt("exam-1", STUDENT, false, true));
        assertEquals(ErrorCode.EXAM_NOT_OPEN, ex.getErrorCode());
    }

    @Test
    @DisplayName("R14-05: autosave and submit keep working after close, and submit grades the attempt normally")
    void autosaveAndSubmitWorkAfterClose() throws Exception {
        Instant now = Instant.now();
        ExamAttempt running = runningAttempt(now.minus(20, ChronoUnit.MINUTES), now.plus(40, ChronoUnit.MINUTES));
        closeExamNow(now.minus(5, ChronoUnit.MINUTES));
        when(attemptRepository.findByIdForUpdate("att-1")).thenReturn(Optional.of(running));
        when(attemptAnswerRepository.save(any(AttemptAnswer.class))).thenAnswer(inv -> inv.getArgument(0));

        ExamAttemptDto saved = examService.saveAnswers("att-1", STUDENT, Map.of("q-1", "B"));
        assertEquals("IN_PROGRESS", saved.getStatus());

        AttemptAnswer stored = new AttemptAnswer("att-1", "q-1", "B");
        when(attemptAnswerRepository.findByAttemptId("att-1")).thenReturn(List.of(stored));
        SubmitAttemptRequest request = new SubmitAttemptRequest();
        request.setAnswers(Map.of("q-1", "B"));

        ExamAttemptDto submitted = examService.submitAttempt("att-1", STUDENT, request);

        assertEquals("PUBLISHED", submitted.getStatus());
        assertEquals(0, new java.math.BigDecimal("100").compareTo(submitted.getScore()));
    }

    @Test
    @DisplayName("R14-05: the timeout sweeper leaves a running attempt of a CLOSED exam alone until its own deadline")
    void sweeperDoesNotFinalizeClosedExamAttemptEarly() throws Exception {
        Instant now = Instant.now();
        closeExamNow(now.minus(5, ChronoUnit.MINUTES));
        ExamAttempt running = runningAttempt(now.minus(20, ChronoUnit.MINUTES), now.plus(40, ChronoUnit.MINUTES));
        ExamAttempt expired = runningAttempt(now.minus(90, ChronoUnit.MINUTES), now.minus(1, ChronoUnit.MINUTES));
        expired.setId("att-expired");
        when(attemptRepository.findByStatusAndIsPreviewFalse("IN_PROGRESS")).thenReturn(List.of(running, expired));
        when(attemptRepository.findByIdForUpdate("att-expired")).thenReturn(Optional.of(expired));
        when(attemptAnswerRepository.findByAttemptId("att-expired")).thenReturn(List.of());

        examService.finalizeExpiredAttempts();

        assertEquals("IN_PROGRESS", running.getStatus());
        verify(attemptRepository, never()).findByIdForUpdate("att-1");
        assertNotEquals("IN_PROGRESS", expired.getStatus());
    }

    // ----- R14-14 -----

    @Test
    @DisplayName("R14-14: a CANCELLED attempt does not use up the limit, and the next attempt number continues after it")
    void cancelledAttemptDoesNotCountAgainstLimit() {
        when(accessPolicy.isMember(STUDENT, CLASS_ID)).thenReturn(true);
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewFalseOrderByStartedAtDesc("exam-1", STUDENT, "IN_PROGRESS"))
                .thenReturn(Optional.empty());
        // attemptLimit = 1; the only prior attempt (#1) was CANCELLED, so 0 count toward the limit ...
        when(attemptRepository.countAttemptsTowardLimit("exam-1", STUDENT)).thenReturn(0L);
        // ... but attempt_number 1 is taken (unique per exam+user), so the new one must be #2.
        when(attemptRepository.findMaxAttemptNumber("exam-1", STUDENT)).thenReturn(1);

        ExamAttemptDto dto = examService.startAttempt("exam-1", STUDENT, false);

        assertEquals("IN_PROGRESS", dto.getStatus());
        ArgumentCaptor<ExamAttempt> saved = ArgumentCaptor.forClass(ExamAttempt.class);
        verify(attemptRepository).save(saved.capture());
        assertEquals(2, saved.getValue().getAttemptNumber());
    }

    @Test
    @DisplayName("R14-14: a real (non-cancelled) attempt still consumes the limit")
    void nonCancelledAttemptStillCountsAgainstLimit() {
        when(accessPolicy.isMember(STUDENT, CLASS_ID)).thenReturn(true);
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewFalseOrderByStartedAtDesc("exam-1", STUDENT, "IN_PROGRESS"))
                .thenReturn(Optional.empty());
        when(attemptRepository.countAttemptsTowardLimit("exam-1", STUDENT)).thenReturn(1L);

        AppException ex = assertThrows(AppException.class, () -> examService.startAttempt("exam-1", STUDENT, false));
        assertEquals(ErrorCode.EXAM_ATTEMPT_LIMIT_REACHED, ex.getErrorCode());
        verify(attemptRepository, never()).save(any());
    }

    // ----- R14-15 -----

    private void ownerPreviewSetup() {
        when(accessPolicy.isOwner(OWNER, CLASS_ID)).thenReturn(true);
    }

    @Test
    @DisplayName("R14-15: the first preview start creates exactly one preview attempt")
    void firstPreviewCreatesAttempt() {
        ownerPreviewSetup();
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewTrueOrderByStartedAtDesc("exam-1", OWNER, "IN_PROGRESS"))
                .thenReturn(Optional.empty());

        ExamAttemptDto dto = examService.startAttempt("exam-1", OWNER, true);

        assertTrue(dto.isPreview());
        verify(attemptRepository, times(1)).save(any(ExamAttempt.class));
    }

    @Test
    @DisplayName("R14-15: a second preview start returns the SAME running preview attempt and creates no new row")
    void secondPreviewStartResumesExisting() {
        ownerPreviewSetup();
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewTrueOrderByStartedAtDesc("exam-1", OWNER, "IN_PROGRESS"))
                .thenReturn(Optional.empty());
        ExamAttemptDto first = examService.startAttempt("exam-1", OWNER, true);
        ArgumentCaptor<ExamAttempt> created = ArgumentCaptor.forClass(ExamAttempt.class);
        verify(attemptRepository).save(created.capture());

        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewTrueOrderByStartedAtDesc("exam-1", OWNER, "IN_PROGRESS"))
                .thenReturn(Optional.of(created.getValue()));
        ExamAttemptDto second = examService.startAttempt("exam-1", OWNER, true);

        assertEquals(first.getId(), second.getId());
        assertEquals(1, second.getQuestions().size());
        verify(attemptRepository, times(1)).save(any(ExamAttempt.class)); // still only the first creation
    }

    @Test
    @DisplayName("R14-15: an edited exam retires the stale preview attempt (CANCELLED) and starts a fresh one")
    void staleSnapshotPreviewIsReplaced() {
        ownerPreviewSetup();
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewTrueOrderByStartedAtDesc("exam-1", OWNER, "IN_PROGRESS"))
                .thenReturn(Optional.empty());
        ExamAttemptDto first = examService.startAttempt("exam-1", OWNER, true);
        ArgumentCaptor<ExamAttempt> created = ArgumentCaptor.forClass(ExamAttempt.class);
        verify(attemptRepository).save(created.capture());
        ExamAttempt stale = created.getValue();

        q1.setQuestionText("2 + 2 = ?"); // author edits the DRAFT question, then runs the preview again
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewTrueOrderByStartedAtDesc("exam-1", OWNER, "IN_PROGRESS"))
                .thenReturn(Optional.of(stale));
        ExamAttemptDto second = examService.startAttempt("exam-1", OWNER, true);

        assertNotEquals(first.getId(), second.getId());
        assertEquals("CANCELLED", stale.getStatus());
        assertEquals("2 + 2 = ?", second.getQuestions().get(0).getQuestionText());
    }

    @Test
    @DisplayName("R14-15: an expired preview attempt is finalized and a fresh preview is started")
    void expiredPreviewIsFinalizedAndReplaced() throws Exception {
        ownerPreviewSetup();
        ExamAttempt expired = new ExamAttempt("exam-1", OWNER, CLASS_ID, Instant.now().minusSeconds(30), true);
        expired.setId("preview-old");
        expired.setQuestionSnapshotJson("[]");
        expired.setGradingSnapshotJson(objectMapper.writeValueAsString(List.of(q1)));
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewTrueOrderByStartedAtDesc("exam-1", OWNER, "IN_PROGRESS"))
                .thenReturn(Optional.of(expired));
        when(attemptAnswerRepository.findByAttemptId("preview-old")).thenReturn(List.of());

        ExamAttemptDto dto = examService.startAttempt("exam-1", OWNER, true);

        assertNotEquals("preview-old", dto.getId());
        assertNotEquals("IN_PROGRESS", expired.getStatus());
    }

    // ----- R15-05 -----

    @Test
    @DisplayName("R15-05: an answer-key-ONLY edit (learner-safe view unchanged) retires the old preview attempt")
    void answerKeyOnlyEditRetiresStalePreview() {
        ownerPreviewSetup();
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewTrueOrderByStartedAtDesc("exam-1", OWNER, "IN_PROGRESS"))
                .thenReturn(Optional.empty());
        ExamAttemptDto first = examService.startAttempt("exam-1", OWNER, true);
        ArgumentCaptor<ExamAttempt> created = ArgumentCaptor.forClass(ExamAttempt.class);
        verify(attemptRepository).save(created.capture());
        ExamAttempt stale = created.getValue();
        String learnerSnapshotBefore = stale.getQuestionSnapshotJson();
        String gradingSnapshotBefore = stale.getGradingSnapshotJson();

        q1.setAnswerKey("C"); // only the key changes: text/options/points/position are identical
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewTrueOrderByStartedAtDesc("exam-1", OWNER, "IN_PROGRESS"))
                .thenReturn(Optional.of(stale));
        ExamAttemptDto second = examService.startAttempt("exam-1", OWNER, true);

        assertNotEquals(first.getId(), second.getId());
        assertEquals("CANCELLED", stale.getStatus());
        ArgumentCaptor<ExamAttempt> saved = ArgumentCaptor.forClass(ExamAttempt.class);
        verify(attemptRepository, atLeastOnce()).save(saved.capture());
        ExamAttempt fresh = saved.getAllValues().stream()
                .filter(a -> a.getId().equals(second.getId())).findFirst().orElseThrow();
        // The learner-safe snapshot is identical by construction - only the grading snapshot moved.
        assertEquals(learnerSnapshotBefore, fresh.getQuestionSnapshotJson());
        assertNotEquals(gradingSnapshotBefore, fresh.getGradingSnapshotJson());
        assertTrue(fresh.getGradingSnapshotJson().contains("\"answerKey\":\"C\""));
    }

    @Test
    @DisplayName("R15-05: an untouched exam still resumes the same preview (grading snapshot compared, no false staleness)")
    void unchangedGradingSnapshotStillResumes() {
        ownerPreviewSetup();
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewTrueOrderByStartedAtDesc("exam-1", OWNER, "IN_PROGRESS"))
                .thenReturn(Optional.empty());
        ExamAttemptDto first = examService.startAttempt("exam-1", OWNER, true);
        ArgumentCaptor<ExamAttempt> created = ArgumentCaptor.forClass(ExamAttempt.class);
        verify(attemptRepository).save(created.capture());
        ExamAttempt running = created.getValue();

        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewTrueOrderByStartedAtDesc("exam-1", OWNER, "IN_PROGRESS"))
                .thenReturn(Optional.of(running));
        ExamAttemptDto second = examService.startAttempt("exam-1", OWNER, true);

        assertEquals(first.getId(), second.getId());
        assertEquals("IN_PROGRESS", running.getStatus());
    }

    // ----- R20-06: autosave / submit answer handling -----

    @Test
    @DisplayName("R20-06: autosave reads the attempt's answers ONCE (not one query per question) and writes only what changed")
    void autosaveUsesOneReadAndWritesOnlyChanges() throws Exception {
        Instant now = Instant.now();
        ExamAttempt running = runningAttempt(now.minus(20, ChronoUnit.MINUTES), now.plus(40, ChronoUnit.MINUTES));
        when(attemptRepository.findByIdForUpdate("att-1")).thenReturn(Optional.of(running));
        AttemptAnswer stored = new AttemptAnswer("att-1", "q-1", "A");
        when(attemptAnswerRepository.findByAttemptId("att-1")).thenReturn(List.of(stored));

        ExamAttemptDto unchanged = examService.saveAnswers("att-1", STUDENT, Map.of("q-1", "A"));
        assertEquals(1, unchanged.getAnswers().size());
        verify(attemptAnswerRepository, never()).save(any(AttemptAnswer.class));

        ExamAttemptDto changed = examService.saveAnswers("att-1", STUDENT, Map.of("q-1", "B"));
        assertEquals("B", changed.getAnswers().get(0).getStudentAnswer());
        assertEquals("B", stored.getStudentAnswer());
        verify(attemptAnswerRepository, times(1)).save(stored);

        verify(attemptAnswerRepository, times(2)).findByAttemptId("att-1"); // once per autosave, including the response
        verify(attemptAnswerRepository, never()).findByAttemptIdAndQuestionId(anyString(), anyString());
    }

    @Test
    @DisplayName("R20-06: an answer longer than the limit, more answers than questions, or a foreign question id is a 400 for autosave and submit")
    void answerPayloadLimits() throws Exception {
        Instant now = Instant.now();
        ExamAttempt running = runningAttempt(now.minus(20, ChronoUnit.MINUTES), now.plus(40, ChronoUnit.MINUTES));
        when(attemptRepository.findByIdForUpdate("att-1")).thenReturn(Optional.of(running));
        when(attemptAnswerRepository.findByAttemptId("att-1")).thenReturn(List.of());

        String tooLong = "x".repeat(ExamService.MAX_ANSWER_CHARS + 1);
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(AppException.class,
                () -> examService.saveAnswers("att-1", STUDENT, Map.of("q-1", tooLong))).getErrorCode());
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(AppException.class,
                () -> examService.saveAnswers("att-1", STUDENT, Map.of("q-1", "A", "q-2", "B"))).getErrorCode());
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(AppException.class,
                () -> examService.saveAnswers("att-1", STUDENT, Map.of("other-exam-question", "A"))).getErrorCode());

        SubmitAttemptRequest request = new SubmitAttemptRequest();
        request.setAnswers(Map.of("q-1", tooLong));
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(AppException.class,
                () -> examService.submitAttempt("att-1", STUDENT, request)).getErrorCode());
        verify(attemptAnswerRepository, never()).save(any(AttemptAnswer.class));

        // exactly at the limit is fine
        String atLimit = "x".repeat(ExamService.MAX_ANSWER_CHARS);
        assertEquals(1, examService.saveAnswers("att-1", STUDENT, Map.of("q-1", atLimit)).getAnswers().size());
    }
}
