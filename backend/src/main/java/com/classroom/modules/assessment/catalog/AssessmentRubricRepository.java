package com.classroom.modules.assessment.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AssessmentRubricRepository extends JpaRepository<AssessmentRubric, String> {
    List<AssessmentRubric> findBySystemPresetTrueOrderByIdAsc();

    /** The system presets plus the rubrics of one class. */
    @Query("select r from AssessmentRubric r where r.systemPreset = true or r.classId = :classId order by r.systemPreset desc, r.createdAt asc, r.id asc")
    List<AssessmentRubric> findAvailableForClass(@Param("classId") String classId);
}
