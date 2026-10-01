-- V32 (R20-06): one answer row per (attempt, question).
--
-- ExamService used to look an answer up with one SELECT per submitted question and then insert or update, with nothing in the
-- schema to stop two racing writers (an autosave and the submit of the same attempt) from both inserting: attempt_answers held
-- no unique key, so duplicates were possible and grading silently picked one of them. Autosave/submit now load the attempt's
-- answers once and upsert in memory under the attempt row lock; this key makes the invariant a database guarantee too.
--
-- Step 1: remove existing duplicates. The table has no timestamp, so "latest" cannot be reconstructed; the survivor is chosen
-- deterministically: a row a teacher already graded (graded_by / points_awarded set) always wins, otherwise the highest id.
-- Step 2: add the unique key (idempotent: skipped if a previous, partially applied run already created it).

DELETE aa FROM attempt_answers aa
JOIN (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY attempt_id, question_id
               ORDER BY (graded_by IS NOT NULL) DESC, (points_awarded IS NOT NULL) DESC, id DESC
           ) AS rn
    FROM attempt_answers
) ranked ON ranked.id = aa.id
WHERE ranked.rn > 1;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'attempt_answers' AND index_name = 'uq_aa_attempt_question') = 0,
    'ALTER TABLE attempt_answers ADD UNIQUE KEY uq_aa_attempt_question (attempt_id, question_id)',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
