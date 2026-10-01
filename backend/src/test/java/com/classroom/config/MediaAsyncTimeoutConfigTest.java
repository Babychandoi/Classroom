package com.classroom.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R8-01: Tomcat's default async request timeout is 30s, which silently truncated large media
 * downloads streamed through MediaController#download (the response had already started with
 * HTTP 200, so the truncation was invisible to the caller - see the finding). This pins the fix at
 * the properties-file level so a future edit cannot quietly drop the override.
 */
public class MediaAsyncTimeoutConfigTest {

    @Test
    @DisplayName("spring.mvc.async.request-timeout is raised well past Spring's 30s default")
    void asyncRequestTimeoutIsRaised() throws Exception {
        Properties props = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            assertNotNull(in, "application.properties must be on the test classpath");
            props.load(in);
        }
        String value = props.getProperty("spring.mvc.async.request-timeout");
        assertNotNull(value, "spring.mvc.async.request-timeout must be explicitly configured");
        long timeoutMs = Long.parseLong(value.trim());
        assertTrue(timeoutMs == -1 || timeoutMs >= 600_000L,
                "async request timeout must be unlimited (-1) or at least 10 minutes, was " + timeoutMs + "ms");
    }
}
