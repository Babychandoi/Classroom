-- R16-03: products.target_course_id had no foreign key, so deleting a DRAFT course that a product
-- still referenced left a dangling id behind. The product stayed PUBLISHED and, on payment success,
-- the entitlement insert then violated fk_ent_course - the buyer paid and got no access.
--
-- Step 1 - repair existing data BEFORE the constraint exists, so the ALTER below cannot fail on it:
--   a) a blank target_course_id ('') is "no target course" (a class-wide product); normalise it to NULL
--      without touching the product's status;
--   b) a product whose target course no longer exists is taken off sale (ARCHIVED, i.e. "gỡ bán") and
--      its dangling pointer cleared. Entitlements/orders already sold are untouched (they keep their own
--      product_id / target_course_id_snapshot); NULL target_course_id stays legal.
UPDATE products
SET target_course_id = NULL
WHERE target_course_id IS NOT NULL AND TRIM(target_course_id) = '';

UPDATE products p
    LEFT JOIN courses c ON c.id = p.target_course_id
SET p.status = 'ARCHIVED',
    p.target_course_id = NULL
WHERE p.target_course_id IS NOT NULL
  AND c.id IS NULL;

-- Step 2 - the constraint itself. RESTRICT: a course cannot be deleted while a product targets it
-- (LearningService.deleteCourse also refuses first, with a Vietnamese message). Both columns are
-- VARCHAR(36) utf8mb4_unicode_ci; uk_products_target_course_id (V15) already indexes the column.
-- Written to be safe to replay after a partially applied run (MySQL DDL implicitly commits).
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE table_schema = DATABASE() AND table_name = 'products' AND constraint_name = 'fk_prod_target_course') = 0,
    'ALTER TABLE products ADD CONSTRAINT fk_prod_target_course FOREIGN KEY (target_course_id) REFERENCES courses(id) ON DELETE RESTRICT',
    'SELECT 1'
);
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;
