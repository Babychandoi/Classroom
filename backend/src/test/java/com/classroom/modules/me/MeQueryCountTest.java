package com.classroom.modules.me;

import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.classroom.service.ClassroomService;
import com.classroom.modules.event.model.ClassEvent;
import com.classroom.modules.event.model.EventRegistration;
import com.classroom.modules.event.repository.ClassEventRepository;
import com.classroom.modules.event.repository.EventRegistrationRepository;
import com.classroom.modules.event.service.EventService;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.model.Lesson;
import com.classroom.modules.learning.model.LessonProgress;
import com.classroom.modules.learning.model.Section;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.learning.repository.LessonProgressRepository;
import com.classroom.modules.learning.repository.LessonRepository;
import com.classroom.modules.learning.repository.SectionRepository;
import com.classroom.modules.learning.service.MyCoursesService;
import io.minio.MinioClient;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-30: the three "của tôi" read models cost a fixed number of statements however many classes / courses / events the page holds
 * (measured on the in-memory database: the number of statements is a property of the code). Each is measured on a page of 2 rows and on a
 * page of 20 rows of the same shape; the counts must be equal (or within one statement), and small.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.classroom.modules.me.MeQueryCountTest$CountingInspector")
@ActiveProfiles("test")
class MeQueryCountTest {

    @Autowired private UserRepository userRepository;
    @Autowired private ClassroomRepository classroomRepository;
    @Autowired private ClassMemberRepository memberRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private LessonProgressRepository progressRepository;
    @Autowired private ClassEventRepository eventRepository;
    @Autowired private EventRegistrationRepository registrationRepository;
    @Autowired private ClassroomService classroomService;
    @Autowired private MyCoursesService myCoursesService;
    @Autowired private EventService eventService;

    @MockBean(name = "minioClient") private MinioClient minioClient;
    @MockBean(name = "minioPresigningClient") private MinioClient minioPresigningClient;

    public static class CountingInspector implements StatementInspector {
        static final ThreadLocal<Long> COUNT = new ThreadLocal<>();
        @Override public String inspect(String sql) {
            Long current = COUNT.get();
            if (current != null) COUNT.set(current + 1);
            return sql;
        }
    }

    private long statementsOf(Supplier<?> work) {
        CountingInspector.COUNT.set(0L);
        try {
            work.get();
            return CountingInspector.COUNT.get();
        } finally {
            CountingInspector.COUNT.remove();
        }
    }

    private User learner;
    private User teacher;

    /** Adds {@code n} FREE classes the learner belongs to, each with a course (2 lessons, 1 done) and a registered upcoming event. */
    private void grow(int n) {
        String run = UUID.randomUUID().toString().substring(0, 8);
        if (teacher == null) {
            teacher = userRepository.save(new User(null, "d30.t." + run + "@test.local", "hash", "D30 Teacher", "USER"));
            learner = userRepository.save(new User(null, "d30.l." + run + "@test.local", "hash", "D30 Learner", "USER"));
        }
        for (int i = 0; i < n; i++) {
            Classroom c = new Classroom();
            c.setOwnerId(teacher.getId());
            c.setSlug("d30-" + run + "-" + i);
            c.setTitle("D30 class " + run + " " + i);
            c.setStatus("ACTIVE");
            c = classroomRepository.save(c);
            memberRepository.save(new ClassMember(c.getId(), teacher.getId(), "OWNER"));
            memberRepository.save(new ClassMember(c.getId(), learner.getId(), "STUDENT"));
            Course course = new Course(c.getId(), "D30 course " + i, "FREE");
            course.setStatus("PUBLISHED");
            course = courseRepository.save(course);
            Section s = sectionRepository.save(new Section(course.getId(), "S", 1));
            Lesson l1 = lessonRepository.save(new Lesson(s.getId(), course.getId(), "L1", "TEXT", 1));
            lessonRepository.save(new Lesson(s.getId(), course.getId(), "L2", "TEXT", 2));
            progressRepository.save(new LessonProgress(learner.getId(), l1.getId(), course.getId(), c.getId()));
            ClassEvent e = new ClassEvent();
            e.setClassId(c.getId());
            e.setCreatedBy(teacher.getId());
            e.setHostUserId(teacher.getId());
            e.setTitle("D30 event " + i);
            e.setFormat(ClassEvent.FORMAT_ONLINE);
            e.setStartsAt(Instant.now().plus(1 + i, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS));
            e.setEndsAt(e.getStartsAt().plus(1, ChronoUnit.HOURS));
            e.setAudience(ClassEvent.AUDIENCE_PUBLIC);
            e = eventRepository.save(e);
            registrationRepository.save(new EventRegistration(e.getId(), learner.getId()));
        }
    }

    @Test
    @DisplayName("/me/classes, /me/courses, /me/events: the statement count does not grow with the number of rows")
    void fixedStatementCount() {
        grow(2);
        String id = learner.getId();
        // warm-up (first call loads metadata / caches)
        assertEquals(2, classroomService.listMine(id, 0, 20).size());
        assertEquals(2, myCoursesService.listMine(id, 0, 20).size());
        assertEquals(2, eventService.listMine(id, "upcoming", 0, 20).size());
        long classesSmall = statementsOf(() -> classroomService.listMine(id, 0, 20));
        long coursesSmall = statementsOf(() -> myCoursesService.listMine(id, 0, 20));
        long eventsSmall = statementsOf(() -> eventService.listMine(id, "upcoming", 0, 20));

        grow(18);
        assertEquals(20, classroomService.listMine(id, 0, 20).size());
        assertEquals(20, myCoursesService.listMine(id, 0, 20).size());
        assertEquals(20, eventService.listMine(id, "upcoming", 0, 20).size());
        long classesBig = statementsOf(() -> classroomService.listMine(id, 0, 20));
        long coursesBig = statementsOf(() -> myCoursesService.listMine(id, 0, 20));
        long eventsBig = statementsOf(() -> eventService.listMine(id, "upcoming", 0, 20));

        System.out.println("[query-count] me classes " + classesSmall + "->" + classesBig + ", courses " + coursesSmall + "->" + coursesBig
                + ", events " + eventsSmall + "->" + eventsBig);
        assertTrue(Math.abs(classesBig - classesSmall) <= 1, "classes " + classesSmall + " vs " + classesBig);
        assertTrue(Math.abs(coursesBig - coursesSmall) <= 1, "courses " + coursesSmall + " vs " + coursesBig);
        assertTrue(Math.abs(eventsBig - eventsSmall) <= 1, "events " + eventsSmall + " vs " + eventsBig);
        assertTrue(classesBig <= 12, "classes " + classesBig);
        assertTrue(coursesBig <= 14, "courses " + coursesBig);
        assertTrue(eventsBig <= 10, "events " + eventsBig);
    }
}
