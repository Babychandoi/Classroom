package com.classroom.modules.exam.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.commerce.policy.ProPolicy;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import com.classroom.modules.exam.dto.ExamAttemptDto;
import com.classroom.modules.exam.dto.GradeAttemptRequest;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

/**
 * R19-04: PREVIEW attempts have no attempt limit, and a graded attempt's score / per-question points ARE the answer
 * key. Staff holding only EXAM:PREVIEW (no explicit EXAM:EDIT, which is what {@code canAccessAnswerKey} requires) could
 * therefore resubmit different answers and read the key off the results. Runs the REAL scoring and audience policies
 * over an in-memory attempt store, so what is asserted is what a previewer is actually told.
 */
@ExtendWith(MockitoExtension.class)
class ExamPreviewAnswerKeyProtectionTest {

    private static final String CLASS_ID = "class-1";
    private static final String OWNER = "owner-1";
    private static final String PREVIEWER = "previewer-1"; // staff: EXAM:PREVIEW only
    private static final String EDITOR = "editor-1";       // staff: explicit EXAM:EDIT
    private static final String KEY_HOLDER_NOTICE = ExamService.PREVIEW_RESULT_HIDDEN_NOTICE;

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
    private List<Question> questions;

    private final Map<String, ExamAttempt> attempts = new HashMap<>();
    private final Map<String, List<AttemptAnswer>> answersByAttempt = new HashMap<>();

    @BeforeEach
    void setUp() {
        ExamAudiencePolicy audiencePolicy = new ExamAudiencePolicy(accessPolicy, proPolicy, entitlementRepository,
                attemptRepository, segmentService, courseRepository);
        examService = new ExamService(examRepository, questionRepository, optionRepository, attemptRepository,
                attemptAnswerRepository, audiencePolicy, new ExamScoringPolicy(), accessPolicy, leaderboardService,
                objectMapper, courseRepository, segmentRepository, auditService, outboxService, userRepository,
                profileVisibilityPolicy);

        exam = new Exam(CLASS_ID, "Kỳ thi giữa kỳ", "ALL", 60);
        exam.setId("exam-1");
        exam.setAttemptLimit(1);
        exam.setStatus("PUBLISHED");

        // Three multiple-choice questions with a secret key: B, D, A.
        Question q1 = new Question("exam-1", "Câu 1", "MULTIPLE_CHOICE", 10, 1, "B");
        q1.setId("q-1");
        Question q2 = new Question("exam-1", "Câu 2", "MULTIPLE_CHOICE", 10, 2, "D");
        q2.setId("q-2");
        Question q3 = new Question("exam-1", "Câu 3", "MULTIPLE_CHOICE", 10, 3, "A");
        q3.setId("q-3");
        questions = List.of(q1, q2, q3);

        lenient().when(examRepository.findByIdForShare("exam-1")).thenReturn(Optional.of(exam));

        lenient().when(examRepository.findByIdForUpdate("exam-1")).thenReturn(Optional.of(exam));
        lenient().when(examRepository.findById("exam-1")).thenReturn(Optional.of(exam));
        lenient().when(questionRepository.findByExamIdOrderByPositionAsc("exam-1")).thenReturn(questions);

        lenient().when(attemptRepository.save(any(ExamAttempt.class))).thenAnswer(inv -> {
            ExamAttempt a = inv.getArgument(0);
            attempts.put(a.getId(), a);
            return a;
        });
        lenient().when(attemptRepository.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(attempts.get(inv.<String>getArgument(0))));
        lenient().when(attemptRepository.findByIdForUpdate(anyString())).thenAnswer(inv -> Optional.ofNullable(attempts.get(inv.<String>getArgument(0))));
        lenient().when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewTrueOrderByStartedAtDesc(anyString(), anyString(), eq("IN_PROGRESS")))
                .thenAnswer(inv -> attempts.values().stream()
                        .filter(a -> a.getUserId().equals(inv.<String>getArgument(1)) && a.isPreview() && "IN_PROGRESS".equals(a.getStatus()))
                        .findFirst());
        lenient().when(attemptRepository.findByExamIdAndUserIdOrderByStartedAtDesc(anyString(), anyString())).thenAnswer(inv ->
                attempts.values().stream().filter(a -> a.getUserId().equals(inv.<String>getArgument(1)))
                        .sorted(Comparator.comparing(ExamAttempt::getStartedAt).reversed()).toList());

