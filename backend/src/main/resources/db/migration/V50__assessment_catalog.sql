-- V50 (D-34): the data-driven education catalog behind the Question Builder (grade > subject > topic) and the rubric presets.
--
-- edu_levels                 TH (lớp 1-5), THCS (6-9), THPT (10-12).
-- edu_grades                 one row per grade 1..12, pointing at its level.
-- edu_subjects               subjects, integrated-subject components, activities and topic clusters (kind SUBJECT | COMPONENT | ACTIVITY |
--                            TOPIC_CLUSTER). suggested_types is a space-separated list of question type codes (Q01..Q28).
-- edu_grade_subjects         which subject is taught in which grade (data, never code).
-- edu_subject_components     the components of an integrated subject (KHTN -> Vật lí / Hóa học / Sinh học ...).
-- edu_languages              foreign languages and their voice / accent codes (space-separated).
-- edu_topics                 optional topics; class_id NULL = shared, otherwise owned by the class (CASCADE with it).
-- assessment_rubrics         rubric presets (system_preset, class_id NULL) and class rubrics; levels / criteria are JSON in TEXT columns.
--
-- The rows themselves are inserted by the idempotent AssessmentCatalogSeeder from assessment/catalog-v1.json (it inserts missing codes and never
-- updates or deletes), so this script only creates the tables. Every instant is DATETIME(6) written by the application in UTC, no defaults.

CREATE TABLE IF NOT EXISTS edu_levels (
    code VARCHAR(8) NOT NULL PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    sort_order INT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS edu_grades (
    grade INT NOT NULL PRIMARY KEY,
    level_code VARCHAR(8) NOT NULL,
    name VARCHAR(40) NOT NULL,
    CONSTRAINT fk_edu_grade_level FOREIGN KEY (level_code) REFERENCES edu_levels(code) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS edu_subjects (
    code VARCHAR(48) NOT NULL PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    integrated BOOLEAN NOT NULL,
    group_label VARCHAR(60) NULL,
    parent_code VARCHAR(48) NULL,
    language_required BOOLEAN NOT NULL,
    default_language_code VARCHAR(16) NULL,
    suggested_types VARCHAR(255) NOT NULL,
    sort_order INT NOT NULL,
    active BOOLEAN NOT NULL,
    INDEX ix_edu_subjects_kind (kind, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS edu_grade_subjects (
    grade INT NOT NULL,
    subject_code VARCHAR(48) NOT NULL,
    PRIMARY KEY (grade, subject_code),
    CONSTRAINT fk_edu_grade_subject_grade FOREIGN KEY (grade) REFERENCES edu_grades(grade) ON DELETE CASCADE,
    CONSTRAINT fk_edu_grade_subject_subject FOREIGN KEY (subject_code) REFERENCES edu_subjects(code) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS edu_subject_components (
    parent_code VARCHAR(48) NOT NULL,
    component_code VARCHAR(48) NOT NULL,
    PRIMARY KEY (parent_code, component_code),
    CONSTRAINT fk_edu_component_parent FOREIGN KEY (parent_code) REFERENCES edu_subjects(code) ON DELETE CASCADE,
    CONSTRAINT fk_edu_component_child FOREIGN KEY (component_code) REFERENCES edu_subjects(code) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS edu_languages (
    code VARCHAR(16) NOT NULL PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    voices VARCHAR(500) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS edu_topics (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    class_id VARCHAR(36) NULL,
    subject_code VARCHAR(48) NOT NULL,
    grade INT NOT NULL,
    parent_id VARCHAR(36) NULL,
    title VARCHAR(200) NOT NULL,
    sort_order INT NOT NULL,
    created_by VARCHAR(36) NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_edu_topic_class FOREIGN KEY (class_id) REFERENCES classrooms(id) ON DELETE CASCADE,
    CONSTRAINT fk_edu_topic_subject FOREIGN KEY (subject_code) REFERENCES edu_subjects(code) ON DELETE CASCADE,
    INDEX ix_edu_topics_lookup (subject_code, grade, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS assessment_rubrics (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    class_id VARCHAR(36) NULL,
    title VARCHAR(200) NOT NULL,
    levels_json TEXT NOT NULL,
    criteria_json TEXT NOT NULL,
    system_preset BOOLEAN NOT NULL,
    created_by VARCHAR(36) NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_assessment_rubric_class FOREIGN KEY (class_id) REFERENCES classrooms(id) ON DELETE CASCADE,
    INDEX ix_assessment_rubrics_class (class_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
