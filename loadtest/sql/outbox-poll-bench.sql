-- R20-05: chi phí MySQL của MỘT lần thăm dò rảnh của outbox worker (3 câu lệnh của OutboxEventRepository) trên bảng lớn.
-- Chạy bởi loadtest/sql/outbox-backlog.sh trong container classroom-drill-mysql; KHÔNG dùng trên stack thật.
-- In avg_ms của từng câu lệnh (10 lần lặp); tổng 3 dòng là chi phí một lần thăm dò rảnh (mục tiêu < 50 ms; trước V36 với 300 000 hàng PROCESSED ~950 ms).
DROP PROCEDURE IF EXISTS outbox_bench;
DELIMITER //
CREATE PROCEDURE outbox_bench(IN label VARCHAR(40), IN q TEXT, IN n INT)
BEGIN
  DECLARE i INT DEFAULT 0;
  DECLARE t0 DATETIME(6);
  SET @q = q;
  PREPARE s FROM @q;
  SET t0 = SYSDATE(6);
  WHILE i < n DO
    EXECUTE s;
    SET i = i + 1;
  END WHILE;
  SELECT label AS stmt, ROUND(TIMESTAMPDIFF(MICROSECOND, t0, SYSDATE(6)) / n / 1000, 3) AS avg_ms;
  DEALLOCATE PREPARE s;
END //
DELIMITER ;
SET @elig = 'SELECT COUNT(*) INTO @c FROM (SELECT e.* FROM outbox_events e WHERE e.status = ''PENDING'' AND (e.retry_count = 0 OR e.processed_at IS NULL OR TIMESTAMPADD(SECOND, CASE e.retry_count WHEN 1 THEN 2 WHEN 2 THEN 4 WHEN 3 THEN 8 WHEN 4 THEN 16 WHEN 5 THEN 32 WHEN 6 THEN 64 WHEN 7 THEN 128 WHEN 8 THEN 256 ELSE 300 END, e.processed_at) <= NOW()) AND NOT EXISTS (SELECT earlier.id FROM outbox_events earlier WHERE earlier.aggregate_type = e.aggregate_type AND earlier.aggregate_id = e.aggregate_id AND earlier.status <> ''PROCESSED'' AND earlier.sequence_no < e.sequence_no) ORDER BY e.sequence_no ASC LIMIT 50) t';
SET @st1 = 'UPDATE outbox_events SET status = ''DEAD_LETTER'', error_message = ''x'' WHERE status = ''PROCESSING'' AND processed_at < ''2020-01-01'' AND retry_count >= 5';
SET @st2 = 'UPDATE outbox_events SET status = ''PENDING'', retry_count = retry_count + 1 WHERE status = ''PROCESSING'' AND processed_at < ''2020-01-01'' AND retry_count < 5';
CALL outbox_bench('eligible-select', @elig, 10);
CALL outbox_bench('stale-deadletter-update', @st1, 10);
CALL outbox_bench('stale-reset-update', @st2, 10);
DROP PROCEDURE IF EXISTS outbox_bench;
