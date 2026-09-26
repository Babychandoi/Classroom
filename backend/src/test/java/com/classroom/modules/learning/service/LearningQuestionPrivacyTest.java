package com.classroom.modules.learning.service;

import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.commerce.repository.OrderItemRepository;
import com.classroom.modules.commerce.repository.ProductRepository;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.policy.ProfileVisibilityPolicy;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.model.Lesson;
import com.classroom.modules.learning.model.LessonAnswer;
import com.classroom.modules.learning.model.LessonQuestion;
import com.classroom.modules.learning.policy.LearningPolicy;
import com.classroom.modules.learning.repository.*;
import com.classroom.modules.media.service.MediaService;
import com.classroom.modules.outbox.service.OutboxService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LearningQuestionPrivacyTest {
    @Test
    void questionAndAnswerAuthorsWithPrivateProfilesAreAnonymizedForPeers() {
        CourseRepository courses = mock(CourseRepository.class);
        LessonRepository lessons = mock(LessonRepository.class);
        LessonQuestionRepository questions = mock(LessonQuestionRepository.class);
        LessonAnswerRepository answers = mock(LessonAnswerRepository.class);
        UserRepository users = mock(UserRepository.class);
        AccessPolicy access = mock(AccessPolicy.class);
        User privateAuthor = new User("author", "private@example.test", "hash", "Private Name", "USER");
        privateAuthor.setProfileVisibility("PRIVATE");
        when(lessons.findById("lesson")).thenReturn(Optional.of(new Lesson("section", "course", "Lesson", "VIDEO", 1)));
        when(courses.findById("course")).thenReturn(Optional.of(new Course("class", "Course", "FREE")));
        when(questions.findByLessonIdOrderByCreatedAtDesc("lesson"))
                .thenReturn(List.of(new LessonQuestion("lesson", "author", "Legacy Stored Name", "Question")));
        when(answers.findByQuestionIdOrderByCreatedAtAsc(anyString()))
                .thenReturn(List.of(new LessonAnswer("question", "author", "Legacy Stored Name", "Answer")));
        when(users.findById("author")).thenReturn(Optional.of(privateAuthor));

        LearningService service = new LearningService(courses, mock(SectionRepository.class), lessons,
                mock(LessonProgressRepository.class), questions, answers, users, mock(LearningPolicy.class), access,
                mock(MediaService.class), mock(OutboxService.class), mock(ProductRepository.class),
                new ProfileVisibilityPolicy(access), mock(OrderItemRepository.class));

        var result = service.getLessonQuestions("lesson", "peer").get(0);
        assertNull(result.getUserId());
        assertEquals(ProfileVisibilityPolicy.ANONYMOUS_DISPLAY_NAME, result.getAuthorName());
        assertNull(result.getAnswers().get(0).getUserId());
        assertEquals(ProfileVisibilityPolicy.ANONYMOUS_DISPLAY_NAME, result.getAnswers().get(0).getAuthorName());
        assertEquals("Question", result.getQuestionText());
        assertEquals("Answer", result.getAnswers().get(0).getAnswerText());
    }
}
