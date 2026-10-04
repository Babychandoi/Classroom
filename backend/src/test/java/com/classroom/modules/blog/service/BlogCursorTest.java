package com.classroom.modules.blog.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class BlogCursorTest {

    private static String b64(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("D-27: a cursor round-trips with microsecond precision (DATETIME(6)), blank means the start")
    void roundTrip() {
        Instant at = Instant.parse("2026-10-04T08:15:30.123456Z");
        BlogCursor decoded = BlogCursor.decode(new BlogCursor(at, "abc-123").encode());
        assertEquals(at, decoded.at());
        assertEquals("abc-123", decoded.id());
        assertEquals(BlogCursor.start(), BlogCursor.decode(null));
        assertEquals(BlogCursor.start(), BlogCursor.decode("  "));
    }

    @Test
    @DisplayName("D-27: forged / out-of-range cursors are a 400, never a 500")
    void rejectsGarbage() {
        for (String bad : new String[]{"%%%", b64("no-separator"), b64("x:y|id"), b64("10:5|"), b64("-5:0|id"),
                b64("999999999999:0|id"), b64("10:2000000000|id"), b64("10|id")}) {
            AppException e = assertThrows(AppException.class, () -> BlogCursor.decode(bad), bad);
            assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        }
    }
}
