package com.classroom.modules.identity.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * R8-06: only a one-way SHA-256 digest of the raw refresh token is ever persisted (never the token
 * itself), mirroring how access-token revocation is stored (see config.RevokedToken). familyId
 * groups every token minted by rotating a single login session; reusing an already-rotated
 * (non-current) token in that family signals a stolen token and revokes the whole family.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64, columnDefinition = "CHAR(64)")
    private String tokenHash;

    @Column(name = "family_id", nullable = false, length = 36)
    private String familyId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /**
     * R9-01: set (alongside revokedAt) only when this row was retired by a normal rotation, as
     * opposed to an explicit logout or reuse-detection revocation. Lets rotate() tell "this is the
     * previous link of a legitimate rotation chain, possibly still within the grace window" apart
     * from "this token was revoked outright and must never be honored again".
     */
    @Column(name = "rotated_at")
    private Instant rotatedAt;

    /**
     * R9-01: the hash of the token this row was rotated into. Within the grace window, a reuse of
     * this (now-retired) row can mint a new child off that successor - continuing the same single
     * chain - instead of forking the family or revoking it outright.
     */
    @Column(name = "replaced_by_hash", length = 64, columnDefinition = "CHAR(64)")
    private String replacedByHash;

    /**
     * R19-07: set when this retired row has produced its one and only grace-window re-mint. A second
     * replay of the same retired token is never given another session.
     */
    @Column(name = "grace_minted_at")
    private Instant graceMintedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * R11-03: optimistic-locking defense in depth alongside the pessimistic row lock in
     * RefreshTokenService - a concurrent writer that already bumped this row's version causes this
     * transaction's save() to fail fast (OptimisticLockException) instead of silently overwriting a
     * revocation/rotation it never actually observed, if some future code path ever re-reads this
     * row without properly refreshing it first.
     */
    @Version
    @Column(nullable = false)
    private long version;

    protected RefreshToken() {}

    public RefreshToken(String userId, String tokenHash, String familyId, Instant expiresAt) {
        this.id = UUID.randomUUID().toString();
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
    public String getFamilyId() { return familyId; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
    public Instant getRotatedAt() { return rotatedAt; }
    public void setRotatedAt(Instant rotatedAt) { this.rotatedAt = rotatedAt; }
    public String getReplacedByHash() { return replacedByHash; }
    public void setReplacedByHash(String replacedByHash) { this.replacedByHash = replacedByHash; }
    public Instant getGraceMintedAt() { return graceMintedAt; }
    public void setGraceMintedAt(Instant graceMintedAt) { this.graceMintedAt = graceMintedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
