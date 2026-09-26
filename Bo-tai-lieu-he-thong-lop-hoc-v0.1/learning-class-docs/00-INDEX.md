# Bộ tài liệu dự án lớp học — Mục lục và hướng dẫn

**Dự án:** Nền tảng lớp học trực tuyến · **Phiên bản:** 0.1 · **Trạng thái:** BẢN NHÁP · **Ngày:** 23/09/2026

> Tài liệu thiết kế theo thông tin chủ sản phẩm đã cung cấp. Các mục `[CẦN CHỐT]`, `[ĐIỀN KHI THỰC HIỆN]` chưa được xác nhận. Bản nháp không phải biên bản đã ký, kết quả kiểm thử hay cam kết dịch vụ.

## Tổng quan
Bộ 23 tài liệu theo 6 giai đoạn cho sản phẩm lớp học với 8 tab, Studio OWNER/STAFF, FREE/PRO, sản phẩm có hạn, kỳ thi theo nhóm/khóa, React TypeScript + Spring Boot/JDK 21, MySQL/Neo4j/MongoDB/MinIO. Đây là **bản khởi tạo có nội dung thực** từ cuộc trao đổi, chưa có phỏng vấn stakeholder độc lập, thiết kế Figma, code, test, ký duyệt hoặc triển khai.

## Danh mục
| Giai đoạn | Tệp |
|---|---|
| 1 Yêu cầu | `01-requirements/BRD.md`, `SRS-URS.md`, `WBS.md`, `Requirement-Signoff.md` |
| 2 Thiết kế | `02-design/HLD.md`, `LLD.md`, `UI-UX-Spec.md` |
| 3 Lập trình | `03-implementation/Coding-Standards.md`, `API-Spec.md`, `README-Dev.md`, `Unit-Test-Report.md` |
| 4 Kiểm thử | `04-testing/Test-Plan.md`, `Test-Cases.md`, `Bug-Log.md`, `Test-Summary.md`, `UAT-Signoff.md` |
| 5 Bàn giao | `05-release/Deployment-Plan.md`, `User-Manual.md`, `Operations-Manual.md`, `Handover-Acceptance.md` |
| 6 Bảo trì | `06-maintenance/SLA.md`, `Disaster-Recovery-Plan.md`, `Maintenance-Log.md` |

## Cách sử dụng
1. Chốt các quyết định D-01…D-06 trong BRD và các `[CẦN CHỐT]` cùng chủ sản phẩm.
2. Cập nhật SRS, WBS, HLD, LLD và UI song song; chỉ ký Requirement Sign-off sau khi phạm vi đã nhất quán.
3. Triển khai theo mốc; bổ sung OpenAPI và test report tự sinh từ code/build thật.
4. Điền kết quả test, UAT, deploy, bàn giao, SLA bằng bằng chứng thực tế và chữ ký đúng người.

## Giới hạn
Các mục tiêu tải, lịch, chi phí, nhà cung cấp thanh toán, SLA/RPO/RTO chưa có dữ liệu để cam kết. UI file là design specification/wireframe dạng văn bản; mockup Figma chưa thể tồn tại nếu chưa thiết kế. Mọi mẫu ký duyệt và kết quả đều ghi rõ trạng thái chưa thực hiện.
