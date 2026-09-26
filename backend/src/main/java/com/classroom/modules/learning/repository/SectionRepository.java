package com.classroom.modules.learning.repository;

import com.classroom.modules.learning.model.Section;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SectionRepository extends JpaRepository<Section, String> {
    List<Section> findByCourseIdOrderByPositionAsc(String courseId);
    void deleteByCourseId(String courseId);
}
