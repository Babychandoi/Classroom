package com.classroom.modules.learning.service;

import com.classroom.common.AppException;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.model.StaffAssignment;
import com.classroom.modules.classroom.model.StaffPermission;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.StaffAssignmentRepository;
import com.classroom.modules.classroom.repository.StaffPermissionRepository;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.policy.ProfileVisibilityPolicy;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.learning.dto.AssignmentSubmissionDto;
import com.classroom.modules.learning.model.AssignmentSubmission;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.model.Lesson;
import com.classroom.modules.learning.policy.LearningPolicy;
import com.classroom.modules.learning.repository.AssignmentSubmissionRepository;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.learning.repository.LessonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AssignmentSecurityTest {

    @Mock
    private LessonRepository lessonRepository;
    @Mock
    private CourseRepository courseRepository;
    @Mock
    private AssignmentSubmissionRepository submissionRepository;
    @Mock
    private LearningPolicy learningPolicy;
    @Mock
    private AccessPolicy accessPolicy;
    @Mock
    private AuditService auditService;
    @Mock
    private StaffAssignmentRepository staffAssignmentRepository;
    @Mock
    private StaffPermissionRepository staffPermissionRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ProfileVisibilityPolicy profileVisibilityPolicy;

    @InjectMocks
    private AssignmentService assignmentService;

    private Lesson assignmentLesson;
    private Course course;

    @BeforeEach
    void setUp() {
        course = new Course("class-1", "Test Course", "FREE");
        course.setId("course-1");

        assignmentLesson = new Lesson("section-1", "course-1", "Assignment 1", "ASSIGNMENT", 1);
        assignmentLesson.setId("lesson-1");

        // Batch DTO mapping (R12-03): default every lookup to empty/false so tests that don't care
        // about lesson/course titles or learner display name still pass without over-stubbing.
        lenient().when(lessonRepository.findAllById(any())).thenReturn(List.of());
        lenient().when(courseRepository.findAllById(any())).thenReturn(List.of());
        lenient().when(userRepository.findAllById(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("R12-02: classQueue enforces membership, resolves permission once, and asks the repository for only the caller's scoped courses")
    void testClassQueueFiltersByPermission() {
        String staffId = "staff-1";
        String classId = "class-1";

        AssignmentSubmission sub1 = new AssignmentSubmission("lesson-1", "course-1", classId, "student-1", 1, "Answer 1");

        when(accessPolicy.isOwner(staffId, classId)).thenReturn(false);
        when(accessPolicy.canManage(staffId, classId, "COURSE", "GRADE", null)).thenReturn(false);
        StaffAssignment assignment = new StaffAssignment(classId, staffId);
        assignment.setId("assignment-1");
        when(staffAssignmentRepository.findByClassIdAndUserId(classId, staffId)).thenReturn(Optional.of(assignment));
        when(staffPermissionRepository.findByAssignmentId("assignment-1"))
                .thenReturn(List.of(new StaffPermission("assignment-1", "COURSE", "GRADE", "course-1")));
        when(submissionRepository.findPendingByClassIdAndCourseIdIn(eq(classId), eq(List.of("course-1")), any(Pageable.class)))
                .thenReturn(List.of(sub1));

        List<AssignmentSubmissionDto> queue = assignmentService.classQueue(classId, staffId);

        verify(accessPolicy).enforceMember(staffId, classId);
        // Permission is resolved exactly once - not per submission row.
        verify(staffAssignmentRepository, times(1)).findByClassIdAndUserId(classId, staffId);
        verify(staffPermissionRepository, times(1)).findByAssignmentId("assignment-1");
        verify(submissionRepository, never()).findPendingByClassId(anyString(), any());
        assertEquals(1, queue.size());
        assertEquals("course-1", queue.get(0).getCourseId());
    }

    @Test
    @DisplayName("R12-02: OWNER sees every course's pending submissions via the unscoped query, no permission resolution needed")
    void testClassQueueOwnerSeesAllCourses() {
        String ownerId = "owner-1";
        String classId = "class-1";
        AssignmentSubmission sub = new AssignmentSubmission("lesson-1", "course-1", classId, "student-1", 1, "Answer 1");

        when(accessPolicy.isOwner(ownerId, classId)).thenReturn(true);
        when(submissionRepository.findPendingByClassId(eq(classId), any(Pageable.class))).thenReturn(List.of(sub));

        List<AssignmentSubmissionDto> queue = assignmentService.classQueue(classId, ownerId);

        verify(accessPolicy).enforceMember(ownerId, classId);
        verifyNoInteractions(staffAssignmentRepository);
        assertEquals(1, queue.size());
    }

    @Test
    @DisplayName("R12-02: a staff member with no COURSE:GRADE grant at all gets an empty queue without querying submissions")
    void testClassQueueNoGrantReturnsEmptyWithoutQuery() {
        String staffId = "staff-1";
        String classId = "class-1";

        when(accessPolicy.isOwner(staffId, classId)).thenReturn(false);
        when(accessPolicy.canManage(staffId, classId, "COURSE", "GRADE", null)).thenReturn(false);
        when(staffAssignmentRepository.findByClassIdAndUserId(classId, staffId)).thenReturn(Optional.empty());

        List<AssignmentSubmissionDto> queue = assignmentService.classQueue(classId, staffId);

        verify(accessPolicy).enforceMember(staffId, classId);
        assertTrue(queue.isEmpty());
        verifyNoInteractions(submissionRepository);
    }

    @Test
    @DisplayName("R12-03: classQueue batch-loads lesson/course titles and the learner's display name in one query each, never per row")
    void testClassQueueReturnsDtoWithBatchLoadedTitlesAndVisibleLearnerName() {
        String staffId = "owner-1";
        String classId = "class-1";
        AssignmentSubmission sub1 = new AssignmentSubmission("lesson-1", "course-1", classId, "student-1", 1, "Answer 1");
        AssignmentSubmission sub2 = new AssignmentSubmission("lesson-2", "course-1", classId, "student-2", 1, "Answer 2");

        when(accessPolicy.isOwner(staffId, classId)).thenReturn(true);
        when(submissionRepository.findPendingByClassId(eq(classId), any(Pageable.class))).thenReturn(List.of(sub1, sub2));

        Lesson lesson1 = new Lesson("section-1", "course-1", "Bai tap 1", "ASSIGNMENT", 1);
        lesson1.setId("lesson-1");
        Lesson lesson2 = new Lesson("section-1", "course-1", "Bai tap 2", "ASSIGNMENT", 2);
        lesson2.setId("lesson-2");
        when(lessonRepository.findAllById(any())).thenReturn(List.of(lesson1, lesson2));
        Course courseRow = new Course(classId, "Khoa hoc Toan", "FREE");
        courseRow.setId("course-1");
        when(courseRepository.findAllById(any())).thenReturn(List.of(courseRow));
        User student1 = new User("student-1", "s1@test.local", "hash", "Nguyen Van A", "USER");
        User student2 = new User("student-2", "s2@test.local", "hash", "Tran Thi B", "USER");
        when(userRepository.findAllById(any())).thenReturn(List.of(student1, student2));
        when(profileVisibilityPolicy.isIdentityVisible(any(User.class), eq(staffId), eq(classId))).thenReturn(true);

        List<AssignmentSubmissionDto> queue = assignmentService.classQueue(classId, staffId);

        // Batch-loaded exactly once for the whole page, not once per row.
        verify(lessonRepository, times(1)).findAllById(any());
        verify(courseRepository, times(1)).findAllById(any());
        verify(userRepository, times(1)).findAllById(any());

        assertEquals(2, queue.size());
        assertEquals("Bai tap 1", queue.get(0).getLessonTitle());
        assertEquals("Khoa hoc Toan", queue.get(0).getCourseTitle());
        assertEquals("Nguyen Van A", queue.get(0).getLearner().getDisplayName());
        assertEquals("student-1", queue.get(0).getLearner().getUserId());
        assertEquals("Bai tap 2", queue.get(1).getLessonTitle());
        assertEquals("Tran Thi B", queue.get(1).getLearner().getDisplayName());
    }

    @Test
    @DisplayName("R12-03: when ProfileVisibilityPolicy hides the learner's identity from this grader, the DTO shows an anonymized label instead of the real name")
    void testClassQueueAnonymizesHiddenLearnerIdentity() {
        String staffId = "staff-1";
        String classId = "class-1";
        AssignmentSubmission sub = new AssignmentSubmission("lesson-1", "course-1", classId, "student-1", 1, "Answer 1");

        when(accessPolicy.isOwner(staffId, classId)).thenReturn(true);
        when(submissionRepository.findPendingByClassId(eq(classId), any(Pageable.class))).thenReturn(List.of(sub));
        User student = new User("student-1", "s1@test.local", "hash", "Private Student", "USER");
        when(userRepository.findAllById(any())).thenReturn(List.of(student));
        when(profileVisibilityPolicy.isIdentityVisible(any(User.class), eq(staffId), eq(classId))).thenReturn(false);

        List<AssignmentSubmissionDto> queue = assignmentService.classQueue(classId, staffId);

        assertEquals(1, queue.size());
        assertEquals("student-1", queue.get(0).getLearner().getUserId());
        assertEquals("Học viên ẩn danh #1", queue.get(0).getLearner().getDisplayName());
        assertNotEquals("Private Student", queue.get(0).getLearner().getDisplayName());
    }

    @Test
    @DisplayName("Non-member is rejected when requesting classQueue")
    void testClassQueueNonMemberRejected() {
        doThrow(new AppException(com.classroom.common.ErrorCode.FORBIDDEN, "Not a member"))
                .when(accessPolicy).enforceMember("outsider", "class-1");

        assertThrows(AppException.class, () -> assignmentService.classQueue("class-1", "outsider"));
    }

    @Test
    @DisplayName("grade updates score, feedback, grader, and transitions status to GRADED")
    void testGradeSubmissionSuccess() {
        String staffId = "staff-1";
        AssignmentSubmission sub = new AssignmentSubmission("lesson-1", "course-1", "class-1", "student-1", 1, "Answer 1");

        when(submissionRepository.findByIdForUpdate(sub.getId())).thenReturn(Optional.of(sub));
        when(submissionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AssignmentSubmissionDto graded = assignmentService.grade(sub.getId(), staffId, new BigDecimal("9.50"), "Great work!");

        verify(accessPolicy).enforceManage(staffId, "class-1", "COURSE", "GRADE", "course-1");
        assertEquals("GRADED", graded.getStatus());
        assertEquals(new BigDecimal("9.50"), graded.getScore());
        assertEquals("Great work!", graded.getFeedback());
        assertEquals(sub.getId(), graded.getSubmissionId());
        assertEquals("student-1", graded.getLearner().getUserId());
    }

    @Test
    @DisplayName("grade rejects negative score or invalid scale")
    void testGradeRejectsInvalidScore() {
        AssignmentSubmission sub = new AssignmentSubmission("lesson-1", "course-1", "class-1", "student-1", 1, "Answer 1");
        when(submissionRepository.findByIdForUpdate(sub.getId())).thenReturn(Optional.of(sub));

        assertThrows(AppException.class, () -> assignmentService.grade(sub.getId(), "staff-1", new BigDecimal("-1.00"), "Bad"));
        assertThrows(AppException.class, () -> assignmentService.grade(sub.getId(), "staff-1", new BigDecimal("8.123"), "Bad scale"));
    }

    @Test
    @DisplayName("grade locks the submission row and audits the before/after grade with the acting grader")
    void testGradeLocksRowAndRecordsAuditTrail() {
        AssignmentSubmission sub = new AssignmentSubmission("lesson-1", "course-1", "class-1", "student-1", 1, "Answer 1");
        when(submissionRepository.findByIdForUpdate(sub.getId())).thenReturn(Optional.of(sub));
        when(submissionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // First grading: no prior score, so the audit action is the initial grade.
        assignmentService.grade(sub.getId(), "staff-1", new BigDecimal("7.00"), "Ok");

        // A plain findById would let a second grader read a stale row; the lock query must be used.
        verify(submissionRepository, never()).findById(anyString());
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq("class-1"), eq("staff-1"), action.capture(),
                eq("ASSIGNMENT_SUBMISSION"), eq(sub.getId()), details.capture());
        assertEquals("ASSIGNMENT_GRADE", action.getValue());
        assertTrue(details.getValue().contains("\"beforeScore\":null"));
        assertTrue(details.getValue().contains("\"afterScore\":7.00"));

        // Correction by a second grader must be recorded with the previous grade and previous grader.
        reset(auditService);
        assignmentService.grade(sub.getId(), "staff-2", new BigDecimal("9.00"), "Corrected");
        verify(auditService).record(eq("class-1"), eq("staff-2"), action.capture(),
                eq("ASSIGNMENT_SUBMISSION"), eq(sub.getId()), details.capture());
        assertEquals("ASSIGNMENT_GRADE_CORRECT", action.getValue());
        assertTrue(details.getValue().contains("\"beforeScore\":7.00"));
        assertTrue(details.getValue().contains("\"afterScore\":9.00"));
        assertTrue(details.getValue().contains("\"previousGradedBy\":\"staff-1\""));
    }
}
