# Test Plan — Kế hoạch kiểm thử

**Dự án:** Nền tảng lớp học trực tuyến · **Phiên bản:** 0.1 · **Trạng thái:** BẢN NHÁP · **Ngày:** 23/09/2026

> Tài liệu thiết kế theo thông tin chủ sản phẩm đã cung cấp. Các mục `[CẦN CHỐT]`, `[ĐIỀN KHI THỰC HIỆN]` chưa được xác nhận. Bản nháp không phải biên bản đã ký, kết quả kiểm thử hay cam kết dịch vụ.

## 1. Mục tiêu và môi trường
Đánh giá FR-01…14, NFR-01…06 trên môi trường QA cấu hình gần production nhưng dùng dữ liệu giả. QA phải có tối thiểu: 2 lớp, 2 OWNER, 2 STAFF quyền khác nhau, học viên FREE/PRO/đã hết hạn, sản phẩm A/B, kỳ thi ALL/PRO/COURSE/SEGMENT, thanh toán sandbox và file MinIO riêng.

## 2. Các vòng test
Unit → integration DB/MinIO/payment → API permission → E2E web → performance trên tải đã chốt → security review → UAT. Test nghiệp vụ chính: tạo lớp, cấp quyền, học bài, thi, công bố điểm, mua, gia hạn, hết hạn, refund. Kiểm chéo lớp và cố gọi API trực tiếp với ID bị thay là bắt buộc.

## 3. Điều kiện vào/ra
Vào: BRD/SRS được chốt, build triển khai được, schema migration sạch, seed QA, mock/provider sandbox, test cases review. Ra: 100% P0 pass; không còn bug blocker/critical; tỷ lệ pass P1 và ngưỡng hiệu năng/bảo mật `[CẦN CHỐT]`; UAT có biên bản hoặc danh sách điều kiện tồn đọng được chấp thuận. Không tự động coi test pass là nghiệm thu.

## 4. Phân công và báo cáo
Dev viết unit/integration; QA độc lập chạy regression; owner/giáo viên đại diện chạy UAT; security/performance theo người được chỉ định. Lưu commit, môi trường, dữ liệu test, ngày, evidence và bug ID. Smoke sau mỗi deploy; regression sau thay đổi permission/order/exam.

## 5. Rủi ro
Chưa chọn provider thanh toán hoặc chưa có sandbox → không thể nghiệm thu luồng mua thật. Chưa chốt tải → load test chỉ là đo tham khảo. Neo4j/MongoDB projection chậm → kiểm soát truy cập vẫn phải chính xác từ MySQL.
