package com.classroom.modules.segment.dto;

public class SegmentRule {
    private String criterion; // IS_PRO, COURSE_OWNED, COMPLETED_LESSONS_COUNT, AVG_EXAM_SCORE, DAYS_SINCE_JOINED
    private String operator;  // EQUALS, NOT_EQUALS, GREATER_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL, IN, CONTAINS
    private String value;

    public SegmentRule() {}

    public SegmentRule(String criterion, String operator, String value) {
        this.criterion = criterion;
        this.operator = operator;
        this.value = value;
    }

    public String getCriterion() {
        return criterion;
    }

    public void setCriterion(String criterion) {
        this.criterion = criterion;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
