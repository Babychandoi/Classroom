package com.classroom.modules.commerce.repository;

import com.classroom.modules.commerce.model.Entitlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface EntitlementRepository extends JpaRepository<Entitlement, String> {
    List<Entitlement> findByUserIdAndClassId(String userId, String classId);

    @Query("SELECT e FROM Entitlement e WHERE e.userId = :userId AND e.classId = :classId AND e.state = 'ACTIVE' AND e.startsAt <= :now AND e.expiresAt > :now")
    List<Entitlement> findActiveEntitlements(
            @Param("userId") String userId,
            @Param("classId") String classId,
            @Param("now") Instant now
    );

    @Query("SELECT COUNT(e) > 0 FROM Entitlement e WHERE e.userId = :userId AND e.classId = :classId AND e.state = 'ACTIVE' AND e.startsAt <= :now AND e.expiresAt > :now")
    boolean hasActiveProEntitlement(
            @Param("userId") String userId,
            @Param("classId") String classId,
            @Param("now") Instant now
    );

    @Query("SELECT COUNT(e) > 0 FROM Entitlement e WHERE e.userId = :userId AND e.classId = :classId AND e.targetCourseId = :courseId AND e.productId = :productId AND e.state = 'ACTIVE' AND e.startsAt <= :now AND e.expiresAt > :now")
    boolean hasCourseAccess(
            @Param("userId") String userId,
            @Param("classId") String classId,
            @Param("courseId") String courseId,
            @Param("productId") String productId,
            @Param("now") Instant now
    );

    @Query("SELECT COUNT(e) > 0 FROM Entitlement e WHERE e.userId = :userId AND e.classId = :classId AND e.productId = :productId AND e.state = 'ACTIVE' AND e.startsAt <= :now AND e.expiresAt > :now")
    boolean hasProductAccess(
            @Param("userId") String userId,
            @Param("classId") String classId,
            @Param("productId") String productId,
            @Param("now") Instant now
    );

    @Query("SELECT e FROM Entitlement e WHERE e.userId = :userId AND e.classId = :classId AND e.productId = :productId AND e.state = 'ACTIVE' AND e.expiresAt > :now ORDER BY e.expiresAt DESC")
    List<Entitlement> findLatestActiveByProduct(
            @Param("userId") String userId,
            @Param("classId") String classId,
            @Param("productId") String productId,
            @Param("now") Instant now
    );

    @Query("SELECT e FROM Entitlement e WHERE e.userId = :userId AND e.classId = :classId AND e.productId = :productId AND e.state = 'ACTIVE' AND e.expiresAt > :now ORDER BY e.startsAt ASC")
    List<Entitlement> findFutureActiveByProductAsc(
            @Param("userId") String userId,
            @Param("classId") String classId,
            @Param("productId") String productId,
            @Param("now") Instant now
    );

    List<Entitlement> findByOrderId(String orderId);
}
