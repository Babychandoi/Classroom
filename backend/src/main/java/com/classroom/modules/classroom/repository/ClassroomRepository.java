package com.classroom.modules.classroom.repository;

import com.classroom.modules.classroom.model.Classroom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClassroomRepository extends JpaRepository<Classroom, String> {
    Optional<Classroom> findBySlug(String slug);
    List<Classroom> findByOwnerId(String ownerId);
    boolean existsBySlug(String slug);
}
