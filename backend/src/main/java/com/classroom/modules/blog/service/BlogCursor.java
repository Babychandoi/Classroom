package com.classroom.modules.blog.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * D-27: opaque keyset cursor of the blog list - the (sort instant, id) of the last post returned, encoded as
 * {@code base64url("epochSecond:nano|id")}. Unlike the feed cursor (R5-01) the instant keeps its sub-second part: the columns are
 * DATETIME(6), so truncating to milliseconds could skip or repeat posts written within the same millisecond.
 */
record BlogCursor(Instant at, String id) {

    /** Sorts before every real row: the largest DATETIME value (year 9999) and a high sentinel id. */
    private static final Instant SENTINEL_START = Instant.parse("9999-12-31T23:59:59Z");
    private static final Instant MIN_AT = Instant.parse("1970-01-01T00:00:01Z");

    static BlogCursor start() {
        return new BlogCursor(SENTINEL_START, "￿");
    }

    String encode() {
        String raw = at.getEpochSecond() + ":" + at.getNano() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static BlogCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return start();
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 2);
            if (parts.length != 2) throw new IllegalArgumentException("malformed cursor");
            String[] time = parts[0].split(":", 2);
            if (time.length != 2) throw new IllegalArgumentException("malformed cursor time");
            long seconds = Long.parseLong(time[0]);
            long nanos = Long.parseLong(time[1]);
            if (nanos < 0 || nanos > 999_999_999L) throw new IllegalArgumentException("malformed cursor nanos");
            Instant at = Instant.ofEpochSecond(seconds, nanos);
            if (at.isBefore(MIN_AT) || at.isAfter(SENTINEL_START)) throw new IllegalArgumentException("cursor out of range");
            String id = parts[1];
            if (id.isBlank() || id.length() > 64) throw new IllegalArgumentException("malformed cursor id");
            return new BlogCursor(at, id);
        } catch (RuntimeException ex) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Con trỏ phân trang không hợp lệ");
        }
    }
}
