package com.classroom.config;

import org.springframework.core.env.Environment;

import java.util.Set;

/**
 * Refuses to start the application when a security-sensitive secret still holds one of the
 * values published in this repository's own sample files ({@code .env.example},
 * {@code application-*.properties}). Those values are public knowledge, so accepting them
 * outside the {@code test}/{@code integration} profiles would let anyone forge tokens or
 * webhook signatures against a deployment that was never actually configured.
 */
public final class DevSecretGuard {

    // Every dev/sample value that has ever been published in this repo's tracked files for
    // JWT_SECRET or MOCK_PAYMENT_WEBHOOK_SECRET. Keep this list in sync with .env.example and the
    // application-*.properties defaults so a copy-pasted sample is always caught.
    private static final Set<String> KNOWN_SAMPLE_VALUES = Set.of(
            "super-secret-key-for-dev-environment-at-least-32-chars-long",
            "dev-mock-webhook-secret-key-32-chars-minimum",
            "test-secret-key-for-unit-testing-at-least-32-chars-long",
            "test-webhook-secret-key-32-chars-long"
    );

    private static final Set<String> EXEMPT_PROFILES = Set.of("test", "integration");

    private DevSecretGuard() {
    }

    public static void rejectKnownSampleValue(String settingName, String value, Environment environment) {
        if (value == null) return;
        for (String activeProfile : environment.getActiveProfiles()) {
            if (EXEMPT_PROFILES.contains(activeProfile)) {
                return;
            }
        }
        if (KNOWN_SAMPLE_VALUES.contains(value.trim())) {
            throw new IllegalStateException(
                    settingName + " is set to a publicly known sample/development value from this repository's " +
                    "own example configuration. Refusing to start outside the test/integration profiles with a " +
                    "secret anyone can read in source control - generate and set a unique value instead.");
        }
    }
}
