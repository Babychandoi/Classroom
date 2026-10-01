package com.classroom.modules.identity.service;

import com.classroom.common.AppException;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.commerce.policy.ProPolicy;
import com.classroom.modules.exam.model.Exam;
import com.classroom.modules.exam.model.ExamAttempt;
import com.classroom.modules.exam.repository.ExamAttemptRepository;
import com.classroom.modules.exam.repository.ExamRepository;
import com.classroom.modules.identity.dto.UserJourneyDto;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.policy.ProfileVisibilityPolicy;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.policy.LearningPolicy;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.learning.repository.LessonProgressRepository;
import com.classroom.modules.learning.repository.LessonRepository;
import com.classroom.modules.ranking.repository.LeaderboardEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R13-05 (FR-12/D-05): UserService#getJourney — visibility mirrors ProfileVisibilityPolicy (the
 * same rule the profile endpoint and leaderboard use), and the response batch-loads per-course
 * progress and published exam results.
 */
@ExtendWith(MockitoExtension.class)
class UserJourneyTest {

    @Mock private UserRepository userRepository;
    @Mock private ClassMemberRepository classMemberRepository;
    @Mock private AccessPolicy accessPolicy;
    @Mock private ProPolicy proPolicy;
    @Mock private LeaderboardEntryRepository leaderboardEntryRepository;
    @Mock private CourseRepository courseRepository;
    @Mock private LessonRepository lessonRepository;
    @Mock private LessonProgressRepository lessonProgressRepository;
    @Mock private ExamAttemptRepository examAttemptRepository;
    @Mock private ExamRepository examRepository;
    @Mock private LearningPolicy learningPolicy;

    private UserService userService;

