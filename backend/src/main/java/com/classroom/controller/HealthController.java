package com.classroom.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final DataSource dataSource;

    public HealthController(@Autowired(required = false) DataSource dataSource) {
        this.dataSource = dataSource;
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

    @GetMapping("/health/readiness")
    public ResponseEntity<Map<String, Object>> readiness() {
        boolean dbHealthy = false;
        if (dataSource != null) {
            try (var conn = dataSource.getConnection()) {
                dbHealthy = conn.isValid(3);
            } catch (Exception e) {
                dbHealthy = false;
            }
        } else {
            dbHealthy = true;
        }

        if (!dbHealthy) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "status", "DOWN",
                    "reason", "Database connectivity failure",
                    "timestamp", Instant.now().toString()
            ));
        }

        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "database", "UP",
                "timestamp", Instant.now().toString()
        ));
    }
}
