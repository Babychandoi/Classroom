# Deployment Plan & Checklist

**Dự án:** Nền tảng lớp học trực tuyến · **Phiên bản:** 0.1 · **Trạng thái:** BẢN NHÁP · **Ngày:** 23/09/2026

> Tài liệu thiết kế theo thông tin chủ sản phẩm đã cung cấp. Các mục `[CẦN CHỐT]`, `[ĐIỀN KHI THỰC HIỆN]` chưa được xác nhận. Bản nháp không phải biên bản đã ký, kết quả kiểm thử hay cam kết dịch vụ.

## 1. Chuẩn bị
Chốt image/tag commit, migration, secret, DNS/TLS, bucket policy, endpoint database, firewall, backup/snapshot, provider webhook, CORS, môi trường, người phê duyệt và cửa sổ triển khai. Trước deploy phải có Test Summary/UAT và danh sách thay đổi schema. Không đưa khóa MinIO hay DB lên frontend.

## 2. Trình tự triển khai đề xuất
1. Backup và kiểm tra khả năng restore một bản gần nhất.
2. Chạy migration tương thích ngược; ghi schema version; xử lý data migration có log và phương án dừng.
3. Deploy Spring Boot, kiểm health/readiness, MySQL và phụ thuộc bắt buộc; kiểm outbox worker.
4. Deploy React static assets và route fallback; purge cache theo version.
5. Cấu hình webhook thanh toán, kiểm chữ ký với sandbox hoặc đơn giá trị thấp theo quy trình được phép.
6. Smoke: login, tạo/mở lớp, STAFF permission, khóa FREE/PAID, thi PRO/COURSE, tải tài liệu, tạo đơn, báo cáo lỗi.
7. Theo dõi error rate, latency, DB connection, outbox lag, payment callback, object error; công bố GO/rollback.

## 3. Rollback
Giữ image frontend/backend cũ; chỉ rollback code nếu migration tương thích ngược. Migration phá hủy cần cửa sổ riêng và phương án restore, không coi `git revert` là phục hồi dữ liệu. Tạm dừng payment/webhook khi trạng thái đơn không tin cậy; reconciliation sau phục hồi. Quyền xác nhận rollback `[ĐIỀN]`.

## 4. Checklist trạng thái
| Mục | Owner | Bằng chứng | Done |
|---|---|---|---|
| UAT, backup, migration, secret, TLS, monitoring, smoke, rollback drill, bàn giao | `[ĐIỀN]` | `[ĐIỀN]` | ☐ |

Tài liệu này là kế hoạch; chưa có triển khai thực tế.
