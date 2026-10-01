# Kiểm thử tải trên máy làm server

Ngày nghiệm thu: 2026-10-02 (Asia/Saigon). **Gate tải còn mở.** Không dùng kết quả R20 trước đây để tuyên bố cấu hình HTTPS/hai node mới đã đạt NFR.

Máy Intel i5-12450H, RAM 16 GB; Docker Desktop có 12 CPU/11,52 GiB, chạy đồng thời các kho của stack chính và demo. Client Node chạy trên cùng máy, đích `https://localhost`, xác minh CA thật. Hai backend dùng chung MySQL, quota database; pool 20/node, outbox 4/node, recalculation 3/node. Các kịch bản dưới đây chạy tuần tự, không build/test/backup song song. Dữ liệu riêng `loadtest-*` được tạo thật; không dùng lớp của người học để bắn tải.

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

## Chẩn đoán và giới hạn

- Thử pool 40/node và 10/node không cải thiện tổng thể; đã trả về mặc định 20, không giữ cấu hình thử nghiệm.
- Giữ kết nối Nginx→backend bằng keepalive. Lượt diagnostic timing có overhead Nginx p95 khoảng 1 ms, trong khi upstream p95 320/333 ms; chưa có bằng chứng proxy là điểm nghẽn chính.
- JFR diagnostic chạy riêng, không dùng độ trễ khi profiling làm gate. Một chunk hợp lệ có 969 execution sample: 247 có Hibernate, 156 MySQL driver, 66 Spring Data. GC tổng pause 322 ms, max khoảng 75 ms. Phân bố này gợi ý cần giảm công việc truy vấn/ORM, không chứng minh một hàm duy nhất là nguyên nhân.
- Không dừng chương trình khác của người dùng, giảm độ bền ghi database, bỏ kiểm tra quyền/quota, hoặc nới ngưỡng để biến FAIL thành PASS.
- Dữ liệu báo cáo JSON ở `.artifacts/load-release2-*.json`; log/artifact/credential/backup không được đưa vào Git. JFR giữ trong thư mục ACL hạn chế.

Chưa nghiệm thu tải 200 học viên hoặc upload đồng thời trên máy này. Cần tiếp tục tối ưu theo phép đo và chạy lại exam/feed/media/auth với cùng ngưỡng; cấu hình khởi động, HTTPS và kiểm thử chức năng qua không thay thế gate này.
