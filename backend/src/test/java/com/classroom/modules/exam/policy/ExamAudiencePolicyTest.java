package com.classroom.modules.exam.policy;

import com.classroom.common.AppException;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.commerce.policy.ProPolicy;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import com.classroom.modules.exam.model.Exam;
import com.classroom.modules.exam.model.ExamAttempt;
import com.classroom.modules.exam.repository.ExamAttemptRepository;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.segment.service.SegmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ExamAudiencePolicyTest {

    @Mock
    private AccessPolicy accessPolicy;
    @Mock
    private ProPolicy proPolicy;
    @Mock
    private EntitlementRepository entitlementRepository;
    @Mock
    private ExamAttemptRepository attemptRepository;
    @Mock
    private SegmentService segmentService;
    @Mock
    private CourseRepository courseRepository;

    @InjectMocks
    private ExamAudiencePolicy audiencePolicy;

    private Exam examCourseA;
    private Exam examPro;

    @Test void suppliedLockedCountStillEnforcesLimitWithoutReadingItAgain() {
        var exam = new Exam("class-1", "Locked count", "ALL", 30);
        exam.setStatus("PUBLISHED"); exam.setAttemptLimit(1);
        when(accessPolicy.isMember("user-1", "class-1")).thenReturn(true);
        audiencePolicy.enforceEnterExam("user-1", exam, Instant.now(), false, false, 0L);
        assertEquals(com.classroom.common.ErrorCode.EXAM_ATTEMPT_LIMIT_REACHED,
                assertThrows(AppException.class, () -> audiencePolicy.enforceEnterExam(
                        "user-1", exam, Instant.now(), false, false, 1L)).getErrorCode());
        verifyNoInteractions(attemptRepository);
    }

    @Test void suppliedCountCannotBypassMembershipOrSchedule() {
        var exam = new Exam("class-1", "Locked count", "ALL", 30); exam.setStatus("PUBLISHED");
        assertEquals(com.classroom.common.ErrorCode.FORBIDDEN, assertThrows(AppException.class,
                () -> audiencePolicy.enforceEnterExam("user-1", exam, Instant.now(), false, false, 0L)).getErrorCode());
        when(accessPolicy.isMember("user-1", "class-1")).thenReturn(true);
        Instant now = Instant.now(); exam.setScheduleEnd(now);
        assertEquals(com.classroom.common.ErrorCode.EXAM_NOT_OPEN, assertThrows(AppException.class,
                () -> audiencePolicy.enforceEnterExam("user-1", exam, now, false, false, 0L)).getErrorCode());
        verifyNoInteractions(attemptRepository);
    }

    @Test void batchEligibilityMatchesIndividualPolicyAcrossLiveBoundaries() {
        Instant now = Instant.parse("2026-10-02T00:00:00Z");
        var exams = new java.util.ArrayList<Exam>();
        var active = new java.util.ArrayList<ExamAttempt>();
        var counts = new java.util.HashMap<String, Long>();
        for (int i = 0; i < 8; i++) {
            var exam = new Exam("class-1", "Boundary " + i, "ALL", 30);
            exam.setId("exam-" + i); exam.setStatus("PUBLISHED"); exam.setAttemptLimit(1);
            if (i == 1) exam.setStatus("DRAFT");
            if (i == 2) exam.setScheduleStart(now.plusSeconds(1));
            if (i == 3) exam.setScheduleEnd(now);
            if (i == 4 || i == 5 || i == 6) { exam.setStatus("CLOSED"); exam.setClosedAt(now.minusSeconds(10)); }
            if (i == 7) exam.setAudienceScope("PRO");
            counts.put(exam.getId(), i == 0 || i == 7 ? 0L : 1L);
            if (i >= 4 && i <= 6) {
                var attempt = new ExamAttempt(exam.getId(), "user-1", "class-1", now.plusSeconds(60), i == 6);
                attempt.setStartedAt(now.minusSeconds(20)); attempt.setAudienceEligibleAtStart(true);
                if (i == 5) attempt.setEndsAt(now);
                active.add(attempt);
            }
            exams.add(exam);
        }
        var grouped = counts.entrySet().stream().<ExamAttemptRepository.LimitCount>map(entry -> new ExamAttemptRepository.LimitCount() {
            public String getExamId() { return entry.getKey(); }
            public long getAttempts() { return entry.getValue(); }
        }).toList();
        when(attemptRepository.countAttemptsTowardLimitByClass("class-1", "user-1")).thenReturn(grouped);
        when(attemptRepository.findByClassIdAndUserIdAndStatus("class-1", "user-1", "IN_PROGRESS")).thenReturn(active);
        lenient().when(attemptRepository.countAttemptsTowardLimit(anyString(), eq("user-1")))
                .thenAnswer(call -> counts.get(call.getArgument(0)));
        lenient().when(attemptRepository.findFirstByExamIdAndUserIdAndStatusOrderByStartedAtDesc(anyString(), eq("user-1"), eq("IN_PROGRESS")))
                .thenAnswer(call -> active.stream().filter(a -> a.getExamId().equals(call.getArgument(0))).findFirst());
        lenient().when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewFalseOrderByStartedAtDesc(anyString(), eq("user-1"), eq("IN_PROGRESS")))
                .thenAnswer(call -> active.stream().filter(a -> !a.isPreview() && a.getExamId().equals(call.getArgument(0))).findFirst());
        for (boolean member : new boolean[]{true, false}) for (boolean archived : new boolean[]{false, true}) {
            when(accessPolicy.isMember("user-1", "class-1")).thenReturn(member);
            lenient().when(accessPolicy.isClassFrozen("class-1")).thenReturn(archived);
            var batch = audiencePolicy.listingEligibility("user-1", "class-1", exams, now);
            for (var exam : exams) {
                assertEquals(audiencePolicy.canEnterExam("user-1", exam, now, false), batch.get(exam.getId()).canEnter(),
                        exam.getId() + " member=" + member + " archived=" + archived);
                assertEquals(counts.get(exam.getId()).longValue(), batch.get(exam.getId()).attempts());
            }
        }
    }

    @Test void batchContextCannotBeReusedAcrossClassesOrAnonymousViewers() {
        assertThrows(IllegalArgumentException.class,
                () -> audiencePolicy.listingEligibility("user-1", "foreign-class", java.util.List.of(examPro), Instant.now()));
        var result = audiencePolicy.listingEligibility(null, "class-1", java.util.List.of(examPro), Instant.now());
        assertFalse(result.get(examPro.getId()).canEnter());
        assertEquals(0, result.get(examPro.getId()).attempts());
        verifyNoInteractions(attemptRepository, accessPolicy);
    }

    @BeforeEach
    void setUp() {
        examCourseA = new Exam("class-1", "Exam Course A", "COURSE", 45);
        examCourseA.setId("exam-course-a");
        examCourseA.setTargetCourseId("course-a");
        examCourseA.setStatus("PUBLISHED");
        examCourseA.setAttemptLimit(1);

        examPro = new Exam("class-1", "Exam PRO Members", "PRO", 60);
        examPro.setId("exam-pro");
        examPro.setStatus("PUBLISHED");
        examPro.setAttemptLimit(2);
    }

    @Test
    @DisplayName("TC-08 / UT-04: PRO user who bought course B is REJECTED when entering exam for course A")
    void testUserBuyingCourseBRejectedForCourseAExam() {
        String studentId = "student-b";
        Instant now = Instant.now();

        when(accessPolicy.isMember(studentId, "class-1")).thenReturn(true);
        when(attemptRepository.countAttemptsTowardLimit("exam-course-a", studentId)).thenReturn(0L);
        when(accessPolicy.isOwner(studentId, "class-1")).thenReturn(false);

        // Student has access to B, but NOT to course A
        Course courseA = new Course("class-1", "Course A", "PURCHASE_REQUIRED");
        when(courseRepository.findById("course-a")).thenReturn(Optional.of(courseA));
        assertFalse(audiencePolicy.canEnterExam(studentId, examCourseA, now, false));
        assertThrows(AppException.class, () -> audiencePolicy.enforceEnterExam(studentId, examCourseA, now, false));
    }

    @Test
    @DisplayName("Finding 4: Course target belonging to different class is rejected")
    void testCrossClassCourseAudienceRejected() {
        String studentId = "student-1";
        Instant now = Instant.now();

        when(accessPolicy.isMember(studentId, "class-1")).thenReturn(true);
        when(attemptRepository.countAttemptsTowardLimit("exam-course-a", studentId)).thenReturn(0L);

        // Course belongs to class-2, but exam is in class-1
        Course crossClassCourse = new Course("class-2", "Course Other Class", "PURCHASE_REQUIRED");
        when(courseRepository.findById("course-a")).thenReturn(Optional.of(crossClassCourse));

        assertFalse(audiencePolicy.canEnterExam(studentId, examCourseA, now, false));
        assertThrows(AppException.class, () -> audiencePolicy.enforceEnterExam(studentId, examCourseA, now, false));
    }

    @Test
    @DisplayName("TC-09: User with active PRO membership CAN enter exam for PRO members")
    void testProUserCanEnterProExam() {
        String proStudentId = "student-pro";
        Instant now = Instant.now();

        when(accessPolicy.isMember(proStudentId, "class-1")).thenReturn(true);
        when(attemptRepository.countAttemptsTowardLimit("exam-pro", proStudentId)).thenReturn(0L);
        when(proPolicy.isPro(proStudentId, "class-1")).thenReturn(true);

        assertTrue(audiencePolicy.canEnterExam(proStudentId, examPro, now, false));
        assertDoesNotThrow(() -> audiencePolicy.enforceEnterExam(proStudentId, examPro, now, false));
    }

    @Test
    @DisplayName("Attempt limit reached: rejects attempt even if audience is valid")
    void testAttemptLimitExceeded() {
        String proStudentId = "student-pro";
        Instant now = Instant.now();

        when(accessPolicy.isMember(proStudentId, "class-1")).thenReturn(true);
        when(attemptRepository.countAttemptsTowardLimit("exam-pro", proStudentId)).thenReturn(2L); // Limit is 2

        assertFalse(audiencePolicy.canEnterExam(proStudentId, examPro, now, false));
        assertThrows(AppException.class, () -> audiencePolicy.enforceEnterExam(proStudentId, examPro, now, false));
    }

    @Test
    @DisplayName("Round 8 Finding 1: enforceEnterExam allows resuming active attempt even when attempt limit is reached")
    void testResumeActiveAttemptAllowedWhenAttemptLimitReached() {
        String proStudentId = "student-pro";
        Instant now = Instant.now();

        when(accessPolicy.isMember(proStudentId, "class-1")).thenReturn(true);
        // Resume does not reevaluate mutable PRO/segment/course audience membership.
        assertDoesNotThrow(() -> audiencePolicy.enforceEnterExam(proStudentId, examPro, now, false, true));
    }

    @Test
    @DisplayName("A valid active attempt resumes after mutable audience eligibility changes")
    void resumeUsesEligibilityRecordedAtAttemptStart() {
        String studentId = "student-pro";
        Instant now = Instant.now();
        ExamAttempt startedAttempt = new ExamAttempt("exam-pro", studentId, "class-1", now.plusSeconds(600), false);
        startedAttempt.setAudienceEligibleAtStart(true);
        when(accessPolicy.isMember(studentId, "class-1")).thenReturn(true);

        assertDoesNotThrow(() -> audiencePolicy.enforceResumeAttempt(studentId, examPro, startedAttempt, now));
        verify(proPolicy, never()).isPro(studentId, "class-1");
    }

    @Test
    @DisplayName("Round 8 Finding 1: canEnterExam returns true when active in-progress attempt exists at attempt limit")
    void testCanEnterExamAllowsResumeActiveAttemptAtLimit() {
        String proStudentId = "student-pro";
        Instant now = Instant.now();

        when(accessPolicy.isMember(proStudentId, "class-1")).thenReturn(true);
        when(attemptRepository.countAttemptsTowardLimit("exam-pro", proStudentId)).thenReturn(2L);
        com.classroom.modules.exam.model.ExamAttempt activeAttempt = new com.classroom.modules.exam.model.ExamAttempt("exam-pro", proStudentId, "class-1", now.plusSeconds(1800), false);
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusOrderByStartedAtDesc("exam-pro", proStudentId, "IN_PROGRESS"))
                .thenReturn(Optional.of(activeAttempt));
        when(proPolicy.isPro(proStudentId, "class-1")).thenReturn(true);

        assertTrue(audiencePolicy.canEnterExam(proStudentId, examPro, now, false));
    }

    @Test
    @DisplayName("Unknown audience scope is denied by both decision and enforcement paths")
    void testUnknownAudienceScopeDenied() {
        String studentId = "student-1";
        Instant now = Instant.now();
        Exam malformed = new Exam("class-1", "Malformed", "UNKNOWN", 30);
        malformed.setId("exam-unknown");
        malformed.setStatus("PUBLISHED");

        when(accessPolicy.isMember(studentId, "class-1")).thenReturn(true);
        when(attemptRepository.countAttemptsTowardLimit("exam-unknown", studentId)).thenReturn(0L);

        assertFalse(audiencePolicy.canEnterExam(studentId, malformed, now, false));
        assertThrows(AppException.class, () -> audiencePolicy.enforceEnterExam(studentId, malformed, now, false));
    }

    @Test
    @DisplayName("Active entitlement for the targeted course allows COURSE exam")
    void paidCoursePurchaserCanEnterCourseExam() {
        String student = "buyer";
        Instant now = Instant.now();
        Course course = new Course("class-1", "Course A", "PURCHASE_REQUIRED");
        course.setId("course-a");
        course.setProductId("product-a");
        when(accessPolicy.isMember(student, "class-1")).thenReturn(true);
        when(attemptRepository.countAttemptsTowardLimit("exam-course-a", student)).thenReturn(0L);
        when(courseRepository.findById("course-a")).thenReturn(Optional.of(course));
        when(accessPolicy.isOwner(student, "class-1")).thenReturn(false);
        when(entitlementRepository.hasCourseAccess(student, "class-1", "course-a", "product-a", now)).thenReturn(true);

        assertTrue(audiencePolicy.canEnterExam(student, examCourseA, now, false));
        assertDoesNotThrow(() -> audiencePolicy.enforceEnterExam(student, examCourseA, now, false));
    }

    @Test
    @DisplayName("Version 1 combined audience enforces AND and OR consistently")
    void combinedCourseAndSegmentAudienceHonorsOperator() {
        String student = "buyer";
        Instant now = Instant.now();
        Exam combined = new Exam("class-1", "Combined", "COURSE_SEGMENT", 30);
        combined.setId("combined");
        combined.setTargetCourseId("course-a");
        combined.setTargetSegmentId("segment-a");
        when(accessPolicy.isMember(student, "class-1")).thenReturn(true);
        when(attemptRepository.countAttemptsTowardLimit("combined", student)).thenReturn(0L);
        Course course = new Course("class-1", "Course A", "PURCHASE_REQUIRED");
        course.setId("course-a"); course.setProductId("product-a");
        when(courseRepository.findById("course-a")).thenReturn(Optional.of(course));
        when(accessPolicy.isOwner(student, "class-1")).thenReturn(false);
        when(entitlementRepository.hasCourseAccess(student, "class-1", "course-a", "product-a", now)).thenReturn(true);
        when(segmentService.isUserInSegment("segment-a", student, "class-1")).thenReturn(false);

        assertFalse(audiencePolicy.canEnterExam(student, combined, now, false));
        assertThrows(AppException.class, () -> audiencePolicy.enforceEnterExam(student, combined, now, false));
        combined.setAudienceOperator("OR");
        assertTrue(audiencePolicy.canEnterExam(student, combined, now, false));
        assertDoesNotThrow(() -> audiencePolicy.enforceEnterExam(student, combined, now, false));
        combined.setAudienceRuleVersion(2);
        assertFalse(audiencePolicy.canEnterExam(student, combined, now, false));
    }

    @Test
    @DisplayName("TC-Preview-01: STAFF with a course-scoped EXAM:PREVIEW grant can preview the exam of that course")
    void testCourseScopedPreviewGrantIsAccepted() {
        String staffId = "staff-course-a";
        Instant now = Instant.now();

        when(accessPolicy.isOwner(staffId, "class-1")).thenReturn(false);
        // Grant is scoped to the exam's target course — the policy must check it with that scope.
        when(accessPolicy.canManage(staffId, "class-1", "EXAM", "PREVIEW", "course-a")).thenReturn(true);

        assertTrue(audiencePolicy.canEnterExam(staffId, examCourseA, now, true));
        assertDoesNotThrow(() -> audiencePolicy.enforceEnterExam(staffId, examCourseA, now, true));
    }

    @Test
    @DisplayName("TC-Preview-02: STAFF without any EXAM preview/edit grant is rejected for staff preview")
    void testPreviewRejectedWithoutGrant() {
        String staffId = "staff-other";
        Instant now = Instant.now();

        when(accessPolicy.isOwner(staffId, "class-1")).thenReturn(false);
        when(accessPolicy.canManage(staffId, "class-1", "EXAM", "PREVIEW", "course-a")).thenReturn(false);
        when(accessPolicy.canManage(staffId, "class-1", "EXAM", "EDIT", "course-a")).thenReturn(false);

        assertFalse(audiencePolicy.canEnterExam(staffId, examCourseA, now, true));
        assertThrows(AppException.class, () -> audiencePolicy.enforceEnterExam(staffId, examCourseA, now, true));
    }

    @Test
    @DisplayName("Finding 3: entry is refused at the exact closing instant (exclusive end boundary)")
    void testEntryRefusedAtExactScheduleEnd() {
        String studentId = "student-boundary";
        Instant scheduleEnd = Instant.parse("2026-01-01T10:00:00Z");

        Exam examAll = new Exam("class-1", "Exam All", "ALL", 30);
        examAll.setId("exam-all");
        examAll.setStatus("PUBLISHED");
        examAll.setAttemptLimit(1);
        examAll.setScheduleEnd(scheduleEnd);

        when(accessPolicy.isMember(studentId, "class-1")).thenReturn(true);

        assertFalse(audiencePolicy.canEnterExam(studentId, examAll, scheduleEnd, false));
        assertThrows(AppException.class,
                () -> audiencePolicy.enforceEnterExam(studentId, examAll, scheduleEnd, false));
    }

    @Test
    @DisplayName("Finding 3: entry is still allowed one second before the closing instant")
    void testEntryAllowedJustBeforeScheduleEnd() {
        String studentId = "student-boundary";
        Instant scheduleEnd = Instant.parse("2026-01-01T10:00:00Z");

        Exam examAll = new Exam("class-1", "Exam All", "ALL", 30);
        examAll.setId("exam-all");
        examAll.setStatus("PUBLISHED");
        examAll.setAttemptLimit(1);
        examAll.setScheduleEnd(scheduleEnd);

        when(accessPolicy.isMember(studentId, "class-1")).thenReturn(true);
        when(attemptRepository.countAttemptsTowardLimit("exam-all", studentId)).thenReturn(0L);

        Instant justBefore = scheduleEnd.minusSeconds(1);
        assertTrue(audiencePolicy.canEnterExam(studentId, examAll, justBefore, false));
        assertDoesNotThrow(() -> audiencePolicy.enforceEnterExam(studentId, examAll, justBefore, false));
    }

    // ----- R14-05 / R14-13: closing an exam or archiving a class stops NEW attempts only -----

    private static final String STUDENT = "student-close";
    private static final Instant CLOSED_AT = Instant.parse("2026-03-01T10:00:00Z");

    private Exam closedExam(String status) {
        Exam exam = new Exam("class-1", "Exam Closed", "ALL", 60);
        exam.setId("exam-closed");
        exam.setStatus(status);
        exam.setAttemptLimit(1);
        exam.setClosedAt(CLOSED_AT);
        return exam;
    }

    private ExamAttempt attemptStartedAt(Instant startedAt, Instant endsAt) {
        ExamAttempt attempt = new ExamAttempt("exam-closed", STUDENT, "class-1", endsAt, false);
        attempt.setStartedAt(startedAt);
        attempt.setAudienceEligibleAtStart(true);
        return attempt;
    }

    @Test
    @DisplayName("R14-05: an IN_PROGRESS attempt started before closedAt can still be resumed on a CLOSED exam")
    void resumeAllowedOnClosedExamForAttemptStartedBeforeClose() {
        Exam exam = closedExam("CLOSED");
        ExamAttempt attempt = attemptStartedAt(CLOSED_AT.minusSeconds(300), CLOSED_AT.plusSeconds(1800));
        when(accessPolicy.isMember(STUDENT, "class-1")).thenReturn(true);

        assertDoesNotThrow(() -> audiencePolicy.enforceResumeAttempt(STUDENT, exam, attempt, CLOSED_AT.plusSeconds(60)));
        assertTrue(audiencePolicy.canResumeAfterClose(exam, attempt));
    }

    @Test
    @DisplayName("R14-05: the same attempt also survives the exam being ARCHIVED after the close")
    void resumeAllowedOnArchivedExamForAttemptStartedBeforeClose() {
        Exam exam = closedExam("ARCHIVED");
        ExamAttempt attempt = attemptStartedAt(CLOSED_AT.minusSeconds(300), CLOSED_AT.plusSeconds(1800));
        when(accessPolicy.isMember(STUDENT, "class-1")).thenReturn(true);

        assertDoesNotThrow(() -> audiencePolicy.enforceResumeAttempt(STUDENT, exam, attempt, CLOSED_AT.plusSeconds(60)));
    }

    @Test
    @DisplayName("R14-05: an attempt that started AFTER closedAt (or a closed exam without closedAt) cannot be resumed")
    void resumeRejectedForAttemptStartedAfterClose() {
        Exam exam = closedExam("CLOSED");
        ExamAttempt late = attemptStartedAt(CLOSED_AT.plusSeconds(5), CLOSED_AT.plusSeconds(1800));
        when(accessPolicy.isMember(STUDENT, "class-1")).thenReturn(true);

        AppException ex = assertThrows(AppException.class,
                () -> audiencePolicy.enforceResumeAttempt(STUDENT, exam, late, CLOSED_AT.plusSeconds(60)));
        assertEquals(com.classroom.common.ErrorCode.EXAM_NOT_OPEN, ex.getErrorCode());

        Exam noClosedAt = closedExam("CLOSED");
        noClosedAt.setClosedAt(null);
        ExamAttempt early = attemptStartedAt(CLOSED_AT.minusSeconds(300), CLOSED_AT.plusSeconds(1800));
        assertThrows(AppException.class,
                () -> audiencePolicy.enforceResumeAttempt(STUDENT, noClosedAt, early, CLOSED_AT.plusSeconds(60)));
    }

    @Test
    @DisplayName("R14-05: resume on a CLOSED exam still respects membership and a finished/preview attempt")
    void resumeOnClosedExamStillChecksMembershipAndAttemptState() {
        Exam exam = closedExam("CLOSED");
        ExamAttempt attempt = attemptStartedAt(CLOSED_AT.minusSeconds(300), CLOSED_AT.plusSeconds(1800));

        when(accessPolicy.isMember(STUDENT, "class-1")).thenReturn(false);
        AppException ex = assertThrows(AppException.class,
                () -> audiencePolicy.enforceResumeAttempt(STUDENT, exam, attempt, CLOSED_AT.plusSeconds(60)));
        assertEquals(com.classroom.common.ErrorCode.FORBIDDEN, ex.getErrorCode());

        ExamAttempt submitted = attemptStartedAt(CLOSED_AT.minusSeconds(300), CLOSED_AT.plusSeconds(1800));
        submitted.setStatus("SUBMITTED");
        assertFalse(audiencePolicy.canResumeAfterClose(exam, submitted));
        ExamAttempt preview = new ExamAttempt("exam-closed", STUDENT, "class-1", CLOSED_AT.plusSeconds(1800), true);
        preview.setStartedAt(CLOSED_AT.minusSeconds(300));
        assertFalse(audiencePolicy.canResumeAfterClose(exam, preview));
    }

    @Test
    @DisplayName("R14-05: a PUBLISHED exam resume is unchanged, and a DRAFT exam still cannot be resumed")
    void resumeOnOpenStatusesUnchanged() {
        ExamAttempt attempt = attemptStartedAt(CLOSED_AT.minusSeconds(300), CLOSED_AT.plusSeconds(1800));
        when(accessPolicy.isMember(STUDENT, "class-1")).thenReturn(true);

        assertDoesNotThrow(() -> audiencePolicy.enforceResumeAttempt(STUDENT, closedExam("PUBLISHED"), attempt, CLOSED_AT.minusSeconds(60)));
        assertThrows(AppException.class,
                () -> audiencePolicy.enforceResumeAttempt(STUDENT, closedExam("DRAFT"), attempt, CLOSED_AT.minusSeconds(60)));
    }

    @Test
    @DisplayName("R14-05: NEW attempts stay blocked on CLOSED and ARCHIVED exams")
    void newAttemptsBlockedOnClosedAndArchivedExams() {
        when(accessPolicy.isMember(STUDENT, "class-1")).thenReturn(true);

        for (String status : new String[]{"CLOSED", "ARCHIVED"}) {
            Exam exam = closedExam(status);
            AppException ex = assertThrows(AppException.class,
                    () -> audiencePolicy.enforceEnterExam(STUDENT, exam, CLOSED_AT.plusSeconds(60), false));
            assertEquals(com.classroom.common.ErrorCode.EXAM_NOT_OPEN, ex.getErrorCode());
        }
    }

    @Test
    @DisplayName("R14-05: canEnterExam is true on a CLOSED exam only while the learner has a running pre-close attempt")
    void canEnterClosedExamOnlyWithRunningAttempt() {
        Exam exam = closedExam("CLOSED");
        Instant now = CLOSED_AT.plusSeconds(60);
        when(accessPolicy.isMember(STUDENT, "class-1")).thenReturn(true);

        // No attempt at all -> cannot enter.
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewFalseOrderByStartedAtDesc(
                "exam-closed", STUDENT, "IN_PROGRESS")).thenReturn(Optional.empty());
        assertFalse(audiencePolicy.canEnterExam(STUDENT, exam, now, false));

        // Running attempt started before the close -> can continue.
        ExamAttempt running = attemptStartedAt(CLOSED_AT.minusSeconds(300), CLOSED_AT.plusSeconds(1800));
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewFalseOrderByStartedAtDesc(
                "exam-closed", STUDENT, "IN_PROGRESS")).thenReturn(Optional.of(running));
        assertTrue(audiencePolicy.canEnterExam(STUDENT, exam, now, false));

        // Its own deadline has passed -> no longer.
        assertFalse(audiencePolicy.canEnterExam(STUDENT, exam, CLOSED_AT.plusSeconds(1800), false));
    }

    @Test
    @DisplayName("R14-13: an ARCHIVED class accepts no NEW exam attempt (clear Vietnamese message) but a running one may continue")
    void archivedClassBlocksNewAttemptsOnly() {
        Exam exam = new Exam("class-1", "Exam All", "ALL", 30);
        exam.setId("exam-all");
        exam.setStatus("PUBLISHED");
        exam.setAttemptLimit(2);
        Instant now = Instant.parse("2026-03-02T10:00:00Z");
        when(accessPolicy.isMember(STUDENT, "class-1")).thenReturn(true);
        when(accessPolicy.isClassFrozen("class-1")).thenReturn(true);

        AppException ex = assertThrows(AppException.class,
                () -> audiencePolicy.enforceEnterExam(STUDENT, exam, now, false));
        assertEquals(com.classroom.common.ErrorCode.EXAM_NOT_OPEN, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("Lớp học đã được lưu trữ"));

        // Resuming an existing attempt is not "entering" (isResume=true) and is unaffected.
        assertDoesNotThrow(() -> audiencePolicy.enforceEnterExam(STUDENT, exam, now, false, true));

        // canEnterExam: nothing running -> false; a running attempt -> true.
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewFalseOrderByStartedAtDesc(
                "exam-all", STUDENT, "IN_PROGRESS")).thenReturn(Optional.empty());
        assertFalse(audiencePolicy.canEnterExam(STUDENT, exam, now, false));
        ExamAttempt running = new ExamAttempt("exam-all", STUDENT, "class-1", now.plusSeconds(600), false);
        running.setAudienceEligibleAtStart(true);
        when(attemptRepository.findFirstByExamIdAndUserIdAndStatusAndIsPreviewFalseOrderByStartedAtDesc(
                "exam-all", STUDENT, "IN_PROGRESS")).thenReturn(Optional.of(running));
        assertTrue(audiencePolicy.canEnterExam(STUDENT, exam, now, false));
    }

    @Test
    @DisplayName("R14-13: staff preview is not blocked by an ARCHIVED class")
    void staffPreviewNotBlockedByArchivedClass() {
        Exam exam = new Exam("class-1", "Exam All", "ALL", 30);
        exam.setId("exam-all");
        exam.setStatus("DRAFT");
        when(accessPolicy.isOwner("owner-1", "class-1")).thenReturn(true);

        assertDoesNotThrow(() -> audiencePolicy.enforceEnterExam("owner-1", exam, Instant.now(), true));
        verify(accessPolicy, never()).isClassFrozen(any());
    }
}
