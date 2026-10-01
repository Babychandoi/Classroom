package com.classroom.integration;

import com.classroom.config.SharedRateLimitStore;
import com.classroom.modules.outbox.worker.OutboxLeaseStore;
import com.classroom.modules.outbox.model.OutboxEvent;
import com.classroom.modules.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@ActiveProfiles("integration")
@SpringBootTest(properties = "app.security.rate-limit.store=mysql")
class SharedRateLimitAndLeaseIntegrationTest {
    @Autowired SharedRateLimitStore limits;
    @Autowired OutboxLeaseStore leases;
    @Autowired OutboxEventRepository events;
    @Autowired JdbcTemplate jdbc;
    @Test void parallelConnectionsShareExactlyOneBudget() throws Exception {
        String key = "test:" + UUID.randomUUID();
        var pool = Executors.newFixedThreadPool(12);
        try {
            var jobs = new ArrayList<Callable<Long>>();
            for (int i = 0; i < 30; i++) jobs.add(() -> limits.acquire(key, 7));
            long allowed = 0;
            for (var result : pool.invokeAll(jobs)) if (result.get() == 0) allowed++;
            assertEquals(7, allowed);
            assertTrue(limits.acquire(key, 7) > 0);
            limits.clear(key);
        } finally { pool.shutdownNow(); }
    }
    @Test void sharedLockoutAndExpiredWindowUseDatabaseClock() {
        String key = "test-lock:" + UUID.randomUUID();
        for (int i = 0; i < 5; i++) limits.add(key, 1800, 5, 60, 900);
        assertTrue(limits.peek(key, 0, true) >= 59);
        limits.clear(key);
        assertEquals(0, limits.peek(key, 0, true));
    }
    @Test void saturatedCounterRemainsBlockedAndExpiredWindowStartsAtOne() {
        String key = "test-expiry:" + UUID.randomUUID();
        try {
            assertEquals(0, limits.acquire(key, 2));
            assertEquals(0, limits.acquire(key, 2));
            for (int i = 0; i < 5; i++) assertTrue(limits.acquire(key, 2) > 0);
            jdbc.update("UPDATE rate_limit_buckets SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE bucket_key=SHA2(?,256)", key);
            assertEquals(0, limits.acquire(key, 2));
            assertEquals(1L, jdbc.queryForObject("SELECT hits FROM rate_limit_buckets WHERE bucket_key=SHA2(?,256)", Long.class, key));
        } finally { limits.clear(key); }
    }
    @Test void concurrentNodesCannotReserveMoreThanSharedBudget() throws Exception {
        String key = "test-reserve:" + UUID.randomUUID();
        var pool = Executors.newFixedThreadPool(12);
        try {
            var jobs = new ArrayList<Callable<Integer>>();
            for (int i = 0; i < 30; i++) jobs.add(() -> limits.reserve(key, 7, 3).permits());
            int allowed = 0;
            for (var result : pool.invokeAll(jobs)) allowed += result.get();
            assertEquals(7, allowed);
            assertEquals(0, limits.reserve(key, 7, 8).permits());
            jdbc.update("UPDATE rate_limit_buckets SET expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE bucket_key=SHA2(?,256)", key);
            var fresh = limits.reserve(key, 7, 8);
            assertEquals(7, fresh.permits());
            assertTrue(fresh.lifetimeMillis() > 59000 && fresh.lifetimeMillis() <= 60000);
        } finally { limits.clear(key); pool.shutdownNow(); }
    }
    @Test void oldLeaseCannotFinishReleaseOrFailNewOwnersClaim() {
        OutboxEvent event = new OutboxEvent("LEASE_TEST", UUID.randomUUID().toString(), "TEST", "{}");
        event.setStatus("DEAD_LETTER");
        event = events.saveAndFlush(event);
        String id = event.getId(); String oldToken = UUID.randomUUID().toString(), newToken = UUID.randomUUID().toString();
        try {
            jdbc.update("UPDATE outbox_events SET status='PENDING', retry_count=100 WHERE id=?", id);
            assertEquals(1, leases.claim(id, oldToken, Instant.now()));
            assertEquals(1, leases.release(id, oldToken));
            assertEquals(1, leases.claim(id, newToken, Instant.now()));
            assertEquals(0, leases.processed(id, oldToken, Instant.now()));
            assertEquals(0, leases.release(id, oldToken));
            assertEquals(0, leases.transientFailure(id, oldToken, "old"));
            assertEquals(0, leases.permanentFailure(id, oldToken, "DEAD_LETTER", 999, "old", Instant.now()));
            assertEquals(1, leases.processed(id, newToken, Instant.now()));
        } finally { events.deleteById(id); }
    }
}
