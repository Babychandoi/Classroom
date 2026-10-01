package com.classroom.modules.identity.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R3-09: BCrypt (used to hash passwords on registration) silently truncates any input beyond 72
 * bytes, so two different passwords that share the first 72 bytes would hash identically and the
 * longer one would be accepted as if it were correct. RegisterRequest must reject any password
 * whose UTF-8 byte length exceeds that limit instead of silently weakening the hash.
 */
class RegisterRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private RegisterRequest requestWithPassword(String password) {
        RegisterRequest req = new RegisterRequest();
        req.setEmail("user@test.local");
        req.setPassword(password);
        req.setFullName("Nguyen Van A");
        return req;
    }

    @Test
    @DisplayName("password within 72 bytes passes validation")
    void acceptsPasswordWithinLimit() {
        RegisterRequest req = requestWithPassword("a".repeat(72));
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
        assertTrue(violations.isEmpty(), "expected no violations but got: " + violations);
    }

    @Test
    @DisplayName("password over 72 UTF-8 bytes is rejected")
    void rejectsPasswordOverLimit() {
        RegisterRequest req = requestWithPassword("a".repeat(73));
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("72 byte")));
    }

    @Test
    @DisplayName("password over 72 bytes via multi-byte UTF-8 characters is rejected even though char count is lower")
    void rejectsMultiByteOverLimitByBytesNotChars() {
        // Each 'à' is 2 bytes in UTF-8; 40 chars = 80 bytes > 72, but well under any char-count limit.
        String password = "à".repeat(40);
        assertEquals(80, password.getBytes(StandardCharsets.UTF_8).length);

        RegisterRequest req = requestWithPassword(password);
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("72 byte")));
    }

    @Test
    @DisplayName("exactly 72 bytes via multi-byte characters is accepted")
    void acceptsMultiByteExactlyAtLimit() {
        String password = "à".repeat(36); // 36 * 2 = 72 bytes exactly
        assertEquals(72, password.getBytes(StandardCharsets.UTF_8).length);

        RegisterRequest req = requestWithPassword(password);
        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(req);
        assertTrue(violations.isEmpty(), "expected no violations but got: " + violations);
    }
}
