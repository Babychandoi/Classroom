package com.classroom.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtTokenProvider {
    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    private final SecretKey secretKey;
    private final long expirationHours;
    private final TokenRevocationService tokenRevocationService;

    public JwtTokenProvider(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-hours:1}") long expirationHours,
            TokenRevocationService tokenRevocationService,
            Environment environment) {
        if (secret == null || secret.isBlank() || secret.length() < 32) {
            throw new IllegalStateException(
                    "JWT secret (app.jwt.secret / JWT_SECRET) must be set and at least 32 characters. " +
                    "Refusing to start with an absent or weak secret.");
        }
        DevSecretGuard.rejectKnownSampleValue("JWT_SECRET", secret, environment,
                environment.getProperty("app.payment.mock.webhook-secret"));
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationHours = expirationHours;
        this.tokenRevocationService = tokenRevocationService;
    }

    public String generateToken(String userId, String email, String role) {
        Instant now = Instant.now();
        Instant expiry = now.plus(expirationHours, ChronoUnit.HOURS);

        return Jwts.builder()
                // R19-08 (found while verifying it live): without a unique id, two tokens minted for the same user in the
                // same second are byte-identical, so logging out (which blacklists the token) and logging straight back in
                // handed out a token that was ALREADY on the blacklist - the user was logged out again with a 401.
                .id(UUID.randomUUID().toString())
                .subject(userId)
                .claim("email", email)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(secretKey)
                .compact();
    }

    public boolean validateToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        if (tokenRevocationService.isRevoked(token)) {
            log.warn("Token has been explicitly revoked");
            return false;
        }
        try {
            Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Blacklists an access token until it would have expired anyway.
     *
     * <p>R19-08: only a token whose signature verifies is recorded. {@code /auth/logout} is public (it must still
     * clear the cookie after the access token expired), so previously any caller could make the server insert a
     * row per garbage string. A token that fails verification (forged, malformed, wrong key) or is already expired
     * is unusable as it is: there is nothing to revoke and nothing is written.
     */
    public void revokeToken(String token) {
        if (token == null || token.isBlank()) return;
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Ignoring revoke request for a token that does not verify: {}", e.getClass().getSimpleName());
            return;
        }
        Date exp = claims.getExpiration();
        Instant expiry = (exp != null) ? exp.toInstant() : Instant.now().plus(expirationHours, ChronoUnit.HOURS);
        tokenRevocationService.revoke(token, expiry);
    }

    public String getUserIdFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }

    public String getEmailFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.get("email", String.class);
    }

    public String getRoleFromToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.get("role", String.class);
    }
}
