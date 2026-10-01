-- R13-03: content lifecycle (edit/archive/delete) for sections, lessons, exams, products, orders, attempts.

-- Sections and lessons: soft "archived" flag lets Studio hide content that has learner progress
-- or submissions attached instead of hard-deleting it (would orphan progress/answers/FKs cascade).
ALTER TABLE sections ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE lessons ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;

-- Product: ARCHIVED means "gỡ bán" — no longer purchasable/listed, but existing entitlements
-- (already granted) must keep working until they naturally expire. `status` already exists
-- (DRAFT/PUBLISHED); ARCHIVED is a new allowed value, no column change required.

-- Order: buyer-initiated cancellation of a PENDING order.
-- Column already covers CANCELLED (see V1 order status free-text); no schema change required.

-- Exam attempt: CANCELLED status (e.g. staff invalidates an attempt). Column is free-text status;
-- no schema change required, but keep a reason for audit purposes.
ALTER TABLE exam_attempts ADD COLUMN cancel_reason VARCHAR(512) NULL;

-- Exam: OPEN/CLOSED/ARCHIVED lifecycle needs an explicit closed_at marker for "close early".
ALTER TABLE exams ADD COLUMN closed_at TIMESTAMP NULL;
