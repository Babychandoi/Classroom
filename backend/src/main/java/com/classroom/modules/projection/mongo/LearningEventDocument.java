package com.classroom.modules.projection.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;

import java.time.Instant;

@Document(collection = "learning_events")
@CompoundIndex(name = "user_occurred_idx", def = "{'userId': 1, 'occurredAt': -1}")
@CompoundIndex(name = "class_occurred_idx", def = "{'classId': 1, 'occurredAt': -1}")
@CompoundIndex(name = "aggregate_type_occurred_idx", def = "{'aggregateType': 1, 'aggregateId': 1, 'eventType': 1, 'occurredAt': -1}")
public class LearningEventDocument {

    @Id
    private String id;
    private String eventId;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    private String userId;
    private String classId;
    private String courseId;
    private String lessonId;
    private String payloadJson;
    private Instant occurredAt;

    public LearningEventDocument() {}

    public LearningEventDocument(String eventId, String aggregateType, String aggregateId, String eventType, String payloadJson, Instant occurredAt) {
        this(eventId, aggregateType, aggregateId, eventType, payloadJson, occurredAt, null, null, null, null);
    }

    public LearningEventDocument(String eventId, String aggregateType, String aggregateId, String eventType, String payloadJson, Instant occurredAt,
                                 String userId, String classId, String courseId, String lessonId) {
        this.id = eventId;
        this.eventId = eventId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payloadJson = payloadJson;
        this.occurredAt = occurredAt;
        this.userId = userId;
        this.classId = classId;
        this.courseId = courseId;
        this.lessonId = lessonId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public void setAggregateType(String aggregateType) {
        this.aggregateType = aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(String aggregateId) {
        this.aggregateId = aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public void setPayloadJson(String payloadJson) {
        this.payloadJson = payloadJson;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getUserId() { return userId; }
    public String getClassId() { return classId; }
    public String getCourseId() { return courseId; }
    public String getLessonId() { return lessonId; }
}
