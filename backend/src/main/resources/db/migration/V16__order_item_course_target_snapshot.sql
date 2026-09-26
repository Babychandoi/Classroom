ALTER TABLE order_items ADD COLUMN target_course_id_snapshot VARCHAR(36) NULL;

-- Preserve the currently recorded target for legacy orders. New orders snapshot
-- the target while holding the product lock.
UPDATE order_items oi
JOIN products p ON p.id = oi.product_id
SET oi.target_course_id_snapshot = p.target_course_id;
