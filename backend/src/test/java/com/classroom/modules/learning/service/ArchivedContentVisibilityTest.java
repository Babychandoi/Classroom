package com.classroom.modules.learning.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.StaffAssignmentRepository;
import com.classroom.modules.classroom.repository.StaffPermissionRepository;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import com.classroom.modules.commerce.repository.OrderItemRepository;
import com.classroom.modules.commerce.repository.ProductRepository;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.policy.ProfileVisibilityPolicy;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.learning.dto.CourseDto;
import com.classroom.modules.learning.dto.LessonDto;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.model.Lesson;
import com.classroom.modules.learning.model.LessonQuestion;
import com.classroom.modules.learning.model.Section;
import com.classroom.modules.learning.policy.LearningPolicy;
import com.classroom.modules.learning.repository.AssignmentSubmissionRepository;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.learning.repository.LessonAnswerRepository;
import com.classroom.modules.learning.repository.LessonProgressRepository;
import com.classroom.modules.learning.repository.LessonQuestionRepository;
import com.classroom.modules.learning.repository.LessonRepository;
import com.classroom.modules.learning.repository.SectionRepository;
import com.classroom.modules.media.service.MediaService;
import com.classroom.modules.outbox.service.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * R14-02 / R14-03: wires the REAL LearningPolicy into LearningService and AssignmentService (only
 * repositories are mocked) and proves that an archived lesson - or a lesson inside an archived
 * section - is invisible on every learner-facing path, while a COURSE:EDIT manager keeps access;
 * and that course progress counts only visible lessons through grouped queries.
 */
@ExtendWith(MockitoExtension.class)
class ArchivedContentVisibilityTest {

    private static final String LEARNER = "learner-1";
    private static final String EDITOR = "editor-1";

    @Mock private CourseRepository courseRepository;
    @Mock private SectionRepository sectionRepository;
    @Mock private LessonRepository lessonRepository;
    @Mock private LessonProgressRepository progressRepository;
    @Mock private LessonQuestionRepository questionRepository;
    @Mock private LessonAnswerRepository answerRepository;
    @Mock private UserRepository userRepository;
    @Mock private AccessPolicy accessPolicy;
    @Mock private EntitlementRepository entitlementRepository;
    @Mock private MediaService mediaService;
    @Mock private OutboxService outboxService;
    @Mock private ProductRepository productRepository;
    @Mock private ProfileVisibilityPolicy profileVisibilityPolicy;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private AuditService auditService;
    @Mock private AssignmentSubmissionRepository submissionRepository;
    @Mock private StaffAssignmentRepository staffAssignmentRepository;
    @Mock private StaffPermissionRepository staffPermissionRepository;

    private LearningService learningService;
    private AssignmentService assignmentService;

    private Course course;
    private Section section;
    private Lesson lesson;
    private Lesson assignmentLesson;

    @BeforeEach
    void setUp() {
        LearningPolicy learningPolicy = new LearningPolicy(accessPolicy, entitlementRepository, sectionRepository);
        learningService = new LearningService(courseRepository, sectionRepository, lessonRepository,
                progressRepository, questionRepository, answerRepository, userRepository, learningPolicy,
                accessPolicy, mediaService, outboxService, productRepository, profileVisibilityPolicy,
                orderItemRepository, auditService, submissionRepository,
                staffAssignmentRepository, staffPermissionRepository);
        assignmentService = new AssignmentService(lessonRepository, courseRepository, submissionRepository,
                learningPolicy, accessPolicy, auditService, staffAssignmentRepository, staffPermissionRepository,
                userRepository, profileVisibilityPolicy);

        course = new Course("class-1", "Khóa học", "FREE");
        course.setId("course-1");
        course.setStatus("PUBLISHED");
        section = new Section("course-1", "Chương 1", 0);
        section.setId("section-1");
        lesson = new Lesson("section-1", "course-1", "Bài 1", "TEXT", 0);
        lesson.setId("lesson-1");
        assignmentLesson = new Lesson("section-1", "course-1", "Bài tập 1", "ASSIGNMENT", 1);
        assignmentLesson.setId("lesson-a");

        lenient().when(courseRepository.findById("course-1")).thenReturn(Optional.of(course));
        lenient().when(sectionRepository.findById("section-1")).thenReturn(Optional.of(section));
        lenient().when(lessonRepository.findById("lesson-1")).thenReturn(Optional.of(lesson));
        lenient().when(lessonRepository.findById("lesson-a")).thenReturn(Optional.of(assignmentLesson));
        lenient().when(lessonRepository.findByIdForUpdate("lesson-a")).thenReturn(Optional.of(assignmentLesson));
        lenient().when(accessPolicy.isMember(LEARNER, "class-1")).thenReturn(true);
        lenient().when(accessPolicy.isMember(EDITOR, "class-1")).thenReturn(true);
        lenient().when(accessPolicy.canManage(EDITOR, "class-1", "COURSE", "PREVIEW", "course-1")).thenReturn(true);
        lenient().when(accessPolicy.canManage(EDITOR, "class-1", "COURSE", "EDIT", "course-1")).thenReturn(true);
        lenient().when(userRepository.findById(LEARNER))
                .thenReturn(Optional.of(new User(LEARNER, "l@test.local", "hash", "Học viên", "STUDENT")));
    }

