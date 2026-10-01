package com.classroom.modules.exam.policy;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.commerce.policy.ProPolicy;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import com.classroom.modules.exam.model.Exam;
import com.classroom.modules.exam.model.ExamAttempt;
import com.classroom.modules.exam.repository.ExamAttemptRepository;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.segment.service.SegmentService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Comparator;
import java.util.stream.Collectors;

@Component
public class ExamAudiencePolicy {

    private final AccessPolicy accessPolicy;
    private final ProPolicy proPolicy;
    private final EntitlementRepository entitlementRepository;
    private final ExamAttemptRepository attemptRepository;
    private final SegmentService segmentService;
    private final CourseRepository courseRepository;

    public ExamAudiencePolicy(AccessPolicy accessPolicy,
                              ProPolicy proPolicy,
                              EntitlementRepository entitlementRepository,
                              ExamAttemptRepository attemptRepository,
                              SegmentService segmentService,
                              CourseRepository courseRepository) {
        this.accessPolicy = accessPolicy;
        this.proPolicy = proPolicy;
        this.entitlementRepository = entitlementRepository;
        this.attemptRepository = attemptRepository;
        this.segmentService = segmentService;
        this.courseRepository = courseRepository;
    }

    public boolean canEnterExam(String userId, Exam exam, Instant now, boolean isStaffPreview) {
        if (userId == null || exam == null) return false;

        // Staff / Owner Preview (requires OWNER or explicit EXAM:PREVIEW / EXAM:EDIT permission)
        if (isStaffPreview) {
            // Pass the exam's target course so a course-scoped EXAM:PREVIEW / EXAM:EDIT grant
            // resolves consistently with ExamService.startAttempt.
            String scopeCourseId = exam.getTargetCourseId();
            return accessPolicy.isOwner(userId, exam.getClassId())
                    || accessPolicy.canManage(userId, exam.getClassId(), "EXAM", "PREVIEW", scopeCourseId)
                    || accessPolicy.canManage(userId, exam.getClassId(), "EXAM", "EDIT", scopeCourseId);
        }

        return canEnterLearner(userId, exam, now, null);
    }

    public record EntryState(boolean canEnter, long attempts) {}
    private record ListingContext(boolean member, boolean archived, Map<String, Long> counts,
                                  Map<String, List<ExamAttempt>> active) {
        Optional<ExamAttempt> latest(String examId, boolean learnerOnly) {
            return active.getOrDefault(examId, List.of()).stream()
                    .filter(a -> !learnerOnly || !a.isPreview())
                    .max(Comparator.comparing(ExamAttempt::getStartedAt));
        }
    }

    /** Request-local reads only; no cross-request permission, membership or expiry cache. */
    public Map<String, EntryState> listingEligibility(String userId, String classId, List<Exam> exams, Instant now) {
        if (exams.isEmpty()) return Map.of();
        if (exams.stream().anyMatch(e -> !classId.equals(e.getClassId())))
            throw new IllegalArgumentException("Exam listing must belong to one class");
        if (userId == null) return exams.stream().collect(Collectors.toMap(Exam::getId, e -> new EntryState(false, 0)));
        Map<String, Long> counts = new HashMap<>();
        for (var count : attemptRepository.countAttemptsTowardLimitByClass(classId, userId))
            counts.put(count.getExamId(), count.getAttempts());
        var active = attemptRepository.findByClassIdAndUserIdAndStatus(classId, userId, "IN_PROGRESS").stream()
                .collect(Collectors.groupingBy(ExamAttempt::getExamId));
        var context = new ListingContext(accessPolicy.isMember(userId, classId),
                accessPolicy.isClassArchived(classId), counts, active);
        return exams.stream().collect(Collectors.toMap(Exam::getId,
                e -> new EntryState(canEnterLearner(userId, e, now, context), counts.getOrDefault(e.getId(), 0L))));
    }

