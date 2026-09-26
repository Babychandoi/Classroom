-- Preserve financial and assessment history. Business records are archived by status;
-- deleting a referenced parent must not cascade into evidence needed for disputes.
ALTER TABLE orders DROP FOREIGN KEY fk_ord_buyer;
ALTER TABLE orders ADD CONSTRAINT fk_ord_buyer FOREIGN KEY (buyer_id) REFERENCES users(id) ON DELETE RESTRICT;
ALTER TABLE orders DROP FOREIGN KEY fk_ord_class;
ALTER TABLE orders ADD CONSTRAINT fk_ord_class FOREIGN KEY (class_id) REFERENCES classrooms(id) ON DELETE RESTRICT;

ALTER TABLE order_items DROP FOREIGN KEY fk_oi_order;
ALTER TABLE order_items ADD CONSTRAINT fk_oi_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE RESTRICT;

ALTER TABLE entitlements DROP FOREIGN KEY fk_ent_user;
ALTER TABLE entitlements ADD CONSTRAINT fk_ent_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT;
ALTER TABLE entitlements DROP FOREIGN KEY fk_ent_class;
ALTER TABLE entitlements ADD CONSTRAINT fk_ent_class FOREIGN KEY (class_id) REFERENCES classrooms(id) ON DELETE RESTRICT;
ALTER TABLE entitlements DROP FOREIGN KEY fk_ent_product;
ALTER TABLE entitlements ADD CONSTRAINT fk_ent_product FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE RESTRICT;

ALTER TABLE exams DROP FOREIGN KEY fk_exam_class;
ALTER TABLE exams ADD CONSTRAINT fk_exam_class FOREIGN KEY (class_id) REFERENCES classrooms(id) ON DELETE RESTRICT;
ALTER TABLE exam_attempts DROP FOREIGN KEY fk_ea_exam;
ALTER TABLE exam_attempts ADD CONSTRAINT fk_ea_exam FOREIGN KEY (exam_id) REFERENCES exams(id) ON DELETE RESTRICT;
ALTER TABLE exam_attempts DROP FOREIGN KEY fk_ea_user;
ALTER TABLE exam_attempts ADD CONSTRAINT fk_ea_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT;
ALTER TABLE attempt_answers DROP FOREIGN KEY fk_aa_attempt;
ALTER TABLE attempt_answers ADD CONSTRAINT fk_aa_attempt FOREIGN KEY (attempt_id) REFERENCES exam_attempts(id) ON DELETE RESTRICT;

-- Attempt answers must belong to a question in the same exam as their attempt.
