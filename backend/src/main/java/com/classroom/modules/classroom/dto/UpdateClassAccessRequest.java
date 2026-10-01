package com.classroom.modules.classroom.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/**
 * D-19: PUT /classes/{id}/access - how a class is paid for. {@code accessType} FREE needs nothing else; PAID needs {@code price} (&gt; 0,
 * whole dong for VND - the R19-09 rule) and optionally {@code currency} (only VND is supported today; default VND) and
 * {@code durationDays} (1..3650 days of access per purchase; absent / null / 0 = LIFETIME access). Money is involved, so this is not part of
 * PUT /classes/{id}: it is OWNER, or staff holding both STORE:EDIT and CLASS:EDIT.
 */
public class UpdateClassAccessRequest {
    @NotBlank(message = "Loại truy cập không được để trống")
    @Pattern(regexp = "^(?i)(FREE|PAID)$", message = "Loại truy cập chỉ có thể là FREE hoặc PAID")
    private String accessType;

    private BigDecimal price;
    private String currency;
    private Integer durationDays;

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
}
