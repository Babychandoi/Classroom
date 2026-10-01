package com.classroom.modules.community.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * R5-01: opaque cursor for keyset feed pagination, encoding the (pinned, createdAt, id) position
 * of the last post returned so far. Rows are ordered (pinned DESC, createdAt DESC, id DESC); the
 * cursor identifies the boundary "just after" the last returned row in that ordering, so the next
 * batch's WHERE clause asks the repository for the strictly smaller (later-sorting) rows.
 *
 * <p>Encoded as {@code base64("pinned|epochMillis|id")}. The id is opaque (UUID, no '|'), so a
 * single split on '|' with a limit is unambiguous.</p>
 */
final class FeedCursor {
    private static final String SEPARATOR = "|";

    final boolean pinned;
    final Instant createdAt;
    final String id;

    private FeedCursor(boolean pinned, Instant createdAt, String id) {
        this.pinned = pinned;
        this.createdAt = createdAt;
        this.id = id;
    }

    /**
     * The virtual "start of feed" cursor: sorts before every real row regardless of pin state.
     * Uses the maximum representable value of SQL TIMESTAMP (year 9999), not {@link Instant#MAX}
     * — the underlying {@code posts.created_at} column is a SQL TIMESTAMP, and binding an Instant
     * far beyond that range (as {@code Instant.MAX} is) makes the driver reject the query outright.
     */
    private static final Instant SENTINEL_START = Instant.parse("9999-12-31T23:59:59Z");

    static FeedCursor start() {
        return new FeedCursor(true, SENTINEL_START, "￿");
    }

    static FeedCursor of(boolean pinned, Instant createdAt, String id) {
        return new FeedCursor(pinned, createdAt, id);
    }

    String encode() {
        String raw = pinned + SEPARATOR + createdAt.toEpochMilli() + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * R6-02: earliest createdAt a decoded cursor may carry. A SQL TIMESTAMP's representable range
     * starts at 1970-01-01 00:00:01 UTC; anything at or before the epoch itself is already outside
     * it and would make the driver reject the keyset query.
     */
    private static final Instant MIN_CREATED_AT = Instant.parse("1970-01-01T00:00:01Z");

    static FeedCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return start();
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 3);
            if (parts.length != 3) {
                throw new IllegalArgumentException("malformed cursor");
            }
            // R6-02: Boolean.parseBoolean silently maps any non-"true" string (including garbage)
            // to false, so a malformed first field would otherwise decode successfully instead of
            // being rejected. Require exactly "true" or "false" as encode() produces.
            if (!"true".equals(parts[0]) && !"false".equals(parts[0])) {
                throw new IllegalArgumentException("malformed cursor pinned flag");
            }
            boolean pinned = Boolean.parseBoolean(parts[0]);
            long epochMillis = Long.parseLong(parts[1]);
            Instant createdAt = Instant.ofEpochMilli(epochMillis);
            // R6-02: decode() previously accepted any epoch millis, including values whose year
            // exceeds what a SQL TIMESTAMP column can store (e.g. year > 9999) — binding such a
            // value in the keyset query's WHERE clause raised an unhandled 500 from the MySQL
            // driver instead of a clean 400. Reject anything outside the representable range,
            // matching start()'s own sentinel bound.
            if (createdAt.isBefore(MIN_CREATED_AT) || createdAt.isAfter(SENTINEL_START)) {
                throw new IllegalArgumentException("malformed cursor createdAt out of range");
            }
            String id = parts[2];
            if (id.isBlank()) {
                throw new IllegalArgumentException("malformed cursor id");
            }
            return new FeedCursor(pinned, createdAt, id);
        } catch (RuntimeException ex) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Con trỏ phân trang không hợp lệ");
        }
    }
}
