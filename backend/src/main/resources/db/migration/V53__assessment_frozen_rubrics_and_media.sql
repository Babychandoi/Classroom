-- Keep the grading rubric exactly as it was when the version was assigned.
ALTER TABLE assessment_versions ADD COLUMN rubrics_json MEDIUMTEXT NULL;

-- Bind uploads to one attempt and item before accepting a student's evidence.
CREATE TABLE assessment_attempt_uploads (
    asset_id VARCHAR(36) NOT NULL PRIMARY KEY,
    attempt_id VARCHAR(36) NOT NULL,
    item_id VARCHAR(40) NOT NULL,
    duration_ms BIGINT NOT NULL,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_aa_upload_asset FOREIGN KEY(asset_id) REFERENCES media_assets(id) ON DELETE RESTRICT,
    CONSTRAINT fk_aa_upload_attempt FOREIGN KEY(attempt_id) REFERENCES assessment_attempts(id) ON DELETE CASCADE,
    INDEX ix_aa_upload_item(attempt_id,item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
