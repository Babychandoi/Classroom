-- V48 (D-31): a VIDEO lesson may point at an external YouTube / Google Drive video instead of an uploaded file. The server parses the pasted link
-- (VideoLinkParser) and stores only the provider and the provider's video/file id; URLs are derived on read. Metadata-only (INSTANT), NULL for
-- every existing row. Guarded so a half-applied run can be replayed.

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'lessons' AND column_name = 'video_provider') = 0,
    'ALTER TABLE lessons ADD COLUMN video_provider VARCHAR(16) NULL, ALGORITHM=INSTANT',
    'SELECT 1'
);
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'lessons' AND column_name = 'video_ref') = 0,
    'ALTER TABLE lessons ADD COLUMN video_ref VARCHAR(128) NULL, ALGORITHM=INSTANT',
    'SELECT 1'
);
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;
