package com.classroom.modules.segment.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.commerce.model.Entitlement;
import com.classroom.modules.commerce.policy.ProPolicy;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import com.classroom.modules.exam.model.ExamAttempt;
import com.classroom.modules.exam.repository.ExamAttemptRepository;
import com.classroom.modules.learning.repository.LessonProgressRepository;
import com.classroom.modules.segment.dto.SegmentDto;
import com.classroom.modules.segment.dto.SegmentRule;
import com.classroom.modules.segment.model.Segment;
import com.classroom.modules.segment.policy.SegmentParser;
import com.classroom.modules.segment.repository.SegmentRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SegmentService {

    private final SegmentRepository segmentRepository;
    private final ClassMemberRepository memberRepository;
    private final LessonProgressRepository progressRepository;
    private final ProPolicy proPolicy;
    private final AccessPolicy accessPolicy;
    private final SegmentParser segmentParser;
    private final ObjectMapper objectMapper;
    private final EntitlementRepository entitlementRepository;
    private final ExamAttemptRepository examAttemptRepository;

    public SegmentService(SegmentRepository segmentRepository,
                          ClassMemberRepository memberRepository,
                          LessonProgressRepository progressRepository,
                          ProPolicy proPolicy,
                          AccessPolicy accessPolicy,
                          SegmentParser segmentParser,
                          ObjectMapper objectMapper,
                          EntitlementRepository entitlementRepository,
                          ExamAttemptRepository examAttemptRepository) {
        this.segmentRepository = segmentRepository;
        this.memberRepository = memberRepository;
        this.progressRepository = progressRepository;
        this.proPolicy = proPolicy;
        this.accessPolicy = accessPolicy;
        this.segmentParser = segmentParser;
        this.objectMapper = objectMapper;
        this.entitlementRepository = entitlementRepository;
        this.examAttemptRepository = examAttemptRepository;
    }

    @Transactional(readOnly = true)
    public List<SegmentDto> getSegments(String classId, String currentUserId) {
        accessPolicy.enforceManage(currentUserId, classId, "SEGMENT", "VIEW", null);

        return segmentRepository.findByClassId(classId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public SegmentDto createSegment(String classId, String name, String description, String logicOperator, List<SegmentRule> rules, String currentUserId) {
        accessPolicy.enforceManage(currentUserId, classId, "SEGMENT", "CREATE", null);

        if (name == null || name.isBlank()) throw new AppException(ErrorCode.BAD_REQUEST, "Tên phân khúc là bắt buộc");
        if (logicOperator == null || !(logicOperator.equalsIgnoreCase("AND") || logicOperator.equalsIgnoreCase("OR"))) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Toán tử logic phải là AND hoặc OR");
        }
        if (rules == null || rules.isEmpty()) throw new AppException(ErrorCode.BAD_REQUEST, "Phân khúc phải có ít nhất một điều kiện");

        for (SegmentRule rule : rules) {
            segmentParser.validateRule(rule);
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(rules);
        } catch (Exception e) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Không thể lưu điều kiện phân khúc");
        }

        Segment segment = new Segment(classId, name, description, logicOperator, json);
        Segment saved = segmentRepository.save(segment);

        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public boolean isUserInSegment(String segmentId, String userId, String classId) {
        Segment segment = segmentRepository.findById(segmentId).orElse(null);
        if (segment == null) return false;

        // Finding 4: Verify segment belongs to the specified classroom
        if (classId != null && !classId.equals(segment.getClassId())) {
            return false;
        }
        if (classId == null || memberRepository.findByClassIdAndUserId(classId, userId)
                .filter(member -> "ACTIVE".equalsIgnoreCase(member.getState())).isEmpty()) return false;

        List<SegmentRule> rules = parseRules(segment.getRulesJson());
        // Finding 4: Empty-rule segment must not evaluate to true (reject by default)
        if (rules.isEmpty()) return false;

        Map<String, Object> context = buildUserContext(userId, classId);

        boolean isOr = "OR".equalsIgnoreCase(segment.getLogicOperator());
        if (isOr) {
            for (SegmentRule rule : rules) {
                if (segmentParser.evaluate(rule, context)) return true;
            }
            return false;
        } else {
            for (SegmentRule rule : rules) {
                if (!segmentParser.evaluate(rule, context)) return false;
            }
            return true;
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> previewSegment(String segmentId, String classId, String currentUserId) {
        accessPolicy.enforceManage(currentUserId, classId, "SEGMENT", "VIEW", null);

        List<ClassMember> members = memberRepository.findByClassId(classId).stream()
                .filter(member -> "ACTIVE".equalsIgnoreCase(member.getState())).toList();
        int matched = 0;

        for (ClassMember m : members) {
            if (isUserInSegment(segmentId, m.getUserId(), classId)) {
                matched++;
            }
        }

        return Map.of(
                "segmentId", segmentId,
                "totalMembers", members.size(),
                "matchingMembers", matched
        );
    }

    private Map<String, Object> buildUserContext(String userId, String classId) {
        Map<String, Object> context = new HashMap<>();

        boolean isPro = proPolicy.isPro(userId, classId);
        context.put("IS_PRO", isPro);

        long completedCount = progressRepository.countByUserIdAndClassIdAndCompletedTrue(userId, classId);
        context.put("COMPLETED_LESSONS_COUNT", completedCount);

        memberRepository.findByClassIdAndUserId(classId, userId).ifPresent(m -> {
            long days = Duration.between(m.getJoinedAt(), Instant.now()).toDays();
            context.put("DAYS_SINCE_JOINED", days);
        });

        // Authoritative COURSE_OWNED population (Finding 10)
        Instant now = Instant.now();
        List<Entitlement> entitlements = entitlementRepository.findActiveEntitlements(userId, classId, now);
        Set<String> ownedCourses = (entitlements != null) ? entitlements.stream()
                .map(Entitlement::getTargetCourseId)
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet()) : Collections.emptySet();
        context.put("COURSE_OWNED", ownedCourses);

        // Authoritative AVG_EXAM_SCORE population (Finding 10)
        List<ExamAttempt> publishedAttempts = examAttemptRepository.findByClassIdAndUserIdAndStatusAndIsPreviewFalse(classId, userId, "PUBLISHED");
        if (publishedAttempts != null && !publishedAttempts.isEmpty()) {
            double avgScore = publishedAttempts.stream()
                    .map(ExamAttempt::getScore)
                    .filter(Objects::nonNull)
                    .mapToDouble(BigDecimal::doubleValue)
                    .average()
                    .orElse(0.0);
            context.put("AVG_EXAM_SCORE", avgScore);
        } else {
            context.put("AVG_EXAM_SCORE", 0.0);
        }

        return context;
    }

    private List<SegmentRule> parseRules(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<SegmentRule>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private SegmentDto toDto(Segment segment) {
        SegmentDto dto = new SegmentDto();
        dto.setId(segment.getId());
        dto.setClassId(segment.getClassId());
        dto.setName(segment.getName());
        dto.setDescription(segment.getDescription());
        dto.setLogicOperator(segment.getLogicOperator());
        dto.setRules(parseRules(segment.getRulesJson()));
        dto.setCreatedAt(segment.getCreatedAt());
        return dto;
    }
}
