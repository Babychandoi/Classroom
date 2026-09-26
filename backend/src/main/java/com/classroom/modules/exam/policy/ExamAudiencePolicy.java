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

        // Must be class member
        if (!accessPolicy.isMember(userId, exam.getClassId())) {
            return false;
        }

        // Must be published or open
        if (!"PUBLISHED".equalsIgnoreCase(exam.getStatus()) && !"OPEN".equalsIgnoreCase(exam.getStatus())) {
            return false;
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
        long currentAttempts = attemptRepository.countByExamIdAndUserIdAndIsPreviewFalse(exam.getId(), userId);
        if (currentAttempts >= exam.getAttemptLimit()) {
            Optional<ExamAttempt> inProgressOpt = attemptRepository
                    .findFirstByExamIdAndUserIdAndStatusOrderByStartedAtDesc(exam.getId(), userId, "IN_PROGRESS");
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
            throw new AppException(ErrorCode.FORBIDDEN, "Bạn chưa là thành viên của lớp học này");
        }

        if (!"PUBLISHED".equalsIgnoreCase(exam.getStatus()) && !"OPEN".equalsIgnoreCase(exam.getStatus())) {
            throw new AppException(ErrorCode.EXAM_NOT_OPEN, "Kỳ thi chưa mở hoặc đã kết thúc");
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
            long currentAttempts = attemptRepository.countByExamIdAndUserIdAndIsPreviewFalse(exam.getId(), userId);
            if (currentAttempts >= exam.getAttemptLimit()) {
                throw new AppException(ErrorCode.EXAM_ATTEMPT_LIMIT_REACHED, "Bạn đã hết số lượt làm bài cho kỳ thi này");
            }
        }

        if (!isResume && !audienceMatches(userId, exam, now)) {
            throw new AppException(ErrorCode.EXAM_AUDIENCE_REJECTED, "Bạn không đáp ứng điều kiện đối tượng tham gia kỳ thi");
        }
    }

    /** Validate the live class/exam boundary for an existing attempt without re-evaluating its audience. */
    public void enforceResumeAttempt(String userId, Exam exam, ExamAttempt attempt, Instant now) {
        if (userId == null || exam == null) throw new AppException(ErrorCode.UNAUTHORIZED);
        if (attempt == null || !attempt.isAudienceEligibleAtStart()) {
            throw new AppException(ErrorCode.EXAM_AUDIENCE_REJECTED, "Bài thi không có xác nhận điều kiện tham gia tại thời điểm bắt đầu");
        }
        if (!accessPolicy.isMember(userId, exam.getClassId())) {
            throw new AppException(ErrorCode.FORBIDDEN, "Bạn chưa là thành viên của lớp học này");
        }
        if (!"PUBLISHED".equalsIgnoreCase(exam.getStatus()) && !"OPEN".equalsIgnoreCase(exam.getStatus())) {
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
