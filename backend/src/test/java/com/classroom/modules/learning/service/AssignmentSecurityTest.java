package com.classroom.modules.learning.service;

import com.classroom.common.AppException;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.policy.AccessPolicy;
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
    }

    @Test
    @DisplayName("classQueue enforces membership and filters submissions by staff COURSE:GRADE permission")
    void testClassQueueFiltersByPermission() {
        String staffId = "staff-1";
        String classId = "class-1";

        AssignmentSubmission sub1 = new AssignmentSubmission("lesson-1", "course-1", classId, "student-1", 1, "Answer 1");
        AssignmentSubmission sub2 = new AssignmentSubmission("lesson-2", "course-2", classId, "student-2", 1, "Answer 2");

        when(submissionRepository.findByClassIdOrderBySubmittedAtAsc(classId)).thenReturn(List.of(sub1, sub2));
        when(accessPolicy.canManage(staffId, classId, "COURSE", "GRADE", "course-1")).thenReturn(true);
        when(accessPolicy.canManage(staffId, classId, "COURSE", "GRADE", "course-2")).thenReturn(false);

        List<AssignmentSubmission> queue = assignmentService.classQueue(classId, staffId);

        verify(accessPolicy).enforceMember(staffId, classId);
        assertEquals(1, queue.size());
        assertEquals("course-1", queue.get(0).getCourseId());
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

        AssignmentSubmission graded = assignmentService.grade(sub.getId(), staffId, new BigDecimal("9.50"), "Great work!");

        verify(accessPolicy).enforceManage(staffId, "class-1", "COURSE", "GRADE", "course-1");
        assertEquals("GRADED", graded.getStatus());
        assertEquals(new BigDecimal("9.50"), graded.getScore());
        assertEquals("Great work!", graded.getFeedback());
        assertEquals(staffId, graded.getGradedBy());
        assertNotNull(graded.getGradedAt());
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
