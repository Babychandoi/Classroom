package com.classroom.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R20-01: readiness answers within a short, configurable time even when the connection pool is exhausted (it used to wait for the
 * pool's own timeout - 30 s by default - and so became one more stuck request), never asks for more than one connection at a time,
 * and callers arriving during a probe share its result instead of getting a false DOWN.
 */
class HealthControllerTest {

    @Test
    @DisplayName("UP when a valid connection is obtained")
    void upWhenDatabaseAnswers() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(1)).thenReturn(true);
        HealthController controller = new HealthController(dataSource, 2000);
        try {
            ResponseEntity<Map<String, Object>> response = controller.readiness();
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals("UP", response.getBody().get("database"));
        } finally {
            controller.shutdown();
        }
    }

    @Test
    @DisplayName("UP without a DataSource (nothing to check), DOWN when the connection cannot be obtained")
    void noDataSourceIsUpAndBrokenDatabaseIsDown() throws Exception {
        HealthController none = new HealthController(null, 2000);
        assertEquals(HttpStatus.OK, none.readiness().getStatusCode());
        none.shutdown();

        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("connection refused"));
        HealthController controller = new HealthController(broken, 2000);
        try {
            ResponseEntity<Map<String, Object>> response = controller.readiness();
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
            assertEquals("DOWN", response.getBody().get("status"));
        } finally {
            controller.shutdown();
        }
    }

    @Test
    @DisplayName("an exhausted pool is reported as 503 after the short timeout, with ONE waiting probe shared by every caller")
    void exhaustedPoolIsReportedQuicklyAndProbesAreSingleFlight() throws Exception {
        CountDownLatch neverReleased = new CountDownLatch(1);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenAnswer(inv -> {
            neverReleased.await(20, TimeUnit.SECONDS); // pool exhausted: getConnection blocks
            throw new SQLException("timeout");
        });
        HealthController controller = new HealthController(dataSource, 300);
        ExecutorService callers = Executors.newFixedThreadPool(5);
        try {
            long started = System.nanoTime();
            List<Future<ResponseEntity<Map<String, Object>>>> results = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                results.add(callers.submit(controller::readiness));
            }
            for (Future<ResponseEntity<Map<String, Object>>> f : results) {
                ResponseEntity<Map<String, Object>> response = f.get(5, TimeUnit.SECONDS);
                assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
                assertTrue(String.valueOf(response.getBody().get("reason")).contains("exhausted"), String.valueOf(response.getBody()));
            }
            long ms = (System.nanoTime() - started) / 1_000_000;
            assertTrue(ms < 3_000, "readiness must not wait for the pool timeout, took " + ms + " ms");
            // five callers, but only ONE connection request was ever queued behind the saturated pool
            verify(dataSource, times(1)).getConnection();
        } finally {
            neverReleased.countDown();
            callers.shutdownNow();
            controller.shutdown();
        }
    }
}
