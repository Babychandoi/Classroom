package com.classroom.modules.learning.service;

import com.classroom.common.AppException;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.policy.ProfileVisibilityPolicy;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.model.Lesson;
import com.classroom.modules.learning.model.LessonProgress;
import com.classroom.modules.learning.model.LessonQuestion;
import com.classroom.modules.learning.policy.LearningPolicy;
import com.classroom.modules.learning.repository.*;
import com.classroom.modules.commerce.repository.OrderItemRepository;
import com.classroom.modules.commerce.repository.ProductRepository;
import com.classroom.modules.media.service.MediaService;
import com.classroom.modules.outbox.service.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class LearningServiceTest {

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
    @Mock private com.classroom.modules.audit.service.AuditService auditService;
    @Mock private AssignmentSubmissionRepository assignmentSubmissionRepository;
    @Mock private com.classroom.modules.classroom.repository.StaffAssignmentRepository staffAssignmentRepository;
    @Mock private com.classroom.modules.classroom.repository.StaffPermissionRepository staffPermissionRepository;

    private LearningService learningService;

    private Course course;
    private Lesson lesson;

    @BeforeEach
    void setUp() {
        learningService = new LearningService(courseRepository, sectionRepository, lessonRepository,
                progressRepository, questionRepository, answerRepository, userRepository, learningPolicy,
                accessPolicy, mediaService, outboxService, productRepository, profileVisibilityPolicy,
                orderItemRepository, auditService, assignmentSubmissionRepository,
                staffAssignmentRepository, staffPermissionRepository);

        course = new Course("class-1", "Course 1", "FREE");
        course.setId("course-1");

        lesson = new Lesson("section-1", "course-1", "Lesson 1", "TEXT", 0);
        lesson.setId("lesson-1");
    }

    // ----- R3-03: Q&A null/blank text -> 400, not NPE -----

    @Test
    @DisplayName("R3-03: askQuestion rejects null question text with BAD_REQUEST, not NPE")
    void askQuestionRejectsNullText() {
        when(lessonRepository.findById("lesson-1")).thenReturn(Optional.of(lesson));
        when(courseRepository.findById("course-1")).thenReturn(Optional.of(course));

        AppException ex = assertThrows(AppException.class,
                () -> learningService.askQuestion("lesson-1", "user-1", null));
        assertEquals(com.classroom.common.ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    @DisplayName("R3-03: askQuestion rejects blank question text with BAD_REQUEST")
    void askQuestionRejectsBlankText() {
        when(lessonRepository.findById("lesson-1")).thenReturn(Optional.of(lesson));
        when(courseRepository.findById("course-1")).thenReturn(Optional.of(course));

        AppException ex = assertThrows(AppException.class,
                () -> learningService.askQuestion("lesson-1", "user-1", "   "));
        assertEquals(com.classroom.common.ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    @DisplayName("R3-03: answerQuestion rejects null answer text with BAD_REQUEST, not NPE")
    void answerQuestionRejectsNullText() {
        LessonQuestion question = new LessonQuestion("lesson-1", "asker-1", "Asker", "What?");
        question.setId("question-1");
        when(questionRepository.findById("question-1")).thenReturn(Optional.of(question));
        when(lessonRepository.findById("lesson-1")).thenReturn(Optional.of(lesson));
        when(courseRepository.findById("course-1")).thenReturn(Optional.of(course));

        AppException ex = assertThrows(AppException.class,
                () -> learningService.answerQuestion("question-1", "user-1", null));
        assertEquals(com.classroom.common.ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    @DisplayName("R3-03: answerQuestion rejects blank answer text with BAD_REQUEST")
    void answerQuestionRejectsBlankText() {
        LessonQuestion question = new LessonQuestion("lesson-1", "asker-1", "Asker", "What?");
        question.setId("question-1");
        when(questionRepository.findById("question-1")).thenReturn(Optional.of(question));
        when(lessonRepository.findById("lesson-1")).thenReturn(Optional.of(lesson));
        when(courseRepository.findById("course-1")).thenReturn(Optional.of(course));

        AppException ex = assertThrows(AppException.class,
                () -> learningService.answerQuestion("question-1", "user-1", ""));
        assertEquals(com.classroom.common.ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    @DisplayName("R3-03: askQuestion succeeds and saves trimmed text when valid")
    void askQuestionSucceedsWithValidText() {
        when(lessonRepository.findById("lesson-1")).thenReturn(Optional.of(lesson));
        when(courseRepository.findById("course-1")).thenReturn(Optional.of(course));
        User user = new User("user-1", "u@test.local", "hash", "User One", "USER");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(questionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = learningService.askQuestion("lesson-1", "user-1", "  What is this?  ");
        assertEquals("What is this?", dto.getQuestionText());
    }

    // ----- R3-11: LESSON_COMPLETED double-fire guard -----

    @Test
    @DisplayName("R3-11: marking a lesson complete for the first time emits LESSON_COMPLETED")
    void markLessonCompleteFirstTimeEmitsEvent() {
        when(lessonRepository.findById("lesson-1")).thenReturn(Optional.of(lesson));
        when(courseRepository.findById("course-1")).thenReturn(Optional.of(course));
        when(progressRepository.findByUserIdAndLessonId("user-1", "lesson-1")).thenReturn(Optional.empty());

        learningService.markLessonProgress("lesson-1", "user-1", true);

        verify(outboxService, times(1)).recordEvent(eq("LEARNING"), eq("lesson-1"), eq("LESSON_COMPLETED"), any(Map.class));
    }

    @Test
    @DisplayName("R3-11: re-marking an already-completed lesson complete does not re-emit LESSON_COMPLETED")
    void markAlreadyCompletedLessonDoesNotReEmitEvent() {
        when(lessonRepository.findById("lesson-1")).thenReturn(Optional.of(lesson));
        when(courseRepository.findById("course-1")).thenReturn(Optional.of(course));

        LessonProgress existing = new LessonProgress("user-1", "lesson-1", "course-1", "class-1");
        existing.setCompleted(true);
        when(progressRepository.findByUserIdAndLessonId("user-1", "lesson-1")).thenReturn(Optional.of(existing));

        learningService.markLessonProgress("lesson-1", "user-1", true);

        verify(outboxService, never()).recordEvent(eq("LEARNING"), anyString(), eq("LESSON_COMPLETED"), any(Map.class));
    }

    // ----- R8-09: createLesson must validate type/durationMinutes/position -----

    @Test
    @DisplayName("R8-09: createLesson rejects an unsupported lesson type with BAD_REQUEST")
    void createLessonRejectsUnsupportedType() {
        com.classroom.modules.learning.model.Section section =
                new com.classroom.modules.learning.model.Section("course-1", "Section 1", 0);
        section.setId("section-1");
        when(sectionRepository.findById("section-1")).thenReturn(Optional.of(section));
        when(courseRepository.findById("course-1")).thenReturn(Optional.of(course));

        Lesson badLesson = new Lesson("section-1", "course-1", "Lesson X", "PODCAST", 0);

        AppException ex = assertThrows(AppException.class,
                () -> learningService.createLesson("section-1", badLesson, "owner-1"));
        assertEquals(com.classroom.common.ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verify(lessonRepository, never()).save(any());
    }

    @Test
    @DisplayName("R8-09: createLesson rejects a negative durationMinutes with BAD_REQUEST")
    void createLessonRejectsNegativeDuration() {
        com.classroom.modules.learning.model.Section section =
                new com.classroom.modules.learning.model.Section("course-1", "Section 1", 0);
        section.setId("section-1");
        when(sectionRepository.findById("section-1")).thenReturn(Optional.of(section));
        when(courseRepository.findById("course-1")).thenReturn(Optional.of(course));

        Lesson badLesson = new Lesson("section-1", "course-1", "Lesson X", "VIDEO", 0);
        badLesson.setDurationMinutes(-5);

        AppException ex = assertThrows(AppException.class,
                () -> learningService.createLesson("section-1", badLesson, "owner-1"));
        assertEquals(com.classroom.common.ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verify(lessonRepository, never()).save(any());
    }

    @Test
    @DisplayName("R8-09: createLesson rejects a negative position with BAD_REQUEST")
    void createLessonRejectsNegativePosition() {
        com.classroom.modules.learning.model.Section section =
                new com.classroom.modules.learning.model.Section("course-1", "Section 1", 0);
        section.setId("section-1");
        when(sectionRepository.findById("section-1")).thenReturn(Optional.of(section));
        when(courseRepository.findById("course-1")).thenReturn(Optional.of(course));

        Lesson badLesson = new Lesson("section-1", "course-1", "Lesson X", "VIDEO", -1);

        AppException ex = assertThrows(AppException.class,
                () -> learningService.createLesson("section-1", badLesson, "owner-1"));
        assertEquals(com.classroom.common.ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verify(lessonRepository, never()).save(any());
    }

    @Test
    @DisplayName("R8-09: createLesson accepts a valid lesson and normalizes the type to uppercase")
    void createLessonAcceptsValidLesson() {
        com.classroom.modules.learning.model.Section section =
                new com.classroom.modules.learning.model.Section("course-1", "Section 1", 0);
        section.setId("section-1");
        when(sectionRepository.findById("section-1")).thenReturn(Optional.of(section));
        when(courseRepository.findById("course-1")).thenReturn(Optional.of(course));
        when(lessonRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Lesson goodLesson = new Lesson("section-1", "course-1", "Lesson X", "video", 0);
        goodLesson.setDurationMinutes(10);

        Lesson result = learningService.createLesson("section-1", goodLesson, "owner-1");

        assertEquals("VIDEO", result.getType());
        verify(lessonRepository).save(any());
    }

    @Test
    @DisplayName("R3-11: transitioning from incomplete to complete still emits LESSON_COMPLETED")
    void markLessonCompleteFalseToTrueEmitsEvent() {
        when(lessonRepository.findById("lesson-1")).thenReturn(Optional.of(lesson));
        when(courseRepository.findById("course-1")).thenReturn(Optional.of(course));

        LessonProgress existing = new LessonProgress("user-1", "lesson-1", "course-1", "class-1");
        existing.setCompleted(false);
        when(progressRepository.findByUserIdAndLessonId("user-1", "lesson-1")).thenReturn(Optional.of(existing));

        learningService.markLessonProgress("lesson-1", "user-1", true);

        verify(outboxService, times(1)).recordEvent(eq("LEARNING"), eq("lesson-1"), eq("LESSON_COMPLETED"), any(Map.class));
    }
}
