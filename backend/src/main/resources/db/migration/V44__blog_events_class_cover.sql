-- V44 (D-27): class blog, class events (+ registrations) and an uploaded class cover image.
--
-- classrooms.cover_media_id        an UPLOADED image (media purpose CLASS_COVER) of the same class; NULL = no uploaded cover (the legacy
--                                  cover_image_url stays as it is). SET NULL: removing the media row never breaks the class.
-- blog_posts                       one row per blog post. status DRAFT | PUBLISHED, audience PUBLIC | MEMBERS. published_at is set on the FIRST
--                                  publish and kept across unpublish / republish, so the public order is stable. reading_minutes is computed by
--                                  the application from content_markdown on every write (ceil(words / 200), min 1).
-- class_events                     one row per event. registered_count is a denormalised counter maintained ONLY under a row lock on the event
--                                  (SELECT ... FOR UPDATE) together with the registration rows, so capacity can never be exceeded.
-- class_event_registrations        (event_id, user_id) unique: registering twice is idempotent at the database level too.
--
-- Time zone (R20-08): every instant is DATETIME(6), written by the application in UTC, without CURRENT_TIMESTAMP defaults. This script compares
-- or converts no date values. Every statement is guarded (information_schema / IF NOT EXISTS) so a half-applied run can be replayed.

-- 1. classrooms.cover_media_id -----------------------------------------------------------------------------------------------------------------

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'classrooms' AND column_name = 'cover_media_id') = 0,
    'ALTER TABLE classrooms ADD COLUMN cover_media_id VARCHAR(36) NULL, ALGORITHM=INSTANT',
    'SELECT 1'
);
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE table_schema = DATABASE() AND table_name = 'classrooms' AND constraint_name = 'fk_class_cover_media') = 0,
    'ALTER TABLE classrooms ADD CONSTRAINT fk_class_cover_media FOREIGN KEY (cover_media_id) REFERENCES media_assets(id) ON DELETE SET NULL',
    'SELECT 1'
);
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

-- 2. blog_posts ----------------------------------------------------------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS blog_posts (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    class_id VARCHAR(36) NOT NULL,
    author_id VARCHAR(36) NOT NULL,
    title VARCHAR(200) NOT NULL,
    excerpt VARCHAR(300) NULL,
    category VARCHAR(60) NULL,
    content_markdown MEDIUMTEXT NULL,
    cover_media_id VARCHAR(36) NULL,
    audience VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    reading_minutes INT NOT NULL DEFAULT 1,
    published_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_blog_post_class FOREIGN KEY (class_id) REFERENCES classrooms(id) ON DELETE CASCADE,
    CONSTRAINT fk_blog_post_author FOREIGN KEY (author_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_blog_post_cover FOREIGN KEY (cover_media_id) REFERENCES media_assets(id) ON DELETE SET NULL,
    -- the public list: WHERE class_id = ? AND status = 'PUBLISHED' ORDER BY published_at DESC, id DESC (keyset)
    INDEX ix_blog_posts_class_published (class_id, status, published_at, id),
    INDEX ix_blog_posts_class_category (class_id, status, category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. class_events --------------------------------------------------------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS class_events (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    class_id VARCHAR(36) NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    host_user_id VARCHAR(36) NOT NULL,
    title VARCHAR(200) NOT NULL,
    description MEDIUMTEXT NULL,
    for_whom VARCHAR(500) NULL,
    takeaways_json TEXT NULL,
    format VARCHAR(16) NOT NULL,
    location VARCHAR(300) NULL,
    meeting_url VARCHAR(1000) NULL,
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NOT NULL,
    capacity INT NULL,
    registered_count INT NOT NULL DEFAULT 0,
    cover_media_id VARCHAR(36) NULL,
    audience VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_class_event_class FOREIGN KEY (class_id) REFERENCES classrooms(id) ON DELETE CASCADE,
    CONSTRAINT fk_class_event_creator FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_class_event_host FOREIGN KEY (host_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_class_event_cover FOREIGN KEY (cover_media_id) REFERENCES media_assets(id) ON DELETE SET NULL,
    -- per class: upcoming (ends_at >= now ORDER BY starts_at) / past, and the upcoming-event count of the class cards
    INDEX ix_class_events_class_time (class_id, ends_at, starts_at),
    -- cross-class rail: status = 'SCHEDULED' AND ends_at >= now ORDER BY starts_at
    INDEX ix_class_events_upcoming (status, ends_at, starts_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. class_event_registrations -------------------------------------------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS class_event_registrations (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    event_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    registered_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_class_event_registration UNIQUE (event_id, user_id),
    CONSTRAINT fk_event_registration_event FOREIGN KEY (event_id) REFERENCES class_events(id) ON DELETE CASCADE,
    CONSTRAINT fk_event_registration_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX ix_event_registration_user (user_id, event_id),
    INDEX ix_event_registration_event_time (event_id, registered_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
