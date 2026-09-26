package com.classroom.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

public class FlywayMigrationValidationTest {

    private static final Pattern MIGRATION_PATTERN = Pattern.compile("^V(\\d+)__([a-zA-Z0-9_]+)\\.sql$");

    @Test
    @DisplayName("Verify Flyway migrations V1 through V18 exist sequentially with valid SQL structure")
    void testFlywayMigrationsSequentialAndNonEmpty() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:db/migration/*.sql");

        assertTrue(resources.length >= 18, "Expected at least 18 Flyway migration scripts, found: " + resources.length);

        Map<Integer, Resource> versionMap = new TreeMap<>();
        for (Resource res : resources) {
            String filename = res.getFilename();
            assertNotNull(filename);
            Matcher matcher = MIGRATION_PATTERN.matcher(filename);
            assertTrue(matcher.matches(), "Migration file does not match naming convention V<version>__<description>.sql: " + filename);

            int version = Integer.parseInt(matcher.group(1));
            assertFalse(versionMap.containsKey(version), "Duplicate Flyway migration version detected: " + version);
            versionMap.put(version, res);
        }

        // Verify sequential versions 1..total
        for (int v = 1; v <= resources.length; v++) {
            assertTrue(versionMap.containsKey(v), "Missing sequential Flyway migration version: V" + v);
            Resource res = versionMap.get(v);

            try (InputStream in = res.getInputStream()) {
                String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertFalse(sql.trim().isEmpty(), "Migration file is empty: " + res.getFilename());
                assertTrue(sql.contains(";") || sql.contains("CREATE") || sql.contains("ALTER") || sql.contains("UPDATE"),
                        "Migration file lacks DDL/DML statements: " + res.getFilename());
            }
        }
    }
}
