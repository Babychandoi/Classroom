package com.classroom.modules.classroom.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** D-19: the properties of an invite code that do not depend on any database. */
class InviteCodesTest {

    @Test
    @DisplayName("a generated code is 192 bits of URL-safe Base64 (32 characters, no padding) and decodes back to 24 random bytes")
    void generatedCodeShape() {
        String code = InviteCodes.generate();
        assertEquals(32, code.length());
        assertTrue(code.matches("[A-Za-z0-9_-]{32}"), code);
        assertEquals(24, Base64.getUrlDecoder().decode(code).length, "192 bits, well over the 128-bit floor");
        assertTrue(InviteCodes.isWellFormed(code));
    }

    @Test
    @DisplayName("10 000 generated codes are all different and their bytes look uniform (every Base64 character occurs)")
    void noCollisionsAndNoBias() {
        Set<String> seen = new HashSet<>();
        Set<Character> alphabet = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String code = InviteCodes.generate();
            assertTrue(seen.add(code));
            for (char ch : code.toCharArray()) alphabet.add(ch);
        }
        assertEquals(64, alphabet.size(), "all 64 URL-safe Base64 characters appear");
    }

    @Test
    @DisplayName("only the SHA-256 is stored: 64 lower-case hex characters, deterministic, and it does not contain the code")
    void hashShape() {
        String code = InviteCodes.generate();
        String hash = InviteCodes.hash(code);
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
        assertEquals(hash, InviteCodes.hash(code));
        assertNotEquals(hash, InviteCodes.hash(code + "x"));
        assertFalse(hash.contains(code));
        // a known SHA-256 vector, so the stored form is exactly the standard digest
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", InviteCodes.hash("abc"));
    }

    @Test
    @DisplayName("the hint is the last 4 characters and is useless for recovering the code")
    void hint() {
        assertEquals("wxyz", InviteCodes.hint("abcdefghijklmnopqrstuvwxyz"));
        assertEquals("abc", InviteCodes.hint("abc"));
    }

    @Test
    @DisplayName("constant-time equality: equal strings match, any difference (first, last, length) does not, null never matches")
    void constantTimeEquals() {
        String a = InviteCodes.hash("one");
        assertTrue(InviteCodes.constantTimeEquals(a, InviteCodes.hash("one")));
        assertFalse(InviteCodes.constantTimeEquals(a, InviteCodes.hash("two")));
        assertFalse(InviteCodes.constantTimeEquals(a, a.substring(1)));
        assertFalse(InviteCodes.constantTimeEquals(a, a.substring(0, 63) + (a.charAt(63) == 'a' ? 'b' : 'a')));
        assertFalse(InviteCodes.constantTimeEquals(a, null));
        assertFalse(InviteCodes.constantTimeEquals(null, a));
    }

    @Test
    @DisplayName("format check: 16..128 URL-safe characters only - everything else is rejected before any lookup")
    void wellFormed() {
        assertTrue(InviteCodes.isWellFormed("demo-invite-lop-rieng-tu-2026"), "the fixed demo code passes the same validation");
        assertTrue(InviteCodes.isWellFormed("a".repeat(16)));
        assertTrue(InviteCodes.isWellFormed("a".repeat(128)));
        assertFalse(InviteCodes.isWellFormed("a".repeat(15)));
        assertFalse(InviteCodes.isWellFormed("a".repeat(129)));
        assertFalse(InviteCodes.isWellFormed(null));
        assertFalse(InviteCodes.isWellFormed(""));
        assertFalse(InviteCodes.isWellFormed("has space in it 123456"));
        assertFalse(InviteCodes.isWellFormed("slash/slash/slash/slash"));
        assertFalse(InviteCodes.isWellFormed("unicode-mã-mời-xxxxxxxxxx"));
        assertFalse(InviteCodes.isWellFormed("'; DROP TABLE class_invites;--"));
    }
}
