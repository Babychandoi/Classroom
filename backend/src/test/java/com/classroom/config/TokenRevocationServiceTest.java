package com.classroom.config;

import com.classroom.modules.identity.repository.RevokedTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/** R3-06: revoked-token purge must actually run the repository delete. */
@ExtendWith(MockitoExtension.class)
public class TokenRevocationServiceTest {

    @Mock
    private RevokedTokenRepository repository;

    private TokenRevocationService tokenRevocationService;

    @BeforeEach
    void setUp() {
        tokenRevocationService = new TokenRevocationService(repository);
    }

    @Test
    @DisplayName("R3-06: purgeExpiredRevocations deletes revoked tokens expired before now")
    void purgeExpiredRevocationsDeletesExpiredTokens() {
        tokenRevocationService.purgeExpiredRevocations();

        verify(repository).deleteByExpiresAtBefore(any(Instant.class));
    }
}
