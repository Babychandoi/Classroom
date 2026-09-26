-- Migration V18: Enforce foreign keys for referential integrity across modules (Finding 10)

-- 1. staff_permissions.scope_course_id -> courses.id
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE table_schema = DATABASE() AND table_name = 'staff_permissions' AND constraint_name = 'fk_staff_perm_course') = 0,
    'ALTER TABLE staff_permissions ADD CONSTRAINT fk_staff_perm_course FOREIGN KEY (scope_course_id) REFERENCES courses(id) ON DELETE SET NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. lessons.media_asset_id -> media_assets.id
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE table_schema = DATABASE() AND table_name = 'lessons' AND constraint_name = 'fk_lesson_media') = 0,
    'ALTER TABLE lessons ADD CONSTRAINT fk_lesson_media FOREIGN KEY (media_asset_id) REFERENCES media_assets(id) ON DELETE SET NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. document_assets.media_asset_id -> media_assets.id
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE table_schema = DATABASE() AND table_name = 'document_assets' AND constraint_name = 'fk_doc_media') = 0,
    'ALTER TABLE document_assets ADD CONSTRAINT fk_doc_media FOREIGN KEY (media_asset_id) REFERENCES media_assets(id) ON DELETE RESTRICT',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4. order_items.product_id -> products.id
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE table_schema = DATABASE() AND table_name = 'order_items' AND constraint_name = 'fk_oi_product') = 0,
    'ALTER TABLE order_items ADD CONSTRAINT fk_oi_product FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE RESTRICT',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 5. entitlements.product_id -> products.id
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE table_schema = DATABASE() AND table_name = 'entitlements' AND constraint_name = 'fk_ent_product') = 0,
    'ALTER TABLE entitlements ADD CONSTRAINT fk_ent_product FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 6. entitlements.target_course_id -> courses.id
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE table_schema = DATABASE() AND table_name = 'entitlements' AND constraint_name = 'fk_ent_course') = 0,
    'ALTER TABLE entitlements ADD CONSTRAINT fk_ent_course FOREIGN KEY (target_course_id) REFERENCES courses(id) ON DELETE SET NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 7. exams.target_course_id -> courses.id
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE table_schema = DATABASE() AND table_name = 'exams' AND constraint_name = 'fk_exam_course') = 0,
    'ALTER TABLE exams ADD CONSTRAINT fk_exam_course FOREIGN KEY (target_course_id) REFERENCES courses(id) ON DELETE SET NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 8. exams.target_segment_id -> segments.id
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE table_schema = DATABASE() AND table_name = 'exams' AND constraint_name = 'fk_exam_segment') = 0,
    'ALTER TABLE exams ADD CONSTRAINT fk_exam_segment FOREIGN KEY (target_segment_id) REFERENCES segments(id) ON DELETE SET NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
