-- Durable retry queue for leaderboard recalculation.
-- A job row is written inside the publishing transaction, so a recalculation that
-- fails (or never runs because the process died after commit) is still replayed by
-- the sweeper instead of silently leaving a stale total.
CREATE TABLE IF NOT EXISTS leaderboard_recalc_jobs (
    id VARCHAR(36) PRIMARY KEY,
    class_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    next_attempt_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_lb_recalc_class_user (class_id, user_id),
    KEY idx_lb_recalc_next_attempt (next_attempt_at),
    CONSTRAINT fk_lbrj_class FOREIGN KEY (class_id) REFERENCES classrooms(id) ON DELETE CASCADE,
    CONSTRAINT fk_lbrj_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
