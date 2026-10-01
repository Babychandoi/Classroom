-- V26__assignment_submission_class_status_index.sql
-- R12-02: AssignmentService.classQueue() now runs
--   SELECT ... WHERE class_id = ? AND status = 'SUBMITTED' [AND course_id IN (...)] ORDER BY submitted_at ASC
-- (see AssignmentSubmissionRepository.findPendingByClassId /
-- findPendingByClassIdAndCourseIdIn) instead of loading every submission of the class and
-- filtering/sorting in Java. Without a supporting index this still forces a full scan of
-- assignment_submissions filtered by class_id, then a filesort. V7 already added
-- ix_assignment_submission_queue (lesson_id, status, submitted_at) for the per-lesson queue; this
-- adds the class-scoped equivalent so the class-wide queue can use the same
-- index-range-scan-then-in-order pattern.
ALTER TABLE assignment_submissions
    ADD INDEX ix_assignment_submission_class_queue (class_id, status, submitted_at);
