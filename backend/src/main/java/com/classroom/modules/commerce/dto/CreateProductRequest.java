package com.classroom.modules.commerce.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * R4-05: typed, validated request body for product creation. The previous Map<String, Object>
 * body let bad input types (a non-numeric price, a malformed accessStartsAt, a non-integer
 * durationDays) throw ClassCastException / NumberFormatException / DateTimeParseException
 * straight into a 500. Field names match the existing frontend payload (StudioStore.tsx).
 */
public class CreateProductRequest {
    private String title;
    private String description;
    private String targetCourseId;
    private BigDecimal price;
    private Integer durationDays;
    private String accessStartsAt;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getTargetCourseId() {
        return targetCourseId;
    }

    public void setTargetCourseId(String targetCourseId) {
        this.targetCourseId = targetCourseId;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getDurationDays() {
        return durationDays;
    }

    public void setDurationDays(Integer durationDays) {
        this.durationDays = durationDays;
    }

    public String getAccessStartsAt() {
        return accessStartsAt;
    }

    public void setAccessStartsAt(String accessStartsAt) {
        this.accessStartsAt = accessStartsAt;
    }
}
