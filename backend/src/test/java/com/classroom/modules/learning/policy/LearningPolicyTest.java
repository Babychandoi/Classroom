package com.classroom.modules.learning.policy;

import com.classroom.common.AppException;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.commerce.model.Entitlement;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.model.Lesson;
import com.classroom.modules.learning.model.Section;
import com.classroom.modules.learning.repository.SectionRepository;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class LearningPolicyTest {

    @Mock
    private AccessPolicy accessPolicy;
    @Mock
    private EntitlementRepository entitlementRepository;
    @Mock
    private SectionRepository sectionRepository;

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

    // R13-09 (Learn "hết hạn"): resolveAccessReason tests.

    @Test
    @DisplayName("R13-09: OWNER access reason is OWNER")
    void resolveAccessReasonOwner() {
        when(accessPolicy.isOwner("owner-1", "class-1")).thenReturn(true);
        LearningPolicy.AccessReason reason = learningPolicy.resolveAccessReason("owner-1", paidCourse);
        assertEquals("OWNER", reason.reason());
        assertNull(reason.expiresAt());
    }

    @Test
    @DisplayName("R13-09: STAFF with COURSE:PREVIEW access reason is STAFF")
    void resolveAccessReasonStaff() {
        when(accessPolicy.isOwner("staff-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("staff-1", "class-1", "COURSE", "PREVIEW", "course-paid-1")).thenReturn(true);
        LearningPolicy.AccessReason reason = learningPolicy.resolveAccessReason("staff-1", paidCourse);
        assertEquals("STAFF", reason.reason());
    }

    @Test
    @DisplayName("R13-09: FREE course access reason is FREE")
    void resolveAccessReasonFree() {
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage(eq("student-1"), eq("class-1"), eq("COURSE"), eq("PREVIEW"), any())).thenReturn(false);
        LearningPolicy.AccessReason reason = learningPolicy.resolveAccessReason("student-1", freeCourse);
        assertEquals("FREE", reason.reason());
    }

    @Test
    @DisplayName("R13-09: never purchased -> NOT_PURCHASED")
    void resolveAccessReasonNotPurchased() {
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage(eq("student-1"), eq("class-1"), eq("COURSE"), eq("PREVIEW"), any())).thenReturn(false);
        when(entitlementRepository.findByUserIdAndClassId("student-1", "class-1")).thenReturn(List.of());
        LearningPolicy.AccessReason reason = learningPolicy.resolveAccessReason("student-1", paidCourse);
        assertEquals("NOT_PURCHASED", reason.reason());
        assertNull(reason.expiresAt());
    }

    @Test
    @DisplayName("R13-09: active entitlement -> OWNED with expiresAt")
    void resolveAccessReasonOwned() {
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage(eq("student-1"), eq("class-1"), eq("COURSE"), eq("PREVIEW"), any())).thenReturn(false);
        Instant expiresAt = Instant.now().plus(10, ChronoUnit.DAYS);
        Entitlement active = new Entitlement("student-1", "class-1", "product-1", "course-paid-1",
                Instant.now().minus(1, ChronoUnit.DAYS), expiresAt);
        when(entitlementRepository.findByUserIdAndClassId("student-1", "class-1")).thenReturn(List.of(active));
        LearningPolicy.AccessReason reason = learningPolicy.resolveAccessReason("student-1", paidCourse);
        assertEquals("OWNED", reason.reason());
        assertEquals(expiresAt, reason.expiresAt());
    }

    @Test
    @DisplayName("R13-09: lapsed-by-time entitlement -> EXPIRED with expiresAt, distinct from NOT_PURCHASED")
    void resolveAccessReasonExpired() {
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage(eq("student-1"), eq("class-1"), eq("COURSE"), eq("PREVIEW"), any())).thenReturn(false);
        Instant expiresAt = Instant.now().minus(5, ChronoUnit.DAYS);
        Entitlement expired = new Entitlement("student-1", "class-1", "product-1", "course-paid-1",
                Instant.now().minus(35, ChronoUnit.DAYS), expiresAt);
        expired.setState("EXPIRED");
        when(entitlementRepository.findByUserIdAndClassId("student-1", "class-1")).thenReturn(List.of(expired));
        LearningPolicy.AccessReason reason = learningPolicy.resolveAccessReason("student-1", paidCourse);
        assertEquals("EXPIRED", reason.reason());
        assertEquals(expiresAt, reason.expiresAt());
    }

    @Test
    @DisplayName("R13-09: refunded (REVOKED) entitlement with no time-based lapse -> NOT_PURCHASED (no renew CTA)")
    void resolveAccessReasonRevoked() {
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage(eq("student-1"), eq("class-1"), eq("COURSE"), eq("PREVIEW"), any())).thenReturn(false);
        Entitlement revoked = new Entitlement("student-1", "class-1", "product-1", "course-paid-1",
                Instant.now().minus(5, ChronoUnit.DAYS), Instant.now().plus(25, ChronoUnit.DAYS));
        revoked.setState("REVOKED");
        when(entitlementRepository.findByUserIdAndClassId("student-1", "class-1")).thenReturn(List.of(revoked));
        LearningPolicy.AccessReason reason = learningPolicy.resolveAccessReason("student-1", paidCourse);
        assertEquals("NOT_PURCHASED", reason.reason());
    }

    // ----- R14-12: latest expiry ignores REVOKED entitlements; future-start -> OWNED_UPCOMING -----

    private void nonStaffStudent() {
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage(eq("student-1"), eq("class-1"), eq("COURSE"), eq("PREVIEW"), any())).thenReturn(false);
    }

    @Test
    @DisplayName("R14-12: an active entitlement expiry ignores a REVOKED entitlement that expires later")
    void resolveAccessReasonOwnedIgnoresRevokedLatestExpiry() {
        nonStaffStudent();
        Instant liveExpiry = Instant.now().plus(10, ChronoUnit.DAYS);
        Entitlement live = new Entitlement("student-1", "class-1", "product-1", "course-paid-1",
                Instant.now().minus(1, ChronoUnit.DAYS), liveExpiry);
        Entitlement revokedLater = new Entitlement("student-1", "class-1", "product-1", "course-paid-1",
                Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(300, ChronoUnit.DAYS));
        revokedLater.setState("REVOKED");
        when(entitlementRepository.findByUserIdAndClassId("student-1", "class-1")).thenReturn(List.of(live, revokedLater));

        LearningPolicy.AccessReason reason = learningPolicy.resolveAccessReason("student-1", paidCourse);

        assertEquals("OWNED", reason.reason());
        assertEquals(liveExpiry, reason.expiresAt());
    }

    @Test
    @DisplayName("R14-12: EXPIRED shows the latest NON-revoked expiry, and a revoked-only history is NOT_PURCHASED without a date")
    void resolveAccessReasonExpiredIgnoresRevoked() {
        nonStaffStudent();
        Instant expiredAt = Instant.now().minus(5, ChronoUnit.DAYS);
        Entitlement expired = new Entitlement("student-1", "class-1", "product-1", "course-paid-1",
                Instant.now().minus(35, ChronoUnit.DAYS), expiredAt);
        expired.setState("EXPIRED");
        Entitlement revokedLater = new Entitlement("student-1", "class-1", "product-1", "course-paid-1",
                Instant.now().minus(3, ChronoUnit.DAYS), Instant.now().minus(1, ChronoUnit.DAYS));
        revokedLater.setState("REVOKED");
        when(entitlementRepository.findByUserIdAndClassId("student-1", "class-1")).thenReturn(List.of(expired, revokedLater));

        LearningPolicy.AccessReason reason = learningPolicy.resolveAccessReason("student-1", paidCourse);
        assertEquals("EXPIRED", reason.reason());
        assertEquals(expiredAt, reason.expiresAt());

        // Only a revoked (refunded) row that already lapsed by time: no renewal invitation, no date.
        when(entitlementRepository.findByUserIdAndClassId("student-1", "class-1")).thenReturn(List.of(revokedLater));
        LearningPolicy.AccessReason revokedOnly = learningPolicy.resolveAccessReason("student-1", paidCourse);
        assertEquals("NOT_PURCHASED", revokedOnly.reason());
        assertNull(revokedOnly.expiresAt());
    }

    @Test
    @DisplayName("R14-12: a paid entitlement that has not started yet is OWNED_UPCOMING with its start date (not NOT_PURCHASED)")
    void resolveAccessReasonUpcoming() {
        nonStaffStudent();
        Instant startsAt = Instant.now().plus(7, ChronoUnit.DAYS);
        Instant expiresAt = Instant.now().plus(37, ChronoUnit.DAYS);
        Entitlement scheduled = new Entitlement("student-1", "class-1", "product-1", "course-paid-1", startsAt, expiresAt);
        when(entitlementRepository.findByUserIdAndClassId("student-1", "class-1")).thenReturn(List.of(scheduled));

        LearningPolicy.AccessReason reason = learningPolicy.resolveAccessReason("student-1", paidCourse);

        assertEquals("OWNED_UPCOMING", reason.reason());
        assertEquals(startsAt, reason.startsAt());
        assertEquals(expiresAt, reason.expiresAt());
    }

    @Test
    @DisplayName("R14-12: a REVOKED future-start entitlement is not OWNED_UPCOMING")
    void resolveAccessReasonRevokedUpcomingIsNotUpcoming() {
        nonStaffStudent();
        Entitlement scheduled = new Entitlement("student-1", "class-1", "product-1", "course-paid-1",
                Instant.now().plus(7, ChronoUnit.DAYS), Instant.now().plus(37, ChronoUnit.DAYS));
        scheduled.setState("REVOKED");
        when(entitlementRepository.findByUserIdAndClassId("student-1", "class-1")).thenReturn(List.of(scheduled));

        assertEquals("NOT_PURCHASED", learningPolicy.resolveAccessReason("student-1", paidCourse).reason());
    }

    // ----- R14-02: single archived-lesson visibility rule -----

    private Lesson lessonIn(String sectionId, boolean archived) {
        Lesson lesson = new Lesson(sectionId, "course-free-1", "Bài học", "TEXT", 0);
        lesson.setId("lesson-x");
        lesson.setArchived(archived);
        return lesson;
    }

    @Test
    @DisplayName("R14-02: an archived lesson is hidden from a learner but not from a COURSE:EDIT manager")
    void archivedLessonHiddenFromLearnerOnly() {
        Lesson archived = lessonIn("section-1", true);
        when(accessPolicy.canManage("student-1", "class-1", "COURSE", "EDIT", "course-free-1")).thenReturn(false);
        when(accessPolicy.canManage("editor-1", "class-1", "COURSE", "EDIT", "course-free-1")).thenReturn(true);

        assertTrue(learningPolicy.isLessonHiddenFromLearner(archived, freeCourse, "student-1"));
        assertFalse(learningPolicy.isLessonHiddenFromLearner(archived, freeCourse, "editor-1"));
        assertTrue(learningPolicy.isLessonHiddenFromLearner(archived, freeCourse, null));
    }

    @Test
    @DisplayName("R14-02: a lesson inside an archived section is hidden even when the lesson itself is not archived")
    void lessonInArchivedSectionHidden() {
        Section section = new Section("course-free-1", "Chương", 0);
        section.setId("section-1");
        section.setArchived(true);
        when(sectionRepository.findById("section-1")).thenReturn(Optional.of(section));
        when(accessPolicy.canManage("student-1", "class-1", "COURSE", "EDIT", "course-free-1")).thenReturn(false);
        when(accessPolicy.canManage("owner-1", "class-1", "COURSE", "EDIT", "course-free-1")).thenReturn(true);

        Lesson visibleLesson = lessonIn("section-1", false);
        assertTrue(learningPolicy.isLessonHiddenFromLearner(visibleLesson, freeCourse, "student-1"));
        assertFalse(learningPolicy.isLessonHiddenFromLearner(visibleLesson, freeCourse, "owner-1"));
    }

    @Test
    @DisplayName("R14-02: a normal lesson in a normal section is not hidden (and no manage lookup is needed)")
    void normalLessonNotHidden() {
        Section section = new Section("course-free-1", "Chương", 0);
        section.setId("section-1");
        when(sectionRepository.findById("section-1")).thenReturn(Optional.of(section));

        assertFalse(learningPolicy.isLessonHiddenFromLearner(lessonIn("section-1", false), freeCourse, "student-1"));
        verify(accessPolicy, never()).canManage(any(), any(), any(), any(), any());
    }
}
