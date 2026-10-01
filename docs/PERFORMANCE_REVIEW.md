# Kiểm thử tải trên máy làm server

Ngày nghiệm thu: 2026-10-02 (Asia/Saigon). **Gate bắt đầu thi với 200 kết nối HTTPS mới vẫn mở.** Luồng 200 người đã mở trang đề, bảng tin, tải tệp và NAT/auth đã qua các ngưỡng script; không dùng các kết quả này để tuyên bố mọi tình huống tải đều đạt hoặc có SLA production.

Máy Intel i5-12450H, RAM 16 GB; Docker Desktop có 12 CPU/11,52 GiB, chạy đồng thời các kho của stack chính và demo. Client Node chạy trên cùng máy, đích `https://localhost`, xác minh CA thật. Lượt cũ dùng hai backend; lượt cuối dùng một backend mặc định, pool 20, outbox 4, recalculation 3. Hai node vẫn được kiểm tra riêng về JWT/quota dùng chung. Các kịch bản tải chạy tuần tự, không build/test/backup song song. Dữ liệu riêng `loadtest-*` được tạo thật; không dùng lớp của người học để bắn tải.

## Kết quả đo

Đơn vị độ trễ: ms. `p95` là phân vị 95, `p99` là phân vị 99. Giữ nguyên ngưỡng của script.

| Kịch bản / log | Kết quả | Bằng chứng |
|---|---|---|
| `load-release-warmup-50.log` | PASS ở lượt này | 50 học viên; start p95 1362 ≤ 1500, save 170 ≤ 300, submit 1411 ≤ 5000, readiness p99 392 ≤ 1000; 0 đáp án mất/sai/trùng |
| `load-release-exam-200.log` | FAIL | Start 3139, save 3186, submit 3759; readiness p99 2617 và 1 lần 503; 200/200 bài published, 0 đáp án mất/sai/trùng |
| `load-release-feed.log` | FAIL | 100 phiên, 2240 lượt đọc đều 200; feed p95 1060 > 300, mọi lượt đọc 1114 > 500 |
| `load-release-media.log` | FAIL | 50 × 20 MB; 50 intent/PUT thành công, 49 complete thành công + 1 lỗi mạng; complete p95 4314 > 3000. Control health cũng timeout một lần; không quy toàn bộ lỗi cho database |
| `load-release-auth-nat.log` | PASS | 200 học viên đăng nhập/đăng ký/refresh qua một NAT; không có 5xx, bảo vệ brute force/credential stuffing vẫn chặn |
| `load-release2-warmup-50.log` | FAIL, sau khởi động lại | Start 2383, save 543, submit 1340; readiness p99 684; đủ 50 bài/đáp án. Chưa thể cam kết 50 là năng lực ổn định |
| `load-release2-exam-200.log` | FAIL | Start 3741, save 3333, submit 4019; readiness p99 2391 (mọi probe 200); 8 save trả 429, đủ 200 bài và 0 đáp án mất/sai/trùng, bảng điểm khớp sau 1776 |
| `load-release2-feed.log` | FAIL | 2332 lượt đọc đều 200; feed p95 694, mọi lượt đọc 802; danh sách kỳ thi 1436 |

Lượt `release2` đã loại truy vấn quyền tác giả không dùng đến cho kỳ thi published. Các số trên được đo trước bản sửa fallback quota ở ranh giới thời gian; kết quả sau sửa phải được ghi bổ sung, không thay số cũ bằng suy đoán.

## Lượt cuối — V43, một backend

