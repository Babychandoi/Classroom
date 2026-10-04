package com.classroom.modules.learning.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.commerce.repository.OrderItemRepository;
import com.classroom.modules.commerce.repository.ProductRepository;
import com.classroom.modules.identity.policy.ProfileVisibilityPolicy;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.model.Lesson;
import com.classroom.modules.learning.model.Section;
import com.classroom.modules.learning.policy.LearningPolicy;
import com.classroom.modules.learning.repository.*;
import com.classroom.modules.media.service.MediaService;
import com.classroom.modules.outbox.service.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * R13-03: course/section/lesson edit/archive/delete lifecycle, scoped COURSE:EDIT authz, and the
 * "safe to hard-delete only without learner progress" rule.
 */
@ExtendWith(MockitoExtension.class)
class LearningLifecycleTest {

    @Mock private CourseRepository courseRepository;
    @Mock private SectionRepository sectionRepository;
    @Mock private LessonRepository lessonRepository;
    @Mock private LessonProgressRepository progressRepository;
    @Mock private LessonQuestionRepository questionRepository;
    @Mock private LessonAnswerRepository answerRepository;
    @Mock private UserRepository userRepository;
    @Mock private LearningPolicy learningPolicy;
    @Mock private AccessPolicy accessPolicy;
    @Mock private MediaService mediaService;
    @Mock private OutboxService outboxService;
    @Mock private ProductRepository productRepository;
    @Mock private ProfileVisibilityPolicy profileVisibilityPolicy;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private AuditService auditService;
    @Mock private AssignmentSubmissionRepository assignmentSubmissionRepository;
    @Mock private com.classroom.modules.classroom.repository.StaffAssignmentRepository staffAssignmentRepository;
    @Mock private com.classroom.modules.classroom.repository.StaffPermissionRepository staffPermissionRepository;

    @InjectMocks
    private LearningService learningService;

    private Course course;

    @BeforeEach
    void setUp() {
        course = new Course("class-1", "Khóa học 1", "FREE");
        course.setId("course-1");
        course.setStatus("DRAFT");
    }

    @Test
    @DisplayName("updateCourse is forbidden for a caller without COURSE:EDIT scoped to the course (IDOR)")
    void updateCourseForbiddenWithoutScopedPermission() {
        when(courseRepository.findByIdForUpdate("course-1")).thenReturn(Optional.of(course));
        doThrow(new AppException(ErrorCode.STAFF_PERMISSION_DENIED, "denied"))
                .when(accessPolicy).enforceManage("intruder", "class-1", "COURSE", "EDIT", "course-1");

        com.classroom.modules.learning.dto.CourseDto patch = new com.classroom.modules.learning.dto.CourseDto();
        patch.setTitle("Hack");
        assertThrows(AppException.class, () -> learningService.updateCourse("course-1", patch, "intruder"));
    }