    private boolean canEnterLearner(String userId, Exam exam, Instant now, ListingContext context) {
        // Must be class member
        if (!(context == null ? accessPolicy.isMember(userId, exam.getClassId()) : context.member())) {
            return false;
        }

        // Must be published or open - or CLOSED/ARCHIVED with an attempt still running (below)
        boolean enterableStatus = isEnterableStatus(exam);
        if (!enterableStatus && !isClosedStatus(exam)) {
            return false;
        }

        // R14-05 / R14-13: after the exam is closed/archived, or once the class is archived, nobody
        // can START a new attempt; a learner can only continue one that is already running.
        if (!enterableStatus || (context == null ? accessPolicy.isClassArchived(exam.getClassId()) : context.archived())) {
            return hasResumableAttempt(userId, exam, now, context);
        }

        // Schedule check
        if (exam.getScheduleStart() != null && now.isBefore(exam.getScheduleStart())) {
            return false;
        }
        // Finding 3: the closing instant is exclusive. At exactly scheduleEnd there is no time
        // left to answer, so entry must already be refused rather than consume an attempt.
        if (exam.getScheduleEnd() != null && !now.isBefore(exam.getScheduleEnd())) {
            return false;
        }

        // Attempt limit check (allow entrance if user has an active, unexpired in-progress attempt to resume)
        long currentAttempts = context == null ? attemptRepository.countAttemptsTowardLimit(exam.getId(), userId)
                : context.counts().getOrDefault(exam.getId(), 0L);
        if (currentAttempts >= exam.getAttemptLimit()) {
            Optional<ExamAttempt> inProgressOpt = context == null ? attemptRepository
                    .findFirstByExamIdAndUserIdAndStatusOrderByStartedAtDesc(exam.getId(), userId, "IN_PROGRESS")
                    : context.latest(exam.getId(), false);
            if (inProgressOpt.isEmpty() || !now.isBefore(inProgressOpt.get().getEndsAt())) {
                return false;
            }
        }

        return audienceMatches(userId, exam, now);
    }

    public void enforceEnterExam(String userId, Exam exam, Instant now, boolean isStaffPreview) {
        enforceEnterExam(userId, exam, now, isStaffPreview, false);
    }

    public void enforceEnterExam(String userId, Exam exam, Instant now, boolean isStaffPreview, boolean isResume) {
        enforceEnterExam(userId, exam, now, isStaffPreview, isResume, null);
    }

