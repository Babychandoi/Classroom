package com.classroom.modules.segment.dto;

import java.time.Instant;
import java.util.List;

public class SegmentDto {
    private String id;
    private String classId;
    private String name;
    private String description;
    private String logicOperator;
    private List<SegmentRule> rules;
    private Instant createdAt;

    public SegmentDto() {}

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

    public List<SegmentRule> getRules() {
        return rules;
    }

    public void setRules(List<SegmentRule> rules) {
        this.rules = rules;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
