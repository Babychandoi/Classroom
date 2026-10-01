package com.classroom.modules.classroom.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * D-19: one invite link of a (typically PRIVATE) class. Only the SHA-256 of the code is persisted - never the code itself - so a database
 * read or backup cannot be turned into working links; {@link #codeHint} (the last 4 characters) lets the owner tell two links apart in
 * the list. {@code usedCount} is only ever changed under a row lock (see ClassInviteRepository#findByIdForUpdate), {@code version} is the
 * optimistic-lock backstop for any path that forgets to.
 */
@Entity
@Table(name = "class_invites")
public class ClassInvite {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_EXHAUSTED = "EXHAUSTED";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "class_id", nullable = false, length = 36)
    private String classId;

    @Column(name = "code_hash", nullable = false, unique = true, length = 64, columnDefinition = "CHAR(64)")
    private String codeHash;

    @Column(name = "code_hint", nullable = false, length = 8)
    private String codeHint;

    @Column(name = "created_by", nullable = false, length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "max_uses")
    private Integer maxUses;

    @Column(name = "used_count", nullable = false)
    private int usedCount;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Version
    @Column(nullable = false)
    private long version;

    public ClassInvite() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
    }

    public ClassInvite(String classId, String codeHash, String codeHint, String createdBy, Instant expiresAt, Integer maxUses) {
        this.id = UUID.randomUUID().toString();
        this.classId = classId;
        this.codeHash = codeHash;
        this.codeHint = codeHint;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
        this.maxUses = maxUses;
    }

    /** REVOKED beats EXPIRED beats EXHAUSTED: the most deliberate reason wins when several apply. */
    public String statusAt(Instant now) {
        if (revokedAt != null) return STATUS_REVOKED;
        if (expiresAt != null && !expiresAt.isAfter(now)) return STATUS_EXPIRED;
        if (maxUses != null && usedCount >= maxUses) return STATUS_EXHAUSTED;
        return STATUS_ACTIVE;
    }

    public boolean isUsableAt(Instant now) {
        return STATUS_ACTIVE.equals(statusAt(now));
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getClassId() { return classId; }
    public void setClassId(String classId) { this.classId = classId; }
    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }
    public String getCodeHint() { return codeHint; }
    public void setCodeHint(String codeHint) { this.codeHint = codeHint; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Integer getMaxUses() { return maxUses; }
    public void setMaxUses(Integer maxUses) { this.maxUses = maxUses; }
    public int getUsedCount() { return usedCount; }
    public void setUsedCount(int usedCount) { this.usedCount = usedCount; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
