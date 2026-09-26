# System Operations / Maintenance Manual

**Dự án:** Nền tảng lớp học trực tuyến · **Phiên bản:** 0.1 · **Trạng thái:** BẢN NHÁP · **Ngày:** 23/09/2026

> Tài liệu thiết kế theo thông tin chủ sản phẩm đã cung cấp. Các mục `[CẦN CHỐT]`, `[ĐIỀN KHI THỰC HIỆN]` chưa được xác nhận. Bản nháp không phải biên bản đã ký, kết quả kiểm thử hay cam kết dịch vụ.

## 1. Bản đồ dịch vụ
React static frontend; Spring Boot API; MySQL nguồn giao dịch; MongoDB events; Neo4j graph projection; MinIO media; payment provider. Các endpoint/host, owner, vùng cloud, version image, dashboard, alert route và tài khoản on-call `[ĐIỀN KHI DEPLOY]`.

## 2. Kiểm tra hàng ngày
Health/readiness; 5xx và p95; kết nối DB; ổ đĩa/storage; MinIO lỗi upload/download; backlog outbox và số event lỗi; webhook pending quá hạn; đơn PAID không có entitlement; entitlement hết hạn chưa cập nhật; exam attempt kẹt IN_PROGRESS; backup thành công và khả năng đọc bản backup. Không log mật khẩu, token, đáp án trước công bố hoặc dữ liệu thanh toán nhạy cảm.

## 3. Runbook xử lý sự cố
**Đơn paid không mở khóa:** tra payment ref → order/transaction → entitlement; không tự đổi trạng thái nếu chưa xác minh provider; chạy reconciliation idempotent, ghi audit. **Neo4j/MongoDB chậm:** backend vẫn kiểm quyền MySQL, xem outbox lag, replay từ eventId không nhân đôi. **MinIO lỗi:** dừng cấp upload intent, giữ metadata, báo cho người dùng, khôi phục object trước khi đánh dấu complete. **Sai điểm:** đóng tạm publish nếu cần, đối chiếu attempt/answer/rule snapshot, sửa với quyền và audit, rebuild leaderboard. **Lộ quyền chéo lớp:** giới hạn truy cập, giữ log, xử lý như P0 bảo mật.

## 4. Thay đổi và định kỳ
Backup/restore drill, cập nhật bản vá, certificate renewal, rotate secret, kiểm phân quyền STAFF, lưu trữ log, capacity review; tần suất theo SLA đã ký. Mọi thao tác có ticket, người làm, giờ, trước/sau và kế hoạch rollback. Liên hệ/ma trận escalation `[ĐIỀN]`.
