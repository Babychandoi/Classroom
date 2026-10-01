package com.classroom.config;

import com.classroom.modules.projection.mongo.MongoClientTimeoutConfig;
import com.mongodb.MongoClientSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R20-04a: the scheduler pool and the MongoDB / Neo4j client timeouts are configuration, so the regression guard reads the real
 * {@code application.properties} (placeholders resolved to their defaults) instead of trusting a comment.
 */
class ResilienceConfigurationTest {

    private static StandardEnvironment applicationProperties() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setConversionService(new ApplicationConversionService());
        environment.getPropertySources().addLast(new PropertiesPropertySource("application",
                PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"))));
        return environment;
    }

    @Test
    @DisplayName("R20-04a: the shared scheduler has a real pool (>= 6) with a recognisable thread-name prefix - it was ONE thread for every job")
    void schedulerHasAPoolNamedForDiagnostics() throws Exception {
        StandardEnvironment env = applicationProperties();
        Integer size = env.getProperty("spring.task.scheduling.pool.size", Integer.class);
        assertNotNull(size, "spring.task.scheduling.pool.size must be configured");
        assertTrue(size >= 6, "pool size " + size + " must be at least 6");
        assertEquals("classroom-sched-", env.getProperty("spring.task.scheduling.thread-name-prefix"));
    }

    @Test
    @DisplayName("R20-04a: the pool is at least as large as the number of @Scheduled jobs, so no job can be starved by the others")
    void poolCoversEveryScheduledJob() throws Exception {
        int size = applicationProperties().getProperty("spring.task.scheduling.pool.size", Integer.class);
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        int jobs = 0;
        for (BeanDefinition definition : scanner.findCandidateComponents("com.classroom")) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            for (var method : type.getDeclaredMethods()) {
                if (AnnotatedElementUtils.hasAnnotation(method, Scheduled.class)) {
                    jobs++;
                }
            }
        }
        assertTrue(jobs >= 6, "sanity: the scan must find the scheduled jobs (found " + jobs + ")");
        assertTrue(size >= jobs, "spring.task.scheduling.pool.size=" + size + " is smaller than the " + jobs + " @Scheduled jobs");
    }

    @Test
    @DisplayName("R20-04a: MongoDB timeouts default to selection 3 s, connect 2 s, socket 10 s (driver defaults: 30 s / 10 s / none)")
    void mongoTimeoutsAreShort() throws Exception {
        StandardEnvironment env = applicationProperties();
        assertEquals(3000, env.getProperty("classroom.projection.mongo.server-selection-timeout-ms", Integer.class));
        assertEquals(2000, env.getProperty("classroom.projection.mongo.connect-timeout-ms", Integer.class));
        assertEquals(10000, env.getProperty("classroom.projection.mongo.socket-timeout-ms", Integer.class));
    }

    @Test
    @DisplayName("R20-04a: the customizer really applies those timeouts to the MongoClientSettings")
    void mongoCustomizerAppliesTimeouts() {
        MongoClientSettings.Builder builder = MongoClientSettings.builder();

        new MongoClientTimeoutConfig().classroomMongoTimeouts(3000, 2000, 10000).customize(builder);
        MongoClientSettings settings = builder.build();

        assertEquals(3000, settings.getClusterSettings().getServerSelectionTimeout(TimeUnit.MILLISECONDS));
        assertEquals(2000, settings.getSocketSettings().getConnectTimeout(TimeUnit.MILLISECONDS));
        assertEquals(10000, settings.getSocketSettings().getReadTimeout(TimeUnit.MILLISECONDS));
        assertEquals(3000, settings.getConnectionPoolSettings().getMaxWaitTime(TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("R20-04a: a timeout <= 0 leaves the URI / driver value alone")
    void nonPositiveTimeoutKeepsTheDriverDefault() {
        MongoClientSettings.Builder builder = MongoClientSettings.builder();
        long defaultSelection = builder.build().getClusterSettings().getServerSelectionTimeout(TimeUnit.MILLISECONDS);

        new MongoClientTimeoutConfig().classroomMongoTimeouts(0, 0, 0).customize(builder);

        assertEquals(defaultSelection, builder.build().getClusterSettings().getServerSelectionTimeout(TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("R20-04a: Neo4j connects within 2 s, acquires a pooled connection within 3 s, retries transactions for 3 s (defaults: 30 s / 60 s / 30 s)")
    void neo4jTimeoutsAreShort() throws Exception {
        StandardEnvironment env = applicationProperties();
        assertEquals(Duration.ofSeconds(2), env.getProperty("spring.neo4j.connection-timeout", Duration.class));
        assertEquals(Duration.ofSeconds(3), env.getProperty("spring.neo4j.pool.connection-acquisition-timeout", Duration.class));
        assertEquals(Duration.ofSeconds(3), env.getProperty("spring.neo4j.max-transaction-retry-time", Duration.class));
        assertTrue(env.getProperty("spring.neo4j.pool.idle-time-before-connection-test", Duration.class).compareTo(Duration.ofSeconds(30)) <= 0);
    }

    @Test
    @DisplayName("R20-05: the outbox polls at most every second, batches per aggregate, purges after 7 days and re-drives automatically by default")
    void outboxDefaults() throws Exception {
        StandardEnvironment env = applicationProperties();
        assertTrue(env.getProperty("classroom.outbox.poll-delay-ms", Long.class) <= 1000);
        assertTrue(env.getProperty("classroom.outbox.workers", Integer.class) >= 2);
        assertEquals(50, env.getProperty("classroom.outbox.max-events-per-aggregate-per-pass", Integer.class));
        assertEquals(7, env.getProperty("classroom.outbox.retention.days", Integer.class));
        assertEquals(true, env.getProperty("classroom.outbox.retention.enabled", Boolean.class));
        assertEquals(true, env.getProperty("classroom.outbox.redrive.enabled", Boolean.class));
        assertTrue(env.getProperty("classroom.outbox.breaker.max-delay-ms", Long.class) <= 60000);
        assertEquals(false, env.getProperty("app.db.allow-non-utc-migration", Boolean.class));
    }
}
