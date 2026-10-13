-- V52 (D-34): assigning an assessment version to a class (or a segment of it), the students' attempts and their per-item answers.
--
-- assessment_assignments      one row per "Giao bài". version_id is the immutable assessment_versions row taken at assignment time (RESTRICT:
--                             a version that was handed out can never disappear). status OPEN | CLOSED | CANCELLED. time_limit_minutes 0 and
--                             max_attempts 0 mean "no limit". result_visibility IMMEDIATE | AFTER_DUE | MANUAL.
-- assessment_attempts         one row per student attempt (attempt_no 1..n, unique per assignment and user). shuffle_seed + order_json pin the
--                             display order (stable numbering) for resume and grading; playback_json counts listening plays per asset key;
--                             recording_takes_json counts speaking takes per item. status IN_PROGRESS | SUBMITTED | GRADED | RETURNED.
-- assessment_attempt_answers  one row per item of an attempt: the student's response (autosaved), the auto / manual / final points, the rubric
--                             levels and the speaking recording (RESTRICT: a submitted recording cannot be deleted under the grader).
--
-- Every instant is DATETIME(6) written by the application in UTC, without CURRENT_TIMESTAMP defaults.

CREATE TABLE IF NOT EXISTS assessment_assignments (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    class_id VARCHAR(36) NOT NULL,
    assessment_id VARCHAR(36) NOT NULL,
    version_id VARCHAR(36) NOT NULL,
    title VARCHAR(200) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    assigned_by VARCHAR(36) NOT NULL,
    target_segment_id VARCHAR(36) NULL,
    open_at DATETIME(6) NULL,
    due_at DATETIME(6) NULL,
    time_limit_minutes INT NOT NULL,
    max_attempts INT NOT NULL,
    result_visibility VARCHAR(16) NOT NULL,
    allow_late BOOLEAN NOT NULL,
    shuffle BOOLEAN NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_assessment_assignment_class FOREIGN KEY (class_id) REFERENCES classrooms(id) ON DELETE CASCADE,
    CONSTRAINT fk_assessment_assignment_assessment FOREIGN KEY (assessment_id) REFERENCES assessments(id) ON DELETE CASCADE,
    CONSTRAINT fk_assessment_assignment_version FOREIGN KEY (version_id) REFERENCES assessment_versions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_assessment_assignment_assigner FOREIGN KEY (assigned_by) REFERENCES users(id) ON DELETE RESTRICT,
    INDEX ix_assessment_assignments_class (class_id, status, due_at),
    INDEX ix_assessment_assignments_assessment (assessment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS assessment_attempts (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    assignment_id VARCHAR(36) NOT NULL,
    class_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    attempt_no INT NOT NULL,
    version_id VARCHAR(36) NOT NULL,
    status VARCHAR(16) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    deadline_at DATETIME(6) NULL,
    submitted_at DATETIME(6) NULL,
    late BOOLEAN NOT NULL,
    shuffle_seed BIGINT NOT NULL,
    order_json MEDIUMTEXT NOT NULL,
    playback_json TEXT NOT NULL,
    recording_takes_json TEXT NOT NULL,
    auto_score DECIMAL(8,2) NULL,
    manual_score DECIMAL(8,2) NULL,
    total_score DECIMAL(8,2) NULL,
    max_score DECIMAL(8,2) NOT NULL,
    pass_fail VARCHAR(8) NULL,
    teacher_comment TEXT NULL,
    graded_by VARCHAR(36) NULL,
    graded_at DATETIME(6) NULL,
    returned_at DATETIME(6) NULL,
    revision INT NOT NULL,
    CONSTRAINT uk_assessment_attempt_no UNIQUE (assignment_id, user_id, attempt_no),
    CONSTRAINT fk_assessment_attempt_assignment FOREIGN KEY (assignment_id) REFERENCES assessment_assignments(id) ON DELETE CASCADE,
    CONSTRAINT fk_assessment_attempt_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_assessment_attempt_version FOREIGN KEY (version_id) REFERENCES assessment_versions(id) ON DELETE RESTRICT,
    INDEX ix_assessment_attempts_assignment (assignment_id, status),
    INDEX ix_assessment_attempts_user (user_id, assignment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS assessment_attempt_answers (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    attempt_id VARCHAR(36) NOT NULL,
    item_id VARCHAR(40) NOT NULL,
    response_json TEXT NULL,
    auto_points DECIMAL(8,2) NULL,
    manual_points DECIMAL(8,2) NULL,
    final_points DECIMAL(8,2) NULL,
    status VARCHAR(16) NOT NULL,
    correctness VARCHAR(12) NULL,
    sub_results_json TEXT NULL,
    rubric_json TEXT NULL,
    pass_fail VARCHAR(8) NULL,
    teacher_comment TEXT NULL,
    recording_asset_id VARCHAR(36) NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_assessment_attempt_answer UNIQUE (attempt_id, item_id),
    CONSTRAINT fk_assessment_answer_attempt FOREIGN KEY (attempt_id) REFERENCES assessment_attempts(id) ON DELETE CASCADE,
    CONSTRAINT fk_assessment_answer_recording FOREIGN KEY (recording_asset_id) REFERENCES media_assets(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
