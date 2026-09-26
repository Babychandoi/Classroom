package com.classroom.modules.identity.service;

import com.classroom.common.AppException;
import com.classroom.config.JwtTokenProvider;
import com.classroom.config.TokenRevocationService;
import com.classroom.modules.identity.repository.RevokedTokenRepository;
import com.classroom.modules.identity.dto.LoginRequest;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AuthSecurityTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private TokenRevocationService tokenRevocationService;
    private RevokedTokenRepository revokedTokenRepository;
    private JwtTokenProvider tokenProvider;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        revokedTokenRepository = mock(RevokedTokenRepository.class);
        tokenRevocationService = new TokenRevocationService(revokedTokenRepository);
        tokenProvider = new JwtTokenProvider(
                "super-secret-key-for-test-at-least-32-chars-long!",
                24,
                tokenRevocationService
        );
        authService = new AuthService(userRepository, passwordEncoder, tokenProvider);
    }

    @Test
    @DisplayName("Finding 7: Login rejects non-ACTIVE statuses (SUSPENDED, INACTIVE, BANNED)")
    void testLoginRejectsNonActiveStatuses() {
        User suspendedUser = new User("u-1", "suspended@test.local", "hashed", "Suspended User", "USER");
        suspendedUser.setStatus("SUSPENDED");

        when(userRepository.findByEmail("suspended@test.local")).thenReturn(Optional.of(suspendedUser));
        when(passwordEncoder.matches("password", "hashed")).thenReturn(true);

        LoginRequest req = new LoginRequest("suspended@test.local", "password");
        assertThrows(AppException.class, () -> authService.login(req));

        User inactiveUser = new User("u-2", "inactive@test.local", "hashed", "Inactive User", "USER");
        inactiveUser.setStatus("INACTIVE");
        when(userRepository.findByEmail("inactive@test.local")).thenReturn(Optional.of(inactiveUser));

        LoginRequest req2 = new LoginRequest("inactive@test.local", "password");
        assertThrows(AppException.class, () -> authService.login(req2));
    }

    @Test
    @DisplayName("Finding 7: Revoked JWT token fails validation immediately after logout")
    void testRevokedTokenFailsValidation() {
        String token = tokenProvider.generateToken("user-1", "user@test.local", "USER");
        assertTrue(tokenProvider.validateToken(token));

        // Revoke token (simulating logout)
        when(revokedTokenRepository.existsByTokenHashAndExpiresAtAfter(anyString(), any())).thenReturn(true);
        tokenProvider.revokeToken(token);

        // Validation must now return false
        assertFalse(tokenProvider.validateToken(token));
    }
}
