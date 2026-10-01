package com.classroom.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R2-02: DevSecretGuard must reject not only the exact sample values this repo has ever shipped,
 * but also the .env.example placeholder ("replace-with-a-unique-secret-...") the README tells
 * users to copy, any similarly-worded unfilled placeholder, and the case where JWT_SECRET and
 * MOCK_PAYMENT_WEBHOOK_SECRET were set to the same value (compromising one would forge the other).
 */
class DevSecretGuardTest {

    // Copied from .env.example rather than reading the file directly: the backend Docker test
    // build context does not include the repo root, so .env.example is not reachable from here.
    // Keep this in sync with .env.example's JWT_SECRET / MOCK_PAYMENT_WEBHOOK_SECRET placeholders.
    private static final String ENV_EXAMPLE_PLACEHOLDER = "replace-with-a-unique-secret-at-least-32-characters-long";

    private static MockEnvironment prodLikeEnvironment() {
        // No "test"/"integration" profile active, so the guard is not exempt.
        return new MockEnvironment();
    }

    @Test
    void rejectsExactEnvExamplePlaceholder() {
        assertThrows(IllegalStateException.class, () ->
                DevSecretGuard.rejectKnownSampleValue("JWT_SECRET", ENV_EXAMPLE_PLACEHOLDER, prodLikeEnvironment()));
    }

    // R10-02: the rejection message must be actionable - name the exact variable, point at
    // infra/.env (not just .env, which compose never reads for -f infra/compose.yaml), and give a
    // ready-to-run generator, instead of leaving the operator to go find README.md themselves.
    @Test
    void rejectionMessageNamesTheVariableAndInfraEnvAndAGenerator() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                DevSecretGuard.rejectKnownSampleValue("JWT_SECRET", ENV_EXAMPLE_PLACEHOLDER, prodLikeEnvironment()));
        assertTrue(ex.getMessage().contains("JWT_SECRET"));
        assertTrue(ex.getMessage().contains("infra/.env"));
        assertTrue(ex.getMessage().toLowerCase(java.util.Locale.ROOT).contains("openssl rand -hex 32"));
    }

    @Test
    void rejectsEnvExamplePlaceholderCaseInsensitively() {
        assertThrows(IllegalStateException.class, () ->
                DevSecretGuard.rejectKnownSampleValue("MOCK_PAYMENT_WEBHOOK_SECRET",
                        ENV_EXAMPLE_PLACEHOLDER.toUpperCase(java.util.Locale.ROOT), prodLikeEnvironment()));
    }

    @Test
    void rejectsChangeMePlaceholder() {
        assertThrows(IllegalStateException.class, () ->
                DevSecretGuard.rejectKnownSampleValue("NEO4J_PASSWORD",
                        "change-me-neo4j-password", prodLikeEnvironment()));
    }

    @Test
    void rejectsChangemeNoHyphenPlaceholder() {
        assertThrows(IllegalStateException.class, () ->
                DevSecretGuard.rejectKnownSampleValue("SOME_SECRET",
                        "changeme-please-pick-something-unique-32chars", prodLikeEnvironment()));
    }

    @Test
    void rejectsYourDashPlaceholder() {
        assertThrows(IllegalStateException.class, () ->
                DevSecretGuard.rejectKnownSampleValue("SOME_SECRET",
                        "your-super-secret-key-goes-here-32-chars-min", prodLikeEnvironment()));
    }

    @Test
    void rejectsKnownSampleValuesFromPreviousReview() {
        assertThrows(IllegalStateException.class, () ->
                DevSecretGuard.rejectKnownSampleValue("JWT_SECRET",
                        "super-secret-key-for-dev-environment-at-least-32-chars-long", prodLikeEnvironment()));
        assertThrows(IllegalStateException.class, () ->
                DevSecretGuard.rejectKnownSampleValue("MOCK_PAYMENT_WEBHOOK_SECRET",
                        "dev-mock-webhook-secret-key-32-chars-minimum", prodLikeEnvironment()));
    }

    @Test
    void rejectsJwtSecretEqualToMockPaymentWebhookSecretOutsideTestProfiles() {
        String sharedValue = "a-unique-looking-secret-that-is-reused-by-mistake-32c";
        assertThrows(IllegalStateException.class, () ->
                DevSecretGuard.rejectKnownSampleValue("JWT_SECRET", sharedValue, prodLikeEnvironment(), sharedValue));
    }

    @Test
    void allowsDistinctRealSecretsOutsideTestProfiles() {
        // Two distinct, non-placeholder values of realistic length (synthetic - never a real deployment secret).
        String jwtSecret = "unit-test-only-jwt-0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a6978";
        String webhookSecret = "unit-test-only-webhook-1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d";
        assertDoesNotThrow(() ->
                DevSecretGuard.rejectKnownSampleValue("JWT_SECRET", jwtSecret, prodLikeEnvironment(), webhookSecret));
        assertDoesNotThrow(() ->
                DevSecretGuard.rejectKnownSampleValue("MOCK_PAYMENT_WEBHOOK_SECRET", webhookSecret, prodLikeEnvironment(), jwtSecret));
    }

    @Test
    void exemptsTestAndIntegrationProfilesEvenForThePlaceholderAndSharedValue() {
        MockEnvironment testEnv = new MockEnvironment();
        testEnv.setActiveProfiles("test");
        assertDoesNotThrow(() ->
                DevSecretGuard.rejectKnownSampleValue("JWT_SECRET", ENV_EXAMPLE_PLACEHOLDER, testEnv, ENV_EXAMPLE_PLACEHOLDER));

        MockEnvironment integrationEnv = new MockEnvironment();
        integrationEnv.setActiveProfiles("integration");
        assertDoesNotThrow(() ->
                DevSecretGuard.rejectKnownSampleValue("MOCK_PAYMENT_WEBHOOK_SECRET", ENV_EXAMPLE_PLACEHOLDER, integrationEnv, ENV_EXAMPLE_PLACEHOLDER));
    }
}
