-- V30 (R19-03): columns that hold FUTURE dates move from TIMESTAMP to DATETIME(6).
--
-- MySQL TIMESTAMP stops at 2038-01-19 03:14:07 UTC. entitlements.expires_at is that kind of column: a 3650-day
-- product bought twice by the same buyer (stacked renewals) ends around 2046, and a product whose access starts in
-- 2039 cannot even be created - the INSERT failed with "Incorrect datetime value", which PAYMENT_SUCCESS reported as a
-- generic 400 while the order stayed PENDING for a payment that had really been taken.
--
-- DATETIME(6) has no such limit (9999-12-31) and keeps microseconds, which is what Hibernate's `Instant` mapping
-- (TIMESTAMP_UTC -> datetime(6)) already expects. The application reads and writes every instant as UTC
-- (jdbc url serverTimezone=UTC); a DATETIME holds the wall-clock value with no zone.
--
-- Existing values must NOT shift. MySQL converts a TIMESTAMP (stored as UTC) to DATETIME using the SESSION time
-- zone, so the session is pinned to UTC for the conversion, then restored. (The MySQL image runs in UTC, in which
-- case this is a no-op; it makes the migration correct on a server whose zone is not UTC as well.)
--
-- Only columns that hold future dates, or are compared with future dates, are converted. created_at / updated_at /
-- *_at columns that record when something already happened (past instants) are left as they are.
--
-- Every statement is an idempotent MODIFY, so the script is safe to replay after a partially applied run (MySQL DDL
-- implicitly commits). Each table is rebuilt once (ALGORITHM=COPY); the tables involved are small, and the write
-- lock is held only for the duration of that copy.

SET @v30_previous_time_zone = @@SESSION.time_zone;
SET SESSION time_zone = '+00:00';

-- Entitlement periods: starts_at / expires_at, stacked by renewals (V1).
ALTER TABLE entitlements
    MODIFY COLUMN starts_at DATETIME(6) NOT NULL,
    MODIFY COLUMN expires_at DATETIME(6) NOT NULL;

-- Product sale start (V15) and the snapshot copied onto each order item (V15).
ALTER TABLE product_prices
    MODIFY COLUMN access_starts_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);

ALTER TABLE order_items
    MODIFY COLUMN access_starts_at_snapshot DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);

-- Exam schedule window (V1) and the per-attempt deadline derived from it (V1).
ALTER TABLE exams
    MODIFY COLUMN schedule_start DATETIME(6) NULL,
    MODIFY COLUMN schedule_end DATETIME(6) NULL;

ALTER TABLE exam_attempts
    MODIFY COLUMN ends_at DATETIME(6) NOT NULL;

-- Token lifetimes (V6, V23): configurable, and compared with "now" on every use.
ALTER TABLE revoked_tokens
    MODIFY COLUMN expires_at DATETIME(6) NOT NULL;

ALTER TABLE refresh_tokens
    MODIFY COLUMN expires_at DATETIME(6) NOT NULL;

-- Retry schedule of the leaderboard recalculation queue (V12): next_attempt_at is a future instant.
ALTER TABLE leaderboard_recalc_jobs
    MODIFY COLUMN next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);

SET SESSION time_zone = @v30_previous_time_zone;
