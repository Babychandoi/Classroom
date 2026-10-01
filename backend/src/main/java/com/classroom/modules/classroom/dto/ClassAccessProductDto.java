package com.classroom.modules.classroom.dto;

import java.math.BigDecimal;

/**
 * D-19: what a PAID class sells, as the UI needs it for the paywall / checkout: the class-access product id (the {@code productId} of
 * POST /orders), the price and the length of access. {@code durationDays == null} (and {@code lifetime == true}) means the purchase
 * never expires. Carried by {@link ClassroomDto#getAccessProduct()}, by the invite preview, and as {@code error.details.accessProduct}
 * of a PAYMENT_REQUIRED (402).
 */
public class ClassAccessProductDto {
    private String id;
    private BigDecimal price;
    private String currency;
    private Integer durationDays;
    private boolean lifetime;

    public ClassAccessProductDto() {
    }

    public ClassAccessProductDto(String id, BigDecimal price, String currency, Integer durationDays) {
        this.id = id;
        this.price = price;
        this.currency = currency;
        this.durationDays = durationDays;
        this.lifetime = durationDays == null;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public Integer getDurationDays() {
        return durationDays;
    }

    public void setDurationDays(Integer durationDays) {
        this.durationDays = durationDays;
        this.lifetime = durationDays == null;
    }

    public boolean isLifetime() {
        return lifetime;
    }

    public void setLifetime(boolean lifetime) {
        this.lifetime = lifetime;
    }
}
