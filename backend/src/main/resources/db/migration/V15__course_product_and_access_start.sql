-- MySQL DDL implicitly commits. If a previous attempt stopped part-way through,
-- make each operation safe to replay after Flyway's documented repair procedure.
-- MySQL permits multiple NULL values in these unique indexes for unlinked records.
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'courses' AND index_name = 'uk_courses_product_id') = 0,
    'CREATE UNIQUE INDEX uk_courses_product_id ON courses(product_id)',
    'SELECT 1'
);
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'products' AND index_name = 'uk_products_target_course_id') = 0,
    'CREATE UNIQUE INDEX uk_products_target_course_id ON products(target_course_id)',
    'SELECT 1'
);
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'product_prices' AND column_name = 'access_starts_at') = 0,
    'ALTER TABLE product_prices ADD COLUMN access_starts_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)',
    'SELECT 1'
);
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'order_items' AND column_name = 'access_starts_at_snapshot') = 0,
    'ALTER TABLE order_items ADD COLUMN access_starts_at_snapshot TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)',
    'SELECT 1'
);
PREPARE migration_stmt FROM @ddl;
EXECUTE migration_stmt;
DEALLOCATE PREPARE migration_stmt;
