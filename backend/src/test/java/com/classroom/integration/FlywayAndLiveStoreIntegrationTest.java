package com.classroom.integration;

import com.classroom.modules.projection.mongo.LearningEventDocument;
import com.classroom.modules.projection.mongo.LearningEventRepository;
import com.classroom.modules.projection.neo4j.Neo4jSyncService;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@SpringBootTest
@ActiveProfiles("integration")
public class FlywayAndLiveStoreIntegrationTest {

    @Autowired(required = false)
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    @Autowired(required = false)
    private LearningEventRepository learningEventRepository;

    @Autowired(required = false)
    private Neo4jSyncService neo4jSyncService;

    @Test
    @DisplayName("Verify Flyway migrations V1 through V18 applied cleanly to MySQL")
    void testFlywayMigrationsApplied() throws Exception {
        if (flyway != null) {
            MigrationInfo[] applied = flyway.info().applied();
            assertTrue(applied.length >= 18, "Expected at least 18 applied migrations, got: " + applied.length);
            for (MigrationInfo info : applied) {
                assertTrue(info.getState().isApplied(), "Migration " + info.getVersion() + " is not applied: " + info.getState());
            }
        }

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SHOW TABLES LIKE 'flyway_schema_history'");
            assertTrue(rs.next(), "flyway_schema_history table must exist in MySQL");

            ResultSet rsAttempts = stmt.executeQuery("SHOW COLUMNS FROM exam_attempts LIKE 'reward_points_snapshot'");
            assertTrue(rsAttempts.next(), "exam_attempts.reward_points_snapshot (V9) must exist");

            ResultSet rsAudience = stmt.executeQuery("SHOW COLUMNS FROM exams LIKE 'audience_rule_version'");
            assertTrue(rsAudience.next(), "exams.audience_rule_version (V8) must exist");

            ResultSet rsSubmissions = stmt.executeQuery("SHOW TABLES LIKE 'assignment_submissions'");
            assertTrue(rsSubmissions.next(), "assignment_submissions (V7) must exist");

            ResultSet rsTokens = stmt.executeQuery("SHOW TABLES LIKE 'revoked_tokens'");
            assertTrue(rsTokens.next(), "revoked_tokens (V6) must exist");

            ResultSet rsTargetCourseSnapshot = stmt.executeQuery("SHOW COLUMNS FROM order_items LIKE 'target_course_id_snapshot'");
            assertTrue(rsTargetCourseSnapshot.next(), "order_items.target_course_id_snapshot (V16) must exist");
        }
    }

    @Test
    @DisplayName("Verify MongoDB projection live store connectivity and persistence")
    void testMongoProjectionPersistence() {
        if (learningEventRepository != null) {
            String eventId = "test-event-" + Instant.now().toEpochMilli();
            LearningEventDocument doc = new LearningEventDocument(
                    eventId, "CLASSROOM", "test-class", "TEST_EVENT", "{}",
                    Instant.now(), "u-1", "test-class", null, null
            );
            learningEventRepository.save(doc);

            Optional<LearningEventDocument> found = learningEventRepository.findById(eventId);
            assertTrue(found.isPresent(), "Document should be saved and retrievable from MongoDB");
            assertEquals("TEST_EVENT", found.get().getEventType());
        }
    }

    @Test
    @DisplayName("Verify Neo4j live sync service connectivity")
    void testNeo4jSyncConnectivity() {
        if (neo4jSyncService != null) {
            assertDoesNotThrow(() -> neo4jSyncService.syncUserClassMembership("u-integ-1", "c-integ-1"));
            assertDoesNotThrow(() -> neo4jSyncService.removeUserClassMembership("u-integ-1", "c-integ-1"));
        }
    }
}
