-- Frozen publication payloads live separately so exam metadata lists do not load answer keys/large JSON.
CREATE TABLE exam_publication_snapshots (
    exam_id VARCHAR(36) PRIMARY KEY,
    learner_json MEDIUMTEXT NOT NULL,
    grading_json MEDIUMTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_publication_snapshot_exam FOREIGN KEY (exam_id) REFERENCES exams(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
