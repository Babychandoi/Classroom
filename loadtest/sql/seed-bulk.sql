-- Dữ liệu lớn cho kịch bản đọc (feed-read.js) trên stack DRILL: thêm ~1800 thành viên, 2000 bài viết, 40 000 bình luận vào lớp @cls.
-- KHÔNG chạy trên stack thật. @cls do seed-bulk.sh đặt (id lớp lấy từ loadtest/.state/state.json).
SET SESSION cte_max_recursion_depth = 100000;
SET @hash = (SELECT password_hash FROM users WHERE email LIKE 'lt.s0.%' LIMIT 1);
INSERT INTO users (id,email,password_hash,full_name,status,role,profile_visibility)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n < 1800)
SELECT UUID(), CONCAT('lt.bulk', n, '.', SUBSTRING(@cls, 1, 8), '@example.com'), @hash, CONCAT('Bulk Student ', n), 'ACTIVE', 'USER', 'PRIVATE' FROM seq;
INSERT INTO class_members (id,class_id,user_id,state,role)
SELECT UUID(), @cls, id, 'ACTIVE', 'STUDENT' FROM users WHERE email LIKE CONCAT('lt.bulk%.', SUBSTRING(@cls, 1, 8), '@example.com');
DROP TEMPORARY TABLE IF EXISTS tmp_m;
CREATE TEMPORARY TABLE tmp_m (k INT PRIMARY KEY, user_id VARCHAR(36));
SET @k = 0;
INSERT INTO tmp_m SELECT (@k := @k + 1), user_id FROM class_members WHERE class_id = @cls ORDER BY user_id;
SET @m = (SELECT COUNT(*) FROM tmp_m);
INSERT INTO posts (id,class_id,author_id,title,content_markdown,visibility,pinned,status,created_at,updated_at)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n < 2000)
SELECT UUID(), @cls, (SELECT user_id FROM tmp_m WHERE k = 1 + (n * 7919) % @m), CONCAT('Bai viet so ', n),
       REPEAT(CONCAT('Noi dung bai viet ', n, ' - thao luan bai tap. '), 12),
       IF(n % 10 = 0, 'PRO', 'FREE'), IF(n <= 2, 1, 0), 'PUBLISHED',
       TIMESTAMPADD(SECOND, -n * 2400, NOW()), TIMESTAMPADD(SECOND, -n * 2400, NOW()) FROM seq;
DROP TEMPORARY TABLE IF EXISTS tmp_p;
CREATE TEMPORARY TABLE tmp_p (k INT PRIMARY KEY, id VARCHAR(36), created_at TIMESTAMP);
SET @k = 0;
INSERT INTO tmp_p SELECT (@k := @k + 1), id, created_at FROM posts WHERE class_id = @cls AND title LIKE 'Bai viet so %' ORDER BY id;
INSERT INTO comments (id,post_id,author_id,content,created_at)
WITH RECURSIVE seq(n) AS (SELECT 0 UNION ALL SELECT n+1 FROM seq WHERE n < 39999)
SELECT UUID(), p.id, (SELECT user_id FROM tmp_m WHERE k = 1 + (seq.n * 104729) % @m),
       CONCAT('Binh luan ', seq.n, ': cam on thay, em da hieu bai.'), TIMESTAMPADD(SECOND, (seq.n % 20) * 60, p.created_at)
FROM seq JOIN tmp_p p ON p.k = 1 + FLOOR(seq.n / 20);
SELECT (SELECT COUNT(*) FROM class_members WHERE class_id=@cls) members, (SELECT COUNT(*) FROM posts WHERE class_id=@cls) posts,
       (SELECT COUNT(*) FROM comments c JOIN posts p ON p.id=c.post_id WHERE p.class_id=@cls) comments;
