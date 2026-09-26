package com.classroom.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "revoked_tokens")
public class RevokedToken {
    @Id
    @Column(length = 64, columnDefinition = "CHAR(64)")
    private String tokenHash;
    @Column(nullable = false)
    private Instant expiresAt;

    protected RevokedToken() {}
    public RevokedToken(String tokenHash, Instant expiresAt) { this.tokenHash = tokenHash; this.expiresAt = expiresAt; }
    public String getTokenHash() { return tokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
}
