package com.classroom.modules.projection.neo4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

@Service
public class Neo4jSyncService {
    private static final Logger log = LoggerFactory.getLogger(Neo4jSyncService.class);

    private final Optional<Neo4jClient> neo4jClient;
    private final boolean projectionEnabled;
    private volatile boolean constraintsReady;

    /** Fences graph state by the durable MySQL sequence so a late lease owner cannot undo a newer event. */
    public void applyMembership(String userId, String classId, long sequence, boolean active) {
        if (!projectionEnabled) return;
        ensureClientAvailable();
        Neo4jClient client = neo4jClient.orElseThrow();
        if (!constraintsReady) {
            client.query("CREATE CONSTRAINT classroom_user_id IF NOT EXISTS FOR (u:User) REQUIRE u.userId IS UNIQUE").run();
            client.query("CREATE CONSTRAINT classroom_class_id IF NOT EXISTS FOR (c:Class) REQUIRE c.classId IS UNIQUE").run();
            client.query("CREATE CONSTRAINT classroom_membership_key IF NOT EXISTS FOR (m:MembershipState) REQUIRE m.key IS UNIQUE").run();
            constraintsReady = true;
        }
        client.query("""
                MERGE (m:MembershipState {key: $key})
                SET m.lockVersion = coalesce(m.lockVersion, 0) + 1
                WITH m WHERE coalesce(m.sequenceNo, -1) < $sequence
                SET m.sequenceNo = $sequence, m.active = $active
                MERGE (u:User {userId: $userId})
                MERGE (c:Class {classId: $classId})
                WITH u, c
                OPTIONAL MATCH (u)-[r:MEMBER_OF]->(c)
                FOREACH (_ IN CASE WHEN NOT $active AND r IS NOT NULL THEN [1] ELSE [] END | DELETE r)
                FOREACH (_ IN CASE WHEN $active THEN [1] ELSE [] END | MERGE (u)-[:MEMBER_OF]->(c))
                """).bindAll(Map.of("key", userId + ":" + classId, "userId", userId, "classId", classId,
                        "sequence", sequence, "active", active)).run();
    }

    public Neo4jSyncService(Optional<Neo4jClient> neo4jClient,
                            @Value("${classroom.projection.neo4j.enabled:true}") boolean projectionEnabled) {
        this.neo4jClient = neo4jClient;
        this.projectionEnabled = projectionEnabled;
    }

    private void ensureClientAvailable() {
        if (!projectionEnabled) {
            return;
        }
        if (neo4jClient.isEmpty()) {
            throw new IllegalStateException("Neo4j projection is enabled but Neo4jClient is not available. Events cannot be processed without dataloss.");
        }
    }

    public void syncUserClassMembership(String userId, String classId) {
        if (!projectionEnabled) return;
        ensureClientAvailable();

        try {
            neo4jClient.get().query("""
                    MERGE (u:User {userId: $userId})
                    MERGE (c:Class {classId: $classId})
                    MERGE (u)-[:MEMBER_OF]->(c)
                    """)
                    .bindAll(Map.of("userId", userId, "classId", classId))
                    .run();
        } catch (Exception e) {
            log.warn("Neo4j projection error: {}", e.getMessage());
            throw new RuntimeException("Neo4j projection failed: " + e.getMessage(), e);
        }
    }

    public void removeUserClassMembership(String userId, String classId) {
        if (!projectionEnabled) return;
        ensureClientAvailable();

        try {
            neo4jClient.get().query("""
                    MATCH (u:User {userId: $userId})-[r:MEMBER_OF]->(c:Class {classId: $classId})
                    DELETE r
                    """)
                    .bindAll(Map.of("userId", userId, "classId", classId))
                    .run();
        } catch (Exception e) {
            log.warn("Neo4j membership removal projection error: {}", e.getMessage());
            throw new RuntimeException("Neo4j projection failed: " + e.getMessage(), e);
        }
    }

    public void syncUserFollowsTeacher(String userId, String teacherId) {
        if (!projectionEnabled) return;
        ensureClientAvailable();

        try {
            neo4jClient.get().query("""
                    MERGE (u:User {userId: $userId})
                    MERGE (t:Teacher {teacherId: $teacherId})
                    MERGE (u)-[:FOLLOWS]->(t)
                    """)
                    .bindAll(Map.of("userId", userId, "teacherId", teacherId))
                    .run();
        } catch (Exception e) {
            log.warn("Neo4j projection error: {}", e.getMessage());
            throw new RuntimeException("Neo4j projection failed: " + e.getMessage(), e);
        }
    }
}
