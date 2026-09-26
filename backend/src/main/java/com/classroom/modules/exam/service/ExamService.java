package com.classroom.modules.exam.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.exam.dto.*;
import com.classroom.modules.exam.model.*;
import com.classroom.modules.exam.policy.ExamAudiencePolicy;
import com.classroom.modules.exam.policy.ExamScoringPolicy;
import com.classroom.modules.exam.repository.*;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.outbox.service.OutboxService;
import com.classroom.modules.ranking.service.LeaderboardService;
import com.classroom.modules.segment.model.Segment;
import com.classroom.modules.segment.repository.SegmentRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ExamService {
    private static final Logger log = LoggerFactory.getLogger(ExamService.class);

    public static final int MAX_QUESTION_POINTS = 10_000;
    public static final int MAX_EXAM_TOTAL_POINTS = 100_000;

    private final ExamRepository examRepository;
    private final QuestionRepository questionRepository;
    private final AnswerOptionRepository optionRepository;
    private final ExamAttemptRepository attemptRepository;
    private final AttemptAnswerRepository attemptAnswerRepository;
    private final ExamAudiencePolicy audiencePolicy;
    private final ExamScoringPolicy scoringPolicy;
    private final AccessPolicy accessPolicy;
    private final LeaderboardService leaderboardService;
    private final ObjectMapper objectMapper;
    private final CourseRepository courseRepository;
    private final SegmentRepository segmentRepository;
    private final AuditService auditService;
    private final OutboxService outboxService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private ExamService selfProxy;

    public ExamService(ExamRepository examRepository,
                       QuestionRepository questionRepository,
                       AnswerOptionRepository optionRepository,
                       ExamAttemptRepository attemptRepository,
                       AttemptAnswerRepository attemptAnswerRepository,
                       ExamAudiencePolicy audiencePolicy,
                       ExamScoringPolicy scoringPolicy,
                       AccessPolicy accessPolicy,
                       LeaderboardService leaderboardService,
                       ObjectMapper objectMapper,
                       CourseRepository courseRepository,
                       SegmentRepository segmentRepository,
                       AuditService auditService,
                       OutboxService outboxService) {
        this.examRepository = examRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.attemptRepository = attemptRepository;
        this.attemptAnswerRepository = attemptAnswerRepository;
        this.audiencePolicy = audiencePolicy;
        this.scoringPolicy = scoringPolicy;
        this.accessPolicy = accessPolicy;
        this.leaderboardService = leaderboardService;
        this.objectMapper = objectMapper;
        this.courseRepository = courseRepository;
        this.segmentRepository = segmentRepository;
        this.auditService = auditService;
        this.outboxService = outboxService;
    }

    @Transactional(readOnly = true)
    public List<ExamDto> getExamsByClass(String classId, String userId) {
        if (userId != null) {
            accessPolicy.enforceMember(userId, classId);
        }

        List<Exam> exams = examRepository.findByClassIdOrderByCreatedAtDesc(classId);
        List<ExamDto> dtos = new ArrayList<>();
        Instant now = Instant.now();

        for (Exam e : exams) {
            boolean canManageThisExam = userId != null
                    && accessPolicy.canManage(userId, classId, "EXAM", "VIEW", resolveRbacCourseScope(e));
            // Hide unpublished / draft exams from regular students (Finding 3)
            if (!canManageThisExam && "DRAFT".equalsIgnoreCase(e.getStatus())) {
                continue;
            }

            boolean canEnter = (userId != null) && audiencePolicy.canEnterExam(userId, e, now, false);
            long attemptsCount = (userId != null)
                    ? attemptRepository.countByExamIdAndUserIdAndIsPreviewFalse(e.getId(), userId)
                    : 0;

            // Safe metadata only, omit question inventory from list (Finding 1, 3)
            ExamDto dto = toExamDto(e, false, false);
            dto.setCanEnter(canEnter);
            dto.setUserAttemptsCount(attemptsCount);
            dtos.add(dto);
        }

        return dtos;
    }

    @Transactional(readOnly = true)
    public ExamDto getExamDetails(String examId, String userId) {
        if (userId == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "Vui lòng đăng nhập để xem thông tin kỳ thi");
        }

        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy kỳ thi"));

        boolean isOwner = accessPolicy.isOwner(userId, exam.getClassId());
        boolean canViewManage = isOwner || accessPolicy.canManage(userId, exam.getClassId(), "EXAM", "VIEW", exam.getTargetCourseId());

        // Security boundary: non-staff must be class members and cannot access unpublished exams (Finding 1, Finding 3)
        if (!canViewManage) {
            accessPolicy.enforceMember(userId, exam.getClassId());
            if (!"PUBLISHED".equalsIgnoreCase(exam.getStatus()) && !"OPEN".equalsIgnoreCase(exam.getStatus())) {
                throw new AppException(ErrorCode.FORBIDDEN, "Kỳ thi chưa được công bố");
            }
        }

        // Segregate question & answer access (Finding 1, Finding 7):
        // Only OWNER or staff with an explicit non-wildcard EXAM:EDIT grant can receive answer keys.
        // Wildcard permissions (EXAM:* or *:*) are deliberately excluded via canAccessAnswerKey.
        // Staff with VIEW only receives safe metadata. Students get questions exclusively via startAttempt.
        boolean canEdit = isOwner || (canViewManage && accessPolicy.canManage(userId, exam.getClassId(), "EXAM", "EDIT", exam.getTargetCourseId()));
        boolean includeQuestions = canEdit;
        // Answer keys require explicit EXAM:EDIT; wildcards do NOT grant this sensitive access
        boolean includeAnswerKey = accessPolicy.canAccessAnswerKey(userId, exam.getClassId(), exam.getTargetCourseId());

        ExamDto dto = toExamDto(exam, includeQuestions, includeAnswerKey);

        Instant now = Instant.now();
        dto.setCanEnter(audiencePolicy.canEnterExam(userId, exam, now, false));
        dto.setUserAttemptsCount(attemptRepository.countByExamIdAndUserIdAndIsPreviewFalse(examId, userId));

        return dto;
    }

    @Transactional
    public ExamAttemptDto startAttempt(String examId, String userId, boolean isPreview) {
        Instant now = Instant.now();

        // Pessimistic write lock on exam row: serialize attempt creation and fail closed on lookup errors (Review 19 Finding 4)
        Exam exam = examRepository.findByIdForUpdate(examId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy kỳ thi"));

        // If preview is requested, strictly validate caller is OWNER or staff with EXAM:PREVIEW or EXAM:EDIT
        if (isPreview) {
            boolean isOwner = accessPolicy.isOwner(userId, exam.getClassId());
            boolean canPreview = accessPolicy.canManage(userId, exam.getClassId(), "EXAM", "PREVIEW", exam.getTargetCourseId())
                    || accessPolicy.canManage(userId, exam.getClassId(), "EXAM", "EDIT", exam.getTargetCourseId());
            if (!isOwner && !canPreview) {
                throw new AppException(ErrorCode.STAFF_PERMISSION_DENIED, "Chỉ chủ lớp hoặc nhân sự có quyền xem trước kỳ thi mới được thực hiện preview");
            }
            audiencePolicy.enforceEnterExam(userId, exam, now, true, false);
        } else {
            // Classroom personnel must use the isolated preview path. Otherwise OWNER/STAFF
            // membership in an ALL audience would create a normal ranked student attempt.
            if (accessPolicy.isOwner(userId, exam.getClassId())
                    || accessPolicy.isActiveStaff(userId, exam.getClassId())) {
                throw new AppException(ErrorCode.FORBIDDEN, "Nhân sự lớp học chỉ có thể làm bài thi ở chế độ xem trước");
            }
            // Check for existing active IN_PROGRESS attempt first before enforcing attempt limit (Round 8 Finding 1)
            Optional<ExamAttempt> inProgressOpt = attemptRepository
                    .findFirstByExamIdAndUserIdAndStatusOrderByStartedAtDesc(examId, userId, "IN_PROGRESS");
            if (inProgressOpt.isPresent()) {
                ExamAttempt existing = inProgressOpt.get();
                boolean withinAttemptDuration = now.isBefore(existing.getEndsAt());
                boolean withinExamSchedule = exam.getScheduleEnd() == null || now.isBefore(exam.getScheduleEnd());

                if (withinAttemptDuration && withinExamSchedule) {
                    // Audience eligibility is evaluated once when an attempt is created. A live
                    // membership, publication and schedule check still applies to resume requests.
                    audiencePolicy.enforceResumeAttempt(userId, exam, existing, now);

                    log.info("Resuming active in-progress attempt {} for user {} on exam {}", existing.getId(), userId, examId);
                    ExamAttemptDto dto = toAttemptDto(existing, exam.getTitle());

                    // Return immutable question snapshot taken at start of attempt
                    List<QuestionDto> questionDtos = parseQuestionSnapshot(existing.getQuestionSnapshotJson());
                    if (questionDtos == null || questionDtos.isEmpty()) {
                        log.error("Failed to parse question snapshot for attempt {}", existing.getId());
                        throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Không thể tải bản chụp câu hỏi của bài thi đã lưu");
                    }
                    dto.setQuestions(questionDtos);
                    return dto;
                } else {
                    // Past deadline or past schedule end: finalize, auto-grade and publish deterministically
                    finalizeTimeoutAttempt(existing, exam);
                    // Return normally so the enclosing transaction commits finalization. Throwing
                    // an eligibility error here would roll the timeout updates back.
                    return studentSafeDto(toAttemptDto(existing, exam.getTitle()));
                }
            }

            // No active attempt to resume: verify full eligibility to start a NEW attempt (including attempt limit)
            if (inProgressOpt.isPresent()
                    && attemptRepository.countByExamIdAndUserIdAndIsPreviewFalse(examId, userId) >= exam.getAttemptLimit()) {
                // Keep timeout finalization in this transaction; returning lets it commit instead of
                // rolling it back via an attempt-limit exception.
                return studentSafeDto(toAttemptDto(inProgressOpt.get(), exam.getTitle()));
            }
            audiencePolicy.enforceEnterExam(userId, exam, now, false, false);
        }

        int attemptNumber = 1;
        if (!isPreview) {
            long existingAttempts = attemptRepository.countByExamIdAndUserIdAndIsPreviewFalse(examId, userId);
            if (existingAttempts >= exam.getAttemptLimit()) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Bạn đã hết lượt tham gia kỳ thi này (tối đa " + exam.getAttemptLimit() + " lượt)");
            }
            attemptNumber = (int) existingAttempts + 1;
        }

        Instant endsAt = now.plus(exam.getDurationMinutes(), ChronoUnit.MINUTES);
        if (exam.getScheduleEnd() != null && endsAt.isAfter(exam.getScheduleEnd())) {
            endsAt = exam.getScheduleEnd();
        }
        // Finding 3: never consume an attempt that has no answering time left. The audience
        // policy already refuses entry at or past scheduleEnd; this guards the clamp directly.
        if (!endsAt.isAfter(now)) {
            throw new AppException(ErrorCode.EXAM_NOT_OPEN, "Kỳ thi đã kết thúc thời gian làm bài");
        }

        ExamAttempt attempt = new ExamAttempt(examId, userId, exam.getClassId(), endsAt, isPreview);
        // Normal attempts reach this point only after the audience rule was checked above.
        attempt.setAudienceEligibleAtStart(!isPreview);
        if (!isPreview) {
            attempt.setAttemptNumber(attemptNumber);
        }

        // Snapshot question set
        List<Question> questions = questionRepository.findByExamIdOrderByPositionAsc(examId);
        List<QuestionDto> questionDtos = questions.stream()
                .map(q -> toQuestionDto(q, false))
                .toList();

        try {
            attempt.setQuestionSnapshotJson(objectMapper.writeValueAsString(questionDtos));
            attempt.setGradingSnapshotJson(objectMapper.writeValueAsString(questions));
        } catch (Exception e) {
            log.error("Failed to serialize question snapshot for exam {}", examId, e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Không thể lưu bản chụp đề thi cho bài làm");
        }

        ExamAttempt saved;
        try {
            saved = attemptRepository.save(attempt);
        } catch (DataIntegrityViolationException e) {
            log.warn("Database constraint prevented duplicate attempt creation for user {} on exam {}", userId, examId);
            Optional<ExamAttempt> inProgressOpt = attemptRepository
                    .findFirstByExamIdAndUserIdAndStatusOrderByStartedAtDesc(examId, userId, "IN_PROGRESS");
            if (inProgressOpt.isPresent() && now.isBefore(inProgressOpt.get().getEndsAt())) {
                ExamAttempt existing = inProgressOpt.get();
                audiencePolicy.enforceResumeAttempt(userId, exam, existing, now);
                ExamAttemptDto resumeDto = toAttemptDto(existing, exam.getTitle());
                resumeDto.setQuestions(parseQuestionSnapshot(existing.getQuestionSnapshotJson()));
                return resumeDto;
            }
            throw new AppException(ErrorCode.BAD_REQUEST, "Bạn đã hết lượt tham gia kỳ thi này hoặc lượt thi đang được khởi tạo");
        }

        ExamAttemptDto dto = toAttemptDto(saved, exam.getTitle());
        dto.setQuestions(questionDtos);
        return dto;
    }

    @Transactional
    public ExamAttemptDto saveAnswers(String attemptId, String userId, Map<String, String> answers) {
        // Pessimistic write lock: serialize autosave against concurrent submit operations
        ExamAttempt attempt = attemptRepository.findByIdForUpdate(attemptId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài thi"));

        if (!attempt.getUserId().equals(userId)) {
            throw new AppException(ErrorCode.FORBIDDEN, "Bạn không thể lưu câu trả lời cho bài thi của người khác");
        }

        if (!attempt.isPreview()) {
            accessPolicy.enforceMember(userId, attempt.getClassId());
        }

        if (!"IN_PROGRESS".equalsIgnoreCase(attempt.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Không thể lưu đáp án cho bài thi đã kết thúc");
        }

        // Validate attempt deadline (Finding 4)
        if (Instant.now().isAfter(attempt.getEndsAt())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Thời gian làm bài thi đã kết thúc");
        }

        if (answers != null && !answers.isEmpty()) {
            // Validate question membership against immutable attempt snapshot
            Set<String> validQuestionIds = getValidQuestionIdsForAttempt(attempt);

            for (Map.Entry<String, String> entry : answers.entrySet()) {
                String qId = entry.getKey();
                if (!validQuestionIds.contains(qId)) {
                    throw new AppException(ErrorCode.BAD_REQUEST, "Câu hỏi không hợp lệ cho đề thi này: " + qId);
                }

                Optional<AttemptAnswer> existingAns = attemptAnswerRepository.findByAttemptIdAndQuestionId(attempt.getId(), qId);
                AttemptAnswer ans = existingAns.orElseGet(() -> new AttemptAnswer(attempt.getId(), qId, entry.getValue()));
                ans.setStudentAnswer(entry.getValue());
                attemptAnswerRepository.save(ans);
            }
        }

        Exam exam = examRepository.findById(attempt.getExamId()).orElse(null);
        String title = (exam != null) ? exam.getTitle() : "Kỳ thi";
        return toAttemptDto(attempt, title);
    }

    @Transactional
    public ExamAttemptDto submitAttempt(String attemptId, String userId, SubmitAttemptRequest request) {
        // Pessimistic write lock: serialize concurrent submissions on the attempt row (Round 8 Finding 2)
        ExamAttempt attempt = attemptRepository.findByIdForUpdate(attemptId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài thi"));

        if (!attempt.getUserId().equals(userId)) {
            throw new AppException(ErrorCode.FORBIDDEN, "Bạn không thể nộp bài thi của người khác");
        }

        if (!attempt.isPreview()) {
            accessPolicy.enforceMember(userId, attempt.getClassId());
        }

        // Idempotency: if already submitted, return the existing state without repeating grading or scores
        if (!"IN_PROGRESS".equalsIgnoreCase(attempt.getStatus())) {
            Exam exam = examRepository.findById(attempt.getExamId()).orElse(null);
            String title = (exam != null) ? exam.getTitle() : "Kỳ thi";
            return studentSafeDto(toAttemptDto(attempt, title));
        }

        Instant now = Instant.now();
        // At or after the authoritative deadline, grade only answers persisted by autosave.
        // Never accept request-body answers beyond the deadline (including network grace).
        if (!now.isBefore(attempt.getEndsAt())) {
            log.warn("Attempt {} submitted after deadline {}", attemptId, attempt.getEndsAt());
            Exam exam = examRepository.findById(attempt.getExamId()).orElse(null);
            finalizeTimeoutAttempt(attempt, exam);
            return studentSafeDto(toAttemptDto(attempt, exam != null ? exam.getTitle() : "Kỳ thi"));
        }

        attempt.setSubmittedAt(now);

        List<Question> questions = gradingQuestionsFor(attempt);
        Set<String> validQuestionIds = getValidQuestionIdsForAttempt(attempt);

        if (now.isBefore(attempt.getEndsAt()) && request != null && request.getAnswers() != null) {
            for (Map.Entry<String, String> entry : request.getAnswers().entrySet()) {
                String qId = entry.getKey();
                if (!validQuestionIds.contains(qId)) {
                    throw new AppException(ErrorCode.BAD_REQUEST, "Câu hỏi không thuộc đề thi này: " + qId);
                }
                Optional<AttemptAnswer> existingAns = attemptAnswerRepository.findByAttemptIdAndQuestionId(attempt.getId(), qId);
                AttemptAnswer ans = existingAns.orElseGet(() -> new AttemptAnswer(attempt.getId(), qId, entry.getValue()));
                ans.setStudentAnswer(entry.getValue());
                attemptAnswerRepository.save(ans);
            }
        }

        List<AttemptAnswer> allAnswers = attemptAnswerRepository.findByAttemptId(attempt.getId());

        // Auto grade objective questions; scoring policy sets status to GRADING if any ESSAY has
        // no pointsAwarded, or PUBLISHED when all questions are graded.
        // Do NOT overwrite the status that autoGradeAttempt just set — it encodes the correct
        // grading state (GRADING when essays are pending, PUBLISHED when all answers scored).
        scoringPolicy.autoGradeAttempt(attempt, questions, allAnswers);

        ExamAttempt saved = attemptRepository.save(attempt);

        // Finding 8: Emit outbox event for attempt submission (idempotent, no duplicates)
        outboxService.recordEventIfNotExists("EXAM", saved.getId(), "EXAM_SUBMITTED", Map.of(
                "userId", saved.getUserId(),
                "classId", saved.getClassId(),
                "examId", saved.getExamId(),
                "attemptId", saved.getId(),
                "submittedAt", now.toString()
        ));

        // If attempt published and not a staff preview, update leaderboard
        if ("PUBLISHED".equalsIgnoreCase(saved.getStatus()) && !saved.isPreview()) {
            captureRewardSnapshot(attempt);
            leaderboardService.scheduleRecalculation(saved.getClassId(), saved.getUserId());

            // Finding 8: Emit outbox event for published exam result (idempotent, no duplicates)
            outboxService.recordEventIfNotExists("EXAM", saved.getId(), "EXAM_PUBLISHED", Map.of(
                    "userId", saved.getUserId(),
                    "classId", saved.getClassId(),
                    "examId", saved.getExamId(),
                    "attemptId", saved.getId(),
                    "score", saved.getScore() != null ? saved.getScore() : BigDecimal.ZERO,
                    "publishedAt", now.toString()
            ));
        }

        Exam exam = examRepository.findById(attempt.getExamId()).orElse(null);
        String title = (exam != null) ? exam.getTitle() : "Kỳ thi";
        return studentSafeDto(toAttemptDto(saved, title));
    }

    @Transactional
    public ExamAttemptDto gradeAttempt(String attemptId, String currentUserId, GradeAttemptRequest request) {
        // Serialize grading of a single attempt. The pessimistic row lock is held for the whole
        // transaction, so concurrent graders cannot read the same attempt, overwrite each other's
        // answer corrections, or race the leaderboard recalculation below.
        ExamAttempt attempt = attemptRepository.findByIdForUpdate(attemptId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài thi"));

        Exam gradingExam = examRepository.findById(attempt.getExamId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy kỳ thi"));
        accessPolicy.enforceManage(currentUserId, attempt.getClassId(), "EXAM", "GRADE", resolveRbacCourseScope(gradingExam));

        // Published results may be corrected by an authorized grader. Keep the same scoped
        // permission check above, and retain the complete before/after score audit below.
        if (!Set.of("SUBMITTED", "GRADING", "PUBLISHED").contains(attempt.getStatus().toUpperCase(Locale.ROOT))) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Chỉ có thể chấm hoặc hiệu chỉnh bài thi đã nộp/công bố. Trạng thái hiện tại: " + attempt.getStatus());
        }

        List<AttemptAnswer> answers = attemptAnswerRepository.findByAttemptId(attemptId);
        Map<String, BigDecimal> beforeScores = new LinkedHashMap<>();
        answers.forEach(answer -> beforeScores.put(answer.getQuestionId(), answer.getPointsAwarded()));
        BigDecimal beforeTotal = attempt.getScore();
        boolean wasPublished = "PUBLISHED".equalsIgnoreCase(attempt.getStatus());
        List<Question> questions = gradingQuestionsFor(attempt);
        Map<String, Question> questionMap = questions.stream()
                .collect(Collectors.toMap(Question::getId, q -> q));

        if (request.getScores() != null) {
            for (Map.Entry<String, BigDecimal> entry : request.getScores().entrySet()) {
                String qId = entry.getKey();
                BigDecimal score = entry.getValue();
                Question q = questionMap.get(qId);
                if (q == null) {
                    throw new AppException(ErrorCode.BAD_REQUEST, "Câu hỏi không tồn tại trong đề thi: " + qId);
                }
                if (!"ESSAY".equalsIgnoreCase(q.getType())) {
                    throw new AppException(ErrorCode.BAD_REQUEST, "Chỉ có thể nhập điểm thủ công cho câu hỏi tự luận (ESSAY): " + qId);
                }
                if (score == null || score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(BigDecimal.valueOf(q.getPoints())) > 0) {
                    throw new AppException(ErrorCode.BAD_REQUEST, String.format("Điểm câu hỏi tự luận %s phải trong khoảng [0, %d]: %s", qId, q.getPoints(), score));
                }
            }

            Map<String, AttemptAnswer> answersByQuestion = answers.stream()
                    .collect(Collectors.toMap(AttemptAnswer::getQuestionId, a -> a));
            for (Map.Entry<String, BigDecimal> scoreEntry : request.getScores().entrySet()) {
                AttemptAnswer ans = answersByQuestion.computeIfAbsent(scoreEntry.getKey(),
                        qId -> new AttemptAnswer(attemptId, qId, ""));
                ans.setPointsAwarded(scoreEntry.getValue());
                ans.setGradedBy(currentUserId);
                if (request.getFeedback() != null && request.getFeedback().containsKey(ans.getQuestionId())) {
                    ans.setTeacherFeedback(request.getFeedback().get(ans.getQuestionId()));
                }
            }
            attemptAnswerRepository.saveAll(answersByQuestion.values());
        }

        // Re-fetch answers after save to ensure updated pointsAwarded values are visible
        List<AttemptAnswer> freshAnswers = attemptAnswerRepository.findByAttemptId(attemptId);
        Map<String, AttemptAnswer> answerMap = freshAnswers.stream()
                .collect(Collectors.toMap(AttemptAnswer::getQuestionId, a -> a));

        // Validate that ALL essay questions have been explicitly graded (have both pointsAwarded and
        // gradedBy set) before allowing publication. A partial grading request must leave the attempt
        // in GRADING state rather than publishing incomplete results.
        List<Question> essayQuestions = questions.stream()
                .filter(q -> "ESSAY".equalsIgnoreCase(q.getType()))
                .collect(Collectors.toList());

        boolean allEssaysGraded = essayQuestions.stream().allMatch(eq -> {
            AttemptAnswer ans = answerMap.get(eq.getId());
            return ans != null && ans.getPointsAwarded() != null && ans.getGradedBy() != null;
        });

        if (!essayQuestions.isEmpty() && !allEssaysGraded) {
            // Still pending manual grading — compute partial score and force GRADING status.
            // autoGradeAttempt would set GRADING because essays lack pointsAwarded, but we guard
            // explicitly to prevent any path from publishing incomplete grading.
            scoringPolicy.autoGradeAttempt(attempt, questions, freshAnswers);
            attempt.setStatus("GRADING");
            ExamAttempt saved = attemptRepository.save(attempt);

            // Audit partial grading
            auditService.record(
                    saved.getClassId(),
                    currentUserId,
                    "EXAM_GRADE_UPDATE",
                    "EXAM_ATTEMPT",
                    saved.getId(),
                    String.format("{\"status\":\"GRADING\",\"gradedBy\":\"%s\"}", currentUserId)
            );

            Exam partialExam = examRepository.findById(attempt.getExamId()).orElse(null);
            String partialTitle = (partialExam != null) ? partialExam.getTitle() : "Kỳ thi";
            return toAttemptDto(saved, partialTitle);
        }

        // All essays are graded (or there are no essay questions) — proceed to final scoring
        scoringPolicy.autoGradeAttempt(attempt, questions, freshAnswers);
        ExamAttempt saved = attemptRepository.save(attempt);

        if ("PUBLISHED".equalsIgnoreCase(saved.getStatus()) && !saved.isPreview()) {
            captureRewardSnapshot(attempt);
            leaderboardService.scheduleRecalculation(saved.getClassId(), saved.getUserId());

            // Finding 7: Record transactional audit event for grading and publication
            Map<String, Object> auditDetails = new LinkedHashMap<>();
            auditDetails.put("beforeScore", beforeTotal);
            auditDetails.put("afterScore", saved.getScore());
            auditDetails.put("beforeQuestionScores", beforeScores);
            Map<String, Object> afterQuestionScores = new LinkedHashMap<>();
            freshAnswers.forEach(answer -> afterQuestionScores.put(answer.getQuestionId(),
                    answer.getPointsAwarded() == null ? null : answer.getPointsAwarded()));
            auditDetails.put("afterQuestionScores", afterQuestionScores);
            auditDetails.put("totalPoints", saved.getTotalPoints());
            auditDetails.put("gradedBy", currentUserId);
            auditService.record(
                    saved.getClassId(),
                    currentUserId,
                    wasPublished ? "EXAM_GRADE_CORRECT" : "EXAM_GRADE_PUBLISH",
                    "EXAM_ATTEMPT",
                    saved.getId(),
                    serializeAuditDetails(auditDetails)
            );

            // Finding 8: Emit outbox event for published exam result (idempotent, no duplicates)
            String publicationEvent = wasPublished
                    ? "EXAM_RESULT_CORRECTED" : "EXAM_PUBLISHED";
            outboxService.recordEvent("EXAM", saved.getId(), publicationEvent, Map.of(
                    "userId", saved.getUserId(),
                    "classId", saved.getClassId(),
                    "examId", saved.getExamId(),
                    "attemptId", saved.getId(),
                    "score", saved.getScore() != null ? saved.getScore() : BigDecimal.ZERO,
                    "publishedAt", Instant.now().toString()
            ));
        } else if ("GRADING".equalsIgnoreCase(saved.getStatus())) {
            // Finding 7: Audit partial manual grading
            auditService.record(
                    saved.getClassId(),
                    currentUserId,
                    "EXAM_GRADE_UPDATE",
                    "EXAM_ATTEMPT",
                    saved.getId(),
                    String.format("{\"status\":\"GRADING\",\"gradedBy\":\"%s\"}", currentUserId)
            );
        }

        Exam exam = examRepository.findById(attempt.getExamId()).orElse(null);
        String title = (exam != null) ? exam.getTitle() : "Kỳ thi";
        return toAttemptDto(saved, title);
    }

    @Transactional(readOnly = true)
    public List<ExamAttemptDto> getGradingQueue(String classId, String currentUserId) {
        // Reject non-graders up front so this endpoint is consistent with its siblings
        // (403 instead of an empty 200). Course-scoped staff still pass here and are
        // narrowed to their scoped courses by the per-attempt canManage check below.
        if (!accessPolicy.isOwner(currentUserId, classId) && !accessPolicy.isActiveStaff(currentUserId, classId)) {
            throw new AppException(ErrorCode.STAFF_PERMISSION_DENIED,
                    "Không có quyền xem hàng đợi chấm bài của lớp học");
        }
        List<ExamAttemptDto> queue = new ArrayList<>();
        for (ExamAttempt attempt : attemptRepository.findByClassIdAndStatusInAndIsPreviewFalseOrderBySubmittedAtAsc(
                classId, List.of("SUBMITTED", "GRADING"))) {
            Exam exam = examRepository.findById(attempt.getExamId()).orElse(null);
            if (exam == null || !accessPolicy.canManage(currentUserId, classId, "EXAM", "GRADE", exam.getTargetCourseId())) continue;
            queue.add(toAttemptDto(attempt, exam.getTitle()));
        }
        return queue;
    }

    @Transactional(readOnly = true)
    public ExamAttemptDto getGradingAttempt(String attemptId, String currentUserId) {
        ExamAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài thi"));
        Exam exam = examRepository.findById(attempt.getExamId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy kỳ thi"));
        accessPolicy.enforceManage(currentUserId, attempt.getClassId(), "EXAM", "GRADE", resolveRbacCourseScope(exam));
        if (attempt.isPreview() || (!"SUBMITTED".equalsIgnoreCase(attempt.getStatus()) && !"GRADING".equalsIgnoreCase(attempt.getStatus()))) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Bài thi không nằm trong hàng đợi chấm");
        }
        ExamAttemptDto dto = toAttemptDto(attempt, exam.getTitle());
        dto.setQuestions(gradingQuestionsFor(attempt).stream().map(q -> toQuestionDto(q, false)).toList());
        return dto;
    }

    @Transactional(readOnly = true)
    public ExamAttemptDto getAttemptResult(String attemptId, String userId) {
        ExamAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài thi"));

        boolean isOwner = attempt.getUserId().equals(userId);
        Exam exam = examRepository.findById(attempt.getExamId()).orElse(null);
        String targetCourseId = exam == null ? null : exam.getTargetCourseId();
        boolean canView = isOwner || accessPolicy.canManage(userId, attempt.getClassId(), "EXAM", "VIEW", targetCourseId);
        boolean canGrade = !isOwner && accessPolicy.canManage(userId, attempt.getClassId(), "EXAM", "GRADE", targetCourseId);

        if (!isOwner && !canView) {
            throw new AppException(ErrorCode.FORBIDDEN, "Bạn không có quyền xem kết quả bài thi này");
        }

        if (isOwner && !"PUBLISHED".equalsIgnoreCase(attempt.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Kết quả bài thi đang chờ chấm và chưa được công bố");
        }

        String title = (exam != null) ? exam.getTitle() : "Kỳ thi";
        ExamAttemptDto dto = toAttemptDto(attempt, title);

        // Read-only staff with EXAM:VIEW receives only metadata/result summaries;
        // detailed answers, student responses, and teacher feedback require EXAM:GRADE or attempt ownership.
        if (!isOwner && !canGrade) {
            dto.setAnswers(List.of());
            if (!"PUBLISHED".equalsIgnoreCase(attempt.getStatus())) {
                dto.setScore(null);
                dto.setTotalPoints(0);
            }
            return dto;
        }

        return dto;
    }

    @Transactional(readOnly = true)
    public List<ExamAttemptDto> getMyAttempts(String examId, String userId) {
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy kỳ thi"));
        accessPolicy.enforceMember(userId, exam.getClassId());
        List<ExamAttempt> attempts = attemptRepository.findByExamIdAndUserIdOrderByStartedAtDesc(examId, userId);
        return attempts.stream().map(a -> {
            ExamAttemptDto dto = toAttemptDto(a, exam.getTitle());
            if (!"PUBLISHED".equalsIgnoreCase(a.getStatus())) {
                dto.setScore(null);
                dto.setTotalPoints(0);
                if (dto.getAnswers() != null) {
                    dto.getAnswers().forEach(ans -> {
                        ans.setPointsAwarded(null);
                        ans.setTeacherFeedback(null);
                    });
                }
            }
            return dto;
        }).toList();
    }

    public String resolveRbacCourseScope(Exam exam) {
        if (exam == null) return null;
        String scope = exam.getAudienceScope() == null ? "" : exam.getAudienceScope().trim().toUpperCase(Locale.ROOT);
        if (Set.of("COURSE", "COURSE_SEGMENT").contains(scope)) {
            return exam.getTargetCourseId();
        }
        return null;
    }

    @Transactional
    public Exam createExam(String classId, Exam exam, String currentUserId) {
        exam.setId(UUID.randomUUID().toString()); // Never allow a client-provided identifier to update an existing exam.
        exam.setClassId(classId);
        String scope = exam.getAudienceScope() == null ? "" : exam.getAudienceScope().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ALL", "PRO", "COURSE", "SEGMENT", "COURSE_SEGMENT").contains(scope)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Đối tượng tham gia kỳ thi không hợp lệ");
        }
        exam.setAudienceScope(scope);
        exam.setStatus("DRAFT");

        // Finding 4: Validate targetCourseId belongs to the specified class
        boolean needsCourse = Set.of("COURSE", "COURSE_SEGMENT").contains(scope);
        boolean needsSegment = Set.of("SEGMENT", "COURSE_SEGMENT").contains(scope);
        exam.setAudienceRuleVersion(1);
        String operator = exam.getAudienceOperator() == null ? "AND" : exam.getAudienceOperator().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("AND", "OR").contains(operator)) throw new AppException(ErrorCode.BAD_REQUEST, "Toán tử audience không hợp lệ");
        exam.setAudienceOperator(operator);

        if (!needsCourse && exam.getTargetCourseId() != null && !exam.getTargetCourseId().isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Kỳ thi không thuộc phạm vi khóa học không được chỉ định targetCourseId");
        }
        if (needsCourse && (exam.getTargetCourseId() == null || exam.getTargetCourseId().isBlank())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Kỳ thi theo khóa học phải chỉ định targetCourseId");
        }
        if (exam.getTargetCourseId() != null && !exam.getTargetCourseId().isBlank()) {
            Course course = courseRepository.findById(exam.getTargetCourseId())
                    .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));
            if (!course.getClassId().equals(classId)) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Khóa học mục tiêu không thuộc lớp học này");
            }
        }

        // Finding 4: Validate targetSegmentId belongs to the specified class
        if (needsSegment || exam.getTargetSegmentId() != null) {
            if (needsSegment && (exam.getTargetSegmentId() == null || exam.getTargetSegmentId().isBlank())) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Kỳ thi theo nhóm đối tượng phải chỉ định targetSegmentId");
            }
            Segment segment = segmentRepository.findById(exam.getTargetSegmentId())
                    .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy nhóm học viên"));
            if (!segment.getClassId().equals(classId)) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Nhóm học viên mục tiêu không thuộc lớp học này");
            }
        }

        String rbacScope = needsCourse ? exam.getTargetCourseId() : null;
        accessPolicy.enforceManage(currentUserId, classId, "EXAM", "CREATE", rbacScope);
        if (exam.getTitle() == null || exam.getTitle().isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Tên kỳ thi không được để trống");
        }

        Exam saved = examRepository.save(exam);

        auditService.record(
                classId,
                currentUserId,
                "EXAM_CREATE",
                "EXAM",
                saved.getId(),
                String.format("{\"title\":\"%s\",\"audienceScope\":\"%s\"}", saved.getTitle(), saved.getAudienceScope())
        );

        return saved;
    }

    @Transactional
    public Question addQuestion(String examId, Question question, List<AnswerOption> options, String currentUserId) {
        // Serialize all draft mutations and publication on the same exam row. This both prevents
        // post-publication edits and makes the total-points check atomic across concurrent adds.
        Exam exam = examRepository.findByIdForUpdate(examId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy kỳ thi"));
        accessPolicy.enforceManage(currentUserId, exam.getClassId(), "EXAM", "EDIT", resolveRbacCourseScope(exam));
        if (!"DRAFT".equalsIgnoreCase(exam.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Không thể sửa câu hỏi sau khi kỳ thi được công bố");
        }

        validateQuestion(question, options);

        List<Question> existingQuestions = questionRepository.findByExamIdOrderByPositionAsc(examId);
        long currentTotal = 0;
        for (Question q : existingQuestions) {
            currentTotal = Math.addExact(currentTotal, (long) q.getPoints());
        }
        if (Math.addExact(currentTotal, (long) question.getPoints()) > MAX_EXAM_TOTAL_POINTS) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Tổng điểm kỳ thi sau khi thêm câu hỏi sẽ vượt quá giới hạn tối đa (" + MAX_EXAM_TOTAL_POINTS + ")");
        }

        question.setId(UUID.randomUUID().toString()); // Question creation must not update an existing question by supplied ID.
        question.setExamId(examId);
        Question savedQ = questionRepository.save(question);

        if (options != null) {
            for (AnswerOption opt : options) {
                opt.setId(UUID.randomUUID().toString());
                opt.setQuestionId(savedQ.getId());
                optionRepository.save(opt);
            }
        }
        return savedQ;
    }

    private String serializeAuditDetails(Map<String, Object> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize exam grading audit details", e);
        }
    }

    @Transactional
    public Exam publishExam(String examId, String currentUserId) {
        Exam exam = examRepository.findByIdForUpdate(examId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy kỳ thi"));
        accessPolicy.enforceManage(currentUserId, exam.getClassId(), "EXAM", "PUBLISH", resolveRbacCourseScope(exam));
        if (!"DRAFT".equalsIgnoreCase(exam.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Chỉ có thể công bố kỳ thi ở trạng thái DRAFT");
        }
        List<Question> questions = questionRepository.findByExamIdOrderByPositionAsc(examId);
        if (questions.isEmpty()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Không thể công bố kỳ thi chưa có câu hỏi");
        }
        questions.forEach(question -> validateQuestion(question,
                optionRepository.findByQuestionIdOrderByPositionAsc(question.getId())));

        long totalPoints = 0;
        for (Question q : questions) {
            totalPoints = Math.addExact(totalPoints, (long) q.getPoints());
        }
        if (totalPoints <= 0 || totalPoints > MAX_EXAM_TOTAL_POINTS) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Tổng điểm kỳ thi phải từ 1 đến " + MAX_EXAM_TOTAL_POINTS);
        }

        if (exam.getDurationMinutes() <= 0 || exam.getAttemptLimit() <= 0
                || (exam.getScheduleStart() != null && exam.getScheduleEnd() != null
                && !exam.getScheduleEnd().isAfter(exam.getScheduleStart()))) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Lịch hoặc cấu hình thời lượng kỳ thi không hợp lệ");
        }
        exam.setStatus("PUBLISHED");
        exam.setUpdatedAt(Instant.now());
        Exam published = examRepository.save(exam);
        auditService.record(exam.getClassId(), currentUserId, "EXAM_PUBLISH", "EXAM", examId, "{} ");
        return published;
    }

    private void validateQuestion(Question question, List<AnswerOption> options) {
        if (question == null || question.getQuestionText() == null || question.getQuestionText().isBlank()
                || question.getPoints() <= 0 || question.getPoints() > MAX_QUESTION_POINTS) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Câu hỏi phải có nội dung và số điểm từ 1 đến " + MAX_QUESTION_POINTS);
        }
        String type = question.getType() == null ? "" : question.getType().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("MULTIPLE_CHOICE", "TRUE_FALSE", "ESSAY").contains(type)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Loại câu hỏi không được hỗ trợ");
        }
        question.setType(type);
        List<AnswerOption> safeOptions = options == null ? List.of() : options;
        if ("ESSAY".equals(type)) {
            if (!safeOptions.isEmpty()) throw new AppException(ErrorCode.BAD_REQUEST, "Câu tự luận không nhận lựa chọn đáp án");
            return;
        }
        if (safeOptions.size() < 2 || safeOptions.stream().anyMatch(o -> o.getOptionKey() == null
                || o.getOptionKey().isBlank() || o.getOptionText() == null || o.getOptionText().isBlank())
                || safeOptions.stream().map(o -> o.getOptionKey().trim().toUpperCase(Locale.ROOT)).distinct().count() != safeOptions.size()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Câu trắc nghiệm cần ít nhất hai lựa chọn có khóa duy nhất và nội dung");
        }
        String answerKey = question.getAnswerKey() == null ? "" : question.getAnswerKey().trim().toUpperCase(Locale.ROOT);
        if (safeOptions.stream().noneMatch(o -> answerKey.equals(o.getOptionKey().trim().toUpperCase(Locale.ROOT)))) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Đáp án đúng phải khớp với một lựa chọn");
        }
        question.setAnswerKey(answerKey);
    }

    private void captureRewardSnapshot(ExamAttempt attempt) {
        if (attempt.getRewardPointsSnapshot() != null && attempt.getRewardRuleSnapshot() != null) return;
        String rules = leaderboardService.snapshotRewardRules(attempt.getClassId(), attempt.getExamId());
        BigDecimal score = attempt.getScore() == null ? BigDecimal.ZERO : attempt.getScore();
        attempt.setRewardRuleSnapshot(rules);
        attempt.setRewardScoreSnapshot(score);
        attempt.setRewardPointsSnapshot(leaderboardService.calculateReward(rules, score));
        attemptRepository.save(attempt);
    }

    private ExamDto toExamDto(Exam exam, boolean includeQuestions, boolean includeAnswerKey) {
        ExamDto dto = new ExamDto();
        dto.setId(exam.getId());
        dto.setClassId(exam.getClassId());
        dto.setTitle(exam.getTitle());
        dto.setDescription(exam.getDescription());
        dto.setScheduleStart(exam.getScheduleStart());
        dto.setScheduleEnd(exam.getScheduleEnd());
        dto.setDurationMinutes(exam.getDurationMinutes());
        dto.setAttemptLimit(exam.getAttemptLimit());
        dto.setAudienceScope(exam.getAudienceScope());
        dto.setTargetCourseId(exam.getTargetCourseId());
        dto.setTargetSegmentId(exam.getTargetSegmentId());
        dto.setStatus(exam.getStatus());
        dto.setPassScore(exam.getPassScore());
        dto.setCreatedAt(exam.getCreatedAt());

        long count = questionRepository.countByExamId(exam.getId());
        dto.setQuestionCount((int) count);

        if (includeQuestions) {
            List<Question> questions = questionRepository.findByExamIdOrderByPositionAsc(exam.getId());
            List<QuestionDto> qDtos = questions.stream()
                    .map(q -> toQuestionDto(q, includeAnswerKey))
                    .toList();
            dto.setQuestions(qDtos);
        } else {
            dto.setQuestions(null);
        }

        return dto;
    }

    private QuestionDto toQuestionDto(Question q, boolean includeAnswerKey) {
        QuestionDto dto = new QuestionDto();
        dto.setId(q.getId());
        dto.setExamId(q.getExamId());
        dto.setQuestionText(q.getQuestionText());
        dto.setType(q.getType());
        dto.setPoints(q.getPoints());
        dto.setPosition(q.getPosition());

        if (includeAnswerKey) {
            dto.setAnswerKey(q.getAnswerKey());
        }

        List<AnswerOption> options = optionRepository.findByQuestionIdOrderByPositionAsc(q.getId());
        List<AnswerOptionDto> optDtos = options.stream()
                .map(o -> new AnswerOptionDto(o.getId(), o.getQuestionId(), o.getOptionKey(), o.getOptionText(), o.getPosition()))
                .toList();
        dto.setOptions(optDtos);

        return dto;
    }

    private ExamAttemptDto toAttemptDto(ExamAttempt attempt, String examTitle) {
        ExamAttemptDto dto = new ExamAttemptDto();
        dto.setId(attempt.getId());
        dto.setExamId(attempt.getExamId());
        dto.setExamTitle(examTitle);
        dto.setUserId(attempt.getUserId());
        dto.setClassId(attempt.getClassId());
        dto.setStartedAt(attempt.getStartedAt());
        dto.setSubmittedAt(attempt.getSubmittedAt());
        dto.setEndsAt(attempt.getEndsAt());
        dto.setScore(attempt.getScore());
        dto.setTotalPoints(attempt.getTotalPoints());
        dto.setStatus(attempt.getStatus());
        dto.setPreview(attempt.isPreview());

        List<AttemptAnswer> answers = attemptAnswerRepository.findByAttemptId(attempt.getId());
        List<ExamAttemptDto.AttemptAnswerDto> aDtos = answers.stream()
                .map(a -> new ExamAttemptDto.AttemptAnswerDto(a.getQuestionId(), a.getStudentAnswer(), a.getPointsAwarded(), a.getTeacherFeedback()))
                .toList();
        dto.setAnswers(aDtos);

        return dto;
    }

    private ExamAttemptDto studentSafeDto(ExamAttemptDto dto) {
        if (!"PUBLISHED".equalsIgnoreCase(dto.getStatus())) {
            dto.setScore(null);
            dto.setTotalPoints(0);
            if (dto.getAnswers() != null) dto.getAnswers().forEach(answer -> {
                answer.setPointsAwarded(null);
                answer.setTeacherFeedback(null);
            });
        }
        return dto;
    }

    private void finalizeTimeoutAttempt(ExamAttempt attempt, Exam exam) {
        if (attempt == null || !"IN_PROGRESS".equalsIgnoreCase(attempt.getStatus())) {
            return;
        }
        attempt.setSubmittedAt(attempt.getEndsAt());

        List<Question> questions = gradingQuestionsFor(attempt);
        List<AttemptAnswer> allAnswers = attemptAnswerRepository.findByAttemptId(attempt.getId());

        // Auto grade objective questions
        scoringPolicy.autoGradeAttempt(attempt, questions, allAnswers);

        boolean hasEssay = questions.stream().anyMatch(q -> "ESSAY".equalsIgnoreCase(q.getType()));
        if (!hasEssay) {
            attempt.setStatus("PUBLISHED");
            attemptRepository.save(attempt);

            if (!attempt.isPreview()) {
                captureRewardSnapshot(attempt);
                leaderboardService.scheduleRecalculation(attempt.getClassId(), attempt.getUserId());
                outboxService.recordEventIfNotExists("EXAM", attempt.getId(), "EXAM_PUBLISHED", Map.of(
                        "attemptId", attempt.getId(),
                        "examId", attempt.getExamId(),
                        "userId", attempt.getUserId(),
                        "classId", attempt.getClassId(),
                        "score", attempt.getScore() != null ? attempt.getScore() : BigDecimal.ZERO
                ));
            }
        } else {
            attempt.setStatus("SUBMITTED");
            attemptRepository.save(attempt);
            outboxService.recordEventIfNotExists("EXAM", attempt.getId(), "EXAM_SUBMITTED", Map.of(
                    "attemptId", attempt.getId(),
                    "examId", attempt.getExamId(),
                    "userId", attempt.getUserId(),
                    "classId", attempt.getClassId()
            ));
        }
    }

    /**
     * Independently finalizes expired attempts so closure of the exam schedule or a later
     * attempt-limit rejection cannot roll back timeout processing.
     */
    @Scheduled(fixedDelayString = "${classroom.exam.timeout-scan-ms:30000}")
    public void finalizeExpiredAttempts() {
        Instant now = Instant.now();
        for (ExamAttempt candidate : attemptRepository.findByStatusAndIsPreviewFalse("IN_PROGRESS")) {
            Exam exam = examRepository.findById(candidate.getExamId()).orElse(null);
            if (exam == null) continue;
            boolean attemptExpired = candidate.getEndsAt() != null && !now.isBefore(candidate.getEndsAt());
            boolean examClosed = exam.getScheduleEnd() != null && !now.isBefore(exam.getScheduleEnd());
            if (!attemptExpired && !examClosed) continue;
            try {
                if (selfProxy == null) {
                    finalizeExpiredAttemptTransactional(candidate.getId(), now);
                } else {
                    selfProxy.finalizeExpiredAttemptTransactional(candidate.getId(), now);
                }
            } catch (RuntimeException failure) {
                log.error("Failed to finalize expired attempt {}; continuing timeout batch", candidate.getId(), failure);
            }
        }
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void finalizeExpiredAttemptTransactional(String attemptId, Instant now) {
        attemptRepository.findByIdForUpdate(attemptId).ifPresent(locked -> {
            if (!"IN_PROGRESS".equalsIgnoreCase(locked.getStatus())) return;
            Exam exam = examRepository.findById(locked.getExamId()).orElse(null);
            if (exam == null) return;
            boolean attemptExpired = locked.getEndsAt() != null && !now.isBefore(locked.getEndsAt());
            boolean examClosed = exam.getScheduleEnd() != null && !now.isBefore(exam.getScheduleEnd());
            if (attemptExpired || examClosed) finalizeTimeoutAttempt(locked, exam);
        });
    }

    private Set<String> getValidQuestionIdsForAttempt(ExamAttempt attempt) {
        if (attempt.getQuestionSnapshotJson() != null && !attempt.getQuestionSnapshotJson().isBlank()) {
            List<QuestionDto> dtos = parseQuestionSnapshot(attempt.getQuestionSnapshotJson());
            if (dtos != null && !dtos.isEmpty()) {
                return dtos.stream().map(QuestionDto::getId).collect(Collectors.toSet());
            }
        }
        throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Bản chụp câu hỏi của bài thi không tồn tại");
    }

    private List<Question> gradingQuestionsFor(ExamAttempt attempt) {
        if (attempt.getGradingSnapshotJson() == null || attempt.getGradingSnapshotJson().isBlank()) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Bản chụp chấm điểm của bài thi không tồn tại");
        }
        try {
            return objectMapper.readValue(attempt.getGradingSnapshotJson(), new TypeReference<List<Question>>() {});
        } catch (Exception e) {
            log.error("Failed to parse grading snapshot for attempt {}", attempt.getId(), e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Không thể tải bản chụp chấm điểm của bài thi");
        }
    }

    private List<QuestionDto> parseQuestionSnapshot(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<List<QuestionDto>>() {});
        } catch (Exception e) {
            log.error("Failed to parse question snapshot JSON: {}", e.getMessage());
            return null;
        }
    }
}
