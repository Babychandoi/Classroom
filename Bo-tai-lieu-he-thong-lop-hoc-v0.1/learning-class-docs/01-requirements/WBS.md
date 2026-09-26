# WBS — Cấu trúc phân rã công việc

**Dự án:** Nền tảng lớp học trực tuyến · **Phiên bản:** 0.1 · **Trạng thái:** BẢN NHÁP · **Ngày:** 23/09/2026

> Tài liệu thiết kế theo thông tin chủ sản phẩm đã cung cấp. Các mục `[CẦN CHỐT]`, `[ĐIỀN KHI THỰC HIỆN]` chưa được xác nhận. Bản nháp không phải biên bản đã ký, kết quả kiểm thử hay cam kết dịch vụ.

## 1. Cách lập kế hoạch
WBS theo đầu ra, chưa phải lịch hoặc báo giá. Ước lượng sau khi chốt D-01…D-06, thiết kế UI và chọn thanh toán. Mỗi gói phải có người phụ trách, điều kiện bắt đầu và tiêu chí hoàn tất.

| WBS | Gói công việc | Đầu ra | Phụ thuộc |
|---|---|---|---|
| 1.1 | Phỏng vấn và chốt BRD/SRS | Quy tắc quyền, mục tiêu, scope ký xác nhận | Chủ sản phẩm |
| 1.2 | Hành trình, sitemap, wireframe | 8 tab lớp + Studio | 1.1 |
| 2.1 | HLD/LLD, mô hình dữ liệu | ERD, API, security matrix | 1.1 |
| 2.2 | Thiết kế UI chi tiết | Component, responsive, prototype | 1.2 |
| 3.1 | Nền tảng backend | Auth, lớp, thành viên, RBAC, audit | 2.1 |
| 3.2 | Nền tảng frontend | Layout lớp/Studio, API client, auth | 2.2, 3.1 |
| 3.3 | Học tập | Khóa/chương/bài, MinIO, tiến độ, bài tập, Q&A | 3.1 |
| 3.4 | Cộng đồng lớp | Bảng tin, tài liệu, profile, giới thiệu | 3.1, 3.2 |
| 3.5 | Thi và xếp hạng | Đề, attempt, chấm, audience, điểm quy đổi | 3.3 |
| 3.6 | Bán hàng | Sản phẩm, giá/hạn, đơn, thanh toán, entitlement | 3.1, nhà cung cấp thanh toán |
| 3.7 | Dữ liệu bổ sung | Neo4j profile graph, MongoDB activity projection | Luồng cốt lõi ổn định |
| 4.1 | Kiểm thử | Functional, permission, integration, tải, UAT | Các gói 3.x |
| 5.1 | Phát hành | Migration, backup, smoke test, tài liệu vận hành | 4.1 |
| 6.1 | Bảo trì | Theo dõi, backup, xử lý sự cố | 5.1 |

## 2. Mốc bàn giao đề xuất
M1: lớp + Studio + học miễn phí; M2: thi + ranking; M3: thanh toán + PRO + quyền theo khóa; M4: Neo4j/MongoDB khi luồng tương ứng có yêu cầu đo được. Mỗi mốc có demo, test case, danh sách lỗi và xác nhận phạm vi; không gọi mốc hoàn thành nếu chưa có bằng chứng kiểm thử.
