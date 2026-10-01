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
        java.time.Instant now = java.time.Instant.now();
        if (classId == null || memberRepository.findByClassIdAndUserId(classId, userId)
                .filter(member -> member.isActiveAt(now)).isEmpty()) return false;

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

        java.time.Instant previewNow = java.time.Instant.now();
        List<ClassMember> members = memberRepository.findByClassId(classId).stream()
                .filter(member -> member.isActiveAt(previewNow)).toList();

        // R3-08: isUserInSegment re-fetches and re-parses the segment on every call; for a preview
        // over an entire class's membership that means the same segment row and rules JSON are
        // loaded and parsed once per member instead of once per request. Resolve them a single time
        // and evaluate the parsed rules directly against each member's context.
        Segment segment = segmentRepository.findById(segmentId).orElse(null);
        int matched = 0;
        if (segment != null && classId.equals(segment.getClassId())) {
            List<SegmentRule> rules = parseRules(segment.getRulesJson());
            boolean isOr = "OR".equalsIgnoreCase(segment.getLogicOperator());
            if (!rules.isEmpty()) {
                for (ClassMember m : members) {
                    Map<String, Object> context = buildUserContext(m.getUserId(), classId);
                    boolean isMatch;
                    if (isOr) {
                        isMatch = rules.stream().anyMatch(rule -> segmentParser.evaluate(rule, context));
                    } else {
                        isMatch = rules.stream().allMatch(rule -> segmentParser.evaluate(rule, context));
                    }
                    if (isMatch) matched++;
                }
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

        // R14-03: count completions only on learner-visible lessons (non-archived lesson in a
        // non-archived section) so the segment criterion agrees with the progress shown to the learner.
        long completedCount = progressRepository.countCompletedVisibleByUserIdAndClassId(userId, classId);
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

        // R8-08/D-04: AVG_EXAM_SCORE must be the average of the learner's BEST published attempt
        // per exam (mirroring LeaderboardService.recalculateUserPoints' "highest published score
        // per exam" rule), not an average over every published attempt (which would let a poor
        // resit drag the average down even though only the best result ever counts anywhere else).
        // And per the segment parser's "missing value -> no match" rule, a learner with no published
        // attempts at all must have NO entry for this criterion (SegmentParser.evaluate treats a
        // missing key as non-match for every operator, including NOT_EQUALS) — populating it with
        // 0.0 would wrongly make e.g. "AVG_EXAM_SCORE <= 50" match someone with no exam history.
        List<ExamAttempt> publishedAttempts = examAttemptRepository.findByClassIdAndUserIdAndStatusAndIsPreviewFalse(classId, userId, "PUBLISHED");
        if (publishedAttempts != null && !publishedAttempts.isEmpty()) {
            Map<String, BigDecimal> bestScoreByExam = new HashMap<>();
            for (ExamAttempt attempt : publishedAttempts) {
                BigDecimal score = attempt.getScore() != null ? attempt.getScore() : BigDecimal.ZERO;
                bestScoreByExam.merge(attempt.getExamId(), score, BigDecimal::max);
            }
            if (!bestScoreByExam.isEmpty()) {
                double avgScore = bestScoreByExam.values().stream()
                        .mapToDouble(BigDecimal::doubleValue)
                        .average()
                        .orElse(0.0);
                context.put("AVG_EXAM_SCORE", avgScore);
            }
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
