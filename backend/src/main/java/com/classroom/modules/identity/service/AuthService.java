package com.classroom.modules.identity.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.config.JwtTokenProvider;
import com.classroom.modules.identity.dto.AuthResponse;
import com.classroom.modules.identity.dto.LoginRequest;
import com.classroom.modules.identity.dto.RegisterRequest;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final RefreshTokenService refreshTokenService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider tokenProvider,
                       RefreshTokenService refreshTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.refreshTokenService = refreshTokenService;
    }

    /** R8-06: login/register issue a refresh token alongside the access JWT (caller sets the cookie). */
    public record AuthResult(AuthResponse response, RefreshTokenService.IssuedToken refreshToken) {}

    /**
     * R20-02: a valid bcrypt hash (cost 10 = the strength of the application's {@code BCryptPasswordEncoder}) of a throw-away value.
     * When the e-mail is unknown, the submitted password is still checked against it, so an unknown e-mail costs the same ~100 ms of
     * hashing as a known one with a wrong password. Without this the response TIME told an attacker which e-mail addresses have an
     * account (the rate limiter is keyed by the e-mail string and deliberately treats known and unknown addresses alike).
     */
    private static final String TIMING_EQUALISER_HASH = "$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG";

    @Transactional
    public AuthResult login(LoginRequest request) {
        java.util.Optional<User> found = userRepository.findByEmail(request.getEmail().toLowerCase().trim());
        String storedHash = found.map(User::getPasswordHash).orElse(TIMING_EQUALISER_HASH);
        boolean passwordMatches = passwordEncoder.matches(request.getPassword(), storedHash);
        if (found.isEmpty() || !passwordMatches) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "Email hoặc mật khẩu không chính xác");
        }
        User user = found.get();

        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new AppException(ErrorCode.FORBIDDEN, "Tài khoản của bạn chưa kích hoạt hoặc đã bị khóa");
        }

        String token = tokenProvider.generateToken(user.getId(), user.getEmail(), user.getRole());
        RefreshTokenService.IssuedToken refreshToken = refreshTokenService.issue(user.getId());
        AuthResponse response = new AuthResponse(token, user.getId(), user.getEmail(), user.getFullName(), user.getRole(), user.getAvatarUrl());
        return new AuthResult(response, refreshToken);
    }

    /** bcrypt silently truncates/ignores input past this many UTF-8 bytes. */
    private static final int MAX_PASSWORD_BYTES = 72;

    @Transactional
    public AuthResult register(RegisterRequest request) {
        String email = request.getEmail().toLowerCase().trim();
        if (userRepository.existsByEmail(email)) {
            throw new AppException(ErrorCode.CONFLICT, "Email đã được đăng ký trong hệ thống");
        }

        // R3-09: @Size on RegisterRequest.password counts characters, not bytes; bcrypt only
        // considers the first 72 UTF-8 bytes of the input, so a password using multi-byte
        // characters (accents, symbols) could pass @Size yet be silently truncated by bcrypt.
        if (request.getPassword().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Mật khẩu không được vượt quá 72 byte (UTF-8)");
        }

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setFullName(request.getFullName().trim());
        user.setRole("USER");
        user.setStatus("ACTIVE");

        userRepository.save(user);

        String token = tokenProvider.generateToken(user.getId(), user.getEmail(), user.getRole());
        RefreshTokenService.IssuedToken refreshToken = refreshTokenService.issue(user.getId());
        AuthResponse response = new AuthResponse(token, user.getId(), user.getEmail(), user.getFullName(), user.getRole(), user.getAvatarUrl());
        return new AuthResult(response, refreshToken);
    }
}
