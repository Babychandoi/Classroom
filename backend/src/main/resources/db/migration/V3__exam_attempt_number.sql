-- Migration V3: Add attempt_number to exam_attempts with unique constraint to serialize attempt creation atomically (Finding 4)
ALTER TABLE exam_attempts
    ADD COLUMN attempt_number INT NULL AFTER is_preview;

CREATE UNIQUE INDEX uq_ea_exam_user_attempt ON exam_attempts(exam_id, user_id, attempt_number);
