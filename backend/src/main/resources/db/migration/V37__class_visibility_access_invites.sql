-- V37 (D-19): class visibility (PUBLIC | PRIVATE), class access type (FREE | PAID), paid-access expiry of a membership and invite links.
--
-- classrooms.visibility          PUBLIC (default - every existing class keeps today's behaviour) | PRIVATE (hidden from non-members; joinable by invite only)
-- classrooms.access_type         FREE (default) | PAID (a non-member must buy the class-access product to belong to the class)
-- classrooms.access_product_id   the class-access product of a PAID class (NULL while the class has never been PAID). RESTRICT: a product that a
--                                class still points at cannot be deleted. Kept after PAID -> FREE (the product is ARCHIVED, not dropped) so a
--                                later FREE -> PAID re-uses it and the entitlement/order history stays attached to one product.
-- class_members.access_expires_at  end of the paid access of a member. NULL = no expiry (free classes, owner/staff, members grandfathered when a
--                                free class became paid, lifetime purchases). The state machine gains EXPIRED (see docs/DECISIONS.md D-19).
-- class_invites                  one row per invite link. Only the SHA-256 of the code is stored (+ the last 4 characters as a hint for the list).
-- products.kind                  STANDARD (default - everything that exists today: PRO packages and course products, no backfill needed) |
--                                CLASS_ACCESS (the product that sells membership of a PAID class). An explicit column instead of overloading
--                                target_course_id (NULL there already means "PRO package").
--
-- Cost on big tables: every ADD COLUMN is ALGORITHM=INSTANT (metadata only, MySQL 8.4); both class_members indexes are built ONLINE (INPLACE,
-- LOCK=NONE). Every statement is guarded by an information_schema check so the script can be replayed after a partially applied run (MySQL DDL
-- implicitly commits). Nothing here rewrites or changes an existing row.
--
-- Time zone (R20-08): all new date columns are DATETIME(6) written by the application in UTC; class_invites.created_at deliberately has NO
-- CURRENT_TIMESTAMP default (whose value would follow the server zone) - the application always supplies it. This script converts and compares no
-- date values, so it does NOT opt in to the startup UTC preflight (it runs on any server zone); the DatabaseTimeZonePreflight still refuses to start
-- an older pending V30 on a non-UTC server before this script can run.

-- 1. classrooms ---------------------------------------------------------------------------------------------------------------------------------

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'classrooms' AND column_name = 'visibility') = 0,
    'ALTER TABLE classrooms ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT ''PUBLIC'', ALGORITHM=INSTANT',
    'SELECT 1'
);
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'classrooms' AND column_name = 'access_type') = 0,
    'ALTER TABLE classrooms ADD COLUMN access_type VARCHAR(16) NOT NULL DEFAULT ''FREE'', ALGORITHM=INSTANT',
    'SELECT 1'
);
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'classrooms' AND column_name = 'access_product_id') = 0,
    'ALTER TABLE classrooms ADD COLUMN access_product_id VARCHAR(36) NULL, ALGORITHM=INSTANT',
    'SELECT 1'
);
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

-- The constraint also builds the supporting index on access_product_id. classrooms is small (one row per class), so the table-copy a foreign
-- key needs while foreign_key_checks is on is cheap. Both columns are VARCHAR(36) utf8mb4_unicode_ci.
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE table_schema = DATABASE() AND table_name = 'classrooms' AND constraint_name = 'fk_class_access_product') = 0,
    'ALTER TABLE classrooms ADD CONSTRAINT fk_class_access_product FOREIGN KEY (access_product_id) REFERENCES products(id) ON DELETE RESTRICT',
    'SELECT 1'
);
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

-- 2. class_members --------------------------------------------------------------------------------------------------------------------------------

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'class_members' AND column_name = 'access_expires_at') = 0,
    'ALTER TABLE class_members ADD COLUMN access_expires_at DATETIME(6) NULL, ALGORITHM=INSTANT',
    'SELECT 1'
);
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

-- (class_id, state, access_expires_at): per-class work - who is about to lapse, the PAID -> FREE clean-up, the active-member headcount.
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'class_members' AND index_name = 'ix_class_members_access_expiry') = 0,
    'ALTER TABLE class_members ADD INDEX ix_class_members_access_expiry (class_id, state, access_expires_at), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1'
);
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

-- (state, access_expires_at): the expiry sweeper looks across ALL classes for "ACTIVE and already lapsed", which the class-first index above
-- cannot serve; with this one an idle sweep is a single empty range probe however many members exist.
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'class_members' AND index_name = 'ix_class_members_expiry_sweep') = 0,
    'ALTER TABLE class_members ADD INDEX ix_class_members_expiry_sweep (state, access_expires_at), ALGORITHM=INPLACE, LOCK=NONE',
    'SELECT 1'
);
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

-- 3. products ---------------------------------------------------------------------------------------------------------------------------------

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'products' AND column_name = 'kind') = 0,
    'ALTER TABLE products ADD COLUMN kind VARCHAR(24) NOT NULL DEFAULT ''STANDARD'', ALGORITHM=INSTANT',
    'SELECT 1'
);
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

-- orders.invite_id: the invite a buyer used to reach the checkout of a PRIVATE paid class. A use of the invite is reserved when the order is created
-- and given back if that order is cancelled or fails, so max_uses holds for paid classes too. NULL for every other order (no FK on purpose: an
-- order is a financial record and must never block, or be cascaded by, the deletion of an invite).
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'orders' AND column_name = 'invite_id') = 0,
    'ALTER TABLE orders ADD COLUMN invite_id VARCHAR(36) NULL, ALGORITHM=INSTANT',
    'SELECT 1'
);
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

-- 4. class_invites -----------------------------------------------------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS class_invites (
    id VARCHAR(36) PRIMARY KEY,
    class_id VARCHAR(36) NOT NULL,
    code_hash CHAR(64) NOT NULL,
    code_hint VARCHAR(8) NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NULL,
    max_uses INT NULL,
    used_count INT NOT NULL DEFAULT 0,
    revoked_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_class_invites_code_hash (code_hash),
    KEY ix_class_invites_class (class_id, created_at),
    CONSTRAINT fk_invite_class FOREIGN KEY (class_id) REFERENCES classrooms(id) ON DELETE CASCADE,
    CONSTRAINT fk_invite_creator FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
