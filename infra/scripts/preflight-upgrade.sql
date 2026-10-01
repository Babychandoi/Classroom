-- =====================================================================================================================
-- preflight-upgrade.sql  (R20-09 / R20-10 / R20-11)  -  kiểm tra TRƯỚC KHI nâng cấp một CSDL cũ.  READ-ONLY (chỉ SELECT).
-- =====================================================================================================================
-- Vì sao cần: các migration V17 và V18 đã được áp dụng ở mọi nơi quan trọng nên KHÔNG được sửa (checksum). Nhưng một CSDL còn ở schema
-- < V17 / < V18 mà có dữ liệu "bẩn" sẽ làm chúng THẤT BẠI giữa chừng (MySQL DDL tự commit -> phải flyway repair):
--   * V17 (R20-10): điền attempt_number cho các lượt thi cũ bằng ROW_NUMBER() bắt đầu từ 1, nên va chạm với những attempt_number ĐÃ CÓ của
--     cùng (exam_id, user_id) -> lỗi khóa duy nhất uq_ea_exam_user_attempt.
--   * V18 (R20-09): thêm 8 khóa ngoại; nếu còn bản ghi trỏ tới hàng đã bị xóa (mồ côi) thì ALTER ... ADD CONSTRAINT báo lỗi.
--   * V30 (R20-11): đổi 10 cột TIMESTAMP -> DATETIME bằng ALGORITHM=COPY: bảng bị KHÓA GHI trong lúc sao chép (xem phần C).
--
-- Cách chạy (không cần dừng ứng dụng; chỉ đọc):
--   docker exec -i classroom-mysql sh -c 'MYSQL_PWD=$MYSQL_ROOT_PASSWORD mysql -uroot $MYSQL_DATABASE -t' < infra/scripts/preflight-upgrade.sql
--   (stack drill: thay classroom-mysql bằng classroom-drill-mysql)
-- Đọc kết quả: MỖI truy vấn kiểm tra "0 rows" (hoặc dòng "OK") là đạt. Truy vấn liệt kê => có hàng cần xử lý TRƯỚC khi nâng cấp.
-- Các lệnh DỌN DẸP nằm trong khối chú thích "-- CLEANUP" ở cuối mỗi phần: đọc kỹ, SAO LƯU (RUNBOOK 5.1), chạy trong giao dịch, rồi chạy lại
-- script này cho tới khi sạch. Không lệnh nào ở đây tự chạy.
-- =====================================================================================================================

-- ---------------------------------------------------------------------------------------------------------------------
-- 0. Ngữ cảnh: phiên bản schema hiện tại và múi giờ của máy chủ MySQL
-- ---------------------------------------------------------------------------------------------------------------------
SET @q = IF((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'flyway_schema_history') = 1,
  'SELECT ''schema version (max applied Flyway migration)'' AS info, (SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success = 1) AS value',
  'SELECT ''schema version'' AS info, ''no flyway_schema_history table (never started by the backend): every migration is still pending'' AS value');
PREPARE s FROM @q; EXECUTE s; DEALLOCATE PREPARE s;
-- Phần A chỉ cần khi version < 17; phần B chỉ cần khi version < 18; phần C khi version < 30 (đã >= 30 thì V30 đã chạy rồi).

-- R20-08: V30 giả định máy chủ MySQL chạy UTC. Backend TỪ CHỐI khởi động nếu V30 còn chờ mà múi giờ không phải UTC (app.db.allow-non-utc-migration
-- để ghi đè). Cột "offset_from_utc" phải là 00:00:00 và global_time_zone phải là UTC / +00:00 / SYSTEM (với system_time_zone = UTC).
SELECT @@global.time_zone AS global_time_zone, @@session.time_zone AS session_time_zone, @@system_time_zone AS system_time_zone,
       TIMEDIFF(NOW(), UTC_TIMESTAMP) AS offset_from_utc,
       IF(TIMEDIFF(NOW(), UTC_TIMESTAMP) = '00:00:00', 'OK: server is at UTC offset', 'NOT UTC: fix default-time-zone BEFORE upgrading past V29') AS verdict;