| Kịch bản / log | Kết quả | Bằng chứng |
|---|---|---|
| `load-final-exam-200.log`, trước V43 | FAIL start | Start 2266, save 72, submit 1478, readiness 479; 200 bài đúng, không lỗi/mất đáp án |
| `load-v43-exam-200.log` | FAIL start | Bản đề cố định: start 1695, save 88, submit 1066, readiness 681 |
| `load-jdbc-exam-200.log` | FAIL start | Thêm JDBC prepared cache: start 1591, save 120, submit 1153, readiness 349 |
| `load-publication-exam-200.log` | FAIL start | Bỏ count/roster lặp: start 1568, save 81, submit 1562, readiness 408 |
| `load-pool25-exam-200.log` | FAIL start | Pool 25 không cải thiện: start 1673; đã trả về 20 |
| `load-persistable-exam-200.log` | FAIL start | Persistable cho UUID gán sẵn; 200 bài/đáp án đúng và mọi request thành công; start 1925, save 99, submit 1140, readiness p99 532. Kết quả cold còn dao động |
| `load-exam-preloaded-200.log` | PASS, học viên đã mở trang đề | 200 GET đề đều 200 (p95 833); sau đó start 1262 ≤ 1500, save 57 ≤ 300, submit 1367 ≤ 5000, readiness 244 ≤ 1000; 200 bài published, 0 mất/sai/thừa/trùng, bảng điểm khớp sau 2159 |
| `load-persistable-feed.log` | PASS | 100 phiên/30 giây, 2943 lượt đều 200; feed p95 41 ≤ 300, mọi lượt đọc 35 ≤ 500 |
| `load-persistable-media.log` | PASS theo script hiện có | 50 × 20 MB, đủ 50 intent/PUT/complete đều 200; complete p95 1280 ≤ 3000; readiness p99 1132 ≤ max(1000, control 1126 + 300) = 1426. Không gọi 1132 là đã đạt ngưỡng tuyệt đối 1000 |
| `load-persistable-auth-nat.log` | PASS | 200 login/register/refresh qua cùng NAT đều thành công, không 5xx/mạng; brute force vẫn chặn, 400 stuffing chỉ 200 lượt được kiểm tra |

`--preload-exams` là kịch bản bổ sung: người học mở trang đề trước khi cùng bấm bắt đầu. Mặc định script vẫn dùng kết nối mới; giữ nguyên mọi ngưỡng và giữ riêng cả báo cáo FAIL mặc định. Không dùng preload để thay số cold.

Diagnostic riêng 200 GET `/health` không dùng DB: HTTPS mới p95 556, HTTPS dùng lại kết nối 67, HTTP mới 136; cả ba 200/200 thành công (`https-connection-diagnostic.json`). Chi phí mở TLS và client chung máy có đóng góp vào độ trễ đo; đây là suy luận từ phép đo, chưa chứng minh năng lực 200 thiết bị độc lập. Cần máy phát tải riêng để nghiệm thu năng lực triển khai ngoài máy này.

## Chẩn đoán và giới hạn

- Thử pool 40/node và 10/node không cải thiện tổng thể; đã trả về mặc định 20, không giữ cấu hình thử nghiệm.
- Giữ kết nối Nginx→backend bằng keepalive. Lượt diagnostic timing có overhead Nginx p95 khoảng 1 ms, trong khi upstream p95 320/333 ms; chưa có bằng chứng proxy là điểm nghẽn chính.
- JFR diagnostic chạy riêng, không dùng độ trễ khi profiling làm gate. Một chunk hợp lệ có 969 execution sample: 247 có Hibernate, 156 MySQL driver, 66 Spring Data. GC tổng pause 322 ms, max khoảng 75 ms. Phân bố này gợi ý cần giảm công việc truy vấn/ORM, không chứng minh một hàm duy nhất là nguyên nhân.
- Không dừng chương trình khác của người dùng, giảm độ bền ghi database, bỏ kiểm tra quyền/quota, hoặc nới ngưỡng để biến FAIL thành PASS.
- Dữ liệu báo cáo JSON ở `.artifacts/load-release2-*.json`; log/artifact/credential/backup không được đưa vào Git. JFR giữ trong thư mục ACL hạn chế.

Đã kiểm chứng tải luồng đang mở trang thi và upload đồng thời theo điều kiện ở bảng cuối. Gate cold HTTPS 200 vẫn chưa đạt; chưa có cam kết SLA hoặc phép đo từ các thiết bị/máy phát tải độc lập. Source giữ kiểm tra quyền, thời hạn, quota, trạng thái tài khoản và độ bền ghi database.
