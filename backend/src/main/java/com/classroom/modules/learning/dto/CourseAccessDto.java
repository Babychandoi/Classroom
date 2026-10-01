package com.classroom.modules.learning.dto;

import java.time.Instant;

/**
 * R13-09 (Learn "hết hạn"): response for {@code GET /courses/{id}/access} — the viewer's access
 * reason (FREE, OWNED, OWNED_UPCOMING, PRO, EXPIRED, NOT_PURCHASED, STAFF, OWNER) and, when access comes from a
 * time-boxed entitlement, when it expires.
 */
public class CourseAccessDto {
    private boolean canLearn;
    private String accessReason;
    private Instant expiresAt;
    private Instant accessStartsAt;

    public CourseAccessDto() {}

    public CourseAccessDto(boolean canLearn, String accessReason, Instant expiresAt) {
        this.canLearn = canLearn;
        this.accessReason = accessReason;
        this.expiresAt = expiresAt;
    }

    public CourseAccessDto(boolean canLearn, String accessReason, Instant expiresAt, Instant accessStartsAt) {
        this(canLearn, accessReason, expiresAt);
        this.accessStartsAt = accessStartsAt;
    }

    public boolean isCanLearn() { return canLearn; }
    public void setCanLearn(boolean canLearn) { this.canLearn = canLearn; }
    public String getAccessReason() { return accessReason; }
    public void setAccessReason(String accessReason) { this.accessReason = accessReason; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getAccessStartsAt() { return accessStartsAt; }
    public void setAccessStartsAt(Instant accessStartsAt) { this.accessStartsAt = accessStartsAt; }
}
