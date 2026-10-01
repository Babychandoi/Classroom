package com.classroom.modules.classroom.repository;

import com.classroom.modules.classroom.model.StaffAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface StaffAssignmentRepository extends JpaRepository<StaffAssignment, String> {
    Optional<StaffAssignment> findByClassIdAndUserId(String classId, String userId);
    List<StaffAssignment> findByClassId(String classId);

    /** R16-08: the user's staff assignments across a page of classes (batch form of findByClassIdAndUserId). */
    List<StaffAssignment> findByUserIdAndClassIdIn(String userId, Collection<String> classIds);
    boolean existsByClassIdAndUserId(String classId, String userId);
}
