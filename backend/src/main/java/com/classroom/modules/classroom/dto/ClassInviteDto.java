package com.classroom.modules.classroom.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * D-19: one invite as the owner / MEMBER:EDIT staff sees it. The code itself is NEVER part of the list (only {@code codeHint}, its last 4
 * characters); {@code code} is set exactly once - in the response of the create call - and is omitted from the JSON everywhere else.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ClassInviteDto {
    private String id;
    private String code;
    private String codeHint;
    private Instant createdAt;
    private Instant expiresAt;
    private Integer maxUses;
    private int usedCount;
    private String status;
    private String createdBy;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getCodeHint() {
        return codeHint;
    }

    public void setCodeHint(String codeHint) {
        this.codeHint = codeHint;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Integer getMaxUses() {
        return maxUses;
    }

    public void setMaxUses(Integer maxUses) {
        this.maxUses = maxUses;
    }

    public int getUsedCount() {
        return usedCount;
    }

    public void setUsedCount(int usedCount) {
        this.usedCount = usedCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
}
