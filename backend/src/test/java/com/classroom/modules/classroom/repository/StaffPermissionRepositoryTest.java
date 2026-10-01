package com.classroom.modules.classroom.repository;

import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.StaffAssignment;
import com.classroom.modules.classroom.model.StaffPermission;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.repository.CourseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test for a re-save-after-delete race on {@code uk_staff_permission}
 * (assignment_id, module, action, scope_course_id). Hibernate's default flush order runs
 * queued inserts before queued deletes, so re-granting the same course-scoped permission for
 * an assignment used to collide with the row the delete had not removed yet. The bulk JPQL
 * delete in {@link StaffPermissionRepository#deleteByAssignmentId} flushes immediately, so the
 * delete is guaranteed to hit the database before any subsequent insert for the same key.
 */
@SpringBootTest
@ActiveProfiles("test")
class StaffPermissionRepositoryTest {

    @Autowired
    private StaffAssignmentRepository staffAssignmentRepository;
    @Autowired
    private StaffPermissionRepository staffPermissionRepository;
    @Autowired
    private CourseRepository courseRepository;

    @Test
    @Transactional
    @DisplayName("Re-saving the same course-scoped grant after deleteByAssignmentId does not collide with uk_staff_permission")
    void resavingCourseScopedGrantAfterDeleteDoesNotCollide() {
        String classId = UUID.randomUUID().toString();

        Course course = new Course(classId, "Course for staff perm test", "FREE");
        course = courseRepository.save(course);

        StaffAssignment assignment = new StaffAssignment(classId, UUID.randomUUID().toString());
        assignment.setStatus("ACTIVE");
        assignment = staffAssignmentRepository.save(assignment);

        final String assignmentId = assignment.getId();
        final String courseId = course.getId();

        StaffPermission original = new StaffPermission(assignmentId, "COURSE", "EDIT", courseId);
        staffPermissionRepository.save(original);

        // Simulate StaffService#assignStaff re-granting the identical permission: delete all
        // existing grants for the assignment, then insert the (possibly identical) new set,
        // within the same transaction/persistence context.
        assertDoesNotThrow(() -> {
            staffPermissionRepository.deleteByAssignmentId(assignmentId);
            StaffPermission reGranted = new StaffPermission(assignmentId, "COURSE", "EDIT", courseId);
            staffPermissionRepository.save(reGranted);
        });

        List<StaffPermission> remaining = staffPermissionRepository.findByAssignmentId(assignmentId);
        assertEquals(1, remaining.size());
    }
}
