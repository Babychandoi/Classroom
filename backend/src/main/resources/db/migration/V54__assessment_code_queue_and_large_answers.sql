ALTER TABLE assessment_attempt_answers MODIFY response_json MEDIUMTEXT NULL;
ALTER TABLE assessment_attempts ADD code_run_window TIMESTAMP(6) NULL, ADD code_run_count INT NOT NULL DEFAULT 0;
CREATE INDEX idx_assessment_code_queue ON assessment_attempt_answers(status, updated_at);
