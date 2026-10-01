package com.classroom.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.HexFormat;

/** Shared, database-clocked budgets. Every read-modify-write locks exactly one hashed bucket. */
@Component
@ConditionalOnProperty(name = "app.security.rate-limit.store", havingValue = "mysql")
public class SharedRateLimitStore {
    private final JdbcTemplate jdbc;
    public SharedRateLimitStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private record Bucket(long hits, Instant expires, Instant blocked) {}
    private String hash(String key) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private Instant now() { return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", Timestamp.class).toInstant(); }
    private Bucket lock(String key, Instant now) {
        jdbc.update("INSERT INTO rate_limit_buckets (bucket_key,hits,expires_at) VALUES (?,0,?) ON DUPLICATE KEY UPDATE bucket_key=VALUES(bucket_key)", key, Timestamp.from(now));
        return jdbc.queryForObject("SELECT hits,expires_at,blocked_until FROM rate_limit_buckets WHERE bucket_key=? FOR UPDATE",
                (rs, i) -> new Bucket(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant()), key);
    }
    private long waitSeconds(Instant until, Instant now) { return Math.max(1, (until.toEpochMilli() - now.toEpochMilli() + 999) / 1000); }
    public record Reservation(int permits, long lifetimeMillis, long retrySeconds) {}
    /** Prepaid permits remain charged even if a node stops or discards its cache. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation reserve(String name, int limit, int requested) {
        if (limit < 1 || requested < 1) throw new IllegalArgumentException("Positive permit budget required");
        String key = hash(name);
        Bucket bucket = lock(key, now());
        Instant clock = now(); // Read after acquiring the row lock, including any lock wait.
        boolean expired = !bucket.expires().isAfter(clock);
        long used = expired ? 0 : bucket.hits();
        Instant expiry = expired ? clock.plusSeconds(60) : bucket.expires();
        int granted = (int) Math.min(requested, Math.max(0, limit - used));
        if (granted > 0) jdbc.update("UPDATE rate_limit_buckets SET hits=?,expires_at=?,blocked_until=NULL WHERE bucket_key=?",
                used + granted, Timestamp.from(expiry), key);
        return new Reservation(granted, Math.max(0, expiry.toEpochMilli() - clock.toEpochMilli()), waitSeconds(expiry, clock));
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long acquire(String name, int limit) {
        if (limit <= 0) return 0;
        // One atomic statement locks/increments the bucket and returns THIS caller's count in the
        // MySQL OK packet. Reading the row afterwards would race another caller. No separate clock,
        // insert, SELECT FOR UPDATE and UPDATE on the autosave path.
        String sql = "INSERT INTO rate_limit_buckets (bucket_key,hits,expires_at) "
                + "VALUES (?,LAST_INSERT_ID(1),UTC_TIMESTAMP(6)+INTERVAL 60 SECOND) "
                + "ON DUPLICATE KEY UPDATE "
                + "hits=LAST_INSERT_ID(IF(expires_at<=UTC_TIMESTAMP(6),1,LEAST(hits+1,?))), "
                + "expires_at=IF(expires_at<=UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)+INTERVAL 60 SECOND,expires_at),blocked_until=NULL";
        long hits = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Long>) connection -> {
            try (var statement = connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, hash(name)); statement.setLong(2, (long) limit + 1);
                statement.executeUpdate();
                try (var keys = statement.getGeneratedKeys()) { if (keys.next()) return keys.getLong(1); }
                // A saturated bucket can produce zero affected rows; LAST_INSERT_ID is connection-local.
                try (var read = connection.prepareStatement("SELECT LAST_INSERT_ID()" ); var result = read.executeQuery()) {
                    result.next(); return result.getLong(1);
                }
            }
        });
        return hits <= limit ? 0 : 60; // A conservative retry delay never reopens a live window early.
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long peek(String name, int limit, boolean lockout) {
        String key = hash(name); Instant now = now();
        var buckets = jdbc.query("SELECT hits,expires_at,blocked_until FROM rate_limit_buckets WHERE bucket_key=?",
                (rs, i) -> new Bucket(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant()), key);
        if (buckets.isEmpty()) return 0;
        Bucket b = buckets.get(0);
        if (lockout) return b.blocked() != null && b.blocked().isAfter(now) ? waitSeconds(b.blocked(), now) : 0;
        return limit > 0 && b.hits() >= limit && b.expires().isAfter(now) ? waitSeconds(b.expires(), now) : 0;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void add(String name, long ttl, int threshold, int base, int max) {
        String key = hash(name); Instant now = now(); Bucket b = lock(key, now);
        long hits = b.expires().isAfter(now) ? b.hits() + 1 : 1;
        Instant expiry = threshold > 0 || !b.expires().isAfter(now) ? now.plusSeconds(ttl) : b.expires();
        Instant blocked = threshold > 0 && hits >= threshold
                ? now.plusSeconds(Math.min(max, (long) base * (1L << Math.min(20, hits - threshold)))) : null;
        jdbc.update("UPDATE rate_limit_buckets SET hits=?,expires_at=?,blocked_until=? WHERE bucket_key=?", hits, Timestamp.from(expiry), blocked == null ? null : Timestamp.from(blocked), key);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void clear(String name) { jdbc.update("DELETE FROM rate_limit_buckets WHERE bucket_key=?", hash(name)); }
    @Scheduled(fixedDelayString = "${app.security.rate-limit.cleanup-ms:60000}")
    public void cleanup() { jdbc.update("DELETE FROM rate_limit_buckets WHERE expires_at < UTC_TIMESTAMP(6) LIMIT 2000"); }
}