        lenient().when(attemptAnswerRepository.findByAttemptId(anyString())).thenAnswer(inv ->
                new ArrayList<>(answersByAttempt.getOrDefault(inv.<String>getArgument(0), List.of())));
        lenient().when(attemptAnswerRepository.save(any(AttemptAnswer.class))).thenAnswer(inv -> {
            AttemptAnswer a = inv.getArgument(0);
            List<AttemptAnswer> list = answersByAttempt.computeIfAbsent(a.getAttemptId(), k -> new ArrayList<>());
            if (!list.contains(a)) list.add(a);
            return a;
        });

        // Roles: the owner; a staff member with EXAM:PREVIEW only; a staff member with explicit EXAM:EDIT.
        lenient().when(accessPolicy.isOwner(OWNER, CLASS_ID)).thenReturn(true);
        grantPreviewOnly(true);
        lenient().when(accessPolicy.canManage(EDITOR, CLASS_ID, "EXAM", "EDIT", null)).thenReturn(true);
        lenient().when(accessPolicy.canAccessAnswerKey(EDITOR, CLASS_ID, null)).thenReturn(true);
    }

    private void grantPreviewOnly(boolean granted) {
        lenient().when(accessPolicy.canManage(PREVIEWER, CLASS_ID, "EXAM", "PREVIEW", null)).thenReturn(granted);
        lenient().when(accessPolicy.canManage(PREVIEWER, CLASS_ID, "EXAM", "EDIT", null)).thenReturn(false);
        lenient().when(accessPolicy.canAccessAnswerKey(PREVIEWER, CLASS_ID, null)).thenReturn(false);
    }

    /** Starts a preview and submits the given answers in one go, returning what the submit call told the caller. */
    private ExamAttemptDto previewSubmit(String user, Map<String, String> answers) {
        ExamAttemptDto started = examService.startAttempt("exam-1", user, true);
        SubmitAttemptRequest request = new SubmitAttemptRequest();
        request.setAnswers(answers);
        return examService.submitAttempt(started.getId(), user, request);
    }

    private static void assertNothingRevealed(ExamAttemptDto dto) {
        assertNull(dto.getScore(), "score must not be revealed");
        assertEquals(0, dto.getTotalPoints());
        assertEquals("SUBMITTED", dto.getStatus(), "the attempt is returned as submitted, never as graded/published");
        assertTrue(dto.isResultHidden());
        assertEquals(ExamService.PREVIEW_RESULT_HIDDEN_NOTICE, dto.getNotice());
        assertTrue(dto.isPreview());
        assertNotNull(dto.getAnswers());
        for (ExamAttemptDto.AttemptAnswerDto answer : dto.getAnswers()) {
            assertNull(answer.getPointsAwarded(), "per-question correctness must not be revealed");
            assertNull(answer.getTeacherFeedback());
            assertNotNull(answer.getStudentAnswer(), "the learner's own answer stays visible");
        }
    }

    @Test
    @DisplayName("R19-04: the notice is the agreed neutral Vietnamese sentence")
    void noticeText() {
        assertEquals("Chế độ xem thử: không hiển thị điểm/đáp án cho quyền của bạn", KEY_HOLDER_NOTICE);
    }

    @Test
    @DisplayName("R19-04: submitting a preview as EXAM:PREVIEW-only staff returns the attempt without score, points or status PUBLISHED")
    void previewOnlySubmitRevealsNothing() {
        ExamAttemptDto dto = previewSubmit(PREVIEWER, Map.of("q-1", "B", "q-2", "D", "q-3", "A")); // the fully correct sheet

        assertNothingRevealed(dto);
        assertEquals(3, dto.getAnswers().size());
        // ... while the graded state is still persisted internally (the attempt was really graded).
        ExamAttempt stored = attempts.get(dto.getId());
        assertEquals("PUBLISHED", stored.getStatus());
        assertEquals(0, new java.math.BigDecimal("100.00").compareTo(stored.getScore()));
    }

    @Test
    @DisplayName("R19-04: the k-submissions oracle is closed - every response is identical whatever was answered, so nothing can be learnt")
    void kSubmissionsOracleLearnsNothing() {
        // The classic key-recovery: submit all-A, all-B, all-C, all-D and read which questions scored.
        List<ExamAttemptDto> responses = new ArrayList<>();
        for (String guess : List.of("A", "B", "C", "D")) {
            responses.add(previewSubmit(PREVIEWER, Map.of("q-1", guess, "q-2", guess, "q-3", guess)));
        }
        for (ExamAttemptDto response : responses) {
            assertNothingRevealed(response);
        }
        // Reading the attempts back through the other surfaces leaks nothing either.
        for (ExamAttemptDto response : responses) {
            assertNothingRevealed(examService.getAttemptResult(response.getId(), PREVIEWER));
        }
        List<ExamAttemptDto> mine = examService.getMyAttempts("exam-1", PREVIEWER);
        assertEquals(4, mine.size());
        mine.forEach(ExamPreviewAnswerKeyProtectionTest::assertNothingRevealed);

        // Nothing differs between the responses in any field that depends on correctness.
        assertEquals(1, responses.stream().map(ExamAttemptDto::getScore).distinct().count());
        assertEquals(1, responses.stream().map(ExamAttemptDto::getStatus).distinct().count());
    }

    @Test
    @DisplayName("R19-04: resubmitting an already submitted preview (idempotent path) and a deadline finalize reveal nothing either")
    void idempotentResubmitAndTimeoutRevealNothing() {
        ExamAttemptDto first = previewSubmit(PREVIEWER, Map.of("q-1", "B"));
        SubmitAttemptRequest request = new SubmitAttemptRequest();
        request.setAnswers(Map.of("q-1", "A"));
        assertNothingRevealed(examService.submitAttempt(first.getId(), PREVIEWER, request));

        // A preview whose deadline passed is finalized by the submit call; that path is masked too.
        ExamAttemptDto running = examService.startAttempt("exam-1", PREVIEWER, true);
        attempts.get(running.getId()).setEndsAt(java.time.Instant.now().minusSeconds(5));
        assertNothingRevealed(examService.submitAttempt(running.getId(), PREVIEWER, request));
    }

    @Test
    @DisplayName("R19-04: grading a preview attempt cannot be used as an oracle by a grader without answer-key access")
    void gradeEndpointDoesNotLeakPreviewScoring() {
        ExamAttemptDto submitted = previewSubmit(PREVIEWER, Map.of("q-1", "B", "q-2", "A"));
        GradeAttemptRequest grade = new GradeAttemptRequest();
        grade.setReason("kiểm tra");

        assertNothingRevealed(examService.gradeAttempt(submitted.getId(), PREVIEWER, grade));
    }

    @Test
    @DisplayName("R19-04: owner and explicit EXAM:EDIT staff keep the full preview result (score and per-question points)")
    void ownerAndEditorKeepScoring() {
        for (String user : List.of(OWNER, EDITOR)) {
            ExamAttemptDto dto = previewSubmit(user, Map.of("q-1", "B", "q-2", "A", "q-3", "A")); // 2 of 3 right

            assertEquals("PUBLISHED", dto.getStatus(), user);
            assertNotNull(dto.getScore(), user);
            assertEquals(0, new java.math.BigDecimal("66.67").compareTo(dto.getScore()), user);
            assertEquals(30, dto.getTotalPoints(), user);
            assertFalse(dto.isResultHidden(), user);
            assertNull(dto.getNotice(), user);
            Map<String, java.math.BigDecimal> points = new HashMap<>();
            dto.getAnswers().forEach(a -> points.put(a.getQuestionId(), a.getPointsAwarded()));
            assertEquals(0, java.math.BigDecimal.TEN.compareTo(points.get("q-1")), user);
            assertEquals(0, java.math.BigDecimal.ZERO.compareTo(points.get("q-2")), user);

            ExamAttemptDto viaResult = examService.getAttemptResult(dto.getId(), user);
            assertNotNull(viaResult.getScore(), user);
            List<ExamAttemptDto> mine = examService.getMyAttempts("exam-1", user);
            assertFalse(mine.get(0).isResultHidden(), user);
            assertNotNull(mine.get(0).getScore(), user);
        }
    }

    @Test
    @DisplayName("R19-04: staff whose EXAM:PREVIEW grant was removed can no longer save, submit, read or list their in-flight preview attempt")
    void removedAccessBlocksExistingPreviewAttempts() {
        ExamAttemptDto submitted = previewSubmit(PREVIEWER, Map.of("q-1", "B"));
        ExamAttemptDto running = examService.startAttempt("exam-1", PREVIEWER, true); // a second, still-open preview
        assertNotEquals(submitted.getId(), running.getId());
        assertEquals(2, examService.getMyAttempts("exam-1", PREVIEWER).size());

        grantPreviewOnly(false); // the owner removes the staff grant
        lenient().when(accessPolicy.isMember(PREVIEWER, CLASS_ID)).thenReturn(true);

        SubmitAttemptRequest request = new SubmitAttemptRequest();
        request.setAnswers(Map.of("q-1", "B"));
        assertEquals(ErrorCode.STAFF_PERMISSION_DENIED, assertThrows(AppException.class,
                () -> examService.saveAnswers(running.getId(), PREVIEWER, Map.of("q-1", "B"))).getErrorCode());
        assertEquals(ErrorCode.STAFF_PERMISSION_DENIED, assertThrows(AppException.class,
                () -> examService.submitAttempt(running.getId(), PREVIEWER, request)).getErrorCode());
        assertEquals(ErrorCode.STAFF_PERMISSION_DENIED, assertThrows(AppException.class,
                () -> examService.getAttemptResult(submitted.getId(), PREVIEWER)).getErrorCode());
        assertTrue(examService.getMyAttempts("exam-1", PREVIEWER).isEmpty(), "the former staff member no longer sees preview attempts");
    }

    @Test
    @DisplayName("R19-04: nobody but the person who ran a preview can read its result (a VIEW/GRADE grant is not enough)")
    void previewResultIsPrivateToItsOwner() {
        ExamAttemptDto submitted = previewSubmit(EDITOR, Map.of("q-1", "B"));
        lenient().when(accessPolicy.canManage("grader-1", CLASS_ID, "EXAM", "VIEW", null)).thenReturn(true);
        lenient().when(accessPolicy.canManage("grader-1", CLASS_ID, "EXAM", "GRADE", null)).thenReturn(true);

        assertEquals(ErrorCode.FORBIDDEN, assertThrows(AppException.class,
                () -> examService.getAttemptResult(submitted.getId(), "grader-1")).getErrorCode());
    }

    @Test
    @DisplayName("R19-04: a normal learner attempt is unaffected (its PUBLISHED result still shows the score)")
    void learnerAttemptsAreUnaffected() {
        ExamAttempt learnerAttempt = new ExamAttempt("exam-1", "student-1", CLASS_ID, java.time.Instant.now().plusSeconds(600), false);
        learnerAttempt.setId("att-learner");
        learnerAttempt.setStatus("PUBLISHED");
        learnerAttempt.setScore(new java.math.BigDecimal("80.00"));
        learnerAttempt.setTotalPoints(30);
        attempts.put("att-learner", learnerAttempt);

        ExamAttemptDto dto = examService.getAttemptResult("att-learner", "student-1");

        assertEquals("PUBLISHED", dto.getStatus());
        assertEquals(0, new java.math.BigDecimal("80.00").compareTo(dto.getScore()));
        assertFalse(dto.isResultHidden());
    }
}
