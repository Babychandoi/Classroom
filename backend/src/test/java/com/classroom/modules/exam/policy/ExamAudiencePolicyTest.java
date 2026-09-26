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
        when(attemptRepository.countByExamIdAndUserIdAndIsPreviewFalse("exam-course-a", studentId)).thenReturn(0L);
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
        when(attemptRepository.countByExamIdAndUserIdAndIsPreviewFalse("exam-course-a", studentId)).thenReturn(0L);

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
        when(attemptRepository.countByExamIdAndUserIdAndIsPreviewFalse("exam-pro", proStudentId)).thenReturn(0L);
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
        when(attemptRepository.countByExamIdAndUserIdAndIsPreviewFalse("exam-pro", proStudentId)).thenReturn(2L); // Limit is 2

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
        when(attemptRepository.countByExamIdAndUserIdAndIsPreviewFalse("exam-pro", proStudentId)).thenReturn(2L);
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
        when(attemptRepository.countByExamIdAndUserIdAndIsPreviewFalse("exam-unknown", studentId)).thenReturn(0L);

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
        when(attemptRepository.countByExamIdAndUserIdAndIsPreviewFalse("exam-course-a", student)).thenReturn(0L);
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
        when(attemptRepository.countByExamIdAndUserIdAndIsPreviewFalse("combined", student)).thenReturn(0L);
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
        when(attemptRepository.countByExamIdAndUserIdAndIsPreviewFalse("exam-all", studentId)).thenReturn(0L);

        Instant justBefore = scheduleEnd.minusSeconds(1);
        assertTrue(audiencePolicy.canEnterExam(studentId, examAll, justBefore, false));
        assertDoesNotThrow(() -> audiencePolicy.enforceEnterExam(studentId, examAll, justBefore, false));
    }
}
