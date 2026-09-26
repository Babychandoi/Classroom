package com.classroom.modules.classroom.repository;

import com.classroom.modules.classroom.model.ClassMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClassMemberRepository extends JpaRepository<ClassMember, String> {
    Optional<ClassMember> findByClassIdAndUserId(String classId, String userId);
    List<ClassMember> findByClassId(String classId);
    List<ClassMember> findByUserId(String userId);
    boolean existsByClassIdAndUserId(String classId, String userId);
    long countByClassId(String classId);
}
