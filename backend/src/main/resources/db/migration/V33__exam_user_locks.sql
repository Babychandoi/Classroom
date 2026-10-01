-- V33 (R20-06): per-(exam, user) lock rows for "start attempt".
--
-- ExamService.startAttempt used to take the exam row FOR UPDATE, so every simultaneous start queued behind the previous one
-- while holding a pooled connection. The decision "may this learner start / resume an attempt of this exam" now runs under one
-- exclusive record lock on the learner's own row here (INSERT ... ON DUPLICATE KEY UPDATE, held until the transaction ends):
-- exact for one learner (double click, two tabs, a retry) and never blocking another learner.
--
-- No foreign keys on purpose: a foreign key would take a shared lock on the exam / user row on every insert, which is the
-- contention this table exists to avoid. Rows are 72 bytes; they are never read.
CREATE TABLE IF NOT EXISTS exam_user_locks (
    exam_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (exam_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
