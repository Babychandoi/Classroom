package com.classroom.modules.identity.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.config.JwtTokenProvider;
import com.classroom.config.TokenRevocationService;
import com.classroom.modules.identity.dto.RegisterRequest;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.RefreshTokenRepository;
import com.classroom.modules.identity.repository.RevokedTokenRepository;
import com.classroom.modules.identity.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * R3-09: @Size on RegisterRequest.password counts characters, not bytes. bcrypt only considers
 * the first 72 UTF-8 bytes, so a password using multi-byte characters could pass @Size validation
 * yet be silently truncated. AuthService.register defensively rejects any password over 72 UTF-8
 * bytes (the simpler service-level check, chosen because the codebase has no existing
 * @Constraint/ConstraintValidator convention to extend).
 */
@ExtendWith(MockitoExtension.class)
public class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        RevokedTokenRepository revokedTokenRepository = mock(RevokedTokenRepository.class);
        TokenRevocationService tokenRevocationService = new TokenRevocationService(revokedTokenRepository);
        JwtTokenProvider tokenProvider = new JwtTokenProvider(
                "super-secret-key-for-test-at-least-32-chars-long!",
                24,
                tokenRevocationService,
                new MockEnvironment()
        );
        RefreshTokenRepository refreshTokenRepository = mock(RefreshTokenRepository.class);
        lenient().when(refreshTokenRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));
        RefreshTokenService refreshTokenService = new RefreshTokenService(refreshTokenRepository, userRepository,
                mock(EntityManager.class), mock(org.springframework.transaction.PlatformTransactionManager.class), 30, 60);
        authService = new AuthService(userRepository, passwordEncoder, tokenProvider, refreshTokenService);
    }

    private RegisterRequest registerRequest(String password) {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("new-user@test.local");
        req.setPassword(password);
        req.setFullName("New User");
        return req;
    }

    @Test
    @DisplayName("R3-09: register rejects a password over 72 UTF-8 bytes even though it is under 72 characters")
    void registerRejectsPasswordOverUtf8ByteLimit() {
        // "€" is 3 bytes in UTF-8; 30 of them is 30 chars but 90 bytes, over the bcrypt 72-byte limit.
        String password = "€".repeat(30);
        assertTrue(password.length() < 72, "sanity: char length must be under 72 to prove this isn't just @Size");

        when(userRepository.existsByEmail("new-user@test.local")).thenReturn(false);

        AppException ex = assertThrows(AppException.class, () -> authService.register(registerRequest(password)));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    @DisplayName("R3-09: register accepts a password at exactly 72 ASCII bytes")
    void registerAcceptsPasswordAtExactlySeventyTwoAsciiBytes() {
        String password = "a".repeat(72);
        assertEquals(72, password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);

        when(userRepository.existsByEmail("new-user@test.local")).thenReturn(false);
        when(passwordEncoder.encode(password)).thenReturn("hashed");
        when(userRepository.save(org.mockito.ArgumentMatchers.any(User.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        assertDoesNotThrow(() -> authService.register(registerRequest(password)));
        verify(passwordEncoder).encode(password);
    }
}
