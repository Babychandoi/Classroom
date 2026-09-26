# Sổ tay Vận hành Hệ thống Lớp học Trực tuyến (Operations Runbook)

## 1. Khởi động và Dừng dịch vụ

### 1.1 Khởi động toàn bộ cụm dịch vụ
```bash
docker compose -f infra/compose.yaml up -d
```
Docker Compose sẽ khởi động theo thứ tự phụ thuộc tự động:
1. `mysql`, `mongodb`, `neo4j`, `minio` chạy và kiểm tra healthcheck.
2. `minio-init` tạo bucket `classroom-media` và phân quyền công khai cho media public.
3. `backend` chạy các tệp Flyway migration. `DataSeedRunner` (idempotent) **chỉ chạy khi bật tường minh** `DEMO_SEED_ENABLED=true` — xem 1.4.
4. `frontend` (Nginx + React) khởi động và sẵn sàng phục vụ tại `http://localhost:3000`.

### 1.2 Dừng hệ sinh thái
```bash
docker compose -f infra/compose.yaml down
```

### 1.3 Tái tạo môi trường sạch (Clean Environment Reset)
```bash
docker compose -f infra/compose.yaml down -v
docker compose -f infra/compose.yaml up -d --build
```

### 1.4 Bật chế độ demo (CỤC BỘ, DÙNG-RỒI-BỎ)
Cụm dịch vụ mặc định fail closed: chỉ publish trên `127.0.0.1`, không có tài khoản demo mật khẩu cố định, không có nút chuyển vai trò, không có luồng thanh toán mô phỏng. Lớp phủ `infra/compose.demo.yaml` là cách bật tường minh cả ba:
```bash
docker compose -f infra/compose.yaml -f infra/compose.demo.yaml up -d --build
```
Khi bật, `owner@classroom.local` / `Password123!` tồn tại và OWNER có thể tất toán đơn hàng qua `/api/v1/payments/mock/simulate` mà không cần cổng thanh toán thật. **Không áp dụng cho môi trường lưu trữ lâu dài hoặc truy cập được từ Internet.**

Nếu cần mở cổng ra ngoài loopback cho một mạng bạn kiểm soát, đặt `HOST_BIND_ADDRESS` trong `infra/.env` — và không bao giờ đi kèm lớp phủ demo.

> Lưu ý: nếu volume MySQL đã từng được seed trước đây, các tài khoản demo cũ vẫn còn trong dữ liệu. Chạy `down -v` (mục 1.3) để loại bỏ chúng.

---

## 2. Kiểm thử và Xác minh Chất lượng (Testing & QA)

### 2.1 Chạy toàn bộ Unit & Domain Policy Test (Backend)
```bash
docker compose -f infra/compose.yaml run --rm backend-test
```
*Lưu ý: Hoàn toàn thực thi trong Docker container, không yêu cầu cài đặt JDK hay Maven trên máy host.*

### 2.2 Chạy TypeScript Strict Check & Production Bundle Test (Frontend)
```bash
docker compose -f infra/compose.yaml run --rm frontend-test
```

---

## 3. Theo dõi & Kiểm tra Sức khỏe (Health & Monitoring)

- **Backend Health Check:**
  `curl http://localhost:8080/api/v1/health`
  Phản hồi chuẩn:
  ```json
  {"status":"UP","version":"0.1.0","service":"online-classroom-backend"}
  ```

- **Kiểm tra trạng thái container:**
  `docker compose -f infra/compose.yaml ps`

- **Kiểm tra nhật ký (Logs):**
  - Backend: `docker compose -f infra/compose.yaml logs -f backend`
  - MySQL: `docker compose -f infra/compose.yaml logs -f mysql`
  - Frontend: `docker compose -f infra/compose.yaml logs -f frontend`

---

## 4. Quy trình Xử lý Sự cố thường gặp

### 4.1 Xung đột cổng máy host (Port Collision)
- Mặc định MySQL trong container được ánh xạ ra cổng `3307` của máy host (`ports: - "3307:3306"`) nhằm tránh xung đột với MySQL cài sẵn trên cổng 3306 của Windows.
- Nếu các cổng khác (3000, 8080, 7474, 9000) bị xung đột, chỉnh sửa giá trị tương ứng trong tệp `.env` và `infra/.env`.

### 4.2 Lỗi kết nối NoSQL (MongoDB hoặc Neo4j gián đoạn)
- Nghiệp vụ cốt lõi và kiểm soát quyền hạn (RBAC, Entitlement, AccessPolicy) hoàn toàn hoạt động trên MySQL (Source of Truth).
- Outbox Worker có cơ chế retry tự động với backoff. Nếu MongoDB hoặc Neo4j gặp sự cố tạm thời, các bản ghi outbox sẽ giữ trạng thái `PENDING` và tự động đồng bộ lại khi database phục hồi.
