package com.classroom.modules.learning.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.model.StaffAssignment;
import com.classroom.modules.classroom.model.StaffPermission;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.StaffAssignmentRepository;
import com.classroom.modules.classroom.repository.StaffPermissionRepository;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.policy.ProfileVisibilityPolicy;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.learning.dto.AssignmentSubmissionDto;
import com.classroom.modules.learning.model.*;
import com.classroom.modules.learning.repository.*;
import com.classroom.modules.learning.policy.LearningPolicy;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AssignmentService {
    /**
     * R12-02: caps how many pending submissions the class-wide grading queue returns in one call so
     * a large backlog cannot pull an unbounded result set into memory. The endpoint predates any
     * pagination contract on the frontend, so a generous cap (rather than true pagination) keeps the
     * existing single-request UI working while still bounding the worst case.
     */
    private static final int CLASS_QUEUE_LIMIT = 200;

    private final LessonRepository lessons;
    private final CourseRepository courses;
    private final AssignmentSubmissionRepository submissions;
    private final LearningPolicy learningPolicy;
    private final AccessPolicy accessPolicy;
    private final AuditService auditService;
    private final StaffAssignmentRepository staffAssignments;
    private final StaffPermissionRepository staffPermissions;
    private final UserRepository users;
    private final ProfileVisibilityPolicy profileVisibilityPolicy;

    public AssignmentService(LessonRepository lessons, CourseRepository courses, AssignmentSubmissionRepository submissions,
                             LearningPolicy learningPolicy, AccessPolicy accessPolicy, AuditService auditService,
                             StaffAssignmentRepository staffAssignments, StaffPermissionRepository staffPermissions,
                             UserRepository users, ProfileVisibilityPolicy profileVisibilityPolicy) {
        this.lessons = lessons; this.courses = courses; this.submissions = submissions;
        this.learningPolicy = learningPolicy; this.accessPolicy = accessPolicy; this.auditService = auditService;
        this.staffAssignments = staffAssignments; this.staffPermissions = staffPermissions;
        this.users = users; this.profileVisibilityPolicy = profileVisibilityPolicy;
    }

    @Transactional
    public AssignmentSubmission submit(String lessonId, String userId, String text) {
        // Serialize attempt-number allocation on the stable lesson row. This lock is shared
        // across all submissions for the lesson and is held until the insert commits.
        Lesson lesson = lessons.findByIdForUpdate(lessonId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài học"));
        if (!lesson.isAssignmentEnabled())
            throw new AppException(ErrorCode.BAD_REQUEST, "Bài học này không phải bài tập");
        Course course = course(lesson);
        learningPolicy.enforceLearn(userId, course);
        accessPolicy.enforceNotSuspended(course.getClassId()); // D-29: a suspended class is read-only, also for its owner
        // R14-02: an archived assignment (or one inside an archived section) is invisible to learners
        // and must not accept new submissions through a stale link or a direct API call.
        requireVisibleToLearner(lesson, course, userId);
        if (text == null || text.isBlank() || text.length() > 100_000)
            throw new AppException(ErrorCode.BAD_REQUEST, "Bài nộp phải có nội dung (tối đa 100000 ký tự)");
        int attempt = Math.addExact(submissions.findMaxAttemptNumber(lessonId, userId), 1);
        return submissions.save(new AssignmentSubmission(lessonId, course.getId(), course.getClassId(), userId, attempt, text.trim()));
    }

    @Transactional(readOnly = true)
    public List<AssignmentSubmission> mySubmissions(String lessonId, String userId) {
        Lesson lesson = assignment(lessonId);
        Course course = course(lesson);
        learningPolicy.enforceLearn(userId, course);
        requireVisibleToLearner(lesson, course, userId);
        return submissions.findByLessonIdAndUserIdOrderByAttemptNumberDesc(lessonId, userId);
    }

    @Transactional(readOnly = true)
    public List<AssignmentSubmission> queue(String lessonId, String userId) {
        Lesson lesson = assignment(lessonId);
        Course course = course(lesson);
        accessPolicy.enforceManageRead(userId, course.getClassId(), "COURSE", "GRADE", course.getId());
        return submissions.findByLessonIdOrderBySubmittedAtAsc(lessonId);
    }

    /**
     * R12-02: previously loaded every submission of the class regardless of status (including
     * already-GRADED rows with nothing left to do) and ran accessPolicy.canManage(...) once per row
     * - up to 4 extra queries per row (staff assignment lookup + permission list, each row re-fetched)
     * on top of the unfiltered fetch itself, with no readOnly transaction and no supporting index.
     * Now: (1) permission is resolved exactly once - OWNER or a class-wide COURSE:GRADE grant sees
     * every course, otherwise the caller's specific COURSE:GRADE-scoped course ids are resolved once
     * and passed into the query's IN list; (2) the repository query itself filters to PENDING
     * ('SUBMITTED') status and the permitted courses, ordered oldest-first, capped at
     * CLASS_QUEUE_LIMIT; (3) V26 adds the (class_id, status, submitted_at) index this query relies
     * on; (4) the transaction is readOnly.
     */
    @Transactional(readOnly = true)
    public List<AssignmentSubmissionDto> classQueue(String classId, String userId) {
        accessPolicy.enforceMember(userId, classId);
        Pageable limit = PageRequest.of(0, CLASS_QUEUE_LIMIT);

        List<AssignmentSubmission> result;
        if (accessPolicy.isOwner(userId, classId) || accessPolicy.canManage(userId, classId, "COURSE", "GRADE", null)) {
            // OWNER, or a class-wide (unscoped) COURSE:GRADE grant: every course in the class is
            // visible, so no course-id filter is needed.
            result = submissions.findPendingByClassId(classId, limit);
        } else {
            List<String> scopedCourseIds = scopedGradeCourseIds(userId, classId);
            result = scopedCourseIds.isEmpty()
                    ? Collections.emptyList()
                    : submissions.findPendingByClassIdAndCourseIdIn(classId, scopedCourseIds, limit);
        }
        return toDtos(result, userId, classId);
    }

    /**
     * Resolves, in a single staff-assignment + single permission-list lookup, every course id the
     * caller holds an explicit course-scoped COURSE:GRADE grant on. Callers of this method have
     * already been rejected (classQueue) if they are neither the OWNER nor holding a class-wide
     * COURSE:GRADE grant, so every match here is necessarily course-scoped (scopeCourseId != null).
     */
    private List<String> scopedGradeCourseIds(String userId, String classId) {
        StaffAssignment assignment = staffAssignments.findByClassIdAndUserId(classId, userId)
                .filter(a -> "ACTIVE".equalsIgnoreCase(a.getStatus()))
                .orElse(null);
        if (assignment == null) {
            return Collections.emptyList();
        }
        List<StaffPermission> permissions = staffPermissions.findByAssignmentId(assignment.getId());
        return permissions.stream()
                .filter(p -> p.getScopeCourseId() != null)
                .filter(p -> ("COURSE".equalsIgnoreCase(p.getModule()) || "*".equals(p.getModule())))
                .filter(p -> ("GRADE".equalsIgnoreCase(p.getAction()) || "*".equals(p.getAction())))
                .map(StaffPermission::getScopeCourseId)
                .distinct()
                .toList();
    }

    /**
     * Records a grade for one submission.
     *
     * <p>The submission row is locked for the whole transaction, so two graders acting at once are
     * serialized instead of silently overwriting each other's correction. The previous and new
     * grade are written to the audit log in the same transaction, giving every correction a record
     * of who changed what; if the audit write fails the grade change rolls back with it.</p>
     */
    @Transactional
    public AssignmentSubmissionDto grade(String submissionId, String userId, BigDecimal score, String feedback) {
        AssignmentSubmission submission = submissions.findByIdForUpdate(submissionId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài nộp"));
        accessPolicy.enforceManage(userId, submission.getClassId(), "COURSE", "GRADE", submission.getCourseId());
        if (score == null || score.signum() < 0 || score.scale() > 2)
            throw new AppException(ErrorCode.BAD_REQUEST, "Điểm phải là số không âm, tối đa 2 chữ số thập phân");

        BigDecimal beforeScore = submission.getScore();
        String beforeStatus = submission.getStatus();
        String beforeGrader = submission.getGradedBy();

        submission.grade(score, feedback, userId);
        AssignmentSubmission saved = submissions.save(submission);

        auditService.record(
                saved.getClassId(),
                userId,
                beforeScore == null ? "ASSIGNMENT_GRADE" : "ASSIGNMENT_GRADE_CORRECT",
                "ASSIGNMENT_SUBMISSION",
                saved.getId(),
                String.format("{\"beforeScore\":%s,\"afterScore\":%s,\"beforeStatus\":\"%s\",\"afterStatus\":\"%s\",\"previousGradedBy\":%s,\"gradedBy\":\"%s\"}",
                        beforeScore, saved.getScore(), beforeStatus, saved.getStatus(),
                        beforeGrader == null ? "null" : "\"" + beforeGrader + "\"", userId)
        );
        return toDtos(List.of(saved), userId, saved.getClassId()).get(0);
    }

    /**
     * R12-03: batch-loads the lessons, courses and users referenced by a page of submissions
     * (findAllById/one query each - never one lookup per row) and maps each to
     * {@link AssignmentSubmissionDto}, resolving the learner's display name through
     * {@link ProfileVisibilityPolicy} exactly like the exam grading queue and profile/leaderboard
     * endpoints do. The caller (a grader, already authorized by classQueue/grade) is a class
     * administrator for this purpose, so ProfileVisibilityPolicy grants them visibility into a
     * PRIVATE learner's name; if visibility were ever denied for some other reason, the policy
     * itself substitutes the standard anonymized name.
     */
    private List<AssignmentSubmissionDto> toDtos(List<AssignmentSubmission> rows, String viewerId, String classId) {
        if (rows.isEmpty()) return Collections.emptyList();

        List<String> lessonIds = rows.stream().map(AssignmentSubmission::getLessonId).distinct().toList();
        List<String> courseIds = rows.stream().map(AssignmentSubmission::getCourseId).distinct().toList();
        List<String> userIds = rows.stream().map(AssignmentSubmission::getUserId).distinct().toList();

        Map<String, Lesson> lessonById = new HashMap<>();
        lessons.findAllById(lessonIds).forEach(l -> lessonById.put(l.getId(), l));
        Map<String, Course> courseById = new HashMap<>();
        courses.findAllById(courseIds).forEach(c -> courseById.put(c.getId(), c));
        Map<String, User> userById = new HashMap<>();
        users.findAllById(userIds).forEach(u -> userById.put(u.getId(), u));

        // R12-03: when ProfileVisibilityPolicy hides a learner's real name from this particular
        // grader (e.g. a course-scoped grader without MEMBER:VIEW, viewing a PRIVATE learner who
        // isn't in one of their scoped courses' visibility overrides), fall back to a
        // per-batch-stable ordinal label ("Học viên ẩn danh #n") instead of the policy's shared
        // generic string, so distinct anonymized learners in the same queue remain distinguishable
        // to the grader across rows without ever leaking their identity.
        Map<String, Integer> anonymousOrdinalByUserId = new HashMap<>();
        int[] nextOrdinal = {1};

        return rows.stream().map(sub -> {
            AssignmentSubmissionDto dto = new AssignmentSubmissionDto();
            dto.setSubmissionId(sub.getId());
            dto.setLessonId(sub.getLessonId());
            Lesson lesson = lessonById.get(sub.getLessonId());
            dto.setLessonTitle(lesson != null ? lesson.getTitle() : null);
            dto.setCourseId(sub.getCourseId());
            Course course = courseById.get(sub.getCourseId());
            dto.setCourseTitle(course != null ? course.getTitle() : null);

            User learnerUser = userById.get(sub.getUserId());
            String displayName;
            if (learnerUser != null && profileVisibilityPolicy.isIdentityVisible(learnerUser, viewerId, classId)) {
                displayName = learnerUser.getFullName();
            } else {
                int ordinal = anonymousOrdinalByUserId.computeIfAbsent(sub.getUserId(), id -> nextOrdinal[0]++);
                displayName = "Học viên ẩn danh #" + ordinal;
            }
            dto.setLearner(new AssignmentSubmissionDto.Learner(sub.getUserId(), displayName));

            dto.setSubmissionText(sub.getSubmissionText());
            dto.setAttemptNumber(sub.getAttemptNumber());
            dto.setStatus(sub.getStatus());
            dto.setSubmittedAt(sub.getSubmittedAt());
            dto.setScore(sub.getScore());
            dto.setFeedback(sub.getFeedback());
            return dto;
        }).toList();
    }

    private Lesson assignment(String id) {
        Lesson lesson = lessons.findById(id).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài học"));
        if (!lesson.isAssignmentEnabled())
            throw new AppException(ErrorCode.BAD_REQUEST, "Bài học này không phải bài tập");
        return lesson;
    }
    private void requireVisibleToLearner(Lesson lesson, Course course, String userId) {
        if (learningPolicy.isLessonHiddenFromLearner(lesson, course, userId)) {
            throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài học");
        }
    }
    private Course course(Lesson lesson) {
        return courses.findById(lesson.getCourseId()).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));
    }
}
