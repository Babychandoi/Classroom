package com.classroom.modules.segment.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "segments")
public class Segment {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "class_id", nullable = false, length = 36)
    private String classId;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "logic_operator", nullable = false, length = 10)
    private String logicOperator = "AND"; // AND, OR

    @Column(name = "rules_json", columnDefinition = "MEDIUMTEXT", nullable = false)
    private String rulesJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Segment() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
    }

    public Segment(String classId, String name, String description, String logicOperator, String rulesJson) {
        this.id = UUID.randomUUID().toString();
        this.classId = classId;
        this.name = name;
        this.description = description;
        this.logicOperator = (logicOperator != null) ? logicOperator : "AND";
        this.rulesJson = rulesJson;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getClassId() {
        return classId;
    }

    public void setClassId(String classId) {
        this.classId = classId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getLogicOperator() {
        return logicOperator;
    }

    public void setLogicOperator(String logicOperator) {
        this.logicOperator = logicOperator;
    }

    public String getRulesJson() {
        return rulesJson;
    }

    public void setRulesJson(String rulesJson) {
        this.rulesJson = rulesJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
