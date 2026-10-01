package com.classroom.modules.classroom.dto;

import java.time.Instant;

/**
 * D-19: POST /classes/{id}/invites. Both fields are optional: no {@code expiresAt} = never expires, no {@code maxUses} = unlimited uses.
 * {@code expiresAt} is an ISO-8601 instant in the future; {@code maxUses} is 1..100000.
 */
public class CreateInviteRequest {
    private Instant expiresAt;
    private Integer maxUses;

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
}
