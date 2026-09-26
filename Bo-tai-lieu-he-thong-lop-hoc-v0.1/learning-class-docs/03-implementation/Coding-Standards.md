# Coding Standards & Guidelines

**Dự án:** Nền tảng lớp học trực tuyến · **Phiên bản:** 0.1 · **Trạng thái:** BẢN NHÁP · **Ngày:** 23/09/2026

> Tài liệu thiết kế theo thông tin chủ sản phẩm đã cung cấp. Các mục `[CẦN CHỐT]`, `[ĐIỀN KHI THỰC HIỆN]` chưa được xác nhận. Bản nháp không phải biên bản đã ký, kết quả kiểm thử hay cam kết dịch vụ.

## 1. Quy ước chung
Java 21 + Spring Boot 4.x; React + TypeScript strict. Một repo gồm `backend/`, `frontend/`, `docs/`; feature branch, PR review, migration có version; secret không commit; commit có mã yêu cầu nếu có. Định dạng bằng công cụ tự động của dự án và CI kiểm build/test/typecheck.

## 2. Backend
Package theo module (`learning`, `exam`, `commerce`...), trong module có `api`, `application`, `domain`, `persistence`. Controller nhận DTO/validate/ủy quyền; service điều phối transaction và policy; repository chỉ truy cập dữ liệu. Không trả JPA entity thẳng cho API. DTO định danh bằng ID ổn định; thời gian lưu UTC `Instant`, hiển thị theo timezone người dùng. Mọi truy vấn quyền luôn lọc `classId`; OWNER override chỉ sau khi xác nhận owner của chính lớp. Các thao tác nhạy cảm phải có audit.

## 3. Frontend
`src/features/<feature>/{api,components,pages,types}`; tách component và API client; schema form và error handling nhất quán. Route guard chỉ hỗ trợ UX, không thay backend. Không lưu access secret trong client; không đặt ID/flag PRO do client tự tính làm nguồn quyết định. Hỗ trợ loading/empty/error/forbidden/expired state cho mọi trang có quyền.

## 4. Đa dữ liệu
Mỗi model ghi rõ `source of truth`; dùng outbox/eventId để đồng bộ Neo4j/MongoDB; consumer idempotent; không viết transaction giả qua nhiều DB. MinIO object key chỉ sinh ở server; validate MIME/size, kiểm quyền trước upload/download, có cơ chế cleanup.

## 5. Test tối thiểu và PR checklist
Unit test access policy, điểm thi/quy đổi, thời hạn quyền; integration test MySQL repository và webhook idempotency; API test vượt quyền chéo lớp; E2E luồng học/mua/thi. PR nêu yêu cầu, migration, ảnh UI nếu có, test đã chạy, rủi ro rollback, cập nhật API docs và quyết định mới. Không merge khi test thiết yếu hoặc migration thất bại.
