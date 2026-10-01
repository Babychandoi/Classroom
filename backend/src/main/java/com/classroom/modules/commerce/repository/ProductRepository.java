package com.classroom.modules.commerce.repository;

import com.classroom.modules.commerce.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

import java.util.List;

@Repository
public interface ProductRepository extends JpaRepository<Product, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") String id);
    List<Product> findByClassIdAndStatusOrderByCreatedAtDesc(String classId, String status);
    List<Product> findByClassIdOrderByCreatedAtDesc(String classId);

    /** R16-03: whether any product still targets the course (a course must not be deleted under one). */
    boolean existsByTargetCourseId(String targetCourseId);
}
