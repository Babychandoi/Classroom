package com.classroom.modules.commerce.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product_prices")
public class ProductPrice {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "product_id", nullable = false, length = 36)
    private String productId;

    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal price = BigDecimal.ZERO;

    @Column(length = 10, nullable = false)
    private String currency = "VND";

    @Column(name = "duration_days", nullable = false)
    private int durationDays = 30;

    @Column(name = "access_starts_at", nullable = false)
    private Instant accessStartsAt = Instant.EPOCH;

    public ProductPrice() {
        this.id = UUID.randomUUID().toString();
    }

    public ProductPrice(String productId, BigDecimal price, String currency, int durationDays) {
        this(productId, price, currency, durationDays, Instant.now());
    }

    public ProductPrice(String productId, BigDecimal price, String currency, int durationDays, Instant accessStartsAt) {
        this.id = UUID.randomUUID().toString();
        this.productId = productId;
        this.price = price;
        this.currency = (currency != null) ? currency : "VND";
        this.durationDays = durationDays;
        this.accessStartsAt = accessStartsAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public int getDurationDays() {
        return durationDays;
    }

    public void setDurationDays(int durationDays) {
        this.durationDays = durationDays;
    }

    public Instant getAccessStartsAt() { return accessStartsAt; }

    public void setAccessStartsAt(Instant accessStartsAt) { this.accessStartsAt = accessStartsAt; }
}