-- ---------------------------------------------------------------------------------------------------------------------
-- A. R20-10 - V17 (schema < 17): lượt thi của học viên chưa có attempt_number va chạm với số đã có
--    (cần cột exam_attempts.attempt_number của V3; nếu schema < V3 thì không có gì để va chạm)
-- ---------------------------------------------------------------------------------------------------------------------
SET @has_attempt_number = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'exam_attempts' AND column_name = 'attempt_number');

-- A0. Tổng quan: bao nhiêu lượt thi của học viên còn attempt_number NULL (V17 sẽ đánh số lại tất cả)
SET @q = IF(@has_attempt_number = 1,
  'SELECT COUNT(*) AS learner_attempts_with_null_number FROM exam_attempts WHERE is_preview = FALSE AND attempt_number IS NULL',
  'SELECT ''A0 skipped: exam_attempts.attempt_number does not exist yet (schema < V3)'' AS note');
PREPARE s FROM @q; EXECUTE s; DEALLOCATE PREPARE s;

-- A1. (exam, user) có CẢ lượt đã đánh số LẪN lượt NULL: ứng viên va chạm. Phải trả về 0 dòng trước khi nâng cấp lên V17.
SET @q = IF(@has_attempt_number = 1,
  'SELECT n.exam_id, n.user_id, COUNT(*) AS null_attempts, MIN(k.attempt_number) AS min_numbered, MAX(k.attempt_number) AS max_numbered, COUNT(DISTINCT k.id) AS numbered_attempts
   FROM exam_attempts n
   JOIN exam_attempts k ON k.exam_id = n.exam_id AND k.user_id = n.user_id AND k.is_preview = FALSE AND k.attempt_number IS NOT NULL
   WHERE n.is_preview = FALSE AND n.attempt_number IS NULL
   GROUP BY n.exam_id, n.user_id ORDER BY null_attempts DESC LIMIT 200',
  'SELECT ''A1 skipped: schema < V3'' AS note');
PREPARE s FROM @q; EXECUTE s; DEALLOCATE PREPARE s;

-- A2. Chính xác những hàng mà ROW_NUMBER() của V17 sẽ gán TRÙNG một attempt_number đã tồn tại (nguyên nhân gây lỗi). Phải là 0 dòng.
SET @q = IF(@has_attempt_number = 1,
  'SELECT r.id AS attempt_id, r.exam_id, r.user_id, r.rn AS number_v17_would_assign
   FROM (SELECT id, exam_id, user_id, ROW_NUMBER() OVER (PARTITION BY exam_id, user_id ORDER BY started_at, id) AS rn
         FROM exam_attempts WHERE is_preview = FALSE AND attempt_number IS NULL) r
   JOIN exam_attempts k ON k.exam_id = r.exam_id AND k.user_id = r.user_id AND k.is_preview = FALSE AND k.attempt_number = r.rn
   ORDER BY r.exam_id, r.user_id, r.rn LIMIT 200',
  'SELECT ''A2 skipped: schema < V3'' AS note');
PREPARE s FROM @q; EXECUTE s; DEALLOCATE PREPARE s;

