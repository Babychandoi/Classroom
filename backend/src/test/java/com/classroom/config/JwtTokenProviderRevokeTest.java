package com.classroom.config;

import com.classroom.modules.identity.repository.RevokedTokenRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * R19-08: {@code /auth/logout} is public, and {@code revokeToken} used to blacklist even a string that failed
 * verification - 20 garbage bearer tokens = 20 database rows written by an unauthenticated caller.
 */
class JwtTokenProviderRevokeTest {

    private static final String SECRET = "super-secret-key-for-test-at-least-32-chars-long!";

    private RevokedTokenRepository repository;
    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        repository = mock(RevokedTokenRepository.class);
        provider = new JwtTokenProvider(SECRET, 1, new TokenRevocationService(repository), new MockEnvironment());
    }

    @Test
    @DisplayName("R19-08: a valid access token is revoked exactly once, until its own expiry")
    void validTokenIsRevoked() {
        String token = provider.generateToken("user-1", "user@test.local", "USER");

        provider.revokeToken(token);

        verify(repository, times(1)).save(any(RevokedToken.class));
    }

    @Test
    @DisplayName("R19-08: tokens minted for the same user in the same second are distinct, so revoking one never blacklists a later login")
    void sameSecondTokensAreDistinct() {
        String first = provider.generateToken("user-1", "user@test.local", "USER");
        String second = provider.generateToken("user-1", "user@test.local", "USER");

        assertNotEquals(first, second);
        // The blacklist is keyed by a digest of the token: a different token has a different key.
        when(repository.existsByTokenHashAndExpiresAtAfter(anyString(), any())).thenReturn(false);
        assertTrue(provider.validateToken(second));
    }

    @Test
    @DisplayName("R19-08: unparseable / garbage tokens write nothing")
    void garbageTokensWriteNothing() {
        for (int i = 0; i < 20; i++) {
            provider.revokeToken("garbage-token-" + i);
        }
        provider.revokeToken("a.b.c");
        provider.revokeToken("eyJhbGciOiJIUzI1NiJ9.e30.");
        provider.revokeToken("   ");
        provider.revokeToken(null);

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("R19-08: a token signed with a different key (bad signature) writes nothing")
    void forgedSignatureWritesNothing() {
        SecretKey otherKey = Keys.hmacShaKeyFor("a-completely-different-secret-key-32-chars-min!".getBytes(StandardCharsets.UTF_8));
        String forged = Jwts.builder()
                .subject("user-1")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(otherKey)
                .compact();

        provider.revokeToken(forged);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("R19-08: a correctly signed but already expired token writes nothing (it is unusable anyway)")
    void expiredTokenWritesNothing() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String expired = Jwts.builder()
                .subject("user-1")
                .issuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .expiration(Date.from(Instant.now().minusSeconds(3600)))
                .signWith(key)
                .compact();

        provider.revokeToken(expired);

        verify(repository, never()).save(any());
        assertFalse(provider.validateToken(expired));
    }

    @Test
    @DisplayName("R19-08: a token with a tampered payload (valid-looking, broken signature) writes nothing")
    void tamperedPayloadWritesNothing() {
        String token = provider.generateToken("user-1", "user@test.local", "USER");
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA." + parts[2];

        provider.revokeToken(tampered);

        verify(repository, never()).save(any());
        verify(repository, never()).existsByTokenHashAndExpiresAtAfter(anyString(), any());
    }
}
