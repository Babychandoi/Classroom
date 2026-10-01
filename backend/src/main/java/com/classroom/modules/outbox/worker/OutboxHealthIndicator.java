package com.classroom.modules.outbox.worker;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code outbox} contributor of {@code /actuator/health} (R20-04). Details - visible to OPS only, see
 * {@code management.endpoint.health.roles} - list the PENDING / DEAD_LETTER counts, the age of the oldest waiting event and the state of
 * the MongoDB / Neo4j projections.
 *
 * <p>It never reports DOWN: a lagging or partly failed projection must not take the application out of a load balancer's rotation
 * (the core features run on MySQL alone). {@code attention=true} is the signal to look, and the periodic WARN log line says the same.
 */
@Component
public class OutboxHealthIndicator implements HealthIndicator {

    private final OutboxMonitor monitor;

    public OutboxHealthIndicator(OutboxMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public Health health() {
        try {
            OutboxMonitor.Snapshot s = monitor.snapshot();
            Map<String, Object> sinks = new LinkedHashMap<>();
            boolean sinkDown = false;
            for (SinkStatus sink : monitor.sinks()) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("status", sink.enabled() ? (sink.up() ? "UP" : "DOWN") : "DISABLED");
                if (sink.enabled() && !sink.up()) {
                    sinkDown = true;
                    detail.put("downSince", String.valueOf(sink.downSince()));
                    detail.put("nextProbeAt", String.valueOf(sink.nextProbeAt()));
                    detail.put("failedProbes", sink.consecutiveFailures());
                    detail.put("lastError", sink.lastError());
                }
                sinks.put(sink.sink().toLowerCase(), detail);
            }
            return Health.up()
                    .withDetail("pending", s.pending())
                    .withDetail("processing", s.processing())
                    .withDetail("failed", s.failed())
                    .withDetail("deadLetter", s.deadLetter())
                    .withDetail("oldestPendingSeconds", s.oldestPendingSeconds() == null ? -1 : s.oldestPendingSeconds())
                    .withDetail("attention", s.needsAttention() || sinkDown)
                    .withDetail("sinks", sinks)
                    .build();
        } catch (Exception ex) {
            return Health.unknown().withDetail("error", String.valueOf(ex.getMessage())).build();
        }
    }
}
