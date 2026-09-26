-- Migration V2: Add target product and course columns to document_assets for product-specific access control (Finding 5)
ALTER TABLE document_assets
    ADD COLUMN target_product_id VARCHAR(36) NULL AFTER visibility,
    ADD COLUMN target_course_id VARCHAR(36) NULL AFTER target_product_id;

CREATE INDEX idx_doc_target_prod ON document_assets(class_id, target_product_id);
CREATE INDEX idx_doc_target_course ON document_assets(class_id, target_course_id);
