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

    // Generic placeholder prefixes used across .env.example (and any future sample file) to mark
    // a value the user is expected to replace. A prefix match catches every such placeholder
    // without having to keep an exact copy of each one in KNOWN_SAMPLE_VALUES in sync.
    private static final java.util.List<String> PLACEHOLDER_PREFIXES = java.util.List.of(
            "replace-with", "change-me", "changeme", "your-"
    );

    private static final Set<String> EXEMPT_PROFILES = Set.of("test", "integration");

    private DevSecretGuard() {
    }

    /**
     * Actionable, bilingual (English/Vietnamese) remediation text appended to every rejection so an
     * operator sees exactly what to edit and how to generate a value, without having to go hunt for
     * the README. Kept generic to the setting name since the same guard call rejects JWT_SECRET,
     * MOCK_PAYMENT_WEBHOOK_SECRET, or any other secret it is wired up for.
     */
    private static String howToFix(String settingName) {
        return "Edit " + settingName + " in infra/.env with a unique value (>= 32 chars). " +
                "PowerShell: $b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); " +
                "($b | ForEach-Object { $_.ToString('x2') }) -join ''  |  bash: openssl rand -hex 32. " +
                "See README.md muc 2, Buoc 1. " +
                "(Sua bien " + settingName + " trong infra/.env thanh mot gia tri duy nhat, toi thieu 32 ky tu, " +
                "khac voi moi secret khac. Xem huong dan sinh gia tri o README.md, muc 2, Buoc 1.)";
    }

    public static void rejectKnownSampleValue(String settingName, String value, Environment environment) {
        rejectKnownSampleValue(settingName, value, environment, null);
    }

    /**
     * Same as {@link #rejectKnownSampleValue(String, String, Environment)}, plus a fail-closed
     * check that this secret is not identical to another secret it must be independently unique
     * from (e.g. JWT_SECRET must never equal MOCK_PAYMENT_WEBHOOK_SECRET) - otherwise compromising
     * one secret would forge the other.
     */
    public static void rejectKnownSampleValue(String settingName, String value, Environment environment,
                                               String otherSettingValue) {
        if (value == null) return;
        boolean exempt = false;
        for (String activeProfile : environment.getActiveProfiles()) {
            if (EXEMPT_PROFILES.contains(activeProfile)) {
                exempt = true;
                break;
            }
        }

        String trimmed = value.trim();
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        if (!exempt) {
            if (KNOWN_SAMPLE_VALUES.contains(trimmed)) {
                throw new IllegalStateException(
                        settingName + " is set to a publicly known sample/development value from this repository's " +
                        "own example configuration. Refusing to start outside the test/integration profiles with a " +
                        "secret anyone can read in source control - generate and set a unique value instead. " +
                        howToFix(settingName));
            }
            for (String prefix : PLACEHOLDER_PREFIXES) {
                if (lower.startsWith(prefix)) {
                    throw new IllegalStateException(
                            settingName + " is still set to an unfilled placeholder value from .env.example " +
                            "(starts with \"" + prefix + "\"). Refusing to start outside the test/integration " +
                            "profiles - generate and set a unique value instead. " + howToFix(settingName));
                }
            }
            if (otherSettingValue != null && trimmed.equals(otherSettingValue.trim())) {
                throw new IllegalStateException(
                        settingName + " must not be equal to another secret it is required to be independently " +
                        "unique from. Refusing to start outside the test/integration profiles - generate a " +
                        "separate unique value for each secret. " + howToFix(settingName));
            }
        }
    }
}
