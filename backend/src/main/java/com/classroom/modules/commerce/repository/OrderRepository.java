package com.classroom.modules.commerce.repository;

import com.classroom.modules.commerce.model.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {
    Optional<Order> findByOrderNumber(String orderNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.orderNumber = :orderNumber")
    Optional<Order> findByOrderNumberForUpdate(@Param("orderNumber") String orderNumber);

    Optional<Order> findByProviderRef(String providerRef);
    Optional<Order> findByIdempotencyKey(String idempotencyKey);
    Optional<Order> findByIdempotencyKeyAndBuyerId(String idempotencyKey, String buyerId);
    List<Order> findByBuyerIdOrderByCreatedAtDesc(String buyerId);
    List<Order> findByClassIdOrderByCreatedAtDesc(String classId);
}