-- CLEANUP A (chạy TRƯỚC khi nâng cấp; xong thì V17 không còn hàng NULL để đánh số và không thể va chạm).
-- Đánh số các lượt NULL tiếp nối SAU số lớn nhất đã có của cùng (exam, user), theo thứ tự started_at, id:
--   START TRANSACTION;
--   UPDATE exam_attempts ea
--   JOIN (
--       SELECT n.id, COALESCE(m.max_no, 0) + ROW_NUMBER() OVER (PARTITION BY n.exam_id, n.user_id ORDER BY n.started_at, n.id) AS new_no
--       FROM exam_attempts n
--       LEFT JOIN (SELECT exam_id, user_id, MAX(attempt_number) AS max_no FROM exam_attempts
--                  WHERE is_preview = FALSE AND attempt_number IS NOT NULL GROUP BY exam_id, user_id) m
--              ON m.exam_id = n.exam_id AND m.user_id = n.user_id
--       WHERE n.is_preview = FALSE AND n.attempt_number IS NULL
--   ) r ON r.id = ea.id
--   SET ea.attempt_number = r.new_no;
--   -- kiểm tra: phải là 0 dòng ở A0/A1/A2 khi chạy lại script này, và không có (exam_id, user_id, attempt_number) trùng:
--   SELECT exam_id, user_id, attempt_number, COUNT(*) c FROM exam_attempts WHERE is_preview = FALSE GROUP BY 1,2,3 HAVING c > 1;
--   COMMIT;   -- hoặc ROLLBACK nếu kết quả không như mong đợi
-- Lưu ý nghiệp vụ: số thứ tự mới có thể vượt attemptLimit của kỳ thi (lượt cũ nay được coi là các lượt sau cùng); xem lại với chủ lớp nếu cần.

-- ---------------------------------------------------------------------------------------------------------------------
-- B. R20-09 - V18 (schema < 18): bản ghi MỒ CÔI (trỏ tới hàng đã bị xóa). Mỗi truy vấn phải trả về 0 dòng trước khi nâng cấp lên V18.
--    (tám khóa ngoại của V18; tất cả các bảng/cột này đã có từ V1)
-- ---------------------------------------------------------------------------------------------------------------------
-- B0. Tóm tắt: số hàng mồ côi theo từng khóa ngoại
SELECT 'fk_staff_perm_course  staff_permissions.scope_course_id -> courses'   AS constraint_v18,
       COUNT(*) AS orphans FROM staff_permissions sp LEFT JOIN courses c ON c.id = sp.scope_course_id WHERE sp.scope_course_id IS NOT NULL AND c.id IS NULL
UNION ALL SELECT 'fk_lesson_media  lessons.media_asset_id -> media_assets',
       COUNT(*) FROM lessons l LEFT JOIN media_assets m ON m.id = l.media_asset_id WHERE l.media_asset_id IS NOT NULL AND m.id IS NULL
UNION ALL SELECT 'fk_doc_media  document_assets.media_asset_id -> media_assets',
       COUNT(*) FROM document_assets d LEFT JOIN media_assets m ON m.id = d.media_asset_id WHERE m.id IS NULL
UNION ALL SELECT 'fk_oi_product  order_items.product_id -> products',
       COUNT(*) FROM order_items oi LEFT JOIN products p ON p.id = oi.product_id WHERE p.id IS NULL
UNION ALL SELECT 'fk_ent_product  entitlements.product_id -> products',
       COUNT(*) FROM entitlements e LEFT JOIN products p ON p.id = e.product_id WHERE p.id IS NULL
UNION ALL SELECT 'fk_ent_course  entitlements.target_course_id -> courses',
       COUNT(*) FROM entitlements e LEFT JOIN courses c ON c.id = e.target_course_id WHERE e.target_course_id IS NOT NULL AND c.id IS NULL
UNION ALL SELECT 'fk_exam_course  exams.target_course_id -> courses',
       COUNT(*) FROM exams x LEFT JOIN courses c ON c.id = x.target_course_id WHERE x.target_course_id IS NOT NULL AND c.id IS NULL
UNION ALL SELECT 'fk_exam_segment  exams.target_segment_id -> segments',
       COUNT(*) FROM exams x LEFT JOIN segments s ON s.id = x.target_segment_id WHERE x.target_segment_id IS NOT NULL AND s.id IS NULL;

