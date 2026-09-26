package com.classroom.modules.commerce.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "order_id", nullable = false, length = 36)
    private String orderId;

    @Column(name = "product_id", nullable = false, length = 36)
    private String productId;

    @Column(name = "product_name_snapshot", nullable = false)
    private String productNameSnapshot;

    @Column(name = "price_snapshot", precision = 12, scale = 2, nullable = false)
    private BigDecimal priceSnapshot;

    @Column(name = "duration_days_snapshot", nullable = false)
    private int durationDaysSnapshot;

    @Column(name = "access_starts_at_snapshot", nullable = false)
    private Instant accessStartsAtSnapshot = Instant.EPOCH;

    @Column(name = "target_course_id_snapshot", length = 36)
    private String targetCourseIdSnapshot;

    public OrderItem() {
        this.id = UUID.randomUUID().toString();
    }

    public OrderItem(String orderId, String productId, String productNameSnapshot, BigDecimal priceSnapshot, int durationDaysSnapshot) {
        this(orderId, productId, productNameSnapshot, priceSnapshot, durationDaysSnapshot, Instant.EPOCH);
    }

    public OrderItem(String orderId, String productId, String productNameSnapshot, BigDecimal priceSnapshot,
                     int durationDaysSnapshot, Instant accessStartsAtSnapshot) {
        this(orderId, productId, productNameSnapshot, priceSnapshot, durationDaysSnapshot, accessStartsAtSnapshot, null);
    }

    public OrderItem(String orderId, String productId, String productNameSnapshot, BigDecimal priceSnapshot,
                     int durationDaysSnapshot, Instant accessStartsAtSnapshot, String targetCourseIdSnapshot) {
        this.id = UUID.randomUUID().toString();
        this.orderId = orderId;
        this.productId = productId;
        this.productNameSnapshot = productNameSnapshot;
        this.priceSnapshot = priceSnapshot;
        this.durationDaysSnapshot = durationDaysSnapshot;
        this.accessStartsAtSnapshot = accessStartsAtSnapshot;
        this.targetCourseIdSnapshot = targetCourseIdSnapshot;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getProductNameSnapshot() {
        return productNameSnapshot;
    }

    public void setProductNameSnapshot(String productNameSnapshot) {
        this.productNameSnapshot = productNameSnapshot;
    }

    public BigDecimal getPriceSnapshot() {
        return priceSnapshot;
    }

    public void setPriceSnapshot(BigDecimal priceSnapshot) {
        this.priceSnapshot = priceSnapshot;
    }

    public int getDurationDaysSnapshot() {
        return durationDaysSnapshot;
    }

    public void setDurationDaysSnapshot(int durationDaysSnapshot) {
        this.durationDaysSnapshot = durationDaysSnapshot;
    }

    public Instant getAccessStartsAtSnapshot() { return accessStartsAtSnapshot; }

    public void setAccessStartsAtSnapshot(Instant accessStartsAtSnapshot) { this.accessStartsAtSnapshot = accessStartsAtSnapshot; }
    public String getTargetCourseIdSnapshot() { return targetCourseIdSnapshot; }
    public void setTargetCourseIdSnapshot(String targetCourseIdSnapshot) { this.targetCourseIdSnapshot = targetCourseIdSnapshot; }
}
