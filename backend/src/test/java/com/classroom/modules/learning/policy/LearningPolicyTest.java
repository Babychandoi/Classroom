package com.classroom.modules.learning.policy;

import com.classroom.common.AppException;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import com.classroom.modules.learning.model.Course;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class LearningPolicyTest {

    @Mock
    private AccessPolicy accessPolicy;
    @Mock
    private EntitlementRepository entitlementRepository;

    @InjectMocks
    private LearningPolicy learningPolicy;

    private Course freeCourse;
    private Course paidCourse;

    @BeforeEach
    void setUp() {
        freeCourse = new Course("class-1", "Free Course", "FREE");
        freeCourse.setId("course-free-1");
        freeCourse.setStatus("PUBLISHED");

        paidCourse = new Course("class-1", "Paid Course", "PURCHASE_REQUIRED");
        paidCourse.setId("course-paid-1");
        paidCourse.setStatus("PUBLISHED");
        paidCourse.setProductId("product-1");
    }

    @Test
    @DisplayName("TC-02: OWNER can access paid course without an order or entitlement")
    void testOwnerAccessPaidCourse() {
        when(accessPolicy.isOwner("owner-1", "class-1")).thenReturn(true);

        assertTrue(learningPolicy.canLearn("owner-1", paidCourse));
    }

    @Test
    @DisplayName("TC-03: FREE member can access free course, but is rejected for paid course without entitlement")
    void testFreeStudentAccess() {
        String studentId = "student-free";
        when(accessPolicy.isOwner(studentId, "class-1")).thenReturn(false);
        when(accessPolicy.canManage(eq(studentId), eq("class-1"), eq("COURSE"), eq("PREVIEW"), any())).thenReturn(false);
        when(accessPolicy.isMember(studentId, "class-1")).thenReturn(true);

        // Can access FREE course
        assertTrue(learningPolicy.canLearn(studentId, freeCourse));

        // Rejected for PAID course
        when(entitlementRepository.hasCourseAccess(eq(studentId), eq("class-1"), eq("course-paid-1"), eq("product-1"), any()))
                .thenReturn(false);

        assertFalse(learningPolicy.canLearn(studentId, paidCourse));
        assertThrows(AppException.class, () -> learningPolicy.enforceLearn(studentId, paidCourse));
    }

    @Test
    @DisplayName("TC-13: STAFF with COURSE_PREVIEW on course A can preview course A")
    void testStaffCoursePreview() {
        String staffId = "staff-1";
        when(accessPolicy.isOwner(staffId, "class-1")).thenReturn(false);
        when(accessPolicy.canManage(staffId, "class-1", "COURSE", "PREVIEW", "course-paid-1")).thenReturn(true);

        assertTrue(learningPolicy.canLearn(staffId, paidCourse));
    }

    @Test
    @DisplayName("TC-Draft-01: OWNER can access DRAFT course")
    void testOwnerCanAccessDraftCourse() {
        Course draftCourse = new Course("class-1", "Draft Course", "FREE");
        draftCourse.setStatus("DRAFT");
        when(accessPolicy.isOwner("owner-1", "class-1")).thenReturn(true);

        assertTrue(learningPolicy.canLearn("owner-1", draftCourse));
    }

    @Test
    @DisplayName("TC-Draft-02: STAFF with preview/edit permission can access DRAFT course")
    void testStaffCanAccessDraftCourse() {
        Course draftCourse = new Course("class-1", "Draft Course", "FREE");
        draftCourse.setId("course-draft-1");
        draftCourse.setStatus("DRAFT");
        when(accessPolicy.isOwner("staff-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("staff-1", "class-1", "COURSE", "PREVIEW", "course-draft-1")).thenReturn(true);

        assertTrue(learningPolicy.canLearn("staff-1", draftCourse));
    }

    @Test
    @DisplayName("TC-Draft-03: Active student member cannot access DRAFT course")
    void testStudentCannotAccessDraftCourse() {
        Course draftCourse = new Course("class-1", "Draft Course", "FREE");
        draftCourse.setId("course-draft-1");
        draftCourse.setStatus("DRAFT");
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage(eq("student-1"), eq("class-1"), eq("COURSE"), eq("PREVIEW"), any())).thenReturn(false);

        assertFalse(learningPolicy.canLearn("student-1", draftCourse));
        assertThrows(AppException.class, () -> learningPolicy.enforceLearn("student-1", draftCourse));
    }

    @Test
    @DisplayName("TC-Authz-01: STAFF with only COURSE:EDIT cannot learn a paid course without an entitlement")
    void testCourseEditDoesNotGrantPaidLearningAccess() {
        String staffId = "staff-editor";
        when(accessPolicy.isOwner(staffId, "class-1")).thenReturn(false);
        when(accessPolicy.canManage(staffId, "class-1", "COURSE", "PREVIEW", "course-paid-1")).thenReturn(false);
        when(accessPolicy.isMember(staffId, "class-1")).thenReturn(true);
        when(entitlementRepository.hasCourseAccess(eq(staffId), eq("class-1"), eq("course-paid-1"), eq("product-1"), any()))
                .thenReturn(false);

        assertFalse(learningPolicy.canLearn(staffId, paidCourse));
        assertThrows(AppException.class, () -> learningPolicy.enforceLearn(staffId, paidCourse));
    }

    @Test
    @DisplayName("TC-Draft-04: DRAFT course metadata is hidden from ordinary members but visible to editing STAFF")
    void testDraftCourseVisibility() {
        Course draftCourse = new Course("class-1", "Draft Course", "FREE");
        draftCourse.setId("course-draft-1");
        draftCourse.setStatus("DRAFT");

        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("student-1", "class-1", "COURSE", "PREVIEW", "course-draft-1")).thenReturn(false);
        when(accessPolicy.canManage("student-1", "class-1", "COURSE", "EDIT", "course-draft-1")).thenReturn(false);
        assertFalse(learningPolicy.canViewCourse("student-1", draftCourse));

        when(accessPolicy.isOwner("staff-editor", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("staff-editor", "class-1", "COURSE", "PREVIEW", "course-draft-1")).thenReturn(false);
        when(accessPolicy.canManage("staff-editor", "class-1", "COURSE", "EDIT", "course-draft-1")).thenReturn(true);
        assertTrue(learningPolicy.canViewCourse("staff-editor", draftCourse));

        // Published courses stay visible to any active member
        when(accessPolicy.isMember("student-1", "class-1")).thenReturn(true);
        assertTrue(learningPolicy.canViewCourse("student-1", freeCourse));
    }

    @Test
    void archivedFreeCourseIsNotLearnerVisible() {
        freeCourse.setStatus("ARCHIVED");
        assertFalse(learningPolicy.canLearn("student-1", freeCourse));
        assertFalse(learningPolicy.canViewCourse("student-1", freeCourse));
        verify(entitlementRepository, never()).hasCourseAccess(any(), any(), any(), any(), any());
    }
}
