package com.classroom.integration;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.common.GlobalExceptionHandler;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.model.StaffAssignment;
import com.classroom.modules.classroom.model.StaffPermission;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.classroom.repository.StaffAssignmentRepository;
import com.classroom.modules.classroom.repository.StaffPermissionRepository;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.learning.service.LearningService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R18-04 / R18-07 against a real MySQL: the course list order is deterministic (position, then createdAt,
 * then id) with new courses appended at max + 1, and a course a staff grant is scoped to cannot be deleted -
 * the service answers 409 naming the staff, and the raw foreign-key failure that guards it (V19 RESTRICT) is
 * recognised as a "referenced row" violation by the exception handler.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("integration")
class CourseOrderingAndStaffScopeIntegrationTest {

    @Autowired private ClassroomRepository classroomRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private StaffAssignmentRepository staffAssignmentRepository;
    @Autowired private StaffPermissionRepository staffPermissionRepository;
    @Autowired private LearningService learningService;

    private Classroom newClass(String suffix, User owner) {
        return classroomRepository.save(new Classroom(null, owner.getId(), "course-order-" + suffix,
                "Course order " + suffix, "desc"));
    }

    private User newUser(String prefix, String suffix, String name) {
        return userRepository.save(new User(null, prefix + "-" + suffix + "@test.local", "hash", name, "USER"));
    }

    @Test
    @DisplayName("R18-04: new courses append at max+1 and ties on position break by createdAt then id")
    void newCoursesAppendAndTiesAreDeterministic() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User owner = newUser("owner", suffix, "Owner " + suffix);
        Classroom classroom = newClass(suffix, owner);
        String classId = classroom.getId();

        assertEquals(-1, courseRepository.findMaxPositionByClassId(classId), "an empty class has no position yet");

        Course a = learningService.createCourse(classId, new Course(classId, "A", "FREE"), owner.getId());
        Course b = learningService.createCourse(classId, new Course(classId, "B", "FREE"), owner.getId());
        Course c = learningService.createCourse(classId, new Course(classId, "C", "FREE"), owner.getId());
        assertEquals(List.of(0, 1, 2), List.of(a.getPosition(), b.getPosition(), c.getPosition()));
        assertEquals(List.of(a.getId(), b.getId(), c.getId()),
                courseRepository.findByClassIdOrderByPositionAsc(classId).stream().map(Course::getId).toList());

        // Legacy rows all sit on position 3: older first, and a shared timestamp falls back to the id.
        Instant base = Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Course newer = saveAt(classId, "Newer", 3, base.plusSeconds(60), "ffffffff-0000-0000-0000-000000000001");
        Course older = saveAt(classId, "Older", 3, base, "ffffffff-0000-0000-0000-000000000002");
        Course tieLow = saveAt(classId, "TieLow", 3, base.plusSeconds(120), "aaaaaaaa-0000-0000-0000-000000000001");
        Course tieHigh = saveAt(classId, "TieHigh", 3, base.plusSeconds(120), "bbbbbbbb-0000-0000-0000-000000000001");

        List<String> expected = List.of(a.getId(), b.getId(), c.getId(),
                older.getId(), newer.getId(), tieLow.getId(), tieHigh.getId());
        for (int i = 0; i < 3; i++) { // repeated reads must agree
            assertEquals(expected, courseRepository.findByClassIdOrderByPositionAsc(classId).stream().map(Course::getId).toList());
        }
        assertEquals(List.of(older.getId(), newer.getId(), tieLow.getId(), tieHigh.getId()),
                courseRepository.findByClassIdAndStatusOrderByPositionAsc(classId, "PUBLISHED").stream().map(Course::getId).toList());

        Course last = learningService.createCourse(classId, new Course(classId, "Last", "FREE"), owner.getId());
        assertEquals(4, last.getPosition(), "appended after the highest existing position");
    }

    private Course saveAt(String classId, String title, int position, Instant createdAt, String id) {
        Course course = new Course(classId, title, "FREE");
        course.setId(id);
        course.setStatus("PUBLISHED");
        course.setPosition(position);
        course.setCreatedAt(createdAt);
        return courseRepository.save(course);
    }

    @Test
    @DisplayName("R18-07: deleting a DRAFT course a staff grant is scoped to is a 409 naming the staff; the raw FK failure is recognised")
    void staffScopedGrantBlocksCourseDelete() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User owner = newUser("owner", suffix, "Owner " + suffix);
        User staff = newUser("staff", suffix, "Trợ giảng " + suffix);
        Classroom classroom = newClass(suffix, owner);
        String classId = classroom.getId();

        Course course = learningService.createCourse(classId, new Course(classId, "Khóa nháp", "FREE"), owner.getId());
        StaffAssignment assignment = staffAssignmentRepository.save(new StaffAssignment(classId, staff.getId()));
        StaffPermission grant = staffPermissionRepository.save(
                new StaffPermission(assignment.getId(), "COURSE", "EDIT", course.getId()));

        AppException blocked = assertThrows(AppException.class,
                () -> learningService.deleteCourse(course.getId(), owner.getId()));
        assertEquals(ErrorCode.CONFLICT, blocked.getErrorCode());
        assertTrue(blocked.getMessage().contains("Trợ giảng " + suffix), blocked.getMessage());
        assertTrue(blocked.getMessage().contains("COURSE:EDIT"), blocked.getMessage());
        assertTrue(courseRepository.existsById(course.getId()));

        // Bypassing the service, the database itself refuses (V19 RESTRICT) and the handler maps it to 409.
        DataIntegrityViolationException fk = assertThrows(DataIntegrityViolationException.class, () -> {
            courseRepository.deleteById(course.getId());
            courseRepository.flush();
        });
        assertTrue(GlobalExceptionHandler.isReferencedRowViolation(fk), String.valueOf(fk.getMostSpecificCause()));

        // Once the grant is removed the delete goes through.
        staffPermissionRepository.delete(grant);
        learningService.deleteCourse(course.getId(), owner.getId());
        assertFalse(courseRepository.existsById(course.getId()));
    }
}