    /** The service may supply the count it just read under the per-learner start lock. */
    public void enforceEnterExam(String userId, Exam exam, Instant now, boolean isStaffPreview, boolean isResume,
                                 Long knownAttemptCount) {
        if (userId == null || exam == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        if (isStaffPreview) {
            if (!canEnterExam(userId, exam, now, true)) {
                throw new AppException(ErrorCode.STAFF_PERMISSION_DENIED, "Không có quyền xem trước kỳ thi này");
            }
            return;
        }

        if (!accessPolicy.isMember(userId, exam.getClassId())) {
            if (accessPolicy.isMembershipExpired(userId, exam.getClassId())) {
                throw new AppException(ErrorCode.MEMBERSHIP_EXPIRED); // D-19: lapsed paid access
            }
            throw new AppException(ErrorCode.FORBIDDEN, "Bạn chưa là thành viên của lớp học này");
        }

        if (!isEnterableStatus(exam)) {
            throw new AppException(ErrorCode.EXAM_NOT_OPEN, "Kỳ thi chưa mở hoặc đã kết thúc");
        }

        // R14-13 (D-11): an archived class accepts no NEW attempts (resuming a running one goes
        // through enforceResumeAttempt and is unaffected).
        if (!isResume && accessPolicy.isClassArchived(exam.getClassId())) {
            throw new AppException(ErrorCode.EXAM_NOT_OPEN,
                    "Lớp học đã được lưu trữ; không thể bắt đầu lượt làm bài mới");
        }

        if (exam.getScheduleStart() != null && now.isBefore(exam.getScheduleStart())) {
            throw new AppException(ErrorCode.EXAM_NOT_OPEN, "Kỳ thi chưa đến thời gian bắt đầu");
        }
        // Finding 3: exclusive end boundary, matching canEnterExam and the ExamService clamp.
        if (exam.getScheduleEnd() != null && !now.isBefore(exam.getScheduleEnd())) {
            throw new AppException(ErrorCode.EXAM_NOT_OPEN, "Kỳ thi đã kết thúc thời gian làm bài");
        }

        // Only enforce attempt limit when starting a new attempt (not when resuming an active in-progress attempt)
        if (!isResume) {
            long currentAttempts = knownAttemptCount == null
                    ? attemptRepository.countAttemptsTowardLimit(exam.getId(), userId) : knownAttemptCount;
            if (currentAttempts >= exam.getAttemptLimit()) {
                throw new AppException(ErrorCode.EXAM_ATTEMPT_LIMIT_REACHED, "Bạn đã hết số lượt làm bài cho kỳ thi này");
            }
        }

        if (!isResume && !audienceMatches(userId, exam, now)) {
            throw new AppException(ErrorCode.EXAM_AUDIENCE_REJECTED, "Bạn không đáp ứng điều kiện đối tượng tham gia kỳ thi");
        }
    }

    private static boolean isEnterableStatus(Exam exam) {
        return "PUBLISHED".equalsIgnoreCase(exam.getStatus()) || "OPEN".equalsIgnoreCase(exam.getStatus());
    }

    private static boolean isClosedStatus(Exam exam) {
        return "CLOSED".equalsIgnoreCase(exam.getStatus()) || "ARCHIVED".equalsIgnoreCase(exam.getStatus());
    }

    /**
     * R14-05 (decision D-13): closing (or archiving) an exam only stops NEW attempts. An IN_PROGRESS
     * attempt that started at or before {@code closedAt} keeps working - resume, autosave and submit
     * all stay available until the attempt's own deadline - which is exactly what closeExam has
     * always promised ("left alone"). Previously resume alone was refused with EXAM_NOT_OPEN while
     * autosave/submit still worked, stranding a student mid-exam.
     */
    public boolean canResumeAfterClose(Exam exam, ExamAttempt attempt) {
        if (exam == null || attempt == null || !isClosedStatus(exam)) return false;
        if (!"IN_PROGRESS".equalsIgnoreCase(attempt.getStatus()) || attempt.isPreview()) return false;
        Instant closedAt = exam.getClosedAt();
        return closedAt != null && attempt.getStartedAt() != null && !attempt.getStartedAt().isAfter(closedAt);
    }

    /** Whether {@code userId} has a learner attempt they may still continue right now. */
    private boolean hasResumableAttempt(String userId, Exam exam, Instant now, ListingContext context) {
        if (exam.getScheduleStart() != null && now.isBefore(exam.getScheduleStart())) return false;
        if (exam.getScheduleEnd() != null && !now.isBefore(exam.getScheduleEnd())) return false;
        return (context == null ? attemptRepository
                .findFirstByExamIdAndUserIdAndStatusAndIsPreviewFalseOrderByStartedAtDesc(exam.getId(), userId, "IN_PROGRESS")
                : context.latest(exam.getId(), true))
                .filter(a -> a.getEndsAt() != null && now.isBefore(a.getEndsAt()))
                .filter(ExamAttempt::isAudienceEligibleAtStart)
                .filter(a -> isEnterableStatus(exam) || canResumeAfterClose(exam, a))
                .isPresent();
    }

    /** Validate the live class/exam boundary for an existing attempt without re-evaluating its audience. */
    public void enforceResumeAttempt(String userId, Exam exam, ExamAttempt attempt, Instant now) {
        if (userId == null || exam == null) throw new AppException(ErrorCode.UNAUTHORIZED);
        if (attempt == null || !attempt.isAudienceEligibleAtStart()) {
            throw new AppException(ErrorCode.EXAM_AUDIENCE_REJECTED, "Bài thi không có xác nhận điều kiện tham gia tại thời điểm bắt đầu");
        }
        if (!accessPolicy.isMember(userId, exam.getClassId())) {
            if (accessPolicy.isMembershipExpired(userId, exam.getClassId())) {
                throw new AppException(ErrorCode.MEMBERSHIP_EXPIRED); // D-19: lapsed paid access
            }
            throw new AppException(ErrorCode.FORBIDDEN, "Bạn chưa là thành viên của lớp học này");
        }
        if (!isEnterableStatus(exam) && !canResumeAfterClose(exam, attempt)) {
            throw new AppException(ErrorCode.EXAM_NOT_OPEN, "Kỳ thi chưa mở hoặc đã kết thúc");
        }
        if ((exam.getScheduleStart() != null && now.isBefore(exam.getScheduleStart()))
                || (exam.getScheduleEnd() != null && !now.isBefore(exam.getScheduleEnd()))) {
            throw new AppException(ErrorCode.EXAM_NOT_OPEN, "Kỳ thi không nằm trong thời gian làm bài");
        }
    }

    private boolean audienceMatches(String userId, Exam exam, Instant now) {
        if (exam.getAudienceRuleVersion() != 1) return false;
        String scope = exam.getAudienceScope() == null ? "ALL" : exam.getAudienceScope().toUpperCase();
        if ("ALL".equals(scope)) return true;
        if ("PRO".equals(scope)) return proPolicy.isPro(userId, exam.getClassId());
        boolean combined = "COURSE_SEGMENT".equals(scope);
        if (!combined && !"COURSE".equals(scope) && !"SEGMENT".equals(scope)) return false;
        boolean courseMatch = false;
        if ("COURSE".equals(scope) || combined) {
            Optional<Course> course = exam.getTargetCourseId() == null ? Optional.empty() : courseRepository.findById(exam.getTargetCourseId());
            if (course.isEmpty() || !exam.getClassId().equals(course.get().getClassId())) return false;
            courseMatch = accessPolicy.isOwner(userId, exam.getClassId())
                    || (course.get().getProductId() != null && entitlementRepository.hasCourseAccess(
                    userId, exam.getClassId(), course.get().getId(), course.get().getProductId(), now));
        }
        boolean segmentMatch = false;
        if ("SEGMENT".equals(scope) || combined) {
            segmentMatch = exam.getTargetSegmentId() != null
                    && segmentService.isUserInSegment(exam.getTargetSegmentId(), userId, exam.getClassId());
        }
        if (!combined) return "COURSE".equals(scope) ? courseMatch : segmentMatch;
        return "OR".equalsIgnoreCase(exam.getAudienceOperator()) ? courseMatch || segmentMatch : courseMatch && segmentMatch;
    }
}