-- B1..B8. Liệt kê các hàng mồ côi (tối đa 200 mỗi loại) để quyết định xử lý
SELECT 'B1 staff_permissions -> missing course' AS orphan_kind, sp.id AS row_id, sp.assignment_id, sp.module, sp.action, sp.scope_course_id AS dangling_id
FROM staff_permissions sp LEFT JOIN courses c ON c.id = sp.scope_course_id WHERE sp.scope_course_id IS NOT NULL AND c.id IS NULL LIMIT 200;
SELECT 'B2 lessons -> missing media_asset' AS orphan_kind, l.id AS row_id, l.media_asset_id AS dangling_id
FROM lessons l LEFT JOIN media_assets m ON m.id = l.media_asset_id WHERE l.media_asset_id IS NOT NULL AND m.id IS NULL LIMIT 200;
SELECT 'B3 document_assets -> missing media_asset' AS orphan_kind, d.id AS row_id, d.media_asset_id AS dangling_id
FROM document_assets d LEFT JOIN media_assets m ON m.id = d.media_asset_id WHERE m.id IS NULL LIMIT 200;
SELECT 'B4 order_items -> missing product' AS orphan_kind, oi.id AS row_id, oi.order_id, oi.product_id AS dangling_id
FROM order_items oi LEFT JOIN products p ON p.id = oi.product_id WHERE p.id IS NULL LIMIT 200;
SELECT 'B5 entitlements -> missing product' AS orphan_kind, e.id AS row_id, e.user_id, e.product_id AS dangling_id
FROM entitlements e LEFT JOIN products p ON p.id = e.product_id WHERE p.id IS NULL LIMIT 200;
SELECT 'B6 entitlements -> missing target course' AS orphan_kind, e.id AS row_id, e.user_id, e.target_course_id AS dangling_id
FROM entitlements e LEFT JOIN courses c ON c.id = e.target_course_id WHERE e.target_course_id IS NOT NULL AND c.id IS NULL LIMIT 200;
SELECT 'B7 exams -> missing target course' AS orphan_kind, x.id AS row_id, x.title, x.target_course_id AS dangling_id
FROM exams x LEFT JOIN courses c ON c.id = x.target_course_id WHERE x.target_course_id IS NOT NULL AND c.id IS NULL LIMIT 200;
SELECT 'B8 exams -> missing target segment' AS orphan_kind, x.id AS row_id, x.title, x.target_segment_id AS dangling_id
FROM exams x LEFT JOIN segments s ON s.id = x.target_segment_id WHERE x.target_segment_id IS NOT NULL AND s.id IS NULL LIMIT 200;

