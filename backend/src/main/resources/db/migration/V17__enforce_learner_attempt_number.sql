-- Migration V17: Backfill deterministic attempt numbers and enforce non-null attempt numbers for learner attempts

-- Backfill any existing learner attempts where attempt_number is NULL
UPDATE exam_attempts ea
JOIN (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY exam_id, user_id ORDER BY started_at, id) AS rn
    FROM exam_attempts
    WHERE is_preview = FALSE AND attempt_number IS NULL
) ranked ON ea.id = ranked.id
SET ea.attempt_number = ranked.rn
WHERE ea.is_preview = FALSE AND ea.attempt_number IS NULL;

-- Enforce check constraint for non-null attempt_number on learner attempts
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE table_schema = DATABASE() AND table_name = 'exam_attempts' AND constraint_name = 'chk_ea_learner_attempt_number') = 0,
    'ALTER TABLE exam_attempts ADD CONSTRAINT chk_ea_learner_attempt_number CHECK (is_preview = TRUE OR attempt_number IS NOT NULL)',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