    /** Archives the lesson itself, or its whole section - both must hide every lesson underneath. */
    private void hide(String what) {
        if ("LESSON".equals(what)) {
            lesson.setArchived(true);
            assignmentLesson.setArchived(true);
        } else {
            section.setArchived(true);
        }
    }

    private static void assertNotFound(org.junit.jupiter.api.function.Executable call) {
        AppException ex = assertThrows(AppException.class, call);
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    // ----- one test per learner path -----

    @ParameterizedTest
    @ValueSource(strings = {"LESSON", "SECTION"})
    @DisplayName("R14-02: getLesson hides an archived lesson / archived section from a learner")
    void getLessonHidden(String what) {
        hide(what);
        assertNotFound(() -> learningService.getLesson("lesson-1", LEARNER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"LESSON", "SECTION"})
    @DisplayName("R14-02: markLessonProgress cannot record progress on an archived lesson / section (404, nothing saved)")
    void markProgressHidden(String what) {
        hide(what);
        assertNotFound(() -> learningService.markLessonProgress("lesson-1", LEARNER, true));
        verify(progressRepository, never()).save(any());
        verify(outboxService, never()).recordEvent(any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"LESSON", "SECTION"})
    @DisplayName("R14-02: Q&A list, ask and answer are all hidden for an archived lesson / section")
    void questionsHidden(String what) {
        LessonQuestion question = new LessonQuestion("lesson-1", "asker-1", "Người hỏi", "Câu hỏi?");
        question.setId("q-1");
        lenient().when(questionRepository.findById("q-1")).thenReturn(Optional.of(question));
        hide(what);

        assertNotFound(() -> learningService.getLessonQuestions("lesson-1", LEARNER));
        assertNotFound(() -> learningService.askQuestion("lesson-1", LEARNER, "Xin hỏi"));
        assertNotFound(() -> learningService.answerQuestion("q-1", LEARNER, "Trả lời"));
        verify(questionRepository, never()).save(any());
        verify(answerRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"LESSON", "SECTION"})
    @DisplayName("R14-02: assignment submit and my-submissions are hidden for an archived assignment / section")
    void assignmentPathsHidden(String what) {
        hide(what);
        assertNotFound(() -> assignmentService.submit("lesson-a", LEARNER, "Bài làm"));
        assertNotFound(() -> assignmentService.mySubmissions("lesson-a", LEARNER));
        verify(submissionRepository, never()).save(any());
    }

    // ----- managers keep access; visible content is unaffected -----

    @ParameterizedTest
    @ValueSource(strings = {"LESSON", "SECTION"})
    @DisplayName("R14-02: a COURSE:EDIT manager can still open an archived lesson (to restore it) but cannot record progress on it")
    void managerKeepsAccess(String what) {
        hide(what);

        LessonDto dto = learningService.getLesson("lesson-1", EDITOR);
        assertEquals("lesson-1", dto.getId());

        AppException ex = assertThrows(AppException.class, () -> learningService.markLessonProgress("lesson-1", EDITOR, true));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verify(progressRepository, never()).save(any());
    }

    @Test
    @DisplayName("R14-02 control: a visible lesson in a visible section works on every path")
    void visibleLessonStillWorks() {
        when(questionRepository.save(any(LessonQuestion.class))).thenAnswer(inv -> inv.getArgument(0));
        when(submissionRepository.findMaxAttemptNumber("lesson-a", LEARNER)).thenReturn(0);
        when(submissionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertEquals("lesson-1", learningService.getLesson("lesson-1", LEARNER).getId());
        learningService.markLessonProgress("lesson-1", LEARNER, true);
        assertEquals("Xin hỏi", learningService.askQuestion("lesson-1", LEARNER, "Xin hỏi").getQuestionText());
        assertNotNull(assignmentService.submit("lesson-a", LEARNER, "Bài làm"));
    }

    @Test
    @DisplayName("R14-02: getCourseDetails hides an archived section (and archived lessons) from learners but lists them for editors")
    void courseDetailsListing() {
        Section archivedSection = new Section("course-1", "Chương cũ", 1);
        archivedSection.setId("section-old");
        archivedSection.setArchived(true);
        Lesson archivedLesson = new Lesson("section-1", "course-1", "Bài cũ", "TEXT", 5);
        archivedLesson.setId("lesson-old");
        archivedLesson.setArchived(true);
        Lesson lessonInOldSection = new Lesson("section-old", "course-1", "Bài trong chương cũ", "TEXT", 0);
        lessonInOldSection.setId("lesson-in-old");
        when(courseRepository.findById("course-1")).thenReturn(Optional.of(course));
        when(sectionRepository.findByCourseIdOrderByPositionAsc("course-1")).thenReturn(List.of(section, archivedSection));
        when(lessonRepository.findBySectionIdOrderByPositionAsc("section-1")).thenReturn(List.of(lesson, archivedLesson));
        lenient().when(lessonRepository.findBySectionIdOrderByPositionAsc("section-old")).thenReturn(List.of(lessonInOldSection));

        CourseDto learnerView = learningService.getCourseDetails("course-1", LEARNER);
        assertEquals(1, learnerView.getSections().size());
        assertEquals(List.of("lesson-1"), learnerView.getSections().get(0).getLessons().stream().map(LessonDto::getId).toList());

        CourseDto editorView = learningService.getCourseDetails("course-1", EDITOR);
        assertEquals(2, editorView.getSections().size());
        assertEquals(2, editorView.getSections().get(0).getLessons().size());
    }

    // ----- R14-03: progress counts only visible lessons, via grouped queries -----

    private static LessonRepository.CourseLessonCount total(String courseId, long total) {
        return new LessonRepository.CourseLessonCount() {
            public String getCourseId() { return courseId; }
            public long getTotal() { return total; }
        };
    }

    private static LessonProgressRepository.CourseProgressCount done(String courseId, long total) {
        return new LessonProgressRepository.CourseProgressCount() {
            public String getCourseId() { return courseId; }
            public long getTotal() { return total; }
        };
    }

    @Test
    @DisplayName("R14-03: course progress uses the visible-lesson counts, reaches 100%, and costs 2 queries regardless of course count")
    void courseProgressCountsVisibleLessonsOnlyWithoutNPlusOne() {
        Course second = new Course("class-1", "Khóa 2", "FREE");
        second.setId("course-2");
        second.setStatus("PUBLISHED");
        Course third = new Course("class-1", "Khóa 3", "FREE");
        third.setId("course-3");
        third.setStatus("PUBLISHED");
        when(courseRepository.findByClassIdOrderByPositionAsc("class-1")).thenReturn(List.of(course, second, third));
        // course-1: 5 lessons exist, 2 archived -> 3 visible; the learner completed all 3 visible ones
        // (plus 1 old completion on an archived lesson, which the visible query does not count).
        when(lessonRepository.countVisibleByCourseIdIn(anyList()))
                .thenReturn(List.of(total("course-1", 3), total("course-2", 4)));
        when(progressRepository.countCompletedVisibleByUserAndCourseIdIn(eq(LEARNER), anyList()))
                .thenReturn(List.of(done("course-1", 3), done("course-2", 1)));

        List<CourseDto> dtos = learningService.getCoursesByClass("class-1", LEARNER);

        assertEquals(3, dtos.size());
        assertEquals(3, dtos.get(0).getTotalLessons());
        assertEquals(3, dtos.get(0).getCompletedLessons());
        assertEquals(100.0, dtos.get(0).getProgressPercentage(), 0.0001);
        assertEquals(25.0, dtos.get(1).getProgressPercentage(), 0.0001);
        assertEquals(0, dtos.get(2).getTotalLessons());
        assertEquals(0.0, dtos.get(2).getProgressPercentage(), 0.0001);
        // No per-course count queries at all - exactly one grouped query per side.
        verify(lessonRepository, times(1)).countVisibleByCourseIdIn(anyList());
        verify(progressRepository, times(1)).countCompletedVisibleByUserAndCourseIdIn(eq(LEARNER), anyList());
        verify(lessonRepository, never()).countByCourseId(any());
        verify(progressRepository, never()).countByUserIdAndCourseIdAndCompletedTrue(any(), any());
    }

    @Test
    @DisplayName("R14-03: completed can never exceed total (defensive clamp), so progress never passes 100%")
    void progressIsClampedTo100() {
        when(courseRepository.findByClassIdOrderByPositionAsc("class-1")).thenReturn(List.of(course));
        when(lessonRepository.countVisibleByCourseIdIn(anyList())).thenReturn(List.of(total("course-1", 2)));
        when(progressRepository.countCompletedVisibleByUserAndCourseIdIn(eq(LEARNER), anyList()))
                .thenReturn(List.of(done("course-1", 5)));

        CourseDto dto = learningService.getCoursesByClass("class-1", LEARNER).get(0);

        assertEquals(2, dto.getCompletedLessons());
        assertEquals(100.0, dto.getProgressPercentage(), 0.0001);
    }
}
