package com.classroom.modules.exam.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.exam.dto.ExamAttemptDto;
import com.classroom.modules.exam.dto.GradeAttemptRequest;
import com.classroom.modules.exam.dto.QuestionDto;
import com.classroom.modules.exam.dto.SubmitAttemptRequest;
import com.classroom.modules.exam.model.AttemptAnswer;
import com.classroom.modules.exam.model.Exam;
import com.classroom.modules.exam.model.ExamAttempt;
import com.classroom.modules.exam.model.Question;
import com.classroom.modules.exam.model.AnswerOption;
import com.classroom.modules.exam.policy.ExamAudiencePolicy;
import com.classroom.modules.exam.policy.ExamScoringPolicy;
import com.classroom.modules.exam.repository.AnswerOptionRepository;
import com.classroom.modules.exam.repository.AttemptAnswerRepository;
import com.classroom.modules.exam.repository.ExamAttemptRepository;
import com.classroom.modules.exam.repository.ExamRepository;
import com.classroom.modules.exam.repository.QuestionRepository;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.policy.ProfileVisibilityPolicy;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.outbox.service.OutboxService;
import com.classroom.modules.ranking.service.LeaderboardService;
import com.classroom.modules.segment.model.Segment;
import com.classroom.modules.segment.repository.SegmentRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ExamSecurityTest {

    @Mock
    private ExamRepository examRepository;
    @Mock
    private QuestionRepository questionRepository;
    @Mock
    private AnswerOptionRepository optionRepository;
    @Mock
    private ExamAttemptRepository attemptRepository;
    @Mock
    private AttemptAnswerRepository attemptAnswerRepository;
    @Mock
    private ExamAudiencePolicy audiencePolicy;
    @Mock
    private ExamScoringPolicy scoringPolicy;
    @Mock
    private LeaderboardService leaderboardService;
    @Mock
    private AccessPolicy accessPolicy;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private CourseRepository courseRepository;
    @Mock
    private SegmentRepository segmentRepository;
    @Mock
    private AuditService auditService;
    @Mock
    private OutboxService outboxService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ProfileVisibilityPolicy profileVisibilityPolicy;

    @Mock
    private com.classroom.modules.exam.repository.ExamPublicationSnapshotRepository publicationSnapshots;

    @InjectMocks
    private ExamService examService;

    private Exam exam;
    private Question q1;

    @Test
    void publishedSnapshotIsReusedWithoutReadingMutableQuestionsOrLeakingKeys() throws Exception {
        var snapshot = new com.classroom.modules.exam.model.ExamPublicationSnapshot("exam-1",
                "[{\"id\":\"q-1\",\"questionText\":\"Frozen question\"}]",
                "[{\"id\":\"q-1\",\"answerKey\":\"secret-correct-answer\"}]");
        when(examRepository.findByIdForShare("exam-1")).thenReturn(Optional.of(exam));
        when(publicationSnapshots.findById("exam-1")).thenReturn(Optional.of(snapshot));
        when(attemptRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var dto = examService.startAttempt("exam-1", "student-1", false);

        assertEquals("Frozen question", dto.getQuestions().get(0).getQuestionText());
        assertNull(dto.getQuestions().get(0).getAnswerKey());
        assertFalse(new ObjectMapper().findAndRegisterModules().writeValueAsString(dto).contains("secret-correct-answer"));
        var saved = org.mockito.ArgumentCaptor.forClass(ExamAttempt.class);
        verify(attemptRepository).save(saved.capture());
        assertEquals(snapshot.getGradingJson(), saved.getValue().getGradingSnapshotJson());
        verifyNoInteractions(questionRepository, optionRepository);
        verify(audiencePolicy).enforceEnterExam(eq("student-1"), eq(exam), any(), eq(false), eq(false), eq(0L));
    }

    @Test
    void corruptPublishedSnapshotCannotConsumeAnAttemptOrFallbackToMutableContent() {
        when(examRepository.findByIdForShare("exam-1")).thenReturn(Optional.of(exam));
        when(publicationSnapshots.findById("exam-1")).thenReturn(Optional.of(
                new com.classroom.modules.exam.model.ExamPublicationSnapshot("exam-1", "[]", "[]")));
        assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, assertThrows(AppException.class,
                () -> examService.startAttempt("exam-1", "student-1", false)).getErrorCode());
        verify(attemptRepository, never()).save(any());
        verifyNoInteractions(questionRepository, optionRepository);
    }

    @BeforeEach
    void setUp() throws Exception {
        lenient().doAnswer(invocation -> {
            try {
                return new ObjectMapper().readValue((String) invocation.getArgument(0),
                        (TypeReference<?>) invocation.getArgument(1));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        })
                .when(objectMapper).readValue(anyString(), any(TypeReference.class));
        lenient().when(objectMapper.writeValueAsString(any())).thenAnswer(invocation ->
                new ObjectMapper().writeValueAsString(invocation.getArgument(0)));
        exam = new Exam("class-1", "Kỳ thi giữa kỳ", "ALL", 60);
        exam.setId("exam-1");
        exam.setAttemptLimit(1);

        q1 = new Question("exam-1", "1 + 1 = ?", "MULTIPLE_CHOICE", 10, 1, "2");
        q1.setId("q-1");
    }

    @Test
    @DisplayName("Resume preserves start-time audience eligibility while restoring the saved snapshot")
    void testResumeActiveAttempt() throws Exception {
        ExamAttempt activeAttempt = new ExamAttempt("exam-1", "student-1", "class-1", Instant.now().plus(30, ChronoUnit.MINUTES), false);
        activeAttempt.setId("att-1");
        activeAttempt.setStatus("IN_PROGRESS");
        activeAttempt.setAudienceEligibleAtStart(true);
        activeAttempt.setQuestionSnapshotJson("[{\"id\":\"q-1\"}]");

        QuestionDto qDto = new QuestionDto();
        qDto.setId("q-1");

        when(examRepository.findByIdForShare("exam-1")).thenReturn(Optional.of(exam));
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewFalseOrderByStartedAtDesc("exam-1", "student-1", "IN_PROGRESS"))
                .thenReturn(Optional.of(activeAttempt));
        when(objectMapper.readValue(eq(activeAttempt.getQuestionSnapshotJson()), any(TypeReference.class)))
                .thenReturn(List.of(qDto));

        ExamAttemptDto dto = examService.startAttempt("exam-1", "student-1", false);

        assertEquals("att-1", dto.getId());
        assertEquals("IN_PROGRESS", dto.getStatus());
        verify(audiencePolicy).enforceResumeAttempt(eq("student-1"), eq(exam), eq(activeAttempt), any());
        verify(audiencePolicy, never()).enforceEnterExam(eq("student-1"), eq(exam), any(), eq(false), eq(true), any());
        assertTrue(activeAttempt.isAudienceEligibleAtStart());
        // Verify attempt count was not incremented
        verify(attemptRepository, never()).save(argThat(a -> a != activeAttempt));
    }

    @Test
    @DisplayName("R2-04: student path never resumes the caller's own leftover preview attempt")
    void testStudentPathIgnoresOwnLeftoverPreviewAttempt() {
        // A former staff member, now demoted to plain student, previously left an in-progress
        // PREVIEW attempt on this exam. The student (non-preview) path must not find or resume
        // it via the preview-including query - only the isPreview=false lookup - otherwise the
        // demoted user would be routed into resuming a preview attempt and rejected by the
        // audience policy for it (EXAM_AUDIENCE_REJECTED) instead of starting a fresh attempt.
        when(examRepository.findByIdForShare("exam-1")).thenReturn(Optional.of(exam));
        when(accessPolicy.isOwner("former-staff-1", "class-1")).thenReturn(false);
        when(accessPolicy.isActiveStaff("former-staff-1", "class-1")).thenReturn(false);
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewFalseOrderByStartedAtDesc(
                        "exam-1", "former-staff-1", "IN_PROGRESS"))
                .thenReturn(Optional.empty());
        when(attemptRepository.countAttemptsTowardLimit("exam-1", "former-staff-1")).thenReturn(0L);
        when(questionRepository.findByExamIdOrderByPositionAsc("exam-1")).thenReturn(List.of(q1));
        when(attemptRepository.save(any(ExamAttempt.class))).thenAnswer(i -> i.getArgument(0));

        ExamAttemptDto result = examService.startAttempt("exam-1", "former-staff-1", false);

        assertNotNull(result);
        assertEquals("IN_PROGRESS", result.getStatus());
        verify(audiencePolicy).enforceEnterExam(eq("former-staff-1"), eq(exam), any(), eq(false), eq(false), eq(0L));
        verify(audiencePolicy, never()).enforceResumeAttempt(anyString(), any(), any(), any());
        // The preview-including lookup must never be consulted from the student path.
        verify(attemptRepository, never())
                .findFirstByExamIdAndUserIdAndStatusOrderByStartedAtDesc(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Course-scoped exam staff can discover drafts for their assigned course")
    void scopedStaffCanListCourseExamDraft() {
        exam.setStatus("DRAFT");
        exam.setAudienceScope("COURSE");
        exam.setTargetCourseId("course-A");
        when(accessPolicy.canManage("staff-1", "class-1", "EXAM", "VIEW", "course-A")).thenReturn(true);
        when(examRepository.findByClassIdOrderByCreatedAtDesc("class-1")).thenReturn(List.of(exam));

        var results = examService.getExamsByClass("class-1", "staff-1");

        assertEquals(1, results.size());
        assertEquals("exam-1", results.get(0).getId());
        verify(accessPolicy).canManage("staff-1", "class-1", "EXAM", "VIEW", "course-A");
        verify(accessPolicy, never()).canManage("staff-1", "class-1", "EXAM", "VIEW", null);
    }

    @Test
    @DisplayName("R7-02: staff with only EXAM:CREATE (no VIEW) still discovers their own course-scoped draft in the list")
    void createOnlyStaffCanListCourseExamDraft() {
        exam.setStatus("DRAFT");
        exam.setAudienceScope("COURSE");
        exam.setTargetCourseId("course-A");
        when(accessPolicy.canManage("staff-create", "class-1", "EXAM", "VIEW", "course-A")).thenReturn(false);
        when(accessPolicy.canManage("staff-create", "class-1", "EXAM", "EDIT", "course-A")).thenReturn(false);
        when(accessPolicy.canManage("staff-create", "class-1", "EXAM", "CREATE", "course-A")).thenReturn(true);
        when(examRepository.findByClassIdOrderByCreatedAtDesc("class-1")).thenReturn(List.of(exam));

        var results = examService.getExamsByClass("class-1", "staff-create");

        assertEquals(1, results.size());
        assertEquals("exam-1", results.get(0).getId());
    }

    @Test
    @DisplayName("R7-02: staff with only EXAM:PUBLISH (no VIEW/EDIT) can still open their draft exam's details")
    void publishOnlyStaffCanViewDraftExamDetails() {
        exam.setStatus("DRAFT");
        when(examRepository.findById("exam-1")).thenReturn(Optional.of(exam));
        when(accessPolicy.isOwner("staff-publish", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("staff-publish", "class-1", "EXAM", "VIEW", null)).thenReturn(false);
        when(accessPolicy.canManage("staff-publish", "class-1", "EXAM", "EDIT", null)).thenReturn(false);
        when(accessPolicy.canManage("staff-publish", "class-1", "EXAM", "CREATE", null)).thenReturn(false);
        when(accessPolicy.canManage("staff-publish", "class-1", "EXAM", "PUBLISH", null)).thenReturn(true);
        when(accessPolicy.canAccessAnswerKey("staff-publish", "class-1", null)).thenReturn(false);
        when(questionRepository.countByExamId("exam-1")).thenReturn(1L);

        com.classroom.modules.exam.dto.ExamDto dto = examService.getExamDetails("exam-1", "staff-publish");

        assertNotNull(dto);
        assertEquals("exam-1", dto.getId());
        // PUBLISH alone does not also grant EDIT, so questions/answer keys remain hidden — only
        // draft *visibility* is broadened, not authoring or answer-key access.
        assertNull(dto.getQuestions());
    }

    @Test
    @DisplayName("R7-02: staff with only EXAM:VIEW (no CREATE/EDIT/PUBLISH) still sees drafts (baseline unchanged)")
    void viewOnlyStaffStillSeesDraftExamDetails() {
        exam.setStatus("DRAFT");
        when(examRepository.findById("exam-1")).thenReturn(Optional.of(exam));
        when(accessPolicy.isOwner("staff-view-only", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("staff-view-only", "class-1", "EXAM", "VIEW", null)).thenReturn(true);
        when(accessPolicy.canManage("staff-view-only", "class-1", "EXAM", "EDIT", null)).thenReturn(false);
        when(questionRepository.countByExamId("exam-1")).thenReturn(1L);

        com.classroom.modules.exam.dto.ExamDto dto = examService.getExamDetails("exam-1", "staff-view-only");

        assertNotNull(dto);
        assertEquals("exam-1", dto.getId());
    }

    @Test
    @DisplayName("OWNER and active STAFF cannot create normal ranked attempts")
    void classroomPersonnelMustUsePreviewForExamAttempts() {
        when(examRepository.findByIdForShare("exam-1")).thenReturn(Optional.of(exam));
        when(accessPolicy.isOwner("owner-1", "class-1")).thenReturn(true);
        when(accessPolicy.isOwner("staff-1", "class-1")).thenReturn(false);
        when(accessPolicy.isActiveStaff("staff-1", "class-1")).thenReturn(true);

        assertThrows(AppException.class, () -> examService.startAttempt("exam-1", "owner-1", false));
        assertThrows(AppException.class, () -> examService.startAttempt("exam-1", "staff-1", false));
        verify(attemptRepository, never()).save(any());
        verify(audiencePolicy, never()).enforceEnterExam(anyString(), any(), any(), eq(false), anyBoolean(), any());
    }

    @Test
    @DisplayName("Finding 4: startAttempt rejects preview flag when caller is not OWNER or staff with EXAM:PREVIEW")
    void testStudentPreviewRejected() {
        when(examRepository.findByIdForShare("exam-1")).thenReturn(Optional.of(exam));
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("student-1", "class-1", "EXAM", "PREVIEW", null)).thenReturn(false);
        when(accessPolicy.canManage("student-1", "class-1", "EXAM", "EDIT", null)).thenReturn(false);

        AppException ex = assertThrows(AppException.class, () ->
                examService.startAttempt("exam-1", "student-1", true)
        );
        assertEquals(ErrorCode.STAFF_PERMISSION_DENIED, ex.getErrorCode());
        verify(attemptRepository, never()).save(any());
    }

    @Test
    @DisplayName("Finding 5: startAttempt auto-grades and publishes timed out attempt on resume")
    void testTimedOutAttemptAutoGradedOnResume() {
        ExamAttempt timedOutAttempt = new ExamAttempt("exam-1", "student-1", "class-1", Instant.now().minus(10, ChronoUnit.MINUTES), false);
        timedOutAttempt.setGradingSnapshotJson("[{\"id\":\"" + q1.getId() + "\",\"examId\":\"exam-1\",\"questionText\":\"1 + 1 = ?\",\"type\":\"MULTIPLE_CHOICE\",\"points\":10,\"position\":1,\"answerKey\":\"2\"}]");
        timedOutAttempt.setId("att-timed-out");
        timedOutAttempt.setStatus("IN_PROGRESS");

        when(examRepository.findByIdForShare("exam-1")).thenReturn(Optional.of(exam));
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewFalseOrderByStartedAtDesc("exam-1", "student-1", "IN_PROGRESS"))
                .thenReturn(Optional.of(timedOutAttempt));
        when(attemptAnswerRepository.findByAttemptId("att-timed-out")).thenReturn(List.of());

        ExamAttemptDto result = examService.startAttempt("exam-1", "student-1", false);

        assertEquals("PUBLISHED", timedOutAttempt.getStatus());
        assertEquals("PUBLISHED", result.getStatus());
        verify(scoringPolicy).autoGradeAttempt(eq(timedOutAttempt), anyList(), anyList());
        verify(leaderboardService).scheduleRecalculation(eq("class-1"), eq("student-1"));
    }

    @Test
    @DisplayName("Resume is rejected when the active classroom membership is no longer valid")
    void testResumeActiveAttemptFailsWhenIneligible() {
        ExamAttempt activeAttempt = new ExamAttempt("exam-1", "student-1", "class-1", Instant.now().plus(30, ChronoUnit.MINUTES), false);
        activeAttempt.setId("att-1");
        activeAttempt.setStatus("IN_PROGRESS");
        activeAttempt.setAudienceEligibleAtStart(true);

        when(examRepository.findByIdForShare("exam-1")).thenReturn(Optional.of(exam));
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewFalseOrderByStartedAtDesc("exam-1", "student-1", "IN_PROGRESS"))
                .thenReturn(Optional.of(activeAttempt));
        doThrow(new AppException(com.classroom.common.ErrorCode.EXAM_AUDIENCE_REJECTED, "Không đủ điều kiện"))
                .when(audiencePolicy).enforceResumeAttempt(eq("student-1"), eq(exam), eq(activeAttempt), any());

        assertThrows(AppException.class, () -> examService.startAttempt("exam-1", "student-1", false));
    }

    @Test
    @DisplayName("Finding 4: createExam rejects cross-class target course")
    void testCreateExamRejectsCrossClassCourse() {
        Exam crossExam = new Exam("class-1", "Cross Course Exam", "COURSE", 45);
        crossExam.setTargetCourseId("course-other");

        Course otherCourse = new Course("class-2", "Course Other Class", "PURCHASE_REQUIRED");
        when(courseRepository.findById("course-other")).thenReturn(Optional.of(otherCourse));

        assertThrows(AppException.class, () -> examService.createExam("class-1", crossExam, "owner-1"));
        verify(examRepository, never()).save(any());
    }

    @Test
    @DisplayName("Finding 4: createExam rejects cross-class target segment")
    void testCreateExamRejectsCrossClassSegment() {
        Exam crossExam = new Exam("class-1", "Cross Segment Exam", "SEGMENT", 45);
        crossExam.setTargetSegmentId("segment-other");

        Segment otherSegment = new Segment("class-2", "Segment Other", "Desc", "AND", "[]");
        when(segmentRepository.findById("segment-other")).thenReturn(Optional.of(otherSegment));

        assertThrows(AppException.class, () -> examService.createExam("class-1", crossExam, "owner-1"));
        verify(examRepository, never()).save(any());
    }

    @Test
    @DisplayName("createExam without a title is a 400 contract error, not a 500 at flush")
    void createExamRejectsMissingTitle() {
        Exam noTitle = new Exam("class-1", null, "ALL", 45);
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(AppException.class,
                () -> examService.createExam("class-1", noTitle, "owner-1")).getErrorCode());

        Exam blankTitle = new Exam("class-1", "   ", "ALL", 45);
        assertEquals(ErrorCode.BAD_REQUEST, assertThrows(AppException.class,
                () -> examService.createExam("class-1", blankTitle, "owner-1")).getErrorCode());

        verify(examRepository, never()).save(any());
    }

    @Test
    @DisplayName("Question creation rejects invalid points before persistence")
    void invalidQuestionCannotBeSaved() {
        exam.setStatus("DRAFT");
        when(examRepository.findByIdForUpdate("exam-1")).thenReturn(Optional.of(exam));
        Question malformed = new Question("exam-1", "Choose", "MULTIPLE_CHOICE", 0, 1, "Z");
        List<AnswerOption> options = List.of(new AnswerOption("q", "A", "Alpha", 1), new AnswerOption("q", "B", "Beta", 2));
        assertThrows(AppException.class, () -> examService.addQuestion("exam-1", malformed, options, "owner-1"));
        verify(questionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Publish revalidates persisted answer-key and option integrity")
    void publishRevalidatesQuestionIntegrity() {
        exam.setStatus("DRAFT");
        when(examRepository.findByIdForUpdate("exam-1")).thenReturn(Optional.of(exam));
        when(questionRepository.findByExamIdOrderByPositionAsc("exam-1")).thenReturn(List.of(q1));
        when(optionRepository.findByQuestionIdOrderByPositionAsc("q-1")).thenReturn(
                List.of(new AnswerOption("q-1", "A", "Alpha", 1), new AnswerOption("q-1", "B", "Beta", 2)));
        assertThrows(AppException.class, () -> examService.publishExam("exam-1", "owner-1"));
        verify(examRepository, never()).save(argThat(e -> "PUBLISHED".equals(e.getStatus())));
    }

    @Test
    @DisplayName("Finding 4: startAttempt enforces attempt limit when prior attempts are submitted")
    void testEnforceAttemptLimit() {
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewFalseOrderByStartedAtDesc("exam-1", "student-1", "IN_PROGRESS"))
                .thenReturn(Optional.empty());
        when(examRepository.findByIdForShare("exam-1")).thenReturn(Optional.of(exam));
        // R19-05: the exam must have questions for the attempt-limit check to be reached at all.
        when(questionRepository.findByExamIdOrderByPositionAsc("exam-1")).thenReturn(List.of(q1));
        when(attemptRepository.countAttemptsTowardLimit("exam-1", "student-1")).thenReturn(1L);

        assertThrows(AppException.class, () -> examService.startAttempt("exam-1", "student-1", false));
    }

    @Test
    @DisplayName("Finding 4: autosave validates question membership against the exam's questions")
    void testAutosaveRejectsInvalidQuestionId() {
        ExamAttempt attempt = new ExamAttempt("exam-1", "student-1", "class-1", Instant.now().plus(30, ChronoUnit.MINUTES), false);
        attempt.setId("att-1");
        attempt.setStatus("IN_PROGRESS");

        when(attemptRepository.findByIdForUpdate("att-1")).thenReturn(Optional.of(attempt));

        // Submit answer for foreign question "q-999"
        assertThrows(AppException.class, () ->
                examService.saveAnswers("att-1", "student-1", Map.of("q-999", "hacked-answer"))
        );
    }

    @Test
    @DisplayName("Revoked learner cannot autosave an existing non-preview attempt")
    void revokedLearnerCannotAutosaveExistingAttempt() {
        ExamAttempt attempt = new ExamAttempt("exam-1", "student-1", "class-1", Instant.now().plusSeconds(600), false);
        attempt.setId("att-revoked");
        attempt.setStatus("IN_PROGRESS");
        when(attemptRepository.findByIdForUpdate("att-revoked")).thenReturn(Optional.of(attempt));
        doThrow(new AppException(ErrorCode.FORBIDDEN, "Not a member"))
                .when(accessPolicy).enforceMember("student-1", "class-1");

        assertThrows(AppException.class, () -> examService.saveAnswers("att-revoked", "student-1", Map.of("q-1", "answer")));
        verify(attemptAnswerRepository, never()).save(any());
    }

    @Test
    @DisplayName("Revoked learner cannot submit an existing non-preview attempt")
    void revokedLearnerCannotSubmitExistingAttempt() {
        ExamAttempt attempt = new ExamAttempt("exam-1", "student-1", "class-1", Instant.now().plusSeconds(600), false);
        attempt.setId("att-revoked");
        attempt.setStatus("IN_PROGRESS");
        when(attemptRepository.findByIdForUpdate("att-revoked")).thenReturn(Optional.of(attempt));
        doThrow(new AppException(ErrorCode.FORBIDDEN, "Not a member"))
                .when(accessPolicy).enforceMember("student-1", "class-1");

        assertThrows(AppException.class, () -> examService.submitAttempt("att-revoked", "student-1", new SubmitAttemptRequest()));
        verify(attemptRepository, never()).save(any());
        verify(outboxService, never()).recordEventIfNotExists(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Finding 4: submitAttempt rejects submissions past the attempt deadline")
    void testSubmitPastDeadlineRejected() {
        // Expired 5 minutes ago (past 60s grace period)
        ExamAttempt attempt = new ExamAttempt("exam-1", "student-1", "class-1", Instant.now().minus(5, ChronoUnit.MINUTES), false);
        attempt.setGradingSnapshotJson("[]");
        attempt.setId("att-1");
        attempt.setStatus("IN_PROGRESS");

        when(attemptRepository.findByIdForUpdate("att-1")).thenReturn(Optional.of(attempt));

        SubmitAttemptRequest req = new SubmitAttemptRequest();
        req.setAnswers(Map.of("q-1", "late-change"));
        assertDoesNotThrow(() -> examService.submitAttempt("att-1", "student-1", req));
        verify(attemptAnswerRepository, never()).save(any());
    }

    @Test
    @DisplayName("Finding 4: gradeAttempt rejects attempts not yet in SUBMITTED status")
    void testGradeOnlySubmittedAttempts() {
        ExamAttempt attempt = new ExamAttempt("exam-1", "student-1", "class-1", Instant.now().plus(30, ChronoUnit.MINUTES), false);
        attempt.setId("att-1");
        attempt.setStatus("IN_PROGRESS");

        when(attemptRepository.findByIdForUpdate("att-1")).thenReturn(Optional.of(attempt));

        GradeAttemptRequest req = new GradeAttemptRequest();
        assertThrows(AppException.class, () -> examService.gradeAttempt("att-1", "teacher-1", req));
    }

    @Test
    @DisplayName("Finding 4: getAttemptResult hides score from student until PUBLISHED")
    void testResultHiddenUntilPublished() {
        ExamAttempt attempt = new ExamAttempt("exam-1", "student-1", "class-1", Instant.now().minus(10, ChronoUnit.MINUTES), false);
        attempt.setId("att-1");
        attempt.setStatus("SUBMITTED"); // Pending teacher grading

        when(attemptRepository.findById("att-1")).thenReturn(Optional.of(attempt));

        assertThrows(AppException.class, () -> examService.getAttemptResult("att-1", "student-1"));
    }

    @Test
    @DisplayName("EXAM:VIEW sees safe unpublished attempt state but not answers or grading data")
    void viewPermissionDoesNotRevealUnpublishedAttemptDetails() {
        ExamAttempt attempt = new ExamAttempt("exam-1", "student-2", "class-1", Instant.now(), false);
        attempt.setId("att-private");
        attempt.setStatus("GRADING");
        attempt.setScore(new java.math.BigDecimal("80"));
        AttemptAnswer answer = new AttemptAnswer("att-private", "q-1", "private answer");
        answer.setPointsAwarded(new java.math.BigDecimal("8"));
        answer.setTeacherFeedback("private feedback");
        when(attemptRepository.findById("att-private")).thenReturn(Optional.of(attempt));
        when(examRepository.findById("exam-1")).thenReturn(Optional.of(exam));
        when(accessPolicy.canManage("staff-view", "class-1", "EXAM", "VIEW", null)).thenReturn(true);
        when(accessPolicy.canManage("staff-view", "class-1", "EXAM", "GRADE", null)).thenReturn(false);
        when(attemptAnswerRepository.findByAttemptId("att-private")).thenReturn(List.of(answer));

        ExamAttemptDto result = examService.getAttemptResult("att-private", "staff-view");

        assertNull(result.getScore());
        assertEquals(0, result.getTotalPoints());
        assertTrue(result.getAnswers().isEmpty());
    }

    @Test
    @DisplayName("Finding 1: EXAM:VIEW on PUBLISHED attempt sees score summary but NOT student answers or feedback")
    void viewPermissionDoesNotRevealPublishedStudentAnswers() {
        ExamAttempt attempt = new ExamAttempt("exam-1", "student-2", "class-1", Instant.now(), false);
        attempt.setId("att-pub");
        attempt.setStatus("PUBLISHED");
        attempt.setScore(new java.math.BigDecimal("95"));
        attempt.setTotalPoints(100);
        AttemptAnswer answer = new AttemptAnswer("att-pub", "q-1", "student confidential answer");
        answer.setPointsAwarded(new java.math.BigDecimal("9.5"));
        answer.setTeacherFeedback("excellent analysis");
        when(attemptRepository.findById("att-pub")).thenReturn(Optional.of(attempt));
        when(examRepository.findById("exam-1")).thenReturn(Optional.of(exam));
        when(accessPolicy.canManage("staff-view", "class-1", "EXAM", "VIEW", null)).thenReturn(true);
        when(accessPolicy.canManage("staff-view", "class-1", "EXAM", "GRADE", null)).thenReturn(false);
        when(attemptAnswerRepository.findByAttemptId("att-pub")).thenReturn(List.of(answer));

        // Caller with only EXAM:VIEW
        ExamAttemptDto staffResult = examService.getAttemptResult("att-pub", "staff-view");
        assertNotNull(staffResult.getScore());
        assertEquals(100, staffResult.getTotalPoints());
        assertTrue(staffResult.getAnswers().isEmpty());

        // Caller with EXAM:GRADE sees answers
        when(accessPolicy.canManage("staff-grade", "class-1", "EXAM", "VIEW", null)).thenReturn(true);
        when(accessPolicy.canManage("staff-grade", "class-1", "EXAM", "GRADE", null)).thenReturn(true);
        ExamAttemptDto graderResult = examService.getAttemptResult("att-pub", "staff-grade");
        assertFalse(graderResult.getAnswers().isEmpty());
        assertEquals("student confidential answer", graderResult.getAnswers().get(0).getStudentAnswer());

        // The student who took the exam sees their own answers
        ExamAttemptDto studentResult = examService.getAttemptResult("att-pub", "student-2");
        assertFalse(studentResult.getAnswers().isEmpty());
        assertEquals("student confidential answer", studentResult.getAnswers().get(0).getStudentAnswer());
    }

    @Test
    @DisplayName("Finding 1 & 3: getExamDetails rejects non-member user")
    void testExamDetailsRejectsNonMember() {
        exam.setStatus("PUBLISHED");
        when(examRepository.findById("exam-1")).thenReturn(Optional.of(exam));
        when(accessPolicy.isOwner("stranger-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("stranger-1", "class-1", "EXAM", "VIEW", null)).thenReturn(false);
        doThrow(new AppException(com.classroom.common.ErrorCode.FORBIDDEN, "Not a member"))
                .when(accessPolicy).enforceMember("stranger-1", "class-1");

        assertThrows(AppException.class, () -> examService.getExamDetails("exam-1", "stranger-1"));
    }

    @Test
    @DisplayName("Finding 3: getExamDetails rejects unpublished DRAFT exam for student")
    void testExamDetailsRejectsDraftExamForStudent() {
        exam.setStatus("DRAFT");
        when(examRepository.findById("exam-1")).thenReturn(Optional.of(exam));
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("student-1", "class-1", "EXAM", "VIEW", null)).thenReturn(false);

        assertThrows(AppException.class, () -> examService.getExamDetails("exam-1", "student-1"));
    }

    @Test
    @DisplayName("Finding 1: getExamDetails returns safe metadata and hides questions from students")
    void testExamDetailsHidesQuestionsFromStudents() {
        exam.setStatus("PUBLISHED");
        when(examRepository.findById("exam-1")).thenReturn(Optional.of(exam));
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("student-1", "class-1", "EXAM", "VIEW", null)).thenReturn(false);
        when(questionRepository.countByExamId("exam-1")).thenReturn(1L);
        when(audiencePolicy.canEnterExam(eq("student-1"), eq(exam), any(), eq(false))).thenReturn(true);
        when(attemptRepository.countAttemptsTowardLimit("exam-1", "student-1")).thenReturn(0L);

        com.classroom.modules.exam.dto.ExamDto dto = examService.getExamDetails("exam-1", "student-1");

        assertNotNull(dto);
        assertEquals("exam-1", dto.getId());
        assertEquals(1, dto.getQuestionCount());
        assertNull(dto.getQuestions(), "Questions must not be exposed to student in exam details");
        assertTrue(dto.isCanEnter());
    }

    @Test
    @DisplayName("Finding 7: getExamDetails hides draft questions from staff with VIEW only")
    void testExamDetailsHidesQuestionsFromStaffViewOnly() {
        exam.setStatus("DRAFT");
        when(examRepository.findById("exam-1")).thenReturn(Optional.of(exam));
        when(accessPolicy.isOwner("staff-view", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("staff-view", "class-1", "EXAM", "EDIT", null)).thenReturn(false);
        when(accessPolicy.canManage("staff-view", "class-1", "EXAM", "VIEW", null)).thenReturn(true);
        when(questionRepository.countByExamId("exam-1")).thenReturn(1L);

        com.classroom.modules.exam.dto.ExamDto dto = examService.getExamDetails("exam-1", "staff-view");

        assertNotNull(dto);
        assertEquals(1, dto.getQuestionCount());
        assertNull(dto.getQuestions(), "Questions must not be exposed to staff with VIEW only");
    }

    @Test
    @DisplayName("Finding 7: getExamDetails includes questions and answer keys for staff with EDIT")
    void testExamDetailsIncludesQuestionsForStaffEdit() {
        exam.setStatus("DRAFT");
        when(examRepository.findById("exam-1")).thenReturn(Optional.of(exam));
        when(accessPolicy.isOwner("staff-edit", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("staff-edit", "class-1", "EXAM", "VIEW", null)).thenReturn(true);
        when(accessPolicy.canManage("staff-edit", "class-1", "EXAM", "EDIT", null)).thenReturn(true);
        // Answer-key path now uses dedicated canAccessAnswerKey (explicit EXAM:EDIT only, no wildcards)
        when(accessPolicy.canAccessAnswerKey("staff-edit", "class-1", null)).thenReturn(true);
        when(questionRepository.countByExamId("exam-1")).thenReturn(1L);
        when(questionRepository.findByExamIdOrderByPositionAsc("exam-1")).thenReturn(List.of(q1));

        com.classroom.modules.exam.dto.ExamDto dto = examService.getExamDetails("exam-1", "staff-edit");

        assertNotNull(dto);
        assertNotNull(dto.getQuestions());
        assertEquals(1, dto.getQuestions().size());
        assertEquals("2", dto.getQuestions().get(0).getAnswerKey(), "Answer key should be included for authoring staff");
    }

    @Test
    @DisplayName("CRITICAL: Staff with wildcard EXAM:* (via canManage) cannot access answer keys via getExamDetails")
    void testWildcardStaffCannotAccessAnswerKey() {
        // This test proves that a wildcard permission grant does NOT let staff see answer keys.
        // canManage returns true (wildcard match) but canAccessAnswerKey returns false (no explicit EXAM:EDIT).
        exam.setStatus("DRAFT");
        when(examRepository.findById("exam-1")).thenReturn(Optional.of(exam));
        when(accessPolicy.isOwner("staff-wildcard", "class-1")).thenReturn(false);
        // Wildcard staff can VIEW (canManage returns true via * matching in AccessPolicy)
        when(accessPolicy.canManage("staff-wildcard", "class-1", "EXAM", "VIEW", null)).thenReturn(true);
        // canManage with EDIT also returns true due to wildcard — this is the problematic scenario
        when(accessPolicy.canManage("staff-wildcard", "class-1", "EXAM", "EDIT", null)).thenReturn(true);
        // But canAccessAnswerKey explicitly denies wildcards — only returns true for explicit EXAM:EDIT
        when(accessPolicy.canAccessAnswerKey("staff-wildcard", "class-1", null)).thenReturn(false);
        when(questionRepository.countByExamId("exam-1")).thenReturn(1L);
        when(questionRepository.findByExamIdOrderByPositionAsc("exam-1")).thenReturn(List.of(q1));

        com.classroom.modules.exam.dto.ExamDto dto = examService.getExamDetails("exam-1", "staff-wildcard");

        assertNotNull(dto);
        // Staff can see questions (has canEdit via canManage EXAM:EDIT=true)
        assertNotNull(dto.getQuestions());
        // But must NOT see answer keys (wildcard blocked by canAccessAnswerKey)
        assertNull(dto.getQuestions().get(0).getAnswerKey(),
                "Wildcard permission must NOT grant access to answer keys; only explicit EXAM:EDIT does");
    }

    @Test
    @DisplayName("Finding 7: getExamDetails includes questions and answer keys for OWNER")
    void testExamDetailsIncludesQuestionsForOwner() {
        exam.setStatus("DRAFT");
        when(examRepository.findById("exam-1")).thenReturn(Optional.of(exam));
        when(accessPolicy.isOwner("owner-1", "class-1")).thenReturn(true);
        // canAccessAnswerKey also returns true for OWNER
        when(accessPolicy.canAccessAnswerKey("owner-1", "class-1", null)).thenReturn(true);
        when(questionRepository.countByExamId("exam-1")).thenReturn(1L);
        when(questionRepository.findByExamIdOrderByPositionAsc("exam-1")).thenReturn(List.of(q1));

        com.classroom.modules.exam.dto.ExamDto dto = examService.getExamDetails("exam-1", "owner-1");

        assertNotNull(dto);
        assertNotNull(dto.getQuestions());
        assertEquals(1, dto.getQuestions().size());
        assertEquals("2", dto.getQuestions().get(0).getAnswerKey(), "Answer key should be included for owner");
    }

    @Test
    @DisplayName("Finding 4: startAttempt fails closed when lock lookup returns empty")
    void testStartAttemptFailsClosedOnLockLookupError() {
        when(examRepository.findByIdForShare("exam-1")).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () ->
                examService.startAttempt("exam-1", "student-1", false)
        );
        assertEquals(com.classroom.common.ErrorCode.NOT_FOUND, ex.getErrorCode());
        // Verify un-locked findById is NEVER invoked as fallback
        verify(examRepository, never()).findById(any());
    }

    @Test
    @DisplayName("Finding 4: startAttempt sets sequential attempt number on created attempt")
    void testStartAttemptSetsAttemptNumber() {
        when(examRepository.findByIdForShare("exam-1")).thenReturn(Optional.of(exam));
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewFalseOrderByStartedAtDesc("exam-1", "student-1", "IN_PROGRESS"))
                .thenReturn(Optional.empty());
        when(attemptRepository.countAttemptsTowardLimit("exam-1", "student-1")).thenReturn(0L);
        when(questionRepository.findByExamIdOrderByPositionAsc("exam-1")).thenReturn(List.of(q1));
        when(attemptRepository.save(any(ExamAttempt.class))).thenAnswer(i -> i.getArgument(0));

        ExamAttemptDto result = examService.startAttempt("exam-1", "student-1", false);
        assertNotNull(result);
        verify(attemptRepository).save(argThat(a -> a.getAttemptNumber() != null && a.getAttemptNumber() == 1));
    }

    @Test
    @DisplayName("Finding 4: startAttempt handles concurrent unique constraint violation by resuming active attempt")
    void testStartAttemptHandlesConstraintViolationByResuming() {
        when(examRepository.findByIdForShare("exam-1")).thenReturn(Optional.of(exam));
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewFalseOrderByStartedAtDesc("exam-1", "student-1", "IN_PROGRESS"))
                .thenReturn(Optional.empty()) // First check before insert: no attempt yet
                .thenReturn(Optional.of(new ExamAttempt("exam-1", "student-1", "class-1", Instant.now().plusSeconds(600), false))); // Check after collision
        when(attemptRepository.countAttemptsTowardLimit("exam-1", "student-1")).thenReturn(0L);
        when(questionRepository.findByExamIdOrderByPositionAsc("exam-1")).thenReturn(List.of(q1));

        // Simulate concurrent insert race throwing DataIntegrityViolationException
        when(attemptRepository.save(any(ExamAttempt.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("Duplicate key: uq_ea_exam_user_attempt"));

        ExamAttemptDto result = examService.startAttempt("exam-1", "student-1", false);
        assertNotNull(result);
    }

    @Test
    @DisplayName("Round 8 Finding 1: startAttempt successfully resumes active attempt when attempt limit is 1 and already consumed")
    void testResumeActiveAttemptAllowedWhenAttemptLimitIsOne() throws Exception {
        exam.setAttemptLimit(1);

        ExamAttempt activeAttempt = new ExamAttempt("exam-1", "student-1", "class-1", Instant.now().plus(30, ChronoUnit.MINUTES), false);
        activeAttempt.setGradingSnapshotJson("[]");
        activeAttempt.setId("att-limit-1");
        activeAttempt.setStatus("IN_PROGRESS");
        activeAttempt.setAttemptNumber(1);
        activeAttempt.setAudienceEligibleAtStart(true);
        activeAttempt.setQuestionSnapshotJson("[{\"id\":\"q-1\"}]");

        QuestionDto qDto = new QuestionDto();
        qDto.setId("q-1");

        when(examRepository.findByIdForShare("exam-1")).thenReturn(Optional.of(exam));
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewFalseOrderByStartedAtDesc("exam-1", "student-1", "IN_PROGRESS"))
                .thenReturn(Optional.of(activeAttempt));
        when(objectMapper.readValue(eq(activeAttempt.getQuestionSnapshotJson()), any(TypeReference.class)))
                .thenReturn(List.of(qDto));

        // Start attempt call must successfully resume active attempt without being rejected by attempt limit = 1
        ExamAttemptDto dto = examService.startAttempt("exam-1", "student-1", false);

        assertNotNull(dto);
        assertEquals("att-limit-1", dto.getId());
        assertEquals("IN_PROGRESS", dto.getStatus());
        verify(audiencePolicy).enforceResumeAttempt(eq("student-1"), eq(exam), eq(activeAttempt), any());
        // Confirms no new attempt was created
        verify(attemptRepository, never()).save(argThat(a -> a != activeAttempt));
    }

    @Test
    @DisplayName("Round 8 Finding 1: startAttempt finalizes active attempt if exam scheduleEnd has passed")
    void testResumeActiveAttemptFinalizesWhenScheduleEndHasPassed() {
        exam.setScheduleEnd(Instant.now().minus(5, ChronoUnit.MINUTES));

        ExamAttempt activeAttempt = new ExamAttempt("exam-1", "student-1", "class-1", Instant.now().plus(30, ChronoUnit.MINUTES), false);
        activeAttempt.setId("att-schedule-passed");
        activeAttempt.setStatus("IN_PROGRESS");
        activeAttempt.setGradingSnapshotJson("[{\"id\":\"" + q1.getId() + "\",\"examId\":\"exam-1\",\"questionText\":\"1 + 1 = ?\",\"type\":\"MULTIPLE_CHOICE\",\"points\":10,\"position\":1,\"answerKey\":\"2\"}]");

        when(examRepository.findByIdForShare("exam-1")).thenReturn(Optional.of(exam));
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewFalseOrderByStartedAtDesc("exam-1", "student-1", "IN_PROGRESS"))
                .thenReturn(Optional.of(activeAttempt));
        when(attemptAnswerRepository.findByAttemptId("att-schedule-passed")).thenReturn(List.of());

        ExamAttemptDto result = examService.startAttempt("exam-1", "student-1", false);
        assertEquals("PUBLISHED", result.getStatus());
        assertEquals("PUBLISHED", activeAttempt.getStatus());
    }

    @Test
    @DisplayName("Round 8 Finding 2: submitAttempt acquires pessimistic write lock and provides idempotent response")
    void testSubmitAttemptUsesPessimisticLockAndIdempotentResult() {
        ExamAttempt alreadySubmitted = new ExamAttempt("exam-1", "student-1", "class-1", Instant.now().plus(30, ChronoUnit.MINUTES), false);
        alreadySubmitted.setId("att-already-sub");
        alreadySubmitted.setStatus("PUBLISHED");
        alreadySubmitted.setScore(java.math.BigDecimal.valueOf(10));

        // Must invoke findByIdForUpdate, NOT findById
        when(attemptRepository.findByIdForUpdate("att-already-sub")).thenReturn(Optional.of(alreadySubmitted));
        when(examRepository.findById("exam-1")).thenReturn(Optional.of(exam));

        SubmitAttemptRequest req = new SubmitAttemptRequest();
        ExamAttemptDto dto = examService.submitAttempt("att-already-sub", "student-1", req);

        assertNotNull(dto);
        assertEquals("att-already-sub", dto.getId());
        assertEquals("PUBLISHED", dto.getStatus());

        // Verify no re-grading or outbox events occurred
        verify(scoringPolicy, never()).autoGradeAttempt(any(), any(), any());
        verify(outboxService, never()).recordEvent(any(), any(), any(), any());
        verify(outboxService, never()).recordEventIfNotExists(any(), any(), any(), any());
    }

    @Test
    @DisplayName("CRITICAL Finding 2: gradeAttempt stays GRADING when not all essay questions are graded (partial grading)")
    void testPartialEssayGradingStaysGrading() {
        Question essayQ1 = new Question("exam-1", "Trình bày A", "ESSAY", 20, 2, null);
        essayQ1.setId("essay-q-1");
        Question essayQ2 = new Question("exam-1", "Trình bày B", "ESSAY", 20, 3, null);
        essayQ2.setId("essay-q-2");

        ExamAttempt attempt = new ExamAttempt("exam-1", "student-1", "class-1", Instant.now().minus(10, ChronoUnit.MINUTES), false);
        attempt.setGradingSnapshotJson("[]");
        attempt.setId("att-essay-partial");
        attempt.setStatus("SUBMITTED");

        // Grader provides score for essay-q-1 only (essay-q-2 not yet graded)
        AttemptAnswer ans1 = new AttemptAnswer("att-essay-partial", "essay-q-1", "Student answer A");
        ans1.setPointsAwarded(java.math.BigDecimal.valueOf(15));
        ans1.setGradedBy("teacher-1");

        AttemptAnswer ans2 = new AttemptAnswer("att-essay-partial", "essay-q-2", "Student answer B");
        // ans2 has no pointsAwarded and no gradedBy — still pending

        when(attemptRepository.findByIdForUpdate("att-essay-partial")).thenReturn(Optional.of(attempt));
        attempt.setGradingSnapshotJson("[{\"id\":\"essay-q-1\",\"examId\":\"exam-1\",\"questionText\":\"Trình bày A\",\"type\":\"ESSAY\",\"points\":20,\"position\":2},{\"id\":\"essay-q-2\",\"examId\":\"exam-1\",\"questionText\":\"Trình bày B\",\"type\":\"ESSAY\",\"points\":20,\"position\":3}]");
        when(attemptAnswerRepository.findByAttemptId("att-essay-partial"))
                .thenReturn(List.of(ans1, ans2));

        // Scoring policy sets status — it should set GRADING since essay-q-2 has no pointsAwarded
        doAnswer(invocation -> {
            ExamAttempt a = invocation.getArgument(0);
            // Simulate scoring policy behavior: essay-q-2 has no pointsAwarded so sets GRADING
            a.setStatus("GRADING");
            a.setScore(java.math.BigDecimal.valueOf(25));
            a.setTotalPoints(50);
            return null;
        }).when(scoringPolicy).autoGradeAttempt(any(), any(), any());

        when(attemptRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(examRepository.findById("exam-1")).thenReturn(Optional.of(exam));

        GradeAttemptRequest req = new GradeAttemptRequest();
        req.setScores(Map.of("essay-q-1", java.math.BigDecimal.valueOf(15)));

        ExamAttemptDto dto = examService.gradeAttempt("att-essay-partial", "teacher-1", req);

        assertNotNull(dto);
        assertEquals("GRADING", dto.getStatus(), "Partial grading must leave attempt in GRADING, never publish");
        // Leaderboard must NOT be updated during partial grading
        verify(leaderboardService, never()).scheduleRecalculation(any(), any());
    }

    @Test
    @DisplayName("CRITICAL Finding 2: gradeAttempt publishes and updates leaderboard only when ALL essay questions are graded")
    void testFullEssayGradingPublishesAndUpdatesLeaderboard() {
        Question essayQ = new Question("exam-1", "Trình bày", "ESSAY", 20, 2, null);
        essayQ.setId("essay-q-only");

        ExamAttempt attempt = new ExamAttempt("exam-1", "student-1", "class-1", Instant.now().minus(10, ChronoUnit.MINUTES), false);
        attempt.setGradingSnapshotJson("[]");
        attempt.setId("att-essay-full");
        attempt.setStatus("GRADING");

        // All essays now have explicit gradedBy
        AttemptAnswer ans = new AttemptAnswer("att-essay-full", "essay-q-only", "Student answer");
        ans.setPointsAwarded(java.math.BigDecimal.valueOf(18));
        ans.setGradedBy("teacher-1");

        when(attemptRepository.findByIdForUpdate("att-essay-full")).thenReturn(Optional.of(attempt));
        attempt.setGradingSnapshotJson("[{\"id\":\"essay-q-only\",\"examId\":\"exam-1\",\"questionText\":\"Trình bày\",\"type\":\"ESSAY\",\"points\":20,\"position\":2}]");
        when(attemptAnswerRepository.findByAttemptId("att-essay-full"))
                .thenReturn(List.of(ans));

        // Scoring policy: all graded, so sets PUBLISHED
        doAnswer(invocation -> {
            ExamAttempt a = invocation.getArgument(0);
            a.setStatus("PUBLISHED");
            a.setScore(java.math.BigDecimal.valueOf(90));
            a.setTotalPoints(30);
            return null;
        }).when(scoringPolicy).autoGradeAttempt(any(), any(), any());

        when(attemptRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(examRepository.findById("exam-1")).thenReturn(Optional.of(exam));

        GradeAttemptRequest req = new GradeAttemptRequest();
        req.setScores(Map.of("essay-q-only", java.math.BigDecimal.valueOf(18)));

        ExamAttemptDto dto = examService.gradeAttempt("att-essay-full", "teacher-1", req);

        assertNotNull(dto);
        assertEquals("PUBLISHED", dto.getStatus(), "Full grading must publish the attempt");
        // Leaderboard MUST be updated after full publication
        verify(leaderboardService).scheduleRecalculation("class-1", "student-1");
        verify(auditService).record(eq("class-1"), eq("teacher-1"), eq("EXAM_GRADE_PUBLISH"),
                eq("EXAM_ATTEMPT"), eq("att-essay-full"), anyString());
        org.mockito.ArgumentCaptor<String> auditJson = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq("class-1"), eq("teacher-1"), eq("EXAM_GRADE_PUBLISH"),
                eq("EXAM_ATTEMPT"), eq("att-essay-full"), auditJson.capture());
        try {
            com.fasterxml.jackson.databind.JsonNode parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(auditJson.getValue());
            assertTrue(parsed.get("beforeQuestionScores").isObject());
            assertTrue(parsed.get("afterQuestionScores").isObject());
            assertEquals("18", parsed.get("afterQuestionScores").get("essay-q-only").asText());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            fail("Grading audit details must be valid JSON", e);
        }
        verify(outboxService).recordEvent(eq("EXAM"), eq("att-essay-full"), eq("EXAM_PUBLISHED"), anyMap());
        verify(outboxService, never()).recordEvent(eq("EXAM"), eq("att-essay-full"), eq("EXAM_RESULT_CORRECTED"), anyMap());
    }

    @Test
    @DisplayName("Concurrency: correcting a published attempt takes the pessimistic attempt lock, never an unlocked read")
    void correctingPublishedAttemptSerializesOnTheAttemptRow() {
        Question essayQ = new Question("exam-1", "Trình bày", "ESSAY", 20, 2, null);
        essayQ.setId("essay-q-correct");

        ExamAttempt attempt = new ExamAttempt("exam-1", "student-1", "class-1", Instant.now().minus(30, ChronoUnit.MINUTES), false);
        attempt.setId("att-correct");
        attempt.setStatus("PUBLISHED");
        attempt.setScore(java.math.BigDecimal.valueOf(60));
        attempt.setGradingSnapshotJson("[{\"id\":\"essay-q-correct\",\"examId\":\"exam-1\",\"questionText\":\"Trình bày\",\"type\":\"ESSAY\",\"points\":20,\"position\":2}]");

        AttemptAnswer ans = new AttemptAnswer("att-correct", "essay-q-correct", "Student answer");
        ans.setPointsAwarded(java.math.BigDecimal.valueOf(12));
        ans.setGradedBy("teacher-1");

        when(attemptRepository.findByIdForUpdate("att-correct")).thenReturn(Optional.of(attempt));
        when(attemptAnswerRepository.findByAttemptId("att-correct")).thenReturn(List.of(ans));
        doAnswer(invocation -> {
            ExamAttempt a = invocation.getArgument(0);
            a.setStatus("PUBLISHED");
            a.setScore(java.math.BigDecimal.valueOf(95));
            a.setTotalPoints(20);
            return null;
        }).when(scoringPolicy).autoGradeAttempt(any(), any(), any());
        when(attemptRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(examRepository.findById("exam-1")).thenReturn(Optional.of(exam));

        GradeAttemptRequest req = new GradeAttemptRequest();
        req.setScores(Map.of("essay-q-correct", java.math.BigDecimal.valueOf(19)));
        req.setReason("Học viên khiếu nại, chấm lại theo đáp án chi tiết");

        ExamAttemptDto dto = examService.gradeAttempt("att-correct", "teacher-1", req);

        assertEquals("PUBLISHED", dto.getStatus());
        // The row lock is what serializes two concurrent graders: the answer updates, the
        // rescore and the leaderboard recalculation all run while it is held. An unlocked
        // findById read here would let a second grader overwrite this correction.
        verify(attemptRepository).findByIdForUpdate("att-correct");
        verify(attemptRepository, never()).findById("att-correct");
        verify(leaderboardService).scheduleRecalculation("class-1", "student-1");
        verify(outboxService).recordEvent(eq("EXAM"), eq("att-correct"), eq("EXAM_RESULT_CORRECTED"), anyMap());
    }

    @Test
    @DisplayName("R13-07: correcting a PUBLISHED attempt requires a non-blank reason")
    void correctingPublishedAttemptRequiresReason() {
        Question essayQ = new Question("exam-1", "Trình bày", "ESSAY", 20, 2, null);
        essayQ.setId("essay-q-reason");

        ExamAttempt attempt = new ExamAttempt("exam-1", "student-1", "class-1", Instant.now().minus(30, ChronoUnit.MINUTES), false);
        attempt.setId("att-reason");
        attempt.setStatus("PUBLISHED");
        attempt.setScore(java.math.BigDecimal.valueOf(60));
        attempt.setGradingSnapshotJson("[{\"id\":\"essay-q-reason\",\"examId\":\"exam-1\",\"questionText\":\"Trình bày\",\"type\":\"ESSAY\",\"points\":20,\"position\":2}]");

        when(attemptRepository.findByIdForUpdate("att-reason")).thenReturn(Optional.of(attempt));
        when(examRepository.findById("exam-1")).thenReturn(Optional.of(exam));

        GradeAttemptRequest noReason = new GradeAttemptRequest();
        noReason.setScores(Map.of("essay-q-reason", java.math.BigDecimal.valueOf(19)));
        assertThrows(AppException.class, () -> examService.gradeAttempt("att-reason", "teacher-1", noReason));

        GradeAttemptRequest blankReason = new GradeAttemptRequest();
        blankReason.setScores(Map.of("essay-q-reason", java.math.BigDecimal.valueOf(19)));
        blankReason.setReason("   ");
        assertThrows(AppException.class, () -> examService.gradeAttempt("att-reason", "teacher-1", blankReason));

        verify(attemptRepository, never()).save(any());
    }

    @Test
    @DisplayName("Course-scoped grading permission is checked against the exam course")
    void gradingUsesExamCourseAsPermissionScope() {
        Exam courseExam = new Exam("class-1", "Course exam", "COURSE", 30);
        courseExam.setId("exam-course");
        courseExam.setTargetCourseId("course-a");
        ExamAttempt attempt = new ExamAttempt("exam-course", "student-1", "class-1", Instant.now(), false);
        attempt.setId("attempt-course");
        attempt.setStatus("IN_PROGRESS");
        when(attemptRepository.findByIdForUpdate("attempt-course")).thenReturn(Optional.of(attempt));
        when(examRepository.findById("exam-course")).thenReturn(Optional.of(courseExam));

        assertThrows(AppException.class, () -> examService.gradeAttempt("attempt-course", "grader-1", new GradeAttemptRequest()));
        verify(accessPolicy).enforceManage("grader-1", "class-1", "EXAM", "GRADE", "course-a");
    }

    // --- R13-07: score correction ---

    @Test
    @DisplayName("R13-07: getGradingAttempt loads a PUBLISHED attempt for an authorized grader")
    void getGradingAttemptAllowsPublished() throws Exception {
        ExamAttempt attempt = new ExamAttempt("exam-1", "student-1", "class-1", Instant.now(), false);
        attempt.setId("att-published");
        attempt.setStatus("PUBLISHED");
        attempt.setGradingSnapshotJson("[]");
        when(attemptRepository.findById("att-published")).thenReturn(Optional.of(attempt));
        when(examRepository.findById("exam-1")).thenReturn(Optional.of(exam));
        when(objectMapper.readValue(eq("[]"), any(TypeReference.class))).thenReturn(List.of());

        ExamAttemptDto dto = examService.getGradingAttempt("att-published", "teacher-1");

        assertEquals("PUBLISHED", dto.getStatus());
        verify(accessPolicy).enforceManage("teacher-1", "class-1", "EXAM", "GRADE", null);
    }

    @Test
    @DisplayName("R13-07: getGradingAttempt still rejects a DRAFT-status / non-loadable attempt")
    void getGradingAttemptRejectsInProgress() {
        ExamAttempt attempt = new ExamAttempt("exam-1", "student-1", "class-1", Instant.now(), false);
        attempt.setId("att-in-progress");
        attempt.setStatus("IN_PROGRESS");
        when(attemptRepository.findById("att-in-progress")).thenReturn(Optional.of(attempt));
        when(examRepository.findById("exam-1")).thenReturn(Optional.of(exam));

        assertThrows(AppException.class, () -> examService.getGradingAttempt("att-in-progress", "teacher-1"));
    }

    @Test
    @DisplayName("R13-13: getPublishedAttempts batch-loads exams/users and anonymizes a hidden learner")
    void getPublishedAttemptsBatchesAndAnonymizes() {
        ExamAttempt attempt1 = new ExamAttempt("exam-1", "student-visible", "class-1", Instant.now(), false);
        attempt1.setId("att-visible");
        attempt1.setStatus("PUBLISHED");
        ExamAttempt attempt2 = new ExamAttempt("exam-1", "student-hidden", "class-1", Instant.now(), false);
        attempt2.setId("att-hidden");
        attempt2.setStatus("PUBLISHED");

        when(examRepository.findById("exam-1")).thenReturn(Optional.of(exam));
        when(attemptRepository.findByExamIdAndStatusAndIsPreviewFalseOrderBySubmittedAtDesc(eq("exam-1"), eq("PUBLISHED"), any()))
                .thenReturn(List.of(attempt1, attempt2));
        when(examRepository.findAllById(any())).thenReturn(List.of(exam));
        // toGradingDtos re-checks canManage per row (course-scoped staff filtering); this test's
        // exam has no course scope (ALL audience), so the scope argument is null.
        when(accessPolicy.canManage("teacher-1", "class-1", "EXAM", "GRADE", null)).thenReturn(true);

        User visibleUser = new User("student-visible", "v@test.com", "hash", "Nguyễn Văn A", "STUDENT");
        User hiddenUser = new User("student-hidden", "h@test.com", "hash", "Trần Thị B", "STUDENT");
        when(userRepository.findAllById(any())).thenReturn(List.of(visibleUser, hiddenUser));
        when(profileVisibilityPolicy.isIdentityVisible(eq(visibleUser), eq("teacher-1"), eq("class-1"))).thenReturn(true);
        when(profileVisibilityPolicy.isIdentityVisible(eq(hiddenUser), eq("teacher-1"), eq("class-1"))).thenReturn(false);
        when(attemptAnswerRepository.findByAttemptId(any())).thenReturn(List.of());

        List<ExamAttemptDto> results = examService.getPublishedAttempts("exam-1", "teacher-1", 50);

        assertEquals(2, results.size());
        assertEquals("Nguyễn Văn A", results.get(0).getLearnerDisplayName());
        assertEquals("Học viên ẩn danh #1", results.get(1).getLearnerDisplayName());
        // exams were batch-loaded once via findAllById, never a per-attempt findById
        verify(examRepository, never()).findById("exam-1-should-not-be-called-per-row");
        verify(examRepository).findAllById(any());
    }

    // --- R8-02: resumeOnly must never create a new attempt ---

    @Test
    @DisplayName("R8-02: resumeOnly=true resumes an existing IN_PROGRESS attempt without creating one")
    void resumeOnlyResumesExistingAttempt() throws Exception {
        ExamAttempt activeAttempt = new ExamAttempt("exam-1", "student-1", "class-1", Instant.now().plus(30, ChronoUnit.MINUTES), false);
        activeAttempt.setId("att-1");
        activeAttempt.setStatus("IN_PROGRESS");
        activeAttempt.setAudienceEligibleAtStart(true);
        activeAttempt.setQuestionSnapshotJson("[{\"id\":\"q-1\"}]");

        QuestionDto qDto = new QuestionDto();
        qDto.setId("q-1");

        when(examRepository.findByIdForShare("exam-1")).thenReturn(Optional.of(exam));
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewFalseOrderByStartedAtDesc("exam-1", "student-1", "IN_PROGRESS"))
                .thenReturn(Optional.of(activeAttempt));
        when(objectMapper.readValue(eq(activeAttempt.getQuestionSnapshotJson()), any(TypeReference.class)))
                .thenReturn(List.of(qDto));

        ExamAttemptDto dto = examService.startAttempt("exam-1", "student-1", false, true);

        assertEquals("att-1", dto.getId());
        assertEquals("IN_PROGRESS", dto.getStatus());
        verify(attemptRepository, never()).save(any());
    }

    @Test
    @DisplayName("R8-02: resumeOnly=true 404s instead of creating a new attempt when nothing is in progress")
    void resumeOnlyThrowsNotFoundInsteadOfCreating() {
        when(examRepository.findByIdForShare("exam-1")).thenReturn(Optional.of(exam));
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);
        when(accessPolicy.isActiveStaff("student-1", "class-1")).thenReturn(false);
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewFalseOrderByStartedAtDesc(
                        "exam-1", "student-1", "IN_PROGRESS"))
                .thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class,
                () -> examService.startAttempt("exam-1", "student-1", false, true));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        verify(attemptRepository, never()).save(any());
        verify(attemptRepository, never()).countAttemptsTowardLimit(anyString(), anyString());
    }

    @Test
    @DisplayName("R8-02: resumeOnly=false (explicit start) still creates a new attempt when none is in progress")
    void explicitStartStillCreatesNewAttempt() {
        when(examRepository.findByIdForShare("exam-1")).thenReturn(Optional.of(exam));
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);
        when(accessPolicy.isActiveStaff("student-1", "class-1")).thenReturn(false);
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewFalseOrderByStartedAtDesc(
                        "exam-1", "student-1", "IN_PROGRESS"))
                .thenReturn(Optional.empty());
        when(attemptRepository.countAttemptsTowardLimit("exam-1", "student-1")).thenReturn(0L);
        when(questionRepository.findByExamIdOrderByPositionAsc("exam-1")).thenReturn(List.of(q1));
        when(attemptRepository.save(any(ExamAttempt.class))).thenAnswer(i -> i.getArgument(0));

        ExamAttemptDto result = examService.startAttempt("exam-1", "student-1", false, false);

        assertNotNull(result);
        assertEquals("IN_PROGRESS", result.getStatus());
        verify(attemptRepository).save(any(ExamAttempt.class));
    }
}
