package com.classroom.config;

import com.classroom.modules.identity.repository.RevokedTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists only a one-way token digest so revocation survives restarts and instances. */
@Service
public class TokenRevocationService {
    private final RevokedTokenRepository repository;

    public TokenRevocationService(RevokedTokenRepository repository) { this.repository = repository; }

    @Transactional
    public void revoke(String token, Instant expiresAt) {
        if (token == null || token.isBlank()) return;
        Instant expiry = expiresAt == null ? Instant.now().plusSeconds(86400) : expiresAt;
        if (expiry.isAfter(Instant.now())) repository.save(new RevokedToken(hash(token.trim()), expiry));
    }

    @Transactional(readOnly = true)
    public boolean isRevoked(String token) {
        return token != null && !token.isBlank()
                && repository.existsByTokenHashAndExpiresAtAfter(hash(token.trim()), Instant.now());
    }

    @Transactional
    public void clear() { repository.deleteAllInBatch(); }

    private String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
