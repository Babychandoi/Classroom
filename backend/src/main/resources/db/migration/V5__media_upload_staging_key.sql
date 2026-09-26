ALTER TABLE media_assets ADD COLUMN upload_object_key VARCHAR(512) NULL;
UPDATE media_assets SET upload_object_key = CONCAT('legacy/', id) WHERE upload_object_key IS NULL;
ALTER TABLE media_assets MODIFY upload_object_key VARCHAR(512) NOT NULL;
CREATE UNIQUE INDEX uq_media_upload_object_key ON media_assets (upload_object_key);
