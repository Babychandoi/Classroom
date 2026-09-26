package com.classroom.integration;

import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.learning.model.AssignmentSubmission;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.model.Lesson;
import com.classroom.modules.learning.model.Section;
import com.classroom.modules.learning.repository.AssignmentSubmissionRepository;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.learning.repository.LessonRepository;
import com.classroom.modules.learning.repository.SectionRepository;
import com.classroom.modules.learning.service.AssignmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@SpringBootTest
@ActiveProfiles("integration")
class AssignmentAttemptConcurrencyIntegrationTest {
    @Autowired private ClassroomRepository classroomRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private AssignmentSubmissionRepository submissionRepository;
    @Autowired private AssignmentService assignmentService;

    @Test
    @DisplayName("Concurrent assignment submissions receive distinct consecutive MySQL attempt numbers")
    void concurrentSubmissionsAreSerialized() throws Exception {
        var classroom = classroomRepository.findBySlug("lop-toan-nang-cao").orElseThrow();
        var student = userRepository.findByEmail("student.free@classroom.local").orElseThrow();
        Course course = new Course(classroom.getId(), "Concurrency " + System.nanoTime(), "FREE");
        course.setStatus("PUBLISHED");
        course = courseRepository.save(course);
        Section section = sectionRepository.save(new Section(course.getId(), "Section", 1));
        Lesson lesson = lessonRepository.save(new Lesson(section.getId(), course.getId(), "Assignment", "ASSIGNMENT", 1));

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AssignmentSubmission>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final int answer = i;
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return assignmentService.submit(lesson.getId(), student.getId(), "answer-" + answer);
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        Set<Integer> attemptNumbers = new HashSet<>();
        for (Future<AssignmentSubmission> future : futures) {
            attemptNumbers.add(future.get(20, TimeUnit.SECONDS).getAttemptNumber());
        }
        executor.shutdownNow();

        assertEquals(Set.of(1, 2, 3, 4, 5), attemptNumbers);
        assertEquals(threadCount, submissionRepository.findByLessonIdAndUserIdOrderByAttemptNumberDesc(
                lesson.getId(), student.getId()).size());
    }
}
