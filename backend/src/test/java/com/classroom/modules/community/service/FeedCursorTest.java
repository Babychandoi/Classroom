package com.classroom.modules.community.service;

import com.classroom.common.AppException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R5-01: the opaque keyset cursor. {@code start()}'s sentinel Instant is bound to a MySQL
 * TIMESTAMP's representable range (year 1000–9999) rather than {@link Instant#MAX} — a live
 * verification against the real MySQL container caught "Incorrect TIMESTAMP value" from the
 * driver when the first-page query bound {@code Instant.MAX} as the upper cursor bound, which H2
 * (used by the rest of the unit test suite) does not reject.
 */
class FeedCursorTest {

    @Test
    @DisplayName("R5-01: start() cursor's sentinel Instant is within SQL TIMESTAMP's representable range")
    void startCursorSentinelWithinSqlTimestampRange() {
        FeedCursor start = FeedCursor.start();

        // MySQL TIMESTAMP / DATETIME support up to year 9999; Instant.MAX (year ~1e9) overflows it.
        Instant maxSqlTimestamp = Instant.parse("9999-12-31T23:59:59Z");
        assertTrue(start.createdAt.isBefore(maxSqlTimestamp) || start.createdAt.equals(maxSqlTimestamp),
                "start() cursor's createdAt must not exceed what a SQL TIMESTAMP column can store");
        assertTrue(start.pinned);
    }

    @Test
    @DisplayName("A cursor encodes and decodes back to the same (pinned, createdAt, id)")
    void encodeDecodeRoundTrips() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        FeedCursor original = FeedCursor.of(true, now, "post-123");

        FeedCursor decoded = FeedCursor.decode(original.encode());

        assertEquals(original.pinned, decoded.pinned);
        assertEquals(original.createdAt, decoded.createdAt);
        assertEquals(original.id, decoded.id);
    }

    @Test
    @DisplayName("decode(null) and decode(blank) both return the start-of-feed cursor")
    void decodeNullOrBlankReturnsStart() {
        FeedCursor fromNull = FeedCursor.decode(null);
        FeedCursor fromBlank = FeedCursor.decode("   ");
        FeedCursor start = FeedCursor.start();

        assertEquals(start.pinned, fromNull.pinned);
        assertEquals(start.createdAt, fromNull.createdAt);
        assertEquals(start.id, fromNull.id);
        assertEquals(start.pinned, fromBlank.pinned);
    }

    @Test
    @DisplayName("decode rejects a malformed cursor with a 400 contract error, not an unhandled exception")
    void decodeRejectsMalformedCursor() {
        AppException ex = assertThrows(AppException.class, () -> FeedCursor.decode("not-valid-base64!!"));
        assertEquals(com.classroom.common.ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    @DisplayName("decode rejects a cursor with the wrong number of parts")
    void decodeRejectsWrongPartCount() {
        String badCursor = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("true|123".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(AppException.class, () -> FeedCursor.decode(badCursor));
    }

    /** Encodes an arbitrary raw "pinned|epochMillis|id" cursor the way an attacker/client might. */
    private static String encodeRaw(String raw) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("R6-02: decode rejects a createdAt whose year exceeds what SQL TIMESTAMP can store")
    void decodeRejectsCreatedAtBeyondSqlTimestampRange() {
        // One second past the SENTINEL_START upper bound (year 9999) — into year 10000, which
        // Instant.parse cannot even represent as a 4-digit-year ISO string, so it's built
        // arithmetically instead of parsed.
        long beyondSentinel = Instant.parse("9999-12-31T23:59:59Z").toEpochMilli() + 1000L;
        String cursor = encodeRaw("true|" + beyondSentinel + "|post-1");

        AppException ex = assertThrows(AppException.class, () -> FeedCursor.decode(cursor));
        assertEquals(com.classroom.common.ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    @DisplayName("R6-02: decode rejects a createdAt at or before the Unix epoch")
    void decodeRejectsCreatedAtAtOrBeforeEpoch() {
        String atEpoch = encodeRaw("true|0|post-1");
        String beforeEpoch = encodeRaw("true|-1000|post-1");

        assertThrows(AppException.class, () -> FeedCursor.decode(atEpoch));
        assertThrows(AppException.class, () -> FeedCursor.decode(beforeEpoch));
    }

    @Test
    @DisplayName("R6-02: decode accepts a createdAt exactly at the SQL TIMESTAMP sentinel upper bound")
    void decodeAcceptsCreatedAtAtSentinelBound() {
        long atSentinel = Instant.parse("9999-12-31T23:59:59Z").toEpochMilli();
        String cursor = encodeRaw("true|" + atSentinel + "|post-1");

        FeedCursor decoded = FeedCursor.decode(cursor);
        assertEquals(Instant.parse("9999-12-31T23:59:59Z"), decoded.createdAt);
    }

    @Test
    @DisplayName("R6-02: decode rejects a pinned field that is not exactly \"true\" or \"false\"")
    void decodeRejectsNonStrictBooleanPinnedField() {
        // Boolean.parseBoolean would silently coerce this to false instead of rejecting it.
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String cursor = encodeRaw("garbage|" + now.toEpochMilli() + "|post-1");

        AppException ex = assertThrows(AppException.class, () -> FeedCursor.decode(cursor));
        assertEquals(com.classroom.common.ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    @DisplayName("R6-02: decode accepts the exact literals \"true\" and \"false\" for the pinned field")
    void decodeAcceptsStrictBooleanLiterals() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String truePinned = encodeRaw("true|" + now.toEpochMilli() + "|post-1");
        String falsePinned = encodeRaw("false|" + now.toEpochMilli() + "|post-1");

        assertTrue(FeedCursor.decode(truePinned).pinned);
        assertFalse(FeedCursor.decode(falsePinned).pinned);
    }
}
