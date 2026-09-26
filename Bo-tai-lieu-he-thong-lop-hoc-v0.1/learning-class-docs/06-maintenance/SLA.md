# SLA — Khung cam kết chất lượng dịch vụ, CHƯA THỎA THUẬN

**Dự án:** Nền tảng lớp học trực tuyến · **Phiên bản:** 0.1 · **Trạng thái:** BẢN NHÁP · **Ngày:** 23/09/2026

> Tài liệu thiết kế theo thông tin chủ sản phẩm đã cung cấp. Các mục `[CẦN CHỐT]`, `[ĐIỀN KHI THỰC HIỆN]` chưa được xác nhận. Bản nháp không phải biên bản đã ký, kết quả kiểm thử hay cam kết dịch vụ.

## 1. Phạm vi
Dịch vụ frontend/API, MySQL/Neo4j/MongoDB/MinIO do bên vận hành chịu trách nhiệm và phần phụ thuộc nhà cung cấp được liệt kê riêng. Giờ hỗ trợ, ngày nghỉ, kênh tiếp nhận, khung bảo trì, định nghĩa downtime, loại trừ và bên chịu trách nhiệm `[CẦN CHỐT]`.

## 2. Mẫu chỉ tiêu để đàm phán — KHÔNG PHẢI CAM KẾT
| Chỉ tiêu | Giá trị | Cách đo/ngoại lệ |
|---|---|---|
| Uptime tháng | `[CẦN THỎA THUẬN]` | Synthetic check + log; loại trừ bảo trì được thông báo? |
| P0 phản hồi/khôi phục | `[CẦN THỎA THUẬN]` | Bắt đầu khi xác nhận ticket, cập nhật định kỳ |
| P1/P2 phản hồi/xử lý | `[CẦN THỎA THUẬN]` | Theo giờ hỗ trợ |
| RPO/RTO | `[CẦN THỎA THUẬN]` | Theo DR Plan và diễn tập restore |
| Retention backup/log | `[CẦN THỎA THUẬN]` | Theo yêu cầu riêng tư/pháp lý |

## 3. Phân loại sự cố
P0: lộ dữ liệu lớp, sai giao dịch/điểm diện rộng, mất hệ thống; P1: tính năng học/thi/mua không dùng được cho nhiều người; P2: lỗi có đường vòng; P3: cải tiến hoặc lỗi nhỏ. Xác định đường escalation, người liên lạc và quyền công bố sự cố trước khi ký.

## 4. Ranh giới bảo hành
Bug không đúng SRS đã ký và thay đổi yêu cầu mới phải được phân biệt bằng ticket, bằng chứng và quyết định của PO. Cơ chế bù trừ/phạt, giới hạn trách nhiệm, bảo mật và thời hạn hợp đồng cần thỏa thuận riêng; tài liệu này không tự tạo nghĩa vụ pháp lý.
