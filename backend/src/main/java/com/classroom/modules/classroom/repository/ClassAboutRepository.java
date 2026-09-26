package com.classroom.modules.classroom.repository;

import com.classroom.modules.classroom.model.ClassAbout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ClassAboutRepository extends JpaRepository<ClassAbout, String> {
    Optional<ClassAbout> findByClassId(String classId);
}
