ALTER TABLE exam_attempts
    ADD COLUMN audience_eligible_at_start BOOLEAN NOT NULL DEFAULT TRUE;
