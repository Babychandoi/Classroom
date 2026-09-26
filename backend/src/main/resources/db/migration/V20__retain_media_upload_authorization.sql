ALTER TABLE media_assets ADD COLUMN upload_purpose VARCHAR(32) NOT NULL DEFAULT 'MEDIA';
ALTER TABLE media_assets ADD COLUMN scope_course_id VARCHAR(36) NULL;
