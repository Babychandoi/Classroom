-- V49 (D-32): a lesson is a bundle of optional components (video, content, documents, assignment) instead of one "type".
--
-- lessons.has_assignment            the assignment component (the submit form), replaces type = 'ASSIGNMENT'.
-- lessons.assignment_instructions   its Markdown instructions (previously the lesson's content_text).
-- lesson_attachments                0..20 documents per lesson: an UPLOADED media asset each (unique per asset). The lesson FK cascades; the
--                                   media FK RESTRICTs, so a file that is attached can never be deleted from under a lesson.
-- lessons.type                      stays as a DERIVED summary the application recomputes on every write:
--                                   assignment -> ASSIGNMENT, else video (uploaded file or external link) -> VIDEO, else documents -> DOCUMENT, else TEXT.
--
-- Data migration (sections 3-6), in this order because each step reads what the previous one has not changed yet:
--   3. every lesson whose uploaded file (media_asset_id) is NOT a video/audio file becomes one attachment titled like the lesson (this covers
--      the DOCUMENT lessons of the old model); one lesson per file (the lowest id) if a legacy file is shared;
--   4. the file column of exactly those lessons is cleared (media_asset_id now means "uploaded video" only);
--   5. ASSIGNMENT lessons: has_assignment = TRUE, the content_text moves to assignment_instructions and content_text is cleared;
--   6. type is recomputed for every row.
-- Every step is idempotent: a replay after a half-applied run changes nothing that is already migrated. Time zone: the only date written is
-- UTC_TIMESTAMP(6) (session independent), the column is DATETIME(6) without a default, like every table since V44.

-- 1. columns -------------------------------------------------------------------------------------------------------------------------------------

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'lessons' AND column_name = 'has_assignment') = 0,
    'ALTER TABLE lessons ADD COLUMN has_assignment BOOLEAN NOT NULL DEFAULT FALSE, ALGORITHM=INSTANT',
    'SELECT 1'
);
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'lessons' AND column_name = 'assignment_instructions') = 0,
    'ALTER TABLE lessons ADD COLUMN assignment_instructions MEDIUMTEXT NULL, ALGORITHM=INSTANT',
    'SELECT 1'
);
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

-- 2. lesson_attachments --------------------------------------------------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS lesson_attachments (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    lesson_id VARCHAR(36) NOT NULL,
    media_asset_id VARCHAR(36) NOT NULL,
    title VARCHAR(200) NOT NULL,
    position INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_lesson_attachment_media UNIQUE (media_asset_id),
    CONSTRAINT fk_lesson_attachment_lesson FOREIGN KEY (lesson_id) REFERENCES lessons(id) ON DELETE CASCADE,
    CONSTRAINT fk_lesson_attachment_media FOREIGN KEY (media_asset_id) REFERENCES media_assets(id) ON DELETE RESTRICT,
    INDEX ix_lesson_attachments_lesson (lesson_id, position)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. non-video files of lessons -> attachments ----------------------------------------------------------------------------------------------------

INSERT INTO lesson_attachments (id, lesson_id, media_asset_id, title, position, created_at)
SELECT UUID(), l.id, l.media_asset_id, LEFT(l.title, 200), 0, UTC_TIMESTAMP(6)
FROM lessons l
JOIN media_assets m ON m.id = l.media_asset_id
WHERE l.media_asset_id IS NOT NULL
  AND LOWER(m.mime_type) NOT LIKE 'video/%'
  AND LOWER(m.mime_type) NOT LIKE 'audio/%'
  AND l.id = (SELECT MIN(l2.id) FROM lessons l2 WHERE l2.media_asset_id = l.media_asset_id)
  AND NOT EXISTS (SELECT 1 FROM lesson_attachments a WHERE a.media_asset_id = l.media_asset_id);

-- 4. clear the file column of the lessons that now hold the file as a document --------------------------------------------------------------------

UPDATE lessons SET media_asset_id = NULL
WHERE media_asset_id IS NOT NULL
  AND EXISTS (SELECT 1 FROM lesson_attachments a WHERE a.lesson_id = lessons.id AND a.media_asset_id = lessons.media_asset_id);

-- 5. ASSIGNMENT lessons -> assignment component ---------------------------------------------------------------------------------------------------

UPDATE lessons SET has_assignment = TRUE, assignment_instructions = content_text, content_text = NULL
WHERE type = 'ASSIGNMENT' AND has_assignment = FALSE;

-- 6. the derived type for every row ---------------------------------------------------------------------------------------------------------------

UPDATE lessons SET type = CASE
    WHEN has_assignment = TRUE THEN 'ASSIGNMENT'
    WHEN media_asset_id IS NOT NULL OR (video_provider IS NOT NULL AND video_ref IS NOT NULL) THEN 'VIDEO'
    WHEN EXISTS (SELECT 1 FROM lesson_attachments a WHERE a.lesson_id = lessons.id) THEN 'DOCUMENT'
    ELSE 'TEXT'
END;
