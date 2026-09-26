-- Same-aggregate outbox events were ordered by a second-precision created_at and tie-broken by a
-- random UUID, so two membership transitions written within the same second could be projected in
-- the opposite order to the one they happened in. A monotonic insert sequence gives the worker a
-- total, stable order that does not depend on timestamp precision.
ALTER TABLE outbox_events
    ADD COLUMN sequence_no BIGINT NOT NULL AUTO_INCREMENT UNIQUE;

-- The leaderboard recalculation job used to be written in its own transaction, before the
-- publishing transaction committed. The sweeper could therefore service that job against the old
-- committed score and delete it, leaving nothing behind to repair the total if the publisher's
-- post-commit recalculation never ran.
--
-- The job is now inserted inside the publishing transaction, so it becomes visible exactly when
-- the score does, and a recalculation deletes only the job rows it saw before reading the
-- attempts. That requires one row per publication rather than one row per learner: a single
-- upserted row per learner would make two concurrent publications for the same learner block on
-- each other's uncommitted row for the rest of their transactions.
ALTER TABLE leaderboard_recalc_jobs
    DROP INDEX uk_lb_recalc_class_user,
    ADD INDEX idx_lb_recalc_class_user (class_id, user_id);
