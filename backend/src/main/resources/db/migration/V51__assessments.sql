-- V51 (D-34): Question Builder assessments ("Bài tập & đề").
--
-- assessments                the editable draft. content_json (MEDIUMTEXT, never the MySQL JSON type) holds the whole AssessmentDocument
--                            (schemaVersion 1: sections > nodes (GROUP{children} | ITEM)). revision is the optimistic-concurrency counter the
--                            builder sends back on every save (409 ASSESSMENT_REVISION_CONFLICT on mismatch); item_count / total_points / skills
--                            are recomputed from the document on every save. status DRAFT | ARCHIVED.
-- assessment_versions        IMMUTABLE snapshots taken at assignment time (version_no 1, 2, ...; reused while the canonical SHA-256
--                            content_hash is unchanged). learner_json = the learner projection (no keys, no transcripts), grading_json = the
--                            full document. No code path ever updates a row.
-- assessment_media_refs      the media files the draft references, rebuilt on every save. RESTRICT: a referenced file cannot be deleted.
-- assessment_version_media   the media files a version references, frozen at snapshot time. RESTRICT as well.
-- question_bank_items        one row per item of each draft (rebuilt on save) - the class question bank index.
--
-- Every instant is DATETIME(6) written by the application in UTC, without CURRENT_TIMESTAMP defaults.

CREATE TABLE IF NOT EXISTS assessments (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    class_id VARCHAR(36) NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    title VARCHAR(200) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    grade INT NULL,
    subject_code VARCHAR(48) NULL,
    language_code VARCHAR(16) NULL,
    topic_title VARCHAR(200) NULL,
    status VARCHAR(16) NOT NULL,
    schema_version INT NOT NULL,
    content_json MEDIUMTEXT NOT NULL,
    revision INT NOT NULL,
    latest_version_no INT NOT NULL DEFAULT 0,
    item_count INT NOT NULL,
    total_points DECIMAL(8,2) NOT NULL,
    skills VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_assessment_class FOREIGN KEY (class_id) REFERENCES classrooms(id) ON DELETE CASCADE,
    CONSTRAINT fk_assessment_creator FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE RESTRICT,
    INDEX ix_assessments_class_status (class_id, status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS assessment_versions (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    assessment_id VARCHAR(36) NOT NULL,
    version_no INT NOT NULL,
    learner_json MEDIUMTEXT NOT NULL,
    grading_json MEDIUMTEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    item_count INT NOT NULL,
    total_points DECIMAL(8,2) NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_assessment_version_no UNIQUE (assessment_id, version_no),
    CONSTRAINT fk_assessment_version_assessment FOREIGN KEY (assessment_id) REFERENCES assessments(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS assessment_media_refs (
    assessment_id VARCHAR(36) NOT NULL,
    media_asset_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (assessment_id, media_asset_id),
    CONSTRAINT fk_assessment_media_ref_assessment FOREIGN KEY (assessment_id) REFERENCES assessments(id) ON DELETE CASCADE,
    CONSTRAINT fk_assessment_media_ref_media FOREIGN KEY (media_asset_id) REFERENCES media_assets(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS assessment_version_media (
    version_id VARCHAR(36) NOT NULL,
    media_asset_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (version_id, media_asset_id),
    CONSTRAINT fk_assessment_version_media_version FOREIGN KEY (version_id) REFERENCES assessment_versions(id) ON DELETE CASCADE,
    CONSTRAINT fk_assessment_version_media_media FOREIGN KEY (media_asset_id) REFERENCES media_assets(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS question_bank_items (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    class_id VARCHAR(36) NOT NULL,
    assessment_id VARCHAR(36) NOT NULL,
    item_id VARCHAR(40) NOT NULL,
    group_title VARCHAR(200) NULL,
    type_code VARCHAR(3) NOT NULL,
    grading_mode VARCHAR(16) NOT NULL,
    prompt_excerpt VARCHAR(300) NOT NULL,
    grade INT NULL,
    subject_code VARCHAR(48) NULL,
    max_points DECIMAL(8,2) NOT NULL,
    item_json MEDIUMTEXT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_question_bank_item UNIQUE (assessment_id, item_id),
    CONSTRAINT fk_question_bank_class FOREIGN KEY (class_id) REFERENCES classrooms(id) ON DELETE CASCADE,
    CONSTRAINT fk_question_bank_assessment FOREIGN KEY (assessment_id) REFERENCES assessments(id) ON DELETE CASCADE,
    INDEX ix_question_bank_class_type (class_id, type_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
