package com.classroom.modules.classroom.repository;

import com.classroom.modules.classroom.model.StaffPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StaffPermissionRepository extends JpaRepository<StaffPermission, String> {
    List<StaffPermission> findByAssignmentId(String assignmentId);

    /** R18-07: grants scoped to one course (staff_permissions.scope_course_id, RESTRICT foreign key to courses). */
    List<StaffPermission> findByScopeCourseId(String scopeCourseId);

    /** R16-08: batch form of findByAssignmentId for the class listing. */
    List<StaffPermission> findByAssignmentIdIn(java.util.Collection<String> assignmentIds);

    // Bulk JPQL delete executes immediately against the database rather than being queued
    // as entity-level removals, so it is guaranteed to run before any subsequent save() of
    // permissions for the same assignment - avoiding a flush-ordering collision against
    // uk_staff_permission (assignment_id, module, action, scope_course_id).
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from StaffPermission p where p.assignmentId = :assignmentId")
    void deleteByAssignmentId(String assignmentId);
}
