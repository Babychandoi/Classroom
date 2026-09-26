package com.classroom.modules.learning.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.learning.model.*;
import com.classroom.modules.learning.repository.*;
import com.classroom.modules.learning.policy.LearningPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.List;

@Service
public class AssignmentService {
    private final LessonRepository lessons;
    private final CourseRepository courses;
    private final AssignmentSubmissionRepository submissions;
    private final LearningPolicy learningPolicy;
    private final AccessPolicy accessPolicy;
    private final AuditService auditService;

    public AssignmentService(LessonRepository lessons, CourseRepository courses, AssignmentSubmissionRepository submissions,
                             LearningPolicy learningPolicy, AccessPolicy accessPolicy, AuditService auditService) {
        this.lessons = lessons; this.courses = courses; this.submissions = submissions;
        this.learningPolicy = learningPolicy; this.accessPolicy = accessPolicy; this.auditService = auditService;
    }

    @Transactional
    public AssignmentSubmission submit(String lessonId, String userId, String text) {
        // Serialize attempt-number allocation on the stable lesson row. This lock is shared
        // across all submissions for the lesson and is held until the insert commits.
        Lesson lesson = lessons.findByIdForUpdate(lessonId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài học"));
        if (!"ASSIGNMENT".equalsIgnoreCase(lesson.getType()))
            throw new AppException(ErrorCode.BAD_REQUEST, "Bài học này không phải bài tập");
        Course course = course(lesson);
        learningPolicy.enforceLearn(userId, course);
        if (text == null || text.isBlank() || text.length() > 100_000)
            throw new AppException(ErrorCode.BAD_REQUEST, "Bài nộp phải có nội dung (tối đa 100000 ký tự)");
        int attempt = Math.addExact(submissions.findMaxAttemptNumber(lessonId, userId), 1);
        return submissions.save(new AssignmentSubmission(lessonId, course.getId(), course.getClassId(), userId, attempt, text.trim()));
    }

    @Transactional(readOnly = true)
    public List<AssignmentSubmission> mySubmissions(String lessonId, String userId) {
        Lesson lesson = assignment(lessonId);
        learningPolicy.enforceLearn(userId, course(lesson));
        return submissions.findByLessonIdAndUserIdOrderByAttemptNumberDesc(lessonId, userId);
    }

    @Transactional(readOnly = true)
    public List<AssignmentSubmission> queue(String lessonId, String userId) {
        Lesson lesson = assignment(lessonId);
        Course course = course(lesson);
        accessPolicy.enforceManage(userId, course.getClassId(), "COURSE", "GRADE", course.getId());
        return submissions.findByLessonIdOrderBySubmittedAtAsc(lessonId);
    }

    @Transactional(readOnly = true)
    public List<AssignmentSubmission> classQueue(String classId, String userId) {
        accessPolicy.enforceMember(userId, classId);
        List<AssignmentSubmission> all = submissions.findByClassIdOrderBySubmittedAtAsc(classId);
        return all.stream()
                .filter(sub -> accessPolicy.canManage(userId, classId, "COURSE", "GRADE", sub.getCourseId()))
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
    public AssignmentSubmission grade(String submissionId, String userId, BigDecimal score, String feedback) {
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
        return saved;
    }

    private Lesson assignment(String id) {
        Lesson lesson = lessons.findById(id).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài học"));
        if (!"ASSIGNMENT".equalsIgnoreCase(lesson.getType()))
            throw new AppException(ErrorCode.BAD_REQUEST, "Bài học này không phải bài tập");
        return lesson;
    }
    private Course course(Lesson lesson) {
        return courses.findById(lesson.getCourseId()).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));
    }
}
