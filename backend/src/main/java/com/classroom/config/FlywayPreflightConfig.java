package com.classroom.config;

import com.classroom.config.DatabaseTimeZonePreflight.PendingMigration;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * R20-08: runs {@link DatabaseTimeZonePreflight} immediately before Flyway migrates. A {@link FlywayMigrationStrategy} is the hook
 * Spring Boot offers for exactly this: it replaces the default {@code flyway.migrate()} call, and an exception thrown from it fails
 * the application context - so startup aborts before a single pending migration touches the schema.
 */
@Configuration
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
public class FlywayPreflightConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayPreflightConfig.class);
    /** How many leading lines of a script are searched for the {@link DatabaseTimeZonePreflight#MARKER}. */
    private static final int MARKER_SCAN_LINES = 40;

    @Bean
    public FlywayMigrationStrategy utcPreflightMigrationStrategy(
            @Value("${app.db.allow-non-utc-migration:false}") boolean allowNonUtcMigration,
            @Value("${spring.flyway.locations:classpath:db/migration}") String locations) {
        return flyway -> {
            List<PendingMigration> pending = new ArrayList<>();
            for (MigrationInfo info : flyway.info().pending()) {
                if (info.getVersion() == null) {
                    continue; // repeatable migration
                }
                pending.add(new PendingMigration(info.getVersion().getVersion(), info.getScript(),
                        scriptCarriesMarker(locations, info.getScript())));
            }
            DatabaseTimeZonePreflight.run(flyway.getConfiguration().getDataSource(), pending, allowNonUtcMigration);
            flyway.migrate();
        };
    }

    /** True when the script (looked up under the first classpath location) carries the requires-utc-server marker near its top. */
    static boolean scriptCarriesMarker(String locations, String script) {
        if (script == null) {
            return false;
        }
        for (String location : locations.split(",")) {
            String trimmed = location.trim();
            if (!trimmed.startsWith("classpath:")) {
                continue;
            }
            String base = trimmed.substring("classpath:".length()).replaceAll("^/+|/+$", "");
            ClassPathResource resource = new ClassPathResource(base + "/" + script);
            if (!resource.exists()) {
                continue;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                for (int i = 0; i < MARKER_SCAN_LINES; i++) {
                    String line = reader.readLine();
                    if (line == null) {
                        break;
                    }
                    if (line.trim().startsWith("--") && line.toLowerCase(Locale.ROOT).contains(DatabaseTimeZonePreflight.MARKER)) {
                        return true;
                    }
                }
            } catch (Exception e) {
                log.debug("Could not scan {} for the UTC marker: {}", script, e.getMessage());
            }
            return false;
        }
        return false;
    }
}
