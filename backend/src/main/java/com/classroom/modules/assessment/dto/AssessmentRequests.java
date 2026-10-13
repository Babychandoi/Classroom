package com.classroom.modules.assessment.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public final class AssessmentRequests {
    private AssessmentRequests() {}
    public record Create(String title,String kind,Integer grade,String subjectCode,String languageCode,String topicTitle,JsonNode document) {}
    public record Save(Integer revision,JsonNode document) {}
    public record Validate(JsonNode document) {}
    public record Assign(Integer revision,String targetSegmentId,Instant openAt,Instant dueAt,int timeLimitMinutes,int maxAttempts,
                         String resultVisibility,boolean allowLate,boolean shuffle) {}
    public record Answers(Integer revision,Map<String,JsonNode> answers) {}
    public record Play(String assetKey) {}
    public record RecordingIntent(String filename,String mimeType,long sizeBytes,long durationMs) {}
    public record RecordingComplete(String assetId,long durationMs) {}
    public record Grade(BigDecimal points,Map<String,Integer> rubricLevels,String passFail,String comment) {}
    public record Finalize(String teacherComment,String passFail,boolean release) {}
    public record Issue(String nodeId,String level,String code,String message) {}
}
