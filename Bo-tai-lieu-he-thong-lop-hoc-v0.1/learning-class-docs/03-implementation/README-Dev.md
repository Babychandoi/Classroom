# README — Hướng dẫn dựng môi trường phát triển

**Dự án:** Nền tảng lớp học trực tuyến · **Phiên bản:** 0.1 · **Trạng thái:** BẢN NHÁP · **Ngày:** 23/09/2026

> Tài liệu thiết kế theo thông tin chủ sản phẩm đã cung cấp. Các mục `[CẦN CHỐT]`, `[ĐIỀN KHI THỰC HIỆN]` chưa được xác nhận. Bản nháp không phải biên bản đã ký, kết quả kiểm thử hay cam kết dịch vụ.

## 1. Điều kiện
JDK 21; Maven Wrapper; Node.js theo yêu cầu của bản Vite được khóa trong dự án; Docker/Compose cho MySQL, Neo4j, MongoDB, MinIO. Phiên bản container cụ thể phải pin tại `compose.yaml` khi khởi tạo repo `[CHƯA CÓ REPO MỚI]`.

## 2. Cấu trúc mục tiêu
`backend/` Spring Boot; `frontend/` React TS; `infra/compose.yaml`; `docs/`; `backend/src/main/resources/db/migration/`. Sao chép `.env.example` thành `.env.local`; không commit secret. Mỗi dịch vụ có username/password/bucket dev độc lập. Không dùng cấu hình production để chạy local.

## 3. Trình tự sau khi có mã nguồn
1. `docker compose -f infra/compose.yaml up -d`.
2. Trong `backend/`: `./mvnw spring-boot:run` (Windows: `mvnw.cmd spring-boot:run`). Migration tạo schema; health endpoint xác nhận phụ thuộc.
3. Trong `frontend/`: `npm ci`, `npm run dev` (tên script sẽ được cố định trong package.json).
4. Tạo OWNER/lớp demo qua seed idempotent dành cho dev; mở Swagger/OpenAPI ở URL đã cấu hình.
5. Chạy `./mvnw test` và `npm run build` trước PR.

## 4. Biến môi trường mẫu, chỉ tên
`MYSQL_URL/USER/PASSWORD`, `NEO4J_URI/USER/PASSWORD`, `MONGO_URI`, `MINIO_ENDPOINT/ACCESS_KEY/SECRET_KEY/BUCKET`, `PAYMENT_*`, `APP_BASE_URL`, `CORS_ORIGINS`. Secret thật quản lý riêng. File này là hướng dẫn dự kiến; không tuyên bố các lệnh đã chạy hoặc repo mới đã được tạo.
