-- V45 (D-27 review): optimistic-lock version of a blog post, so concurrent edit / publish / unpublish of one post cannot overwrite each other
-- (the losing request gets 409). Metadata-only (INSTANT); every existing row starts at 0. Guarded so a half-applied run can be replayed.

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'blog_posts' AND column_name = 'version') = 0,
    'ALTER TABLE blog_posts ADD COLUMN version BIGINT NOT NULL DEFAULT 0, ALGORITHM=INSTANT',
    'SELECT 1'
);
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;