-- CLEANUP B (SAO LƯU trước; mỗi khối là một quyết định nghiệp vụ, không phải một lệnh "chạy tất cả"):
--   B1  staff_permissions  KHÔNG đặt scope_course_id = NULL - việc đó biến quyền giới hạn theo khóa học thành quyền toàn lớp (V19 cấm đúng
--       điều này). Khóa học đã mất nên quyền này vô nghĩa: XÓA hàng.
--         DELETE sp FROM staff_permissions sp LEFT JOIN courses c ON c.id = sp.scope_course_id WHERE sp.scope_course_id IS NOT NULL AND c.id IS NULL;
--   B2  lessons  (bài học mất tệp; nội dung chữ vẫn còn) - bỏ liên kết, giống ON DELETE SET NULL của V18:
--         UPDATE lessons l LEFT JOIN media_assets m ON m.id = l.media_asset_id SET l.media_asset_id = NULL WHERE l.media_asset_id IS NOT NULL AND m.id IS NULL;
--   B3  document_assets  (media_asset_id NOT NULL, RESTRICT): tài liệu hỏng vì tệp đã mất. Khôi phục media_assets từ bản sao lưu HOẶC xóa tài liệu:
--         DELETE d FROM document_assets d LEFT JOIN media_assets m ON m.id = d.media_asset_id WHERE m.id IS NULL;
--   B4  order_items  là CHỨNG TỪ TÀI CHÍNH - không xóa. Khôi phục sản phẩm từ bản sao lưu (giữ nguyên id), hoặc tạo lại hàng products với đúng
--       id bị thiếu, trạng thái ARCHIVED (đã thử trên MySQL 8.4: sau đó V18 áp dụng được; các cột NOT NULL khác của products đều có mặc định):
--         INSERT INTO products (id, class_id, title, status)
--         SELECT oi.product_id, MIN(o.class_id), 'San pham da xoa (khoi phuc de giu chung tu don hang)', 'ARCHIVED'
--         FROM order_items oi JOIN orders o ON o.id = oi.order_id LEFT JOIN products p ON p.id = oi.product_id
--         WHERE p.id IS NULL GROUP BY oi.product_id;
--   B5  entitlements  (product_id NOT NULL): quyền truy cập của một sản phẩm không còn - cùng cách xử lý như B4 (khôi phục/thay thế sản phẩm) nếu
--       người mua đã trả tiền; nếu là dữ liệu thử thì xóa:
--         DELETE e FROM entitlements e LEFT JOIN products p ON p.id = e.product_id WHERE p.id IS NULL;
--   B6  entitlements.target_course_id  (khóa học đích đã mất) - bỏ liên kết, giống ON DELETE SET NULL của V18:
--         UPDATE entitlements e LEFT JOIN courses c ON c.id = e.target_course_id SET e.target_course_id = NULL WHERE e.target_course_id IS NOT NULL AND c.id IS NULL;
--   B7  exams.target_course_id  - bỏ liên kết (kỳ thi trở thành kỳ thi toàn lớp):
--         UPDATE exams x LEFT JOIN courses c ON c.id = x.target_course_id SET x.target_course_id = NULL WHERE x.target_course_id IS NOT NULL AND c.id IS NULL;
--   B8  exams.target_segment_id - bỏ liên kết. CHÚ Ý: với audienceScope = 'SEGMENT' việc này thay đổi đối tượng được thi; xem lại từng kỳ thi:
--         UPDATE exams x LEFT JOIN segments s ON s.id = x.target_segment_id SET x.target_segment_id = NULL WHERE x.target_segment_id IS NOT NULL AND s.id IS NULL;

-- ---------------------------------------------------------------------------------------------------------------------
-- C. R20-11 - V30 (schema < 30): kích thước các bảng mà V30 sẽ SAO CHÉP (ALGORITHM=COPY, chặn ghi) => cửa sổ bảo trì
-- ---------------------------------------------------------------------------------------------------------------------
-- Thời gian sao chép tỉ lệ với DUNG LƯỢNG bảng (data_length + index_length), không chỉ số hàng. Số đo tham chiếu (xem RUNBOOK mục 7.3):
--   1 000 000 entitlements + 1 000 000 revoked_tokens + 1 000 000 refresh_tokens + 300 000 exam_attempts  =>  102,9 giây tổng cộng;
--   ước tính 12-15 phút cho ~1 triệu lượt thi thực tế (hàng rộng hơn nhiều so với hàng thử). Cần dung lượng đĩa trống >= bảng lớn nhất
--   (bản sao tạm) và chạy ngoài giờ cao điểm với backend ĐÃ DỪNG (backend chặn ghi trên các bảng đang bị sao chép).
SELECT table_name, table_rows AS approx_rows, ROUND(data_length / 1048576, 1) AS data_mb, ROUND(index_length / 1048576, 1) AS index_mb,
       ROUND((data_length + index_length) / 1048576, 1) AS total_mb
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('entitlements', 'product_prices', 'order_items', 'exams', 'exam_attempts', 'revoked_tokens', 'refresh_tokens', 'leaderboard_recalc_jobs')
ORDER BY (data_length + index_length) DESC;

-- ---------------------------------------------------------------------------------------------------------------------
-- D. Thông tin thêm: bảng outbox_events (V36 thêm chỉ mục ONLINE - INPLACE, LOCK=NONE - và job retention sẽ dọn hàng PROCESSED cũ hơn 7 ngày)
-- ---------------------------------------------------------------------------------------------------------------------
SELECT status, COUNT(*) AS events, MIN(created_at) AS oldest, MAX(created_at) AS newest FROM outbox_events GROUP BY status ORDER BY events DESC;
