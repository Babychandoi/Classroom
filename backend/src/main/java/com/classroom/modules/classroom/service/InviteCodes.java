package com.classroom.modules.classroom.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * D-19: everything about an invite code that has to be identical wherever codes are made, stored or checked.
 *
 * <ul>
 *   <li><b>Generation</b>: 24 bytes (192 bits) from a {@link SecureRandom}, Base64-URL without padding - 32 URL-safe characters, well
 *   over the 128-bit floor, so a code cannot be guessed, and is safe to put in a path or a query string.</li>
 *   <li><b>Storage</b>: only the SHA-256 (64 hex characters) is persisted, plus the last four characters as a display hint. A database
 *   dump, backup or log of the table cannot be turned into a working link.</li>
 *   <li><b>Comparison</b>: the presented code is hashed and looked up by the unique index; the stored hash and the presented hash are then
 *   compared with {@link MessageDigest#isEqual} (constant time), so nothing about a near-miss leaks through timing.</li>
 * </ul>
 */
public final class InviteCodes {

    static final int CODE_BYTES = 24;
    /** Accepts generated codes and the fixed demo-only code; anything else is rejected before it reaches the database. */
    private static final Pattern WELL_FORMED = Pattern.compile("^[A-Za-z0-9_-]{16,128}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private InviteCodes() {
    }

    public static String generate() {
        byte[] bytes = new byte[CODE_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Whether {@code code} could be an invite code at all (length and alphabet). A malformed code is simply "not found". */
    public static boolean isWellFormed(String code) {
        return code != null && WELL_FORMED.matcher(code).matches();
    }

    /** Lower-case hex SHA-256 of the code, exactly as stored in {@code class_invites.code_hash}. */
    public static String hash(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }

    /** The last four characters: enough for the owner to tell two links apart, useless to an attacker. */
    public static String hint(String code) {
        return code.length() <= 4 ? code : code.substring(code.length() - 4);
    }

    /** Constant-time equality of two hashes (or any two strings). */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
