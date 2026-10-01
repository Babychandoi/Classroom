package com.classroom.modules.commerce.repository;

import com.classroom.modules.commerce.model.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, String> {
    List<OrderItem> findByOrderId(String orderId);

    /** R13-06: batch item load for an order list ("Đơn hàng của tôi"), avoiding one query per order. */
    List<OrderItem> findByOrderIdIn(List<String> orderIds);

    @Query("SELECT COUNT(i) FROM OrderItem i, Order o WHERE i.orderId = o.id AND i.productId = :productId AND o.status IN ('PAID', 'REFUNDED')")
    long countSettledPurchasesByProductId(@Param("productId") String productId);

    @Query("SELECT COUNT(i) FROM OrderItem i WHERE i.productId = :productId")
    long countOrdersByProductId(@Param("productId") String productId);
}
