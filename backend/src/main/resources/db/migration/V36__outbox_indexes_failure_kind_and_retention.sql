-- V36 (R20-04 / R20-05): outbox indexes, failure classification and the automatic re-drive budget.
--
-- R20-05: the outbox worker polled `outbox_events` with three statements that had NO usable index (only the primary key and the
-- unique sequence_no existed), so every idle poll full-scanned the table: with 300 000 PROCESSED rows the eligible-events SELECT took
-- ~590 ms and each of the two stale-PROCESSING UPDATEs ~160 ms - about 0.9 s of MySQL time per poll, forever, growing with the table.
-- The indexes below match the worker's actual queries (see OutboxEventRepository):
--   idx_outbox_status_seq            (status, sequence_no)                     eligible-head scan, dead-letter / pending counts
--   idx_outbox_status_processed      (status, processed_at)                    stale-PROCESSING reclaim, retention purge, age filters
--   idx_outbox_aggregate_status_seq  (aggregate_type, aggregate_id, status, sequence_no)
--                                                                              per-aggregate ordering gate (NOT EXISTS / existsEarlier...),
--                                                                              next-events-of-the-aggregate batch, idempotent enqueue
-- With them an idle poll costs one empty index probe whatever the number of PROCESSED rows.
--
-- The indexes are built ONLINE: ALGORITHM=INPLACE, LOCK=NONE. Inserts (the request path that records events) keep working while they
-- are built; only the DDL statement itself takes a while on a very large table (measured: seconds for 300 000 rows). The two new columns
-- are ALGORITHM=INSTANT (metadata only) on MySQL 8.0.12+ (the stack pins 8.4). Both statements are guarded, so the script can be
-- replayed after a partially applied run (MySQL DDL implicitly commits).
--
-- R20-04: `failure_kind` records whether the LAST failure was TRANSIENT (a projection store was unreachable - never counts toward the
-- dead-letter limit) or PERMANENT (the event itself cannot be projected). `auto_replay_count` is the budget of the automatic re-drive
-- of dead-lettered events (OutboxRedriveJob); a manual replay resets it. Both are NULL/0 for existing rows.
--
-- R20-08 NOTE - this database MUST run in UTC. V30 (TIMESTAMP -> DATETIME) pins the session time zone to '+00:00' while it converts,
-- which is only value-preserving when @@global.time_zone is UTC: on a server whose zone is, say, +07:00 the converted DATETIME values
-- shift by the server offset. V30 is already applied on existing databases and cannot be edited, so the backend now runs a startup
-- PREFLIGHT before Flyway (DatabaseTimeZonePreflight): if a pending migration requires a UTC server (V30, or any script that opts in with
-- the marker comment documented on DatabaseTimeZonePreflight.MARKER) and the server zone is not UTC, startup ABORTS with a clear message
-- unless app.db.allow-non-utc-migration=true is set deliberately. Set MySQL's default-time-zone='+00:00' (see docs/RUNBOOK.md, upgrade
-- checklist). Migrations written after V36 that convert or compare TIMESTAMP/DATETIME values should carry that marker. THIS script does not
-- need a UTC server (it only adds columns and indexes), so it deliberately does not carry the marker itself.
--
-- Nothing in this file changes any existing row.

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'outbox_events' AND column_name = 'failure_kind') = 0,
    'ALTER TABLE outbox_events ADD COLUMN failure_kind VARCHAR(16) NULL, ADD COLUMN auto_replay_count INT NOT NULL DEFAULT 0, ALGORITHM=INSTANT',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'outbox_events' AND index_name = 'idx_outbox_status_seq') = 0,
    'ALTER TABLE outbox_events ADD INDEX idx_outbox_status_seq (status, sequence_no), ADD INDEX idx_outbox_status_processed (status, processed_at), ADD INDEX idx_outbox_aggregate_status_seq (aggregate_type, aggregate_id, status, sequence_no), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
