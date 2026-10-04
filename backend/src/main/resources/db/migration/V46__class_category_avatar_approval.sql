-- V46 (D-28): the redesigned "Tạo lớp học" page - class category, square class avatar, cover / avatar focal points and join approval.
--
-- classrooms.category          one of the fixed list in ClassCategories (validated by the application); NULL for existing classes.
-- classrooms.avatar_media_id   an UPLOADED image (media purpose CLASS_AVATAR) of the same class; SET NULL: deleting the media never breaks a class.
-- classrooms.cover_position    CSS object-position of the cover ("50% 30%"), NULL = centre. Same for avatar_position.
-- classrooms.require_approval  joins by id of a PUBLIC FREE class wait for a Studio decision (class_members.state = 'PENDING'; the state
--                              column is a free VARCHAR, so the new value needs no DDL). Every existing class keeps FALSE = today's behaviour.
--
-- Every ADD COLUMN is ALGORITHM=INSTANT; the foreign key builds its index on the small classrooms table. Guarded so a half-applied run can be
-- replayed. No date values are converted.

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'classrooms' AND column_name = 'category') = 0,
    'ALTER TABLE classrooms ADD COLUMN category VARCHAR(40) NULL, ALGORITHM=INSTANT', 'SELECT 1');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'classrooms' AND column_name = 'avatar_media_id') = 0,
    'ALTER TABLE classrooms ADD COLUMN avatar_media_id VARCHAR(36) NULL, ALGORITHM=INSTANT', 'SELECT 1');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'classrooms' AND column_name = 'cover_position') = 0,
    'ALTER TABLE classrooms ADD COLUMN cover_position VARCHAR(16) NULL, ALGORITHM=INSTANT', 'SELECT 1');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'classrooms' AND column_name = 'avatar_position') = 0,
    'ALTER TABLE classrooms ADD COLUMN avatar_position VARCHAR(16) NULL, ALGORITHM=INSTANT', 'SELECT 1');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'classrooms' AND column_name = 'require_approval') = 0,
    'ALTER TABLE classrooms ADD COLUMN require_approval BOOLEAN NOT NULL DEFAULT FALSE, ALGORITHM=INSTANT', 'SELECT 1');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.table_constraints
               WHERE table_schema = DATABASE() AND table_name = 'classrooms' AND constraint_name = 'fk_class_avatar_media') = 0,
    'ALTER TABLE classrooms ADD CONSTRAINT fk_class_avatar_media FOREIGN KEY (avatar_media_id) REFERENCES media_assets(id) ON DELETE SET NULL',
    'SELECT 1');
PREPARE migration_stmt FROM @ddl; EXECUTE migration_stmt; DEALLOCATE PREPARE migration_stmt;

-- The Studio "Yêu cầu tham gia" queue and pendingRequestCount (WHERE class_id = ? AND state = 'PENDING') are served by the existing
-- ix_class_members_access_expiry (class_id, state, access_expires_at) from V37; no new index.
