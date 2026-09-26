package com.classroom.modules.learning.repository;

import com.classroom.modules.learning.model.Course;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;

import java.util.List;

@Repository
public interface CourseRepository extends JpaRepository<Course, String> {
    List<Course> findByClassIdOrderByPositionAsc(String classId);
    List<Course> findByClassIdAndStatusOrderByPositionAsc(String classId, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Course c where c.id = :id")
    java.util.Optional<Course> findByIdForUpdate(@Param("id") String id);
}