    @Test
    @DisplayName("updateCourse is rejected once the course is archived")
    void updateCourseRejectedWhenArchived() {
        course.setStatus("ARCHIVED");
        when(courseRepository.findByIdForUpdate("course-1")).thenReturn(Optional.of(course));

        com.classroom.modules.learning.dto.CourseDto patch = new com.classroom.modules.learning.dto.CourseDto();
        patch.setTitle("x");
        AppException ex = assertThrows(AppException.class, () -> learningService.updateCourse("course-1", patch, "owner-1"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    @DisplayName("archiveCourse keeps the course row (never deletes) so purchased entitlements remain resolvable")
    void archiveCourseFlipsStatusWithoutDeleting() {
        when(courseRepository.findByIdForUpdate("course-1")).thenReturn(Optional.of(course));
        when(courseRepository.save(any(Course.class))).thenAnswer(inv -> inv.getArgument(0));

        Course result = learningService.archiveCourse("course-1", "owner-1");
        assertEquals("ARCHIVED", result.getStatus());
        verify(courseRepository, never()).delete(any());
    }

    @Test
    @DisplayName("deleteCourse is rejected for a PUBLISHED course (must archive instead)")
    void deleteCourseRejectedWhenPublished() {
        course.setStatus("PUBLISHED");
        when(courseRepository.findByIdForUpdate("course-1")).thenReturn(Optional.of(course));

        AppException ex = assertThrows(AppException.class, () -> learningService.deleteCourse("course-1", "owner-1"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    @DisplayName("deleteCourse is rejected when any lesson under it has learner progress recorded")
    void deleteCourseRejectedWithLearnerProgress() {
        when(courseRepository.findByIdForUpdate("course-1")).thenReturn(Optional.of(course));
        Lesson lesson = new Lesson("section-1", "course-1", "Bài 1", "TEXT", 0);
        lesson.setId("lesson-1");
        when(lessonRepository.findByCourseIdOrderByPositionAsc("course-1")).thenReturn(List.of(lesson));
        when(progressRepository.existsByLessonId("lesson-1")).thenReturn(true);

        AppException ex = assertThrows(AppException.class, () -> learningService.deleteCourse("course-1", "owner-1"));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
    }

    @Test
    @DisplayName("deleteSection hard-deletes only when no lesson underneath has progress or Q&A; otherwise must archive")
    void deleteSectionSafetyCheck() {
        Section section = new Section("course-1", "Chương 1", 0);
        section.setId("section-1");
        when(sectionRepository.findByIdForUpdate("section-1")).thenReturn(Optional.of(section));
        when(courseRepository.findById("course-1")).thenReturn(Optional.of(course));
        Lesson lesson = new Lesson("section-1", "course-1", "Bài 1", "TEXT", 0);
        lesson.setId("lesson-1");
        when(lessonRepository.findBySectionIdForUpdate("section-1")).thenReturn(List.of(lesson));
        when(progressRepository.existsByLessonId("lesson-1")).thenReturn(true);

        AppException ex = assertThrows(AppException.class, () -> learningService.deleteSection("section-1", "owner-1"));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());

        // Safe (no progress/Q&A) -> hard delete succeeds
        when(progressRepository.existsByLessonId("lesson-1")).thenReturn(false);
        when(questionRepository.existsByLessonId("lesson-1")).thenReturn(false);
        learningService.deleteSection("section-1", "owner-1");
        verify(sectionRepository).delete(section);
    }

    @Test
    @DisplayName("archiveLesson hides content without destroying progress history")
    void archiveLessonDoesNotDelete() {
        Lesson lesson = new Lesson("section-1", "course-1", "Bài 1", "TEXT", 0);
        lesson.setId("lesson-1");
        when(lessonRepository.findByIdForUpdate("lesson-1")).thenReturn(Optional.of(lesson));
        when(courseRepository.findById("course-1")).thenReturn(Optional.of(course));
        when(lessonRepository.save(any(Lesson.class))).thenAnswer(inv -> inv.getArgument(0));

        Lesson result = learningService.archiveLesson("lesson-1", true, "owner-1");
        assertTrue(result.isArchived());
        verify(lessonRepository, never()).delete(any());
    }

    @Test
    @DisplayName("reorderSections rejects a list that omits or duplicates a section id")
    void reorderSectionsValidatesFullList() {
        when(courseRepository.findById("course-1")).thenReturn(Optional.of(course));
        Section s1 = new Section("course-1", "A", 0);
        s1.setId("s1");
        Section s2 = new Section("course-1", "B", 1);
        s2.setId("s2");
        when(sectionRepository.findByCourseIdForUpdate("course-1")).thenReturn(List.of(s1, s2));

        assertThrows(AppException.class, () -> learningService.reorderSections("course-1", List.of("s1"), "owner-1"));
    }

    // ----- R14-04: assignment submissions (ON DELETE CASCADE from lessons) block hard delete -----

    private Lesson assignmentLesson() {
        Lesson lesson = new Lesson("section-1", "course-1", "Bài tập 1", "ASSIGNMENT", 0);
        lesson.setId("lesson-1");
        return lesson;
    }

    @Test
    @DisplayName("R14-04: deleteLesson is refused (CONFLICT) when the lesson has assignment submissions, even with no progress/Q&A")
    void deleteLessonRejectedWithSubmissions() {
        Lesson lesson = assignmentLesson();
        when(lessonRepository.findByIdForUpdate("lesson-1")).thenReturn(Optional.of(lesson));
        when(courseRepository.findById("course-1")).thenReturn(Optional.of(course));
        when(assignmentSubmissionRepository.existsByLessonId("lesson-1")).thenReturn(true);

        AppException ex = assertThrows(AppException.class, () -> learningService.deleteLesson("lesson-1", "owner-1"));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        verify(lessonRepository, never()).delete(any());
    }

    @Test
    @DisplayName("R14-04: deleteLesson still succeeds when there is no progress, Q&A or submission")
    void deleteLessonAllowedWhenNothingAttached() {
        Lesson lesson = assignmentLesson();
        when(lessonRepository.findByIdForUpdate("lesson-1")).thenReturn(Optional.of(lesson));
        when(courseRepository.findById("course-1")).thenReturn(Optional.of(course));

        learningService.deleteLesson("lesson-1", "owner-1");

        verify(lessonRepository).delete(lesson);
    }

    @Test
    @DisplayName("R14-04: deleteSection is refused when any lesson inside has assignment submissions")
    void deleteSectionRejectedWithSubmissions() {
        Section section = new Section("course-1", "Chương 1", 0);
        section.setId("section-1");
        when(sectionRepository.findByIdForUpdate("section-1")).thenReturn(Optional.of(section));
        when(courseRepository.findById("course-1")).thenReturn(Optional.of(course));
        Lesson lesson = assignmentLesson();
        when(lessonRepository.findBySectionIdForUpdate("section-1")).thenReturn(List.of(lesson));
        when(assignmentSubmissionRepository.existsByLessonId("lesson-1")).thenReturn(true);

        AppException ex = assertThrows(AppException.class, () -> learningService.deleteSection("section-1", "owner-1"));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        verify(lessonRepository, never()).delete(any());
        verify(sectionRepository, never()).delete(any());
    }

    @Test
    @DisplayName("R14-04: deleteCourse is refused when any lesson under it has assignment submissions")
    void deleteCourseRejectedWithSubmissions() {
        when(courseRepository.findByIdForUpdate("course-1")).thenReturn(Optional.of(course));
        Lesson lesson = assignmentLesson();
        when(lessonRepository.findByCourseIdOrderByPositionAsc("course-1")).thenReturn(List.of(lesson));
        when(assignmentSubmissionRepository.existsByLessonId("lesson-1")).thenReturn(true);

        AppException ex = assertThrows(AppException.class, () -> learningService.deleteCourse("course-1", "owner-1"));
        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        verify(sectionRepository, never()).deleteByCourseId(any());
        verify(courseRepository, never()).delete(any());
    }

    // ----- R16-02: publish is DRAFT -> PUBLISHED only, and audited -----

    @Test
    @DisplayName("R16-02: publishCourse moves DRAFT -> PUBLISHED and writes a COURSE_PUBLISH audit event")
    void publishCourseFromDraftIsAudited() {
        when(courseRepository.findByIdForUpdate("course-1")).thenReturn(Optional.of(course));
        when(courseRepository.save(any(Course.class))).thenAnswer(inv -> inv.getArgument(0));

        Course result = learningService.publishCourse("course-1", "owner-1");

        assertEquals("PUBLISHED", result.getStatus());
        verify(auditService).record(eq("class-1"), eq("owner-1"), eq("COURSE_PUBLISH"), eq("COURSE"), eq("course-1"), anyString());
    }

    @Test
    @DisplayName("R16-02: publishCourse refuses an ARCHIVED course (must go through restore) - no state change, no audit")
    void publishCourseRefusesArchived() {
        course.setStatus("ARCHIVED");
        when(courseRepository.findByIdForUpdate("course-1")).thenReturn(Optional.of(course));

        AppException ex = assertThrows(AppException.class, () -> learningService.publishCourse("course-1", "owner-1"));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertEquals("ARCHIVED", course.getStatus());
        verify(courseRepository, never()).save(any());
        verify(auditService, never()).record(any(), any(), eq("COURSE_PUBLISH"), any(), any(), any());
    }

    @Test
    @DisplayName("R16-02: publishCourse on an already PUBLISHED course is an idempotent no-op (no error, no duplicate audit)")
    void publishCourseAlreadyPublishedIsIdempotent() {
        course.setStatus("PUBLISHED");
        when(courseRepository.findByIdForUpdate("course-1")).thenReturn(Optional.of(course));

        Course result = learningService.publishCourse("course-1", "owner-1");

        assertEquals("PUBLISHED", result.getStatus());
        verify(courseRepository, never()).save(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("R16-02: publishCourse still requires COURSE:PUBLISH scoped to the course")
    void publishCourseStillNeedsPublishPermission() {
        when(courseRepository.findByIdForUpdate("course-1")).thenReturn(Optional.of(course));
        doThrow(new AppException(ErrorCode.STAFF_PERMISSION_DENIED, "denied"))
                .when(accessPolicy).enforceManage("intruder", "class-1", "COURSE", "PUBLISH", "course-1");

        assertThrows(AppException.class, () -> learningService.publishCourse("course-1", "intruder"));
        verify(courseRepository, never()).save(any());
    }

    // ----- R16-03: a course a product still points at is never deleted -----

    @Test
    @DisplayName("R16-03: deleteCourse is refused while a product targets the course (no dangling products.target_course_id)")
    void deleteCourseRefusedWhileProductTargetsIt() {
        when(courseRepository.findByIdForUpdate("course-1")).thenReturn(Optional.of(course));
        when(productRepository.existsByTargetCourseId("course-1")).thenReturn(true);

        AppException ex = assertThrows(AppException.class, () -> learningService.deleteCourse("course-1", "owner-1"));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("sản phẩm"), ex.getMessage());
        verify(sectionRepository, never()).deleteByCourseId(any());
        verify(courseRepository, never()).delete(any());
        verify(auditService, never()).record(any(), any(), eq("COURSE_DELETE"), any(), any(), any());
    }

    @Test
    @DisplayName("R16-03: deleteCourse is also refused when the course's own productId still names an existing product")
    void deleteCourseRefusedWhenCourseProductIdExists() {
        course.setProductId("prod-1");
        when(courseRepository.findByIdForUpdate("course-1")).thenReturn(Optional.of(course));
        when(orderItemRepository.countOrdersByProductId("prod-1")).thenReturn(0L);
        when(productRepository.existsByTargetCourseId("course-1")).thenReturn(false);
        when(productRepository.existsById("prod-1")).thenReturn(true);

        AppException ex = assertThrows(AppException.class, () -> learningService.deleteCourse("course-1", "owner-1"));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verify(courseRepository, never()).delete(any());
    }

    @Test
    @DisplayName("R16-03: a DRAFT course with no product and no learner activity is still deletable (and audited)")
    void deleteCourseWithoutProductStillWorks() {
        when(courseRepository.findByIdForUpdate("course-1")).thenReturn(Optional.of(course));
        when(productRepository.existsByTargetCourseId("course-1")).thenReturn(false);
        when(lessonRepository.findByCourseIdOrderByPositionAsc("course-1")).thenReturn(List.of());

        learningService.deleteCourse("course-1", "owner-1");

        verify(sectionRepository).deleteByCourseId("course-1");
        verify(courseRepository).delete(course);
        verify(auditService).record(eq("class-1"), eq("owner-1"), eq("COURSE_DELETE"), eq("COURSE"), eq("course-1"), anyString());
    }

    // ----- R16-10: reorder lists must be exact permutations (no duplicated ids) -----

    @Test
    @DisplayName("R16-10: reorderCourses rejects a same-length list that repeats one id and skips another")
    void reorderCoursesRejectsDuplicateIds() {
        Course c1 = new Course("class-1", "A", "FREE");
        c1.setId("c1");
        Course c2 = new Course("class-1", "B", "FREE");
        c2.setId("c2");
        when(courseRepository.findByClassIdOrderByPositionAsc("class-1")).thenReturn(List.of(c1, c2));

        AppException ex = assertThrows(AppException.class,
                () -> learningService.reorderCourses("class-1", List.of("c1", "c1"), "owner-1"));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verify(courseRepository, never()).save(any());
    }

    @Test
    @DisplayName("R16-10: reorderCourses still accepts a genuine permutation")
    void reorderCoursesAcceptsPermutation() {
        Course c1 = new Course("class-1", "A", "FREE");
        c1.setId("c1");
        Course c2 = new Course("class-1", "B", "FREE");
        c2.setId("c2");
        when(courseRepository.findByClassIdOrderByPositionAsc("class-1")).thenReturn(List.of(c1, c2));

        learningService.reorderCourses("class-1", List.of("c2", "c1"), "owner-1");

        assertEquals(0, c2.getPosition());
        assertEquals(1, c1.getPosition());
    }

    @Test
    @DisplayName("R16-10: reorderSections rejects duplicates and a null entry even when the length matches")
    void reorderSectionsRejectsDuplicateAndNullIds() {
        when(courseRepository.findById("course-1")).thenReturn(Optional.of(course));
        Section s1 = new Section("course-1", "A", 0);
        s1.setId("s1");
        Section s2 = new Section("course-1", "B", 1);
        s2.setId("s2");
        when(sectionRepository.findByCourseIdForUpdate("course-1")).thenReturn(List.of(s1, s2));

        assertThrows(AppException.class, () -> learningService.reorderSections("course-1", List.of("s1", "s1"), "owner-1"));
        assertThrows(AppException.class,
                () -> learningService.reorderSections("course-1", java.util.Arrays.asList("s1", null), "owner-1"));
        verify(sectionRepository, never()).save(any());
    }

    @Test
    @DisplayName("R16-10: reorderLessons rejects a duplicated lesson id")
    void reorderLessonsRejectsDuplicateIds() {
        Section section = new Section("course-1", "A", 0);
        section.setId("section-1");
        when(sectionRepository.findById("section-1")).thenReturn(Optional.of(section));
        when(courseRepository.findById("course-1")).thenReturn(Optional.of(course));
        Lesson l1 = new Lesson("section-1", "course-1", "L1", "TEXT", 0);
        l1.setId("l1");
        Lesson l2 = new Lesson("section-1", "course-1", "L2", "TEXT", 1);
        l2.setId("l2");
        when(lessonRepository.findBySectionIdForUpdate("section-1")).thenReturn(List.of(l1, l2));

        AppException ex = assertThrows(AppException.class,
                () -> learningService.reorderLessons("section-1", List.of("l2", "l2"), "owner-1"));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verify(lessonRepository, never()).save(any());
    }
    // ----- R18-07: a course a staff grant is scoped to cannot be deleted (V19 RESTRICT FK) -----

    private static com.classroom.modules.classroom.model.StaffPermission scopedGrant(String assignmentId, String module, String action) {
        return new com.classroom.modules.classroom.model.StaffPermission(assignmentId, module, action, "course-1");
    }

    private void stubStaff(String assignmentId, String userId, String fullName) {
        com.classroom.modules.classroom.model.StaffAssignment assignment = new com.classroom.modules.classroom.model.StaffAssignment("class-1", userId);
        assignment.setId(assignmentId);
        com.classroom.modules.identity.model.User user = new com.classroom.modules.identity.model.User();
        user.setId(userId);
        user.setFullName(fullName);
        lenient().when(staffAssignmentRepository.findAllById(any())).thenAnswer(inv -> {
            java.util.Collection<String> ids = inv.getArgument(0);
            return ids.contains(assignmentId) ? List.of(assignment) : List.of();
        });
        lenient().when(userRepository.findAllById(any())).thenAnswer(inv -> {
            java.util.Collection<String> ids = inv.getArgument(0);
            return ids.contains(userId) ? List.of(user) : List.of();
        });
    }

    @Test
    @DisplayName("R18-07: deleting a DRAFT course with a course-scoped staff grant is a 409 naming the staff and how to fix it")
    void deleteCourseRefusedWhileStaffGrantIsScopedToIt() {
        when(courseRepository.findByIdForUpdate("course-1")).thenReturn(Optional.of(course));
        when(productRepository.existsByTargetCourseId("course-1")).thenReturn(false);
        when(staffPermissionRepository.findByScopeCourseId("course-1")).thenReturn(List.of(
                scopedGrant("a1", "COURSE", "EDIT"), scopedGrant("a1", "EXAM", "CREATE")));
        stubStaff("a1", "staff-1", "Nguyễn Trợ Giảng");

        AppException ex = assertThrows(AppException.class, () -> learningService.deleteCourse("course-1", "owner-1"));

        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("Nguyễn Trợ Giảng"), ex.getMessage());
        assertTrue(ex.getMessage().contains("COURSE:EDIT") && ex.getMessage().contains("EXAM:CREATE"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Studio > Trợ giảng"), ex.getMessage());
        verify(sectionRepository, never()).deleteByCourseId(any());
        verify(courseRepository, never()).delete(any());
    }

    @Test
    @DisplayName("R18-07: the staff message names at most three people and counts the rest; an unknown user falls back to a generic label")
    void staffScopedMessageIsBounded() {
        when(courseRepository.findByIdForUpdate("course-1")).thenReturn(Optional.of(course));
        when(staffPermissionRepository.findByScopeCourseId("course-1")).thenReturn(List.of(
                scopedGrant("a1", "COURSE", "EDIT"), scopedGrant("a2", "COURSE", "EDIT"),
                scopedGrant("a3", "COURSE", "EDIT"), scopedGrant("a4", "COURSE", "EDIT"),
                scopedGrant("a5", "COURSE", "EDIT")));
        // No assignment/user rows resolve, so every grant holder is reported generically.

        AppException ex = assertThrows(AppException.class, () -> learningService.deleteCourse("course-1", "owner-1"));

        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        assertEquals(3, ex.getMessage().split("Trợ giảng [(]", -1).length - 1, ex.getMessage());
        assertTrue(ex.getMessage().contains("và 2 trợ giảng khác"), ex.getMessage());
    }

    @Test
    @DisplayName("R18-04: reorderCourses renumbers 0..n-1 in the requested order")
    void reorderCoursesRenumbersFromZero() {
        Course c1 = new Course("class-1", "A", "FREE");
        c1.setId("c1");
        Course c2 = new Course("class-1", "B", "FREE");
        c2.setId("c2");
        Course c3 = new Course("class-1", "C", "FREE");
        c3.setId("c3");
        when(courseRepository.findByClassIdOrderByPositionAsc("class-1")).thenReturn(List.of(c1, c2, c3));

        learningService.reorderCourses("class-1", List.of("c3", "c1", "c2"), "owner-1");

        assertEquals(0, c3.getPosition());
        assertEquals(1, c1.getPosition());
        assertEquals(2, c2.getPosition());
    }
}
