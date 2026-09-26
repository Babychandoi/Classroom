# Disaster Recovery Plan — Kế hoạch khôi phục sự cố

**Dự án:** Nền tảng lớp học trực tuyến · **Phiên bản:** 0.1 · **Trạng thái:** BẢN NHÁP · **Ngày:** 23/09/2026

> Tài liệu thiết kế theo thông tin chủ sản phẩm đã cung cấp. Các mục `[CẦN CHỐT]`, `[ĐIỀN KHI THỰC HIỆN]` chưa được xác nhận. Bản nháp không phải biên bản đã ký, kết quả kiểm thử hay cam kết dịch vụ.

## 1. Mục tiêu
RPO/RTO `[CẦN CHỐT]`. Xác định vùng hạ tầng, quyền truy cập backup, mã hóa, retention, bản sao ngoài vùng và lịch diễn tập. MySQL là nguồn chuẩn nghiệp vụ; Neo4j/MongoDB có thể tái dựng projection từ outbox/log nếu retention cho phép; MinIO phải có backup/versioning riêng vì không thể dựng video/PDF từ MySQL.

## 2. Kịch bản và hành động
| Sự cố | Ưu tiên phục hồi |
|---|---|
| Mất MySQL | Đóng ghi giao dịch → snapshot/binlog restore → kiểm order/entitlement/attempt → mở ghi |
| Mất MinIO | Khôi phục object/bucket/policy → đối chiếu media_assets → test bài/video/tài liệu |
| Mất MongoDB | Phục hồi backup hoặc replay event có kiểm `eventId`/retention |
| Mất Neo4j | Restore hoặc rebuild node/edge từ MySQL và event, kiểm ràng buộc ID |
| Provider thanh toán lỗi | Không tự đánh dấu paid; giữ pending, đối soát khi provider trở lại |
| Phát hiện xâm nhập | Cô lập, giữ log, rotate secret, phục hồi từ mốc sạch, đánh giá nghĩa vụ thông báo |

## 3. Quy trình diễn tập
Chọn bản backup → restore trong môi trường tách biệt → chạy migration cần thiết → xác minh 10 tài khoản, quyền chéo lớp, 10 đơn/entitlement, 10 attempt/leaderboard, 10 object media → đo thời gian thực tế và sai khác dữ liệu → ký báo cáo diễn tập. Mọi kết quả, ngày, người thực hiện `[ĐIỀN KHI DIỄN TẬP]`.

## 4. Điều kiện tái mở
Health tốt, số lượng order/entitlement/attempt đối chiếu, file truy cập đúng quyền, backlog outbox đã xử lý, PO/on-call cho phép; thông báo tới người dùng theo playbook đã phê duyệt. Chưa có backup hay diễn tập nào được xác nhận trong tài liệu này.
