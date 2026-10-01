package com.classroom.modules.classroom.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * D-19: GET /classes/invites/{code} - the minimum a person holding a valid invite needs to decide whether to join. Public (no login), so it
 * carries nothing about the viewer and nothing beyond the class card: no member list, no content, no owner identity beyond the display name.
 * For a PAID class the price / currency / duration are included so the UI can show the paywall.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InvitePreviewDto {
    private String classId;
    private String slug;
    private String title;
    private String description;
    private String coverImageUrl;
    private String accessType;
    private BigDecimal price;
    private String currency;
    private Integer durationDays;
    private Boolean lifetime;
    private String ownerName;

    public String getClassId() {
        return classId;
    }

    public void setClassId(String classId) {
        this.classId = classId;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

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

    public String getCoverImageUrl() {
        return coverImageUrl;
    }

    public void setCoverImageUrl(String coverImageUrl) {
        this.coverImageUrl = coverImageUrl;
    }

    public String getAccessType() {
        return accessType;
    }

    public void setAccessType(String accessType) {
        this.accessType = accessType;
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
    }

    public Boolean getLifetime() {
        return lifetime;
    }

    public void setLifetime(Boolean lifetime) {
        this.lifetime = lifetime;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }
}