    private static final String CLASS_ID = "class-1";
    private static final String TARGET_ID = "target-1";

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, classMemberRepository, accessPolicy, proPolicy,
                leaderboardEntryRepository, new ProfileVisibilityPolicy(accessPolicy), courseRepository,
                lessonRepository, lessonProgressRepository, examAttemptRepository, examRepository, learningPolicy);
    }

    private User targetUser(String visibility) {
        User u = new User(TARGET_ID, "target@classroom.local", "hash", "Target Learner", "STUDENT");
        u.setProfileVisibility(visibility);
        return u;
    }

    @Test
    @DisplayName("R13-05: rejects a viewer who is not a member of the class")
    void rejectsNonMemberViewer() {
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(targetUser("PUBLIC")));
        when(accessPolicy.isMember("outsider", CLASS_ID)).thenReturn(false);
        when(accessPolicy.isOwner("outsider", CLASS_ID)).thenReturn(false);

        assertThrows(AppException.class, () -> userService.getJourney(TARGET_ID, "outsider", CLASS_ID));
    }

    @Test
    @DisplayName("R13-05: a PRIVATE learner's journey is hidden from an ordinary peer")
    void privateLearnerHiddenFromPeer() {
        String viewerId = "peer-1";
        when(accessPolicy.isMember(viewerId, CLASS_ID)).thenReturn(true);
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(targetUser("PRIVATE")));
        when(classMemberRepository.findByClassIdAndUserId(CLASS_ID, TARGET_ID))
                .thenReturn(Optional.of(new ClassMember(CLASS_ID, TARGET_ID, "STUDENT")));
        // ProfileVisibilityPolicy.isIdentityVisible -> isClassAdministrator checks canManage; not an admin.
        when(accessPolicy.canManage(viewerId, CLASS_ID, "MEMBER", "VIEW", null)).thenReturn(false);

        assertThrows(AppException.class, () -> userService.getJourney(TARGET_ID, viewerId, CLASS_ID));
    }

    @Test
    @DisplayName("R13-05: self always sees own journey, batch-loaded across courses and published exams")
    void selfSeesOwnJourney() {
        when(accessPolicy.isMember(TARGET_ID, CLASS_ID)).thenReturn(true);
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(targetUser("PRIVATE")));
        when(classMemberRepository.findByClassIdAndUserId(CLASS_ID, TARGET_ID))
                .thenReturn(Optional.of(new ClassMember(CLASS_ID, TARGET_ID, "STUDENT")));

        Course course1 = new Course(CLASS_ID, "Course A", "FREE");
        course1.setId("course-a");
        Course course2 = new Course(CLASS_ID, "Course B", "FREE");
        course2.setId("course-b");
        when(courseRepository.findByClassIdOrderByPositionAsc(CLASS_ID)).thenReturn(List.of(course1, course2));
        when(learningPolicy.canViewCourse(TARGET_ID, course1)).thenReturn(true);
        when(learningPolicy.canLearn(TARGET_ID, course1)).thenReturn(true);
        when(learningPolicy.canViewCourse(TARGET_ID, course2)).thenReturn(true);
        when(learningPolicy.canLearn(TARGET_ID, course2)).thenReturn(true);

        when(lessonRepository.countVisibleByCourseIdIn(any())).thenReturn(List.of(
                countRow("course-a", 10), countRow("course-b", 5)));
        when(lessonProgressRepository.countCompletedVisibleByUserAndCourseIdIn(eq(TARGET_ID), any())).thenReturn(List.of(
                progressRow("course-a", 4)));

        ExamAttempt published = new ExamAttempt("exam-1", TARGET_ID, CLASS_ID, Instant.now(), false);
        published.setStatus("PUBLISHED");
        published.setScore(new BigDecimal("85"));
        published.setSubmittedAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(examAttemptRepository.findByClassIdAndUserIdAndStatusAndIsPreviewFalse(CLASS_ID, TARGET_ID, "PUBLISHED"))
                .thenReturn(List.of(published));
        Exam exam = new Exam();
        exam.setId("exam-1");
        exam.setTitle("Giữa kỳ");
        when(examRepository.findAllById(List.of("exam-1"))).thenReturn(List.of(exam));

        UserJourneyDto dto = userService.getJourney(TARGET_ID, TARGET_ID, CLASS_ID);

        assertEquals(2, dto.getCourses().size());
        UserJourneyDto.CourseProgress courseA = dto.getCourses().stream()
                .filter(c -> c.getCourseId().equals("course-a")).findFirst().orElseThrow();
        assertEquals(4, courseA.getCompletedLessons());
        assertEquals(10, courseA.getTotalLessons());
        UserJourneyDto.CourseProgress courseB = dto.getCourses().stream()
                .filter(c -> c.getCourseId().equals("course-b")).findFirst().orElseThrow();
        assertEquals(0, courseB.getCompletedLessons());
        assertEquals(5, courseB.getTotalLessons());

        assertEquals(1, dto.getExamResults().size());
        assertEquals("Giữa kỳ", dto.getExamResults().get(0).getExamTitle());
        assertEquals(0, new BigDecimal("85").compareTo(dto.getExamResults().get(0).getScore()));
    }

    private static com.classroom.modules.learning.repository.LessonRepository.CourseLessonCount countRow(String courseId, long total) {
        return new com.classroom.modules.learning.repository.LessonRepository.CourseLessonCount() {
            public String getCourseId() { return courseId; }
            public long getTotal() { return total; }
        };
    }

    private static com.classroom.modules.learning.repository.LessonProgressRepository.CourseProgressCount progressRow(String courseId, long total) {
        return new com.classroom.modules.learning.repository.LessonProgressRepository.CourseProgressCount() {
            public String getCourseId() { return courseId; }
            public long getTotal() { return total; }
        };
    }

    // ----- R14-11: only an ACTIVE member has a journey -----

    @Test
    @DisplayName("R14-11: a REMOVED or BLOCKED member has no journey (404), even for a PUBLIC profile")
    void nonActiveMemberHasNoJourney() {
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(targetUser("PUBLIC")));
        when(accessPolicy.isMember("peer-1", CLASS_ID)).thenReturn(true);
        when(accessPolicy.isOwner(TARGET_ID, CLASS_ID)).thenReturn(false);

        for (String state : new String[]{"REMOVED", "BLOCKED", "BANNED"}) {
            ClassMember inactive = new ClassMember(CLASS_ID, TARGET_ID, "STUDENT");
            inactive.setState(state);
            when(classMemberRepository.findByClassIdAndUserId(CLASS_ID, TARGET_ID)).thenReturn(Optional.of(inactive));

            AppException ex = assertThrows(AppException.class, () -> userService.getJourney(TARGET_ID, "peer-1", CLASS_ID));
            assertEquals(com.classroom.common.ErrorCode.NOT_FOUND, ex.getErrorCode(), state);
        }
        verify(courseRepository, never()).findByClassIdOrderByPositionAsc(CLASS_ID);
    }

    // ----- R14-03: progress counts only visible lessons, on both sides, in single grouped queries -----

    @Test
    @DisplayName("R14-03: journey uses the visible-lesson grouped queries (archived lessons/sections excluded) and can reach 100%")
    void journeyCountsOnlyVisibleLessons() {
        when(accessPolicy.isMember(TARGET_ID, CLASS_ID)).thenReturn(true);
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(targetUser("PRIVATE")));
        when(classMemberRepository.findByClassIdAndUserId(CLASS_ID, TARGET_ID))
                .thenReturn(Optional.of(new ClassMember(CLASS_ID, TARGET_ID, "STUDENT")));
        Course course = new Course(CLASS_ID, "Course A", "FREE");
        course.setId("course-a");
        when(courseRepository.findByClassIdOrderByPositionAsc(CLASS_ID)).thenReturn(List.of(course));
        when(learningPolicy.canViewCourse(TARGET_ID, course)).thenReturn(true);
        when(learningPolicy.canLearn(TARGET_ID, course)).thenReturn(true);
        // 5 lessons exist but 2 are archived / in an archived section: the repository reports 3 visible.
        when(lessonRepository.countVisibleByCourseIdIn(any())).thenReturn(List.of(countRow("course-a", 3)));
        when(lessonProgressRepository.countCompletedVisibleByUserAndCourseIdIn(eq(TARGET_ID), any()))
                .thenReturn(List.of(progressRow("course-a", 3)));

        UserJourneyDto dto = userService.getJourney(TARGET_ID, TARGET_ID, CLASS_ID);

        assertEquals(3, dto.getCourses().get(0).getCompletedLessons());
        assertEquals(3, dto.getCourses().get(0).getTotalLessons());
        verify(lessonRepository, never()).countByCourseIdIn(any());
        verify(lessonProgressRepository, never()).countCompletedByUserAndCourseIdIn(any(), any());
    }

    // ----- R15-01: the course list is limited by the VIEWER's rights, not only the target's -----

    /** An OWNER target "can learn" every course of the class (incl. DRAFT), so the target's rights alone over-share. */
    private void stubOwnerTargetWithDraftAndPublishedCourse(String viewerId, Course published, Course draft) {
        when(accessPolicy.isMember(viewerId, CLASS_ID)).thenReturn(true);
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(targetUser("CLASS")));
        when(accessPolicy.isOwner(TARGET_ID, CLASS_ID)).thenReturn(true);
        // R14-11 branch: an owner may have no member row of their own.
        when(classMemberRepository.findByClassIdAndUserId(CLASS_ID, TARGET_ID)).thenReturn(Optional.empty());
        when(courseRepository.findByClassIdOrderByPositionAsc(CLASS_ID)).thenReturn(List.of(published, draft));
        for (Course c : List.of(published, draft)) {
            when(learningPolicy.canViewCourse(TARGET_ID, c)).thenReturn(true);
            when(learningPolicy.canLearn(TARGET_ID, c)).thenReturn(true);
        }
        when(learningPolicy.canViewCourse(viewerId, published)).thenReturn(true);
    }

    @Test
    @DisplayName("R15-01: an OWNER target's DRAFT course is NOT listed to a student peer (CLASS visibility)")
    void ownerDraftCourseHiddenFromPeerViewer() {
        Course published = new Course(CLASS_ID, "Khóa công khai", "FREE");
        published.setId("course-pub");
        published.setStatus("PUBLISHED");
        Course draft = new Course(CLASS_ID, "Khóa nháp bí mật", "FREE");
        draft.setId("course-draft");
        draft.setStatus("DRAFT");
        stubOwnerTargetWithDraftAndPublishedCourse("student-1", published, draft);
        when(learningPolicy.canViewCourse("student-1", draft)).thenReturn(false);
        when(lessonRepository.countVisibleByCourseIdIn(any())).thenReturn(List.of(countRow("course-pub", 6)));
        when(lessonProgressRepository.countCompletedVisibleByUserAndCourseIdIn(eq(TARGET_ID), any())).thenReturn(List.of());

        UserJourneyDto dto = userService.getJourney(TARGET_ID, "student-1", CLASS_ID);

        assertEquals(1, dto.getCourses().size());
        assertEquals("course-pub", dto.getCourses().get(0).getCourseId());
        assertTrue(dto.getCourses().stream().noneMatch(c -> "course-draft".equals(c.getCourseId())),
                "a DRAFT course must never leak its id/title/lesson count to a peer");
        // The lesson-count query is only ever issued for the courses the viewer may see.
        verify(lessonRepository).countVisibleByCourseIdIn(List.of("course-pub"));
    }

    @Test
    @DisplayName("R15-01: a viewer holding COURSE preview rights still sees the DRAFT course in the journey")
    void viewerWithPreviewRightsSeesDraftCourse() {
        Course published = new Course(CLASS_ID, "Khóa công khai", "FREE");
        published.setId("course-pub");
        published.setStatus("PUBLISHED");
        Course draft = new Course(CLASS_ID, "Khóa nháp", "FREE");
        draft.setId("course-draft");
        draft.setStatus("DRAFT");
        stubOwnerTargetWithDraftAndPublishedCourse("staff-1", published, draft);
        when(learningPolicy.canViewCourse("staff-1", draft)).thenReturn(true);
        when(lessonRepository.countVisibleByCourseIdIn(any())).thenReturn(List.of(countRow("course-pub", 6), countRow("course-draft", 2)));
        when(lessonProgressRepository.countCompletedVisibleByUserAndCourseIdIn(eq(TARGET_ID), any())).thenReturn(List.of());

        UserJourneyDto dto = userService.getJourney(TARGET_ID, "staff-1", CLASS_ID);

        assertEquals(2, dto.getCourses().size());
    }

    @Test
    @DisplayName("R15-01: a course the target cannot learn is still excluded even when the viewer can see it")
    void targetRightsStillRequired() {
        Course course = new Course(CLASS_ID, "Khóa trả phí", "PURCHASE_REQUIRED");
        course.setId("course-paid");
        course.setStatus("PUBLISHED");
        when(accessPolicy.isMember("peer-1", CLASS_ID)).thenReturn(true);
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(targetUser("PUBLIC")));
        when(classMemberRepository.findByClassIdAndUserId(CLASS_ID, TARGET_ID))
                .thenReturn(Optional.of(new ClassMember(CLASS_ID, TARGET_ID, "STUDENT")));
        when(courseRepository.findByClassIdOrderByPositionAsc(CLASS_ID)).thenReturn(List.of(course));
        when(learningPolicy.canViewCourse(TARGET_ID, course)).thenReturn(true);
        when(learningPolicy.canLearn(TARGET_ID, course)).thenReturn(false);
        lenient().when(learningPolicy.canViewCourse("peer-1", course)).thenReturn(true);

        UserJourneyDto dto = userService.getJourney(TARGET_ID, "peer-1", CLASS_ID);

        assertTrue(dto.getCourses().isEmpty());
        verify(lessonRepository, never()).countVisibleByCourseIdIn(any());
    }
}
