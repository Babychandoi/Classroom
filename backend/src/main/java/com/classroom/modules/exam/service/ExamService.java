package com.classroom.modules.exam.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.common.ReorderRequests;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.exam.dto.*;
import com.classroom.modules.exam.model.*;
import com.classroom.modules.exam.policy.ExamAudiencePolicy;
import com.classroom.modules.exam.policy.ExamScoringPolicy;
import com.classroom.modules.exam.repository.*;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.policy.ProfileVisibilityPolicy;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.outbox.service.OutboxService;
import com.classroom.modules.ranking.service.LeaderboardService;
import com.classroom.modules.segment.model.Segment;
import com.classroom.modules.segment.repository.SegmentRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
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

    /**
     * R19-04: what a previewer without answer-key access is told instead of a score. A preview attempt has no
     * attempt limit, so revealing its score / per-question points to someone who may not read the answer key would
     * let them recover the key by resubmitting (an oracle); result surfaces show this notice instead.
     */
    public static final String PREVIEW_RESULT_HIDDEN_NOTICE = "Chế độ xem thử: không hiển thị điểm/đáp án cho quyền của bạn";

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
    private final UserRepository userRepository;
    private final ProfileVisibilityPolicy profileVisibilityPolicy;

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
                       OutboxService outboxService,
                       UserRepository userRepository,
                       ProfileVisibilityPolicy profileVisibilityPolicy) {
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
        this.userRepository = userRepository;
        this.profileVisibilityPolicy = profileVisibilityPolicy;
    }

    @Transactional(readOnly = true)
    public List<ExamDto> getExamsByClass(String classId, String userId) {
        if (userId != null) {
            accessPolicy.enforceMember(userId, classId);
        }

        List<Exam> exams = examRepository.findByClassIdOrderByCreatedAtDesc(classId);
        List<ExamDto> dtos = new ArrayList<>();
        Instant now = Instant.now();

        var eligibility = audiencePolicy.listingEligibility(userId, classId, exams, now);
        Map<String, Long> questionCounts = new HashMap<>();
        if (!exams.isEmpty()) for (var count : questionRepository.countByExamIds(exams.stream().map(Exam::getId).toList()))
            questionCounts.put(count.getExamId(), count.getQuestions());
        for (Exam e : exams) {
            // R7-02: draft visibility mirrors LearningPolicy.canViewUnpublished — staff holding
            // EXAM:CREATE/EDIT/PUBLISH (class-wide, or scoped to this exam's targetCourseId) must
            // see their own draft exams in the list, not just staff with EXAM:VIEW.
            // Hide unpublished / draft exams from regular students (Finding 3)
            // Published metadata does not use authoring grants. Avoid several permission queries
            // per published exam while keeping the exact same draft visibility boundary.
            if ("DRAFT".equalsIgnoreCase(e.getStatus())
                    && (userId == null || !canViewDraftExam(userId, classId, e))) {
                continue;
            }

            var entry = eligibility.getOrDefault(e.getId(), new ExamAudiencePolicy.EntryState(false, 0));

            // Safe metadata only, omit question inventory from list (Finding 1, 3)
            ExamDto dto = toExamDto(e, false, false, questionCounts.getOrDefault(e.getId(), 0L));
            dto.setCanEnter(entry.canEnter());
            dto.setUserAttemptsCount(entry.attempts());
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
        // R7-02: mirrors LearningPolicy.canViewUnpublished — VIEW alone must not be the only grant
        // that unlocks a staff member's own draft exam; CREATE/EDIT/PUBLISH (class-wide or scoped
        // to this exam's targetCourseId) authors it and must also see it.
        boolean canViewManage = isOwner || canViewDraftExam(userId, exam.getClassId(), exam);

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
        dto.setUserAttemptsCount(attemptRepository.countAttemptsTowardLimit(examId, userId));

        return dto;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ExamAttemptDto startAttempt(String examId, String userId, boolean isPreview) {
        return startAttempt(examId, userId, isPreview, false);
    }

    /**
     * R8-02: {@code resumeOnly} lets the attempt page's mount-time check resume an existing
     * IN_PROGRESS attempt without ever creating a new one. A student who reloads the attempt page
     * after already submitting (or with no attempt at all) must see a 404 here rather than silently
     * burning a fresh attempt — the page falls back to an explicit "Bắt đầu làm bài" action that
     * calls this same endpoint with {@code resumeOnly=false}.
     */
    /**
     * R20-06: READ_COMMITTED + two narrow locks instead of one exclusive lock on the exam row for the whole start.
     *
     * <p>The exam row used to be taken {@code FOR UPDATE}, so N students pressing "Bắt đầu" at the same moment
     * queued behind each other <em>while every one of them held a pooled connection</em> (200 simultaneous starts:
     * p95 8.1 s, 70 s of cumulative lock wait). Now:</p>
     * <ul>
     *   <li>the exam row is read {@code FOR SHARE}: starts do not exclude each other, but an author's close / edit / publish
     *   (exclusive) still waits for in-flight starts and the next start re-reads the new status, exactly as before;</li>
     *   <li>the attempt-limit / attempt-number / "resume the running attempt" decision is serialised per <b>(exam, user)</b>
     *   through one row of {@code exam_user_locks} (see {@link ExamAttemptRepository#lockUserScope}), so a double click, two
     *   tabs or a retry from the same student cannot create two attempts or exceed the limit, while different students never
     *   contend. {@code uq_ea_exam_user_attempt} stays as the last line of defence;</li>
     *   <li>READ_COMMITTED makes every read after the locks see what the previous holder of the per-user lock committed.</li>
     * </ul>
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ExamAttemptDto startAttempt(String examId, String userId, boolean isPreview, boolean resumeOnly) {
        Instant now = Instant.now();

        // Shared (not exclusive) lock on the exam row; fails closed on lookup errors (Review 19 Finding 4).
        Exam exam = examRepository.findByIdForShare(examId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy kỳ thi"));
        // Serialise this learner's starts of this exam (and only theirs).
        attemptRepository.lockUserScope(examId, userId);

        // If preview is requested, strictly validate caller is OWNER or staff with EXAM:PREVIEW or EXAM:EDIT
        if (isPreview) {
            boolean isOwner = accessPolicy.isOwner(userId, exam.getClassId());
            boolean canPreview = accessPolicy.canManage(userId, exam.getClassId(), "EXAM", "PREVIEW", exam.getTargetCourseId())
                    || accessPolicy.canManage(userId, exam.getClassId(), "EXAM", "EDIT", exam.getTargetCourseId());
            if (!isOwner && !canPreview) {
                throw new AppException(ErrorCode.STAFF_PERMISSION_DENIED, "Chỉ chủ lớp hoặc nhân sự có quyền xem trước kỳ thi mới được thực hiện preview");
            }
            audiencePolicy.enforceEnterExam(userId, exam, now, true, false);

            // R14-15: "Chạy thử" must produce exactly ONE preview attempt per user+exam at a time.
            // The Studio button and the attempt page used to each call this endpoint, creating two
            // (the first one stranded IN_PROGRESS). An existing, unexpired IN_PROGRESS preview
            // attempt is resumed instead - as long as the exam content it snapshotted is still what
            // the exam contains now (an author editing a DRAFT question and re-running the preview
            // must see the edit, so a stale snapshot is retired and a fresh preview is started).
            Optional<ExamAttemptDto> resumedPreview = resumeExistingPreviewAttempt(exam, userId, now);
            if (resumedPreview.isPresent()) {
                return resumedPreview.get();
            }
        } else {
            // Classroom personnel must use the isolated preview path. Otherwise OWNER/STAFF
            // membership in an ALL audience would create a normal ranked student attempt.
            if (accessPolicy.isOwner(userId, exam.getClassId())
                    || accessPolicy.isActiveStaff(userId, exam.getClassId())) {
                throw new AppException(ErrorCode.FORBIDDEN, "Nhân sự lớp học chỉ có thể làm bài thi ở chế độ xem trước");
            }
            // Check for existing active IN_PROGRESS attempt first before enforcing attempt limit (Round 8 Finding 1)
            // R2-04: exclude preview attempts so a former staff member demoted to student cannot
            // resume their own old preview attempt here (it would then fail audience checks).
            Optional<ExamAttempt> inProgressOpt = attemptRepository
                    .findFirstByExamIdAndUserIdAndStatusAndIsPreviewFalseOrderByStartedAtDesc(examId, userId, "IN_PROGRESS");
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
                    if (questionDtos != null && !questionDtos.isEmpty()) {
                        dto.setQuestions(questionDtos);
                        return dto;
                    }
                    // R19-05: an attempt with no usable question snapshot (created while the exam had no questions)
                    // can never be answered or submitted - it used to sit IN_PROGRESS answering every call with a
                    // 500. Retire it instead of stranding the learner: CANCELLED attempts do not count toward the
                    // limit, so the checks below either start a proper attempt or refuse with a clear message.
                    log.warn("Cancelling attempt {} of user {} on exam {}: it has no usable question snapshot",
                            existing.getId(), userId, examId);
                    existing.setStatus("CANCELLED");
                    existing.setCancelReason("Lượt làm bài không có câu hỏi; đã được hủy và không tính vào số lượt");
                    attemptRepository.save(existing);
                } else {
                    // Past deadline or past schedule end: finalize, auto-grade and publish deterministically
                    finalizeTimeoutAttempt(existing, exam);
                    // Return normally so the enclosing transaction commits finalization. Throwing
                    // an eligibility error here would roll the timeout updates back.
                    return studentSafeDto(toAttemptDto(existing, exam.getTitle()));
                }
            }

            // R8-02: resumeOnly must never create a new attempt. No IN_PROGRESS attempt was found
            // to resume (or finalize) above, so there is nothing to return — surface a 404 and let
            // the caller fall back to the explicit "Bắt đầu làm bài" action.
            if (resumeOnly) {
                throw new AppException(ErrorCode.NOT_FOUND, "Không có lượt làm bài đang diễn ra để tiếp tục");
            }

            // No active attempt to resume (both branches above always return when one exists):
            // verify full eligibility to start a NEW attempt.
            audiencePolicy.enforceEnterExam(userId, exam, now, false, false);
        }

        // R19-05: never create an attempt (student OR preview) for an exam without questions. Such an attempt has
        // an empty snapshot, so autosave/submit/resume all fail with a 500 and it stays IN_PROGRESS until it times
        // out - burning the learner's try. publishExam already requires >= 1 question; this also covers exams that
        // reach PUBLISHED without it (seed data, imports, questions deleted afterwards). Checked before anything
        // is written, so a refusal leaves no attempt behind.
        List<Question> questions = questionRepository.findByExamIdOrderByPositionAsc(examId);
        if (questions.isEmpty()) {
            throw new AppException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "Kỳ thi chưa có câu hỏi nên chưa thể bắt đầu làm bài. Vui lòng liên hệ giảng viên của lớp.");
        }

        int attemptNumber = 1;
        if (!isPreview) {
            // R14-14: CANCELLED attempts do not count against the limit ...
            long existingAttempts = attemptRepository.countAttemptsTowardLimit(examId, userId);
            if (existingAttempts >= exam.getAttemptLimit()) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Bạn đã hết lượt tham gia kỳ thi này (tối đa " + exam.getAttemptLimit() + " lượt)");
            }
            // ... but they keep their attempt_number (unique per exam+user), so the next number
            // continues after the highest one ever used rather than after the counted attempts.
            attemptNumber = (int) Math.max(existingAttempts, attemptRepository.findMaxAttemptNumber(examId, userId)) + 1;
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

        // Snapshot question set (loaded and checked non-empty above)
        List<QuestionDto> questionDtos = toQuestionDtos(questions, false);

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
            // R2-04: same preview exclusion as the initial lookup above - this fallback only ever
            // runs on the non-preview (student) path.
            Optional<ExamAttempt> inProgressOpt = attemptRepository
                    .findFirstByExamIdAndUserIdAndStatusAndIsPreviewFalseOrderByStartedAtDesc(examId, userId, "IN_PROGRESS");
            if (inProgressOpt.isPresent() && now.isBefore(inProgressOpt.get().getEndsAt())) {
                ExamAttempt existing = inProgressOpt.get();
                audiencePolicy.enforceResumeAttempt(userId, exam, existing, now);
                ExamAttemptDto resumeDto = toAttemptDto(existing, exam.getTitle());
                resumeDto.setQuestions(parseQuestionSnapshot(existing.getQuestionSnapshotJson()));
                return resumeDto;
            }
            throw new AppException(ErrorCode.BAD_REQUEST, "Bạn đã hết lượt tham gia kỳ thi này hoặc lượt thi đang được khởi tạo");
        }

        // a brand-new attempt has no answers yet: no read-back query
        ExamAttemptDto dto = toAttemptDto(saved, exam.getTitle(), List.of());
        dto.setQuestions(questionDtos);
        return dto;
    }

    /**
     * R14-15: returns the caller's still-running preview attempt for {@code exam} (with its question
     * snapshot and autosaved answers) when it can be reused, otherwise empty so the caller creates a
     * new one. A preview attempt is reusable when it is IN_PROGRESS, has not reached its own
     * deadline / the exam's schedule end, and the questions it snapshotted equal the exam's current
     * questions. An expired one is finalized (like the student timeout path); one whose snapshot
     * went stale because the DRAFT exam was edited is CANCELLED so exactly one live preview remains.
     *
     * <p>R15-05: staleness is judged on BOTH snapshots - the learner-safe one (what the previewer
     * sees) and the grading one (the questions serialized with their answer keys, which is what the
     * preview is scored against). Comparing only the learner-safe view missed an answer-key-only
     * edit: the old preview was resumed and graded against the outdated key.</p>
     */
    private Optional<ExamAttemptDto> resumeExistingPreviewAttempt(Exam exam, String userId, Instant now) {
        Optional<ExamAttempt> existingOpt = attemptRepository
                .findFirstByExamIdAndUserIdAndStatusAndIsPreviewTrueOrderByStartedAtDesc(exam.getId(), userId, "IN_PROGRESS");
        if (existingOpt.isEmpty()) return Optional.empty();
        ExamAttempt existing = existingOpt.get();

        boolean withinAttemptDuration = existing.getEndsAt() != null && now.isBefore(existing.getEndsAt());
        boolean withinExamSchedule = exam.getScheduleEnd() == null || now.isBefore(exam.getScheduleEnd());
        if (!withinAttemptDuration || !withinExamSchedule) {
            finalizeTimeoutAttempt(existing, exam);
            return Optional.empty();
        }

        List<Question> currentQuestions = questionRepository.findByExamIdOrderByPositionAsc(exam.getId());
        List<QuestionDto> current = toQuestionDtos(currentQuestions, false);
        List<QuestionDto> snapshot = parseQuestionSnapshot(existing.getQuestionSnapshotJson());
        String currentJson;
        String currentGradingJson;
        try {
            currentJson = objectMapper.writeValueAsString(current);
            // Same serialization as the snapshot taken in startAttempt (entities incl. answerKey).
            currentGradingJson = objectMapper.writeValueAsString(currentQuestions);
        } catch (Exception e) {
            currentJson = null;
            currentGradingJson = null;
        }
        if (snapshot == null || currentJson == null || currentGradingJson == null
                || !currentJson.equals(existing.getQuestionSnapshotJson())
                || !currentGradingJson.equals(existing.getGradingSnapshotJson())) {
            existing.setStatus("CANCELLED");
            existing.setCancelReason("Đề thi đã thay đổi; lượt xem thử cũ được thay bằng lượt mới");
            attemptRepository.save(existing);
            return Optional.empty();
        }

        log.info("Resuming existing preview attempt {} for user {} on exam {}", existing.getId(), userId, exam.getId());
        ExamAttemptDto dto = toAttemptDto(existing, exam.getTitle());
        dto.setQuestions(snapshot);
        return Optional.of(dto);
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
        } else {
            // R19-04: a preview attempt outlives the staff grant that created it - re-check at use time.
            enforcePreviewStillAllowed(examRepository.findById(attempt.getExamId()).orElse(null), userId);
        }

        if (!"IN_PROGRESS".equalsIgnoreCase(attempt.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Không thể lưu đáp án cho bài thi đã kết thúc");
        }

        // Validate attempt deadline (Finding 4)
        if (Instant.now().isAfter(attempt.getEndsAt())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Thời gian làm bài thi đã kết thúc");
        }

        // R20-06: the attempt's answers are loaded ONCE and upserted in memory (was: one SELECT per submitted question,
        // ~17 statements per autosave). The attempt row is locked FOR UPDATE above, so concurrent saves/submits of the same
        // attempt are serialised and uq_aa_attempt_question (V32) is only a backstop.
        List<AttemptAnswer> stored = attemptAnswerRepository.findByAttemptId(attempt.getId());
        if (answers != null && !answers.isEmpty()) {
            // Validate question membership against immutable attempt snapshot
            Set<String> validQuestionIds = getValidQuestionIdsForAttempt(attempt);
            validateAnswerPayload(answers, validQuestionIds, "Câu hỏi không hợp lệ cho đề thi này: ");
            stored = upsertAnswers(attempt.getId(), answers, stored);
        }

        Exam exam = examRepository.findById(attempt.getExamId()).orElse(null);
        String title = (exam != null) ? exam.getTitle() : "Kỳ thi";
        return toAttemptDto(attempt, title, stored);
    }

    /** Upper bound for one answer (essay text). 10 000 characters stay under the TEXT column limit even at 4 bytes per character. */
    public static final int MAX_ANSWER_CHARS = 10_000;

    /**
     * Size/authorisation limits shared by autosave and the answers carried by the submit request: every key must be a question
     * of THIS attempt's immutable snapshot (so the map can never exceed the exam's question count) and no value may exceed
     * {@link #MAX_ANSWER_CHARS}.
     */
    private void validateAnswerPayload(Map<String, String> answers, Set<String> validQuestionIds, String invalidQuestionMessage) {
        if (answers.size() > validQuestionIds.size()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Số câu trả lời vượt quá số câu hỏi của đề thi");
        }
        for (Map.Entry<String, String> entry : answers.entrySet()) {
            String qId = entry.getKey();
            if (qId == null || !validQuestionIds.contains(qId)) {
                throw new AppException(ErrorCode.BAD_REQUEST, invalidQuestionMessage + qId);
            }
            if (entry.getValue() != null && entry.getValue().length() > MAX_ANSWER_CHARS) {
                throw new AppException(ErrorCode.BAD_REQUEST,
                        "Câu trả lời quá dài (tối đa " + MAX_ANSWER_CHARS + " ký tự): " + qId);
            }
        }
    }

    /**
     * Applies {@code incoming} (questionId -> answer) on top of the attempt's stored answers without a query per question:
     * an existing row is updated in place (dirty checking writes it only when the text changed), a missing one is inserted.
     * Returns the attempt's full answer list afterwards, so the caller does not have to read it back.
     */
    private List<AttemptAnswer> upsertAnswers(String attemptId, Map<String, String> incoming, List<AttemptAnswer> stored) {
        Map<String, AttemptAnswer> byQuestion = new LinkedHashMap<>();
        for (AttemptAnswer a : stored) {
            byQuestion.putIfAbsent(a.getQuestionId(), a); // legacy duplicate rows (pre V32): the first one wins, like grading does
        }
        for (Map.Entry<String, String> entry : incoming.entrySet()) {
            AttemptAnswer existing = byQuestion.get(entry.getKey());
            if (existing == null) {
                AttemptAnswer created = new AttemptAnswer(attemptId, entry.getKey(), entry.getValue());
                attemptAnswerRepository.save(created);
                byQuestion.put(entry.getKey(), created);
            } else if (!Objects.equals(existing.getStudentAnswer(), entry.getValue())) {
                existing.setStudentAnswer(entry.getValue());
                attemptAnswerRepository.save(existing);
            }
        }
        return new ArrayList<>(byQuestion.values());
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
        } else {
            // R19-04: a preview attempt outlives the staff grant that created it - re-check at use time.
            enforcePreviewStillAllowed(examRepository.findById(attempt.getExamId()).orElse(null), userId);
        }

        // Idempotency: if already submitted, return the existing state without repeating grading or scores
        if (!"IN_PROGRESS".equalsIgnoreCase(attempt.getStatus())) {
            Exam exam = examRepository.findById(attempt.getExamId()).orElse(null);
            String title = (exam != null) ? exam.getTitle() : "Kỳ thi";
            return withPreviewScoringHiddenIfNeeded(studentSafeDto(toAttemptDto(attempt, title)), attempt, exam, userId);
        }

        Instant now = Instant.now();
        // At or after the authoritative deadline, grade only answers persisted by autosave.
        // Never accept request-body answers beyond the deadline (including network grace).
        if (!now.isBefore(attempt.getEndsAt())) {
            log.warn("Attempt {} submitted after deadline {}", attemptId, attempt.getEndsAt());
            Exam exam = examRepository.findById(attempt.getExamId()).orElse(null);
            finalizeTimeoutAttempt(attempt, exam);
            return withPreviewScoringHiddenIfNeeded(
                    studentSafeDto(toAttemptDto(attempt, exam != null ? exam.getTitle() : "Kỳ thi")), attempt, exam, userId);
        }

        attempt.setSubmittedAt(now);

        List<Question> questions = gradingQuestionsFor(attempt);
        Set<String> validQuestionIds = getValidQuestionIdsForAttempt(attempt);

        // R20-06: one read of the stored answers, request-body answers upserted in memory (see saveAnswers).
        List<AttemptAnswer> allAnswers = attemptAnswerRepository.findByAttemptId(attempt.getId());
        if (now.isBefore(attempt.getEndsAt()) && request != null && request.getAnswers() != null) {
            validateAnswerPayload(request.getAnswers(), validQuestionIds, "Câu hỏi không thuộc đề thi này: ");
            allAnswers = upsertAnswers(attempt.getId(), request.getAnswers(), allAnswers);
        }

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
        return withPreviewScoringHiddenIfNeeded(studentSafeDto(toAttemptDto(saved, title, allAnswers)), saved, exam, userId);
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

        // R13-07: correcting an already-PUBLISHED result requires an explicit, non-blank reason —
        // recorded in the audit trail alongside the before/after scores below. First-time grading of
        // a SUBMITTED/GRADING attempt (not yet published) does not require one.
        boolean isCorrectingPublished = "PUBLISHED".equalsIgnoreCase(attempt.getStatus());
        String reason = request.getReason() == null ? null : request.getReason().trim();
        if (isCorrectingPublished) {
            if (reason == null || reason.isBlank()) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Sửa điểm bài thi đã công bố cần nêu rõ lý do");
            }
            if (reason.length() > 1000) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Lý do sửa điểm tối đa 1000 ký tự");
            }
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
            return withPreviewScoringHiddenIfNeeded(toAttemptDto(saved, partialTitle), saved, partialExam, currentUserId);
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
            if (isCorrectingPublished) {
                auditDetails.put("reason", reason);
            }
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
        return withPreviewScoringHiddenIfNeeded(toAttemptDto(saved, title), saved, exam, currentUserId);
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
        List<ExamAttempt> attempts = attemptRepository.findByClassIdAndStatusInAndIsPreviewFalseOrderBySubmittedAtAsc(
                classId, List.of("SUBMITTED", "GRADING"));
        return toGradingDtos(attempts, currentUserId, classId);
    }

    /**
     * R13-07 "Kết quả đã công bố": PUBLISHED attempts for one exam, so StudioGrading can offer a
     * "Sửa điểm" action on an already-published result. Scoped to the caller's EXAM:GRADE grant on
     * this exam exactly like every other grading endpoint, capped to a page so a long-running exam
     * cannot return an unbounded list.
     */
    @Transactional(readOnly = true)
    public List<ExamAttemptDto> getPublishedAttempts(String examId, String currentUserId, int limit) {
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy kỳ thi"));
        accessPolicy.enforceManage(currentUserId, exam.getClassId(), "EXAM", "GRADE", resolveRbacCourseScope(exam));
        int safeLimit = Math.max(1, Math.min(limit, 200));
        Pageable page = PageRequest.of(0, safeLimit);
        List<ExamAttempt> attempts = attemptRepository
                .findByExamIdAndStatusAndIsPreviewFalseOrderBySubmittedAtDesc(examId, "PUBLISHED", page);
        return toGradingDtos(attempts, currentUserId, exam.getClassId());
    }

    /**
     * R13-13: batch-loads the distinct exams referenced by a page of attempts (one findAllById
     * query, never one findById per row) and resolves each learner's display name through
     * {@link ProfileVisibilityPolicy} — mirrors AssignmentService.toDtos's batching/anonymization
     * pattern for the assignment grading queue. The caller has already been authorized (by
     * getGradingQueue/getPublishedAttempts) as a class administrator, so ProfileVisibilityPolicy
     * grants them visibility into a PRIVATE learner's name for this class; when it still denies
     * visibility for some other reason, a stable per-batch ordinal label is used instead of the
     * shared generic string, so distinct anonymized learners remain distinguishable in the queue.
     */
    private List<ExamAttemptDto> toGradingDtos(List<ExamAttempt> attempts, String currentUserId, String classId) {
        if (attempts.isEmpty()) return List.of();

        List<String> examIds = attempts.stream().map(ExamAttempt::getExamId).distinct().toList();
        Map<String, Exam> examById = new HashMap<>();
        examRepository.findAllById(examIds).forEach(e -> examById.put(e.getId(), e));

        List<String> userIds = attempts.stream().map(ExamAttempt::getUserId).distinct().toList();
        Map<String, User> userById = new HashMap<>();
        userRepository.findAllById(userIds).forEach(u -> userById.put(u.getId(), u));

        Map<String, Integer> anonymousOrdinalByUserId = new HashMap<>();
        int[] nextOrdinal = {1};

        List<ExamAttemptDto> result = new ArrayList<>();
        for (ExamAttempt attempt : attempts) {
            Exam exam = examById.get(attempt.getExamId());
            if (exam == null || !accessPolicy.canManage(currentUserId, classId, "EXAM", "GRADE", exam.getTargetCourseId())) continue;
            ExamAttemptDto dto = toAttemptDto(attempt, exam.getTitle());

            User learner = userById.get(attempt.getUserId());
            String displayName;
            if (learner != null && profileVisibilityPolicy.isIdentityVisible(learner, currentUserId, classId)) {
                displayName = learner.getFullName();
            } else {
                int ordinal = anonymousOrdinalByUserId.computeIfAbsent(attempt.getUserId(), id -> nextOrdinal[0]++);
                displayName = "Học viên ẩn danh #" + ordinal;
            }
            dto.setLearnerDisplayName(displayName);
            result.add(dto);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public ExamAttemptDto getGradingAttempt(String attemptId, String currentUserId) {
        ExamAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài thi"));
        Exam exam = examRepository.findById(attempt.getExamId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy kỳ thi"));
        accessPolicy.enforceManage(currentUserId, attempt.getClassId(), "EXAM", "GRADE", resolveRbacCourseScope(exam));
        // R13-07: PUBLISHED is also loadable here so an authorized grader can open an already
        // published result for correction ("Sửa điểm"), in addition to the original SUBMITTED/
        // GRADING grading-queue flow.
        Set<String> loadableStatuses = Set.of("SUBMITTED", "GRADING", "PUBLISHED");
        if (attempt.isPreview() || !loadableStatuses.contains(attempt.getStatus().toUpperCase(Locale.ROOT))) {
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
        if (attempt.isPreview()) {
            // R19-04: a preview attempt belongs to the person who ran it and to nobody else, and only while they
            // may still preview this exam (the grant that allowed it can have been removed since).
            if (!isOwner) {
                throw new AppException(ErrorCode.FORBIDDEN, "Bạn không có quyền xem kết quả bài thi này");
            }
            enforcePreviewStillAllowed(exam, userId);
        }
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
        ExamAttemptDto dto = withPreviewScoringHiddenIfNeeded(toAttemptDto(attempt, title), attempt, exam, userId);

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
        // R19-04: preview attempts are listed only while the caller may still preview this exam (someone whose staff
        // grant was removed no longer sees them), and their scoring is hidden unless the caller may read the
        // answer key. Evaluated once per request, and only if the list actually contains a preview.
        boolean hasPreview = attempts.stream().anyMatch(ExamAttempt::isPreview);
        boolean previewAllowed = hasPreview && canPreviewExam(userId, exam);
        boolean previewScoringVisible = previewAllowed && canSeePreviewScoring(userId, exam);
        return attempts.stream().filter(a -> !a.isPreview() || previewAllowed).map(a -> {
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
            return a.isPreview() && !previewScoringVisible ? hidePreviewScoring(dto) : dto;
        }).toList();
    }

    /**
     * R7-02: whether {@code userId} may view/manage {@code exam} including while it is DRAFT —
     * mirrors LearningPolicy.canViewUnpublished's reasoning for courses: any of the staff grants
     * that let someone author or publish the exam (VIEW, EDIT, CREATE, PUBLISH) must also let them
     * see it in list/detail views, evaluated at the same course-scope resolution used everywhere
     * else for this exam (resolveRbacCourseScope: only COURSE/COURSE_SEGMENT audience exams carry
     * a course scope, so a class-wide grant is required for ALL/PRO/SEGMENT exams).
     */
    private boolean canViewDraftExam(String userId, String classId, Exam exam) {
        String scope = resolveRbacCourseScope(exam);
        return accessPolicy.canManage(userId, classId, "EXAM", "VIEW", scope)
                || accessPolicy.canManage(userId, classId, "EXAM", "EDIT", scope)
                || accessPolicy.canManage(userId, classId, "EXAM", "CREATE", scope)
                || accessPolicy.canManage(userId, classId, "EXAM", "PUBLISH", scope);
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

    /**
     * R13-03 (SRS §5 exam state machine): update exam config (title/description/schedule/
     * duration/attempt limit/audience scope) is only allowed while DRAFT. After PUBLISH the spec
     * says edits should follow the state machine — for this platform that means no config edits
     * post-publish (only close/archive transitions and question answer-key corrections via
     * gradeAttempt's regrade path), since audience eligibility and question snapshots are already
     * frozen into every attempt at start time and silently changing them would desync attempts
     * already in flight.
     */
    @Transactional
    public Exam updateExam(String examId, Exam patch, String currentUserId) {
        Exam exam = examRepository.findByIdForUpdate(examId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy kỳ thi"));
        accessPolicy.enforceManage(currentUserId, exam.getClassId(), "EXAM", "EDIT", resolveRbacCourseScope(exam));
        if (!"DRAFT".equalsIgnoreCase(exam.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Chỉ có thể sửa cấu hình kỳ thi ở trạng thái DRAFT");
        }
        if (patch.getTitle() != null) {
            if (patch.getTitle().isBlank()) throw new AppException(ErrorCode.BAD_REQUEST, "Tên kỳ thi không được để trống");
            exam.setTitle(patch.getTitle());
        }
        if (patch.getDescription() != null) exam.setDescription(patch.getDescription());
        // R16-06: the schedule is a full replace on a DRAFT exam - a null start/end means "unscheduled"
        // (before, a null was ignored, so a schedule could never be cleared once set). The Studio form
        // always sends both fields.
        exam.setScheduleStart(patch.getScheduleStart());
        exam.setScheduleEnd(patch.getScheduleEnd());
        if (patch.getDurationMinutes() > 0) exam.setDurationMinutes(patch.getDurationMinutes());
        if (patch.getAttemptLimit() > 0) exam.setAttemptLimit(patch.getAttemptLimit());
        if (patch.getPassScore() > 0) exam.setPassScore(patch.getPassScore());

        if (exam.getScheduleStart() != null && exam.getScheduleEnd() != null
                && !exam.getScheduleEnd().isAfter(exam.getScheduleStart())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Lịch thi không hợp lệ: kết thúc phải sau bắt đầu");
        }

        exam.setUpdatedAt(Instant.now());
        Exam saved = examRepository.save(exam);
        auditService.record(exam.getClassId(), currentUserId, "EXAM_UPDATE", "EXAM", examId,
                String.format("{\"title\":\"%s\"}", saved.getTitle()));
        return saved;
    }

    /**
     * R13-03/R14-05: close a live exam early (PUBLISHED/OPEN -> CLOSED). No new attempts may start
     * once closed. Any IN_PROGRESS attempt that started before {@code closedAt} is left alone: it
     * can still be resumed (ExamAudiencePolicy.enforceResumeAttempt), autosaved and submitted, and
     * finalizes normally at its own endsAt via the existing timeout scan, so a student mid-attempt
     * is not cut off mid-answer by an administrative action (decision D-13).
     */
    @Transactional
    public Exam closeExam(String examId, String currentUserId) {
        Exam exam = examRepository.findByIdForUpdate(examId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy kỳ thi"));
        accessPolicy.enforceManage(currentUserId, exam.getClassId(), "EXAM", "PUBLISH", resolveRbacCourseScope(exam));
        if (!Set.of("PUBLISHED", "OPEN").contains(exam.getStatus().toUpperCase(Locale.ROOT))) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Chỉ có thể đóng kỳ thi đang mở (PUBLISHED/OPEN)");
        }
        exam.setStatus("CLOSED");
        exam.setClosedAt(Instant.now());
        exam.setUpdatedAt(Instant.now());
        Exam saved = examRepository.save(exam);
        auditService.record(exam.getClassId(), currentUserId, "EXAM_CLOSE", "EXAM", examId, "{}");
        return saved;
    }

    /** R13-03: archive a closed exam (CLOSED -> ARCHIVED); terminal, hides it from active listings. */
    @Transactional
    public Exam archiveExam(String examId, String currentUserId) {
        Exam exam = examRepository.findByIdForUpdate(examId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy kỳ thi"));
        accessPolicy.enforceManage(currentUserId, exam.getClassId(), "EXAM", "PUBLISH", resolveRbacCourseScope(exam));
        if (!"CLOSED".equalsIgnoreCase(exam.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Chỉ có thể lưu trữ kỳ thi đã đóng (CLOSED)");
        }
        exam.setStatus("ARCHIVED");
        exam.setUpdatedAt(Instant.now());
        Exam saved = examRepository.save(exam);
        auditService.record(exam.getClassId(), currentUserId, "EXAM_ARCHIVE", "EXAM", examId, "{}");
        return saved;
    }

    /**
     * R13-03: edit a DRAFT question's text/type/points/options/answer key. Mirrors addQuestion's
     * DRAFT-only guard and total-points cap; the row is locked via the parent exam lock plus a
     * direct re-fetch so a concurrent addQuestion cannot race the total-points check.
     */
    // R19-01(c): READ_COMMITTED. The question must be read to learn which exam to lock, and at MySQL's default
    // REPEATABLE READ that plain read froze the snapshot BEFORE the exam lock was taken - so the total-points sum
    // below (and the "question still exists" check) came from data older than the authoring transaction we had just
    // waited for, letting two concurrent edits each pass a cap they jointly exceed. With READ_COMMITTED every read
    // after the lock sees what the previous holder committed.
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Question updateQuestion(String questionId, Question patch, List<AnswerOption> options, String currentUserId) {
        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy câu hỏi"));
        Exam exam = examRepository.findByIdForUpdate(question.getExamId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy kỳ thi"));
        if (!questionRepository.existsById(questionId)) { // deleted while we waited for the exam lock
            throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy câu hỏi");
        }
        accessPolicy.enforceManage(currentUserId, exam.getClassId(), "EXAM", "EDIT", resolveRbacCourseScope(exam));
        if (!"DRAFT".equalsIgnoreCase(exam.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Không thể sửa câu hỏi sau khi kỳ thi được công bố");
        }
        validateQuestion(patch, options);

        long currentTotal = 0;
        for (Question q : questionRepository.findByExamIdOrderByPositionAsc(exam.getId())) {
            if (q.getId().equals(questionId)) continue;
            currentTotal = Math.addExact(currentTotal, (long) q.getPoints());
        }
        if (Math.addExact(currentTotal, (long) patch.getPoints()) > MAX_EXAM_TOTAL_POINTS) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Tổng điểm kỳ thi sau khi sửa câu hỏi sẽ vượt quá giới hạn tối đa (" + MAX_EXAM_TOTAL_POINTS + ")");
        }

        question.setQuestionText(patch.getQuestionText());
        question.setType(patch.getType());
        question.setPoints(patch.getPoints());
        question.setAnswerKey(patch.getAnswerKey());
        Question saved = questionRepository.save(question);

        optionRepository.deleteAll(optionRepository.findByQuestionIdOrderByPositionAsc(questionId));
        if (options != null) {
            for (AnswerOption opt : options) {
                opt.setId(UUID.randomUUID().toString());
                opt.setQuestionId(saved.getId());
                optionRepository.save(opt);
            }
        }
        auditService.record(exam.getClassId(), currentUserId, "EXAM_QUESTION_UPDATE", "QUESTION", questionId, "{}");
        return saved;
    }

    // R19-01(c): READ_COMMITTED, same reason as updateQuestion (plain read of the question precedes the exam lock).
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void deleteQuestion(String questionId, String currentUserId) {
        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy câu hỏi"));
        Exam exam = examRepository.findByIdForUpdate(question.getExamId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy kỳ thi"));
        if (!questionRepository.existsById(questionId)) { // already deleted by the transaction we waited for
            throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy câu hỏi");
        }
        accessPolicy.enforceManage(currentUserId, exam.getClassId(), "EXAM", "EDIT", resolveRbacCourseScope(exam));
        if (!"DRAFT".equalsIgnoreCase(exam.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Không thể xóa câu hỏi sau khi kỳ thi được công bố");
        }
        questionRepository.delete(question);
        auditService.record(exam.getClassId(), currentUserId, "EXAM_QUESTION_DELETE", "QUESTION", questionId, "{}");
    }

    @Transactional
    public void reorderQuestions(String examId, List<String> orderedQuestionIds, String currentUserId) {
        Exam exam = examRepository.findByIdForUpdate(examId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy kỳ thi"));
        accessPolicy.enforceManage(currentUserId, exam.getClassId(), "EXAM", "EDIT", resolveRbacCourseScope(exam));
        if (!"DRAFT".equalsIgnoreCase(exam.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Không thể sắp xếp lại câu hỏi sau khi kỳ thi được công bố");
        }
        List<Question> questions = questionRepository.findByExamIdOrderByPositionAsc(examId);
        Map<String, Question> byId = new LinkedHashMap<>();
        questions.forEach(q -> byId.put(q.getId(), q));
        if (!ReorderRequests.isPermutationOf(orderedQuestionIds, byId.keySet())) { // R16-10: no duplicates
            throw new AppException(ErrorCode.BAD_REQUEST, "Danh sách thứ tự câu hỏi không hợp lệ");
        }
        for (int i = 0; i < orderedQuestionIds.size(); i++) {
            Question q = byId.get(orderedQuestionIds.get(i));
            q.setPosition(i);
            questionRepository.save(q);
        }
    }

    /**
     * R13-03 item 5: staff-initiated cancellation of an invalid attempt (e.g. started in error,
     * suspected cheating pending investigation). Gated on EXAM:GRADE (same permission as grading —
     * cancelling directly affects scoring/leaderboard eligibility) scoped like every other grading
     * action via resolveRbacCourseScope. A CANCELLED attempt is excluded from the leaderboard the
     * same way a preview attempt is (all leaderboard queries already filter isPreview=false AND
     * status='PUBLISHED', so CANCELLED — never PUBLISHED — is naturally excluded without a query
     * change). Only a non-terminal attempt (IN_PROGRESS/SUBMITTED/GRADING) can be cancelled;
     * cancelling an already-PUBLISHED result must go through the same explicit path grading
     * corrections use, so this method refuses that transition and tells the caller so.
     */
    @Transactional
    public ExamAttemptDto cancelAttempt(String attemptId, String currentUserId, String reason) {
        ExamAttempt attempt = attemptRepository.findByIdForUpdate(attemptId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài thi"));
        Exam exam = examRepository.findById(attempt.getExamId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy kỳ thi"));
        accessPolicy.enforceManage(currentUserId, attempt.getClassId(), "EXAM", "GRADE", resolveRbacCourseScope(exam));
        if (!Set.of("IN_PROGRESS", "SUBMITTED", "GRADING").contains(attempt.getStatus().toUpperCase(Locale.ROOT))) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "Chỉ có thể hủy bài thi chưa công bố kết quả. Trạng thái hiện tại: " + attempt.getStatus());
        }
        attempt.setStatus("CANCELLED");
        attempt.setCancelReason(reason);
        ExamAttempt saved = attemptRepository.save(attempt);
        auditService.record(attempt.getClassId(), currentUserId, "EXAM_ATTEMPT_CANCEL", "EXAM_ATTEMPT", attemptId,
                String.format("{\"reason\":\"%s\"}", reason == null ? "" : reason.replace("\"", "'")));
        return withPreviewScoringHiddenIfNeeded(toAttemptDto(saved, exam.getTitle()), saved, exam, currentUserId);
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
        return toExamDto(exam, includeQuestions, includeAnswerKey, questionRepository.countByExamId(exam.getId()));
    }

    private ExamDto toExamDto(Exam exam, boolean includeQuestions, boolean includeAnswerKey, long count) {
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

        dto.setQuestionCount((int) count);

        if (includeQuestions) {
            List<Question> questions = questionRepository.findByExamIdOrderByPositionAsc(exam.getId());
            dto.setQuestions(toQuestionDtos(questions, includeAnswerKey));
        } else {
            dto.setQuestions(null);
        }

        return dto;
    }

    /**
     * R20-06: DTOs for a whole question list with ONE query for all options ({@code IN}), instead of one per question. A
     * 20-question exam used to add 20 SELECTs to every attempt start (x200 simultaneous starts).
     */
    private List<QuestionDto> toQuestionDtos(List<Question> questions, boolean includeAnswerKey) {
        if (questions.isEmpty()) {
            return List.of();
        }
        Map<String, List<AnswerOption>> optionsByQuestion = new HashMap<>();
        for (AnswerOption option : optionRepository.findByQuestionIdInOrderByPositionAsc(
                questions.stream().map(Question::getId).toList())) {
            optionsByQuestion.computeIfAbsent(option.getQuestionId(), k -> new ArrayList<>()).add(option);
        }
        List<QuestionDto> dtos = new ArrayList<>(questions.size());
        for (Question q : questions) {
            dtos.add(toQuestionDto(q, includeAnswerKey, optionsByQuestion.getOrDefault(q.getId(), List.of())));
        }
        return dtos;
    }

    private QuestionDto toQuestionDto(Question q, boolean includeAnswerKey) {
        return toQuestionDto(q, includeAnswerKey, optionRepository.findByQuestionIdOrderByPositionAsc(q.getId()));
    }

    private QuestionDto toQuestionDto(Question q, boolean includeAnswerKey, List<AnswerOption> options) {
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

        List<AnswerOptionDto> optDtos = options.stream()
                .map(o -> new AnswerOptionDto(o.getId(), o.getQuestionId(), o.getOptionKey(), o.getOptionText(), o.getPosition()))
                .toList();
        dto.setOptions(optDtos);

        return dto;
    }

    private ExamAttemptDto toAttemptDto(ExamAttempt attempt, String examTitle) {
        return toAttemptDto(attempt, examTitle, attemptAnswerRepository.findByAttemptId(attempt.getId()));
    }

    /** Same mapping, but from answers the caller already holds - saves the read-back query on the hot autosave/submit paths. */
    private ExamAttemptDto toAttemptDto(ExamAttempt attempt, String examTitle, List<AttemptAnswer> answers) {
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

        List<ExamAttemptDto.AttemptAnswerDto> aDtos = answers.stream()
                .map(a -> new ExamAttemptDto.AttemptAnswerDto(a.getQuestionId(), a.getStudentAnswer(), a.getPointsAwarded(), a.getTeacherFeedback()))
                .toList();
        dto.setAnswers(aDtos);

        return dto;
    }

    /**
     * R19-04: the same rule {@link #startAttempt} applies to open a preview: the class owner, or staff holding
     * EXAM:PREVIEW / EXAM:EDIT for this exam. Evaluated again whenever an existing preview attempt is used or read,
     * because staff access can be removed while a preview attempt is still open.
     */
    private boolean canPreviewExam(String userId, Exam exam) {
        if (exam == null || userId == null) return false;
        return accessPolicy.isOwner(userId, exam.getClassId())
                || accessPolicy.canManage(userId, exam.getClassId(), "EXAM", "PREVIEW", exam.getTargetCourseId())
                || accessPolicy.canManage(userId, exam.getClassId(), "EXAM", "EDIT", exam.getTargetCourseId());
    }

    private void enforcePreviewStillAllowed(Exam exam, String userId) {
        if (exam == null) {
            throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy kỳ thi");
        }
        if (!canPreviewExam(userId, exam)) {
            throw new AppException(ErrorCode.STAFF_PERMISSION_DENIED, "Bạn không còn quyền xem trước kỳ thi này");
        }
    }

    /**
     * R19-04: whether {@code userId} may see the scoring of a PREVIEW attempt - exactly who may see the answer key
     * (the owner, or explicit EXAM:EDIT; wildcards do not count, see {@link AccessPolicy#canAccessAnswerKey}). The
     * score and per-question points of a graded attempt ARE the answer key: preview attempts have no limit, so
     * anyone allowed to read them could resubmit different answers and read the key back off the results.
     */
    private boolean canSeePreviewScoring(String userId, Exam exam) {
        if (exam == null || userId == null) return false;
        return accessPolicy.isOwner(userId, exam.getClassId())
                || accessPolicy.canAccessAnswerKey(userId, exam.getClassId(), exam.getTargetCourseId());
    }

    /** Hides score / points / feedback of a preview attempt from a viewer who may not read the answer key. */
    private ExamAttemptDto withPreviewScoringHiddenIfNeeded(ExamAttemptDto dto, ExamAttempt attempt, Exam exam, String viewerId) {
        if (!attempt.isPreview() || canSeePreviewScoring(viewerId, exam)) {
            return dto;
        }
        return hidePreviewScoring(dto);
    }

    /**
     * Returns the attempt "as submitted": the learner's own answers stay, everything that would tell them what was
     * right does not. A finished attempt is reported as SUBMITTED (never PUBLISHED/GRADING), so no client treats it as
     * having a score; an attempt still IN_PROGRESS or CANCELLED has nothing to hide and is left as it is.
     */
    private ExamAttemptDto hidePreviewScoring(ExamAttemptDto dto) {
        String status = dto.getStatus() == null ? "" : dto.getStatus().toUpperCase(Locale.ROOT);
        if ("IN_PROGRESS".equals(status) || "CANCELLED".equals(status)) {
            return dto;
        }
        dto.setScore(null);
        dto.setTotalPoints(0);
        if (dto.getAnswers() != null) {
            dto.getAnswers().forEach(answer -> {
                answer.setPointsAwarded(null);
                answer.setTeacherFeedback(null);
            });
        }
        dto.setStatus("SUBMITTED");
        dto.setResultHidden(true);
        dto.setNotice(PREVIEW_RESULT_HIDDEN_NOTICE);
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
