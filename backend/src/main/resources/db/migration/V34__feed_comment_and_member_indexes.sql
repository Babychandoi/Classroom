-- V34 (R20-03): indexes for the batched feed / member queries.
--
-- The feed now embeds only the latest few comments of every post of a page in ONE query
-- (ROW_NUMBER() OVER (PARTITION BY post_id ORDER BY created_at DESC, id DESC) restricted to the page's post ids) and has a
-- paged "older comments" endpoint ordered by (created_at, id): both are served by an index on (post_id, created_at, id) instead
-- of sorting every comment of every post.
--
-- Studio member paging orders by joined_at within a class and filters by state / role: (class_id, state, joined_at) serves
-- the default "ACTIVE members, newest first" page without scanning the whole roster.
-- Each statement is guarded so the script can be replayed after a partially applied run (MySQL DDL implicitly commits).

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'comments' AND index_name = 'ix_comments_post_created') = 0,
    'ALTER TABLE comments ADD INDEX ix_comments_post_created (post_id, created_at, id)',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'class_members' AND index_name = 'ix_class_members_state_joined') = 0,
    'ALTER TABLE class_members ADD INDEX ix_class_members_state_joined (class_id, state, joined_at)',
    'SELECT 1'
);
PREPARE stmt2 FROM @ddl;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;
