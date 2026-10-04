-- V47 (D-29): platform admin ("Quản trị nền tảng") - class suspension and the indexes behind the admin overview / lists / audit.
--
-- classrooms.status stays a free VARCHAR(32): the new value SUSPENDED needs no DDL. A suspension remembers what it interrupted so that
-- restore returns the class to exactly that state (ACTIVE or ARCHIVED):
--   classrooms.status_before_suspend  ACTIVE / ARCHIVED while status = 'SUSPENDED', otherwise NULL.
--   classrooms.suspended_reason       the admin's mandatory reason (1..500 characters, validated by the application), shown to the owner.
--   classrooms.suspended_at           when the suspension started (UTC, written by the application, no CURRENT_TIMESTAMP default - R20-08).
--
-- Indexes (all secondary, ALGORITHM=INPLACE, LOCK=NONE so they build online):
--   audit_events had none. The platform-wide audit page reads newest first, optionally narrowed by class, actor or action, and the user
--   detail reads "rows where the user is the actor or the target"; each filter gets a (filter, created_at) index.
--   users (created_at) and (status, role): overview counters, "new in the last N days", the signup histogram and the user list filters.
--   classrooms (status, created_at): overview counters and the class list filter / newest sort.
--   orders (status, paid_at): "PAID orders / revenue in the last 30 days" and the pending-order count.
--
-- Every statement is guarded so a half-applied run can be replayed. No data is converted.

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'classrooms' AND column_name = 'status_before_suspend') = 0,
    'ALTER TABLE classrooms ADD COLUMN status_before_suspend VARCHAR(32) NULL, ALGORITHM=INSTANT', 'SELECT 1');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'classrooms' AND column_name = 'suspended_reason') = 0,
    'ALTER TABLE classrooms ADD COLUMN suspended_reason VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL, ALGORITHM=INSTANT',
    'SELECT 1');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'classrooms' AND column_name = 'suspended_at') = 0,
    'ALTER TABLE classrooms ADD COLUMN suspended_at DATETIME(6) NULL, ALGORITHM=INSTANT', 'SELECT 1');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'audit_events' AND index_name = 'ix_audit_created') = 0,
    'ALTER TABLE audit_events ADD INDEX ix_audit_created (created_at, id), ALGORITHM=INPLACE, LOCK=NONE', 'SELECT 1');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'audit_events' AND index_name = 'ix_audit_class_created') = 0,
    'ALTER TABLE audit_events ADD INDEX ix_audit_class_created (class_id, created_at), ALGORITHM=INPLACE, LOCK=NONE', 'SELECT 1');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'audit_events' AND index_name = 'ix_audit_actor_created') = 0,
    'ALTER TABLE audit_events ADD INDEX ix_audit_actor_created (actor_id, created_at), ALGORITHM=INPLACE, LOCK=NONE', 'SELECT 1');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'audit_events' AND index_name = 'ix_audit_target_created') = 0,
    'ALTER TABLE audit_events ADD INDEX ix_audit_target_created (target_id, created_at), ALGORITHM=INPLACE, LOCK=NONE', 'SELECT 1');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'audit_events' AND index_name = 'ix_audit_action_created') = 0,
    'ALTER TABLE audit_events ADD INDEX ix_audit_action_created (action, created_at), ALGORITHM=INPLACE, LOCK=NONE', 'SELECT 1');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'users' AND index_name = 'ix_users_created') = 0,
    'ALTER TABLE users ADD INDEX ix_users_created (created_at), ALGORITHM=INPLACE, LOCK=NONE', 'SELECT 1');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'users' AND index_name = 'ix_users_status_role') = 0,
    'ALTER TABLE users ADD INDEX ix_users_status_role (status, role), ALGORITHM=INPLACE, LOCK=NONE', 'SELECT 1');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'classrooms' AND index_name = 'ix_classrooms_status_created') = 0,
    'ALTER TABLE classrooms ADD INDEX ix_classrooms_status_created (status, created_at), ALGORITHM=INPLACE, LOCK=NONE', 'SELECT 1');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'orders' AND index_name = 'ix_orders_status_paid') = 0,
    'ALTER TABLE orders ADD INDEX ix_orders_status_paid (status, paid_at), ALGORITHM=INPLACE, LOCK=NONE', 'SELECT 1');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;
