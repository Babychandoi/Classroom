package com.classroom.controller;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final DataSource dataSource;
    /** R20-01: how long readiness waits for a pooled connection + validation before answering DOWN. */
    private final long readinessTimeoutMs;
    /** Single daemon thread: a probe that is stuck on an exhausted pool must never spawn more waiting threads. */
    private final ExecutorService probeExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread t = new Thread(runnable, "readiness-probe");
        t.setDaemon(true);
        return t;
    });
    /** The probe currently running (or last finished). Concurrent callers share it instead of each waiting for a connection. */
    private final AtomicReference<CompletableFuture<Boolean>> currentProbe = new AtomicReference<>();

    public HealthController(@Autowired(required = false) DataSource dataSource,
                            @Value("${classroom.health.readiness.timeout-ms:2000}") long readinessTimeoutMs) {
        this.dataSource = dataSource;
        this.readinessTimeoutMs = Math.max(200, readinessTimeoutMs);
    }

    @PreDestroy
    void shutdown() {
        probeExecutor.shutdownNow();
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "timestamp", Instant.now().toString(),
                "service", "online-classroom-backend",
                "version", "0.1.0"
        ));
    }

    /**
     * Readiness = "can this instance get a working database connection right now". R20-01: the probe is time-boxed
     * ({@code classroom.health.readiness.timeout-ms}, default 2 s) instead of waiting for the pool's own connection timeout, so an
     * exhausted pool is reported as 503 within seconds and the probe never becomes one more request stuck behind the saturated
     * pool. Probing is single-flight: at most one connection is ever requested for probing, and callers that arrive while a probe
     * is running (the Docker health check, a load balancer, a monitor) share its result rather than queue for another connection.
     */
    @GetMapping("/health/readiness")
    public ResponseEntity<Map<String, Object>> readiness() {
        boolean dbHealthy = true;
        String reason = "Database connectivity failure";
        if (dataSource != null) {
            dbHealthy = false;
            try {
                dbHealthy = Boolean.TRUE.equals(probe().get(readinessTimeoutMs, TimeUnit.MILLISECONDS));
            } catch (TimeoutException timeout) {
                reason = "Database connection pool exhausted (no connection within " + readinessTimeoutMs + " ms)";
            } catch (Exception e) {
                dbHealthy = false;
            }
        }

        if (!dbHealthy) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "status", "DOWN",
                    "reason", reason,
                    "timestamp", Instant.now().toString()
            ));
        }

        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "database", "UP",
                "timestamp", Instant.now().toString()
        ));
    }

    /** Returns the running probe, or starts a new one when none is running. */
    private CompletableFuture<Boolean> probe() {
        while (true) {
            CompletableFuture<Boolean> existing = currentProbe.get();
            if (existing != null && !existing.isDone()) {
                return existing;
            }
            CompletableFuture<Boolean> fresh = new CompletableFuture<>();
            if (!currentProbe.compareAndSet(existing, fresh)) {
                continue; // another caller started one first: share theirs
            }
            try {
                probeExecutor.execute(() -> {
                    try (var conn = dataSource.getConnection()) {
                        fresh.complete(conn.isValid(1));
                    } catch (Exception e) {
                        fresh.complete(false);
                    }
                });
            } catch (RuntimeException rejected) {
                fresh.complete(false);
            }
            return fresh;
        }
    }
}
