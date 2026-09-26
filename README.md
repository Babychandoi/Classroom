# Nền Tảng Lớp Học Trực Tuyến (Online Classroom Platform)

Hệ thống quản lý lớp học trực tuyến nhiều giáo viên (multi-classroom), phân quyền Studio OWNER/STAFF, phân cấp học viên FREE/PRO, quản lý khóa học có phí/miễn phí, thi trắc nghiệm và tự luận có xếp hạng, phân khúc học viên (Segment Engine), lưu trữ media MinIO và tích hợp thanh toán giả lập có xác thực webhook & idempotency.

Toàn bộ hệ thống được xây dựng theo chuẩn **DOCKER-FIRST**: máy chủ host **KHÔNG CẦN** cài đặt Java, JDK, Maven, Node.js, npm, MySQL, MongoDB, Neo4j hoặc MinIO. Mọi quy trình build, compile, migration, test và runtime đều chạy trong Docker container.

---

## 1. Kiến trúc Công nghệ (Tech Stack)

- **Backend:** Java 21, Spring Boot 3.4.3 (REST API, Spring Security 6, JWT, JPA Hibernate, Outbox Pattern)
- **Frontend:** React 18, TypeScript strict mode, Vite, Tailwind CSS, Lucide React, Nginx Alpine
- **Cơ sở dữ liệu chính (Source of Truth):** MySQL 8.4 LTS
- **Database hoạt động học tập (Projection):** MongoDB 7.0
- **Database đồ thị quan hệ (Graph DB):** Neo4j 5.20 Community
- **Kho lưu trữ tệp (S3 Object Storage):** MinIO S3-compatible
- **Quản lý Database Migration:** Flyway Core 10.x
- **Điều phối cụm container:** Docker Compose

---

## 2. Hướng dẫn Khởi động Nhanh (Docker Quickstart)

Yêu cầu duy nhất trên máy: **Docker Engine / Docker Desktop** và **Docker Compose**.

### Bước 1: Chuẩn bị biến môi trường
```bash
# Trên Windows PowerShell:
Copy-Item .env.example .env
Copy-Item .env.example infra/.env

# Hoặc trên Linux/macOS:
cp .env.example .env
cp .env.example infra/.env
```

### Bước 2: Khởi động toàn bộ hệ thống

Cụm dịch vụ mặc định **fail closed**: chỉ lắng nghe trên loopback (`127.0.0.1`), không seed tài khoản demo, không bật nút chuyển vai trò và không bật luồng thanh toán mô phỏng.

```bash
docker compose -f infra/compose.yaml up -d --build
```

Để chạy bản **demo đầy đủ** (có tài khoản mẫu, nút chuyển vai trò, thanh toán mô phỏng) — **chỉ dùng cục bộ**, xem mục 4:

```bash
docker compose -f infra/compose.yaml -f infra/compose.demo.yaml up -d --build
```

Sau khi khởi động, các container sẽ tự động kiểm tra healthcheck và sẵn sàng phục vụ.

---

## 3. Địa chỉ Truy cập Dịch vụ (Local URLs)

| Dịch vụ | URL | Thông tin đăng nhập / Ghi chú |
|---|---|---|
| **Giao diện Web (Frontend)** | [http://localhost:3000](http://localhost:3000) | Giao diện React SPA 8 Tab + Studio |
| **Backend REST API** | [http://localhost:8080/api/v1](http://localhost:8080/api/v1) | Spring Boot REST API |
| **Kiểm tra Sức khỏe (Health)** | [http://localhost:8080/api/v1/health](http://localhost:8080/api/v1/health) | Trạng thái UP |
| **MinIO Web Console** | [http://localhost:9001](http://localhost:9001) | User: `minio_admin` / Pass: `minio_password` |
| **Neo4j Browser** | [http://localhost:7474](http://localhost:7474) | User: `neo4j` / Pass: `neo4j_password` |
| **MySQL Database** | `localhost:3307` | User: `classroom_user` / Pass: `classroom_pass` / DB: `classroom_db` |

---

## 4. Tài khoản Demo có sẵn (Development Seed)

Hệ thống có sẵn bộ dữ liệu mẫu (idempotent seed), nhưng **mặc định KHÔNG được tạo**. Bộ dữ liệu này dùng mật khẩu cố định và cho phép OWNER tự tất toán đơn hàng, nên nó chỉ chạy khi bạn bật tường minh bằng lớp phủ demo:

```bash
docker compose -f infra/compose.yaml -f infra/compose.demo.yaml up -d --build
```

Lớp phủ này bật cùng lúc `DEMO_SEED_ENABLED`, `VITE_ENABLE_DEMO_LOGIN`, `PAYMENT_SANDBOX_ENABLED` và `MOCK_PAYMENT_CHECKOUT_ENABLED`. **Chỉ dùng cho cụm dịch vụ cục bộ dùng-rồi-bỏ.** Tuyệt đối không dùng cho môi trường lưu trữ lâu dài hoặc có thể truy cập từ Internet, và không kết hợp với việc mở rộng `HOST_BIND_ADDRESS`.

Tất cả tài khoản demo sử dụng mật khẩu chung: `Password123!`

| Tài khoản | Vai trò | Đặc quyền & Quyền hạn |
|---|---|---|
| `owner@classroom.local` | **OWNER** | Giáo viên chủ nhiệm: Toàn quyền quản trị lớp học, khóa học, doanh thu và nhân sự Studio |
| `staff@classroom.local` | **STAFF** | Trợ giảng: Được cấp quyền chấm thi (`EXAM:GRADE`), xem trước khóa (`COURSE:PREVIEW`) |
| `student.free@classroom.local` | **STUDENT (FREE)** | Học viên thông thường: Học khóa miễn phí, làm bài thi khảo sát chung |
| `student.pro@classroom.local` | **STUDENT (PRO)** | Học viên VIP có gói PRO hiệu lực: Học khóa chuyên sâu, làm bài thi PRO |
| `student.expired@classroom.local`| **STUDENT (EXPIRED)**| Học viên có gói PRO đã hết hạn: Bị khóa nội dung trả phí, lịch sử học được bảo toàn |
| `admin@classroom.local` | **PLATFORM_ADMIN** | Quản trị viên cấp nền tảng |

*Mẹo: Khi chạy với lớp phủ demo, giao diện web có nút chuyển nhanh vai trò 1-click tại góc trên bên phải để dễ dàng kiểm thử các trường hợp phân quyền. Ở bản mặc định, nút này bị loại bỏ khỏi bundle.*

---

## 5. Các Lệnh Điều hành Chính (Commands)

```bash
# Khởi động dịch vụ nền:
docker compose -f infra/compose.yaml up -d

# Dừng hệ thống:
docker compose -f infra/compose.yaml down

# Xem trạng thái sức khỏe các container:
docker compose -f infra/compose.yaml ps

# Xem nhật ký hoạt động (Logs):
docker compose -f infra/compose.yaml logs -f backend

# Chạy toàn bộ Unit & Domain Policy Test (Backend):
docker compose -f infra/compose.yaml run --rm backend-test

# Chạy TypeScript Strict Typecheck & Build Test (Frontend):
docker compose -f infra/compose.yaml run --rm frontend-test

# Tái tạo môi trường sạch từ zero-state (Clean Reset):
docker compose -f infra/compose.yaml down -v
docker compose -f infra/compose.yaml up -d
```

---

## 6. Cấu trúc Thư mục

```
ClassRoom/
├── backend/                  # Mã nguồn Spring Boot (Java 21)
│   ├── src/main/java/        # Packages nghiệp vụ modular
│   │   └── com/classroom/
│   │       ├── config/       # Security, JWT, MinIO, WebConfig
│   │       ├── common/       # ApiResponse, ErrorCode, ExceptionHandler
│   │       ├── modules/      # identity, classroom, learning, media, community, exam, ranking, segment, commerce, outbox, audit
│   │       └── seed/         # DataSeedRunner (Idempotent seed)
│   ├── src/main/resources/
│   │   ├── db/migration/     # Flyway SQL migrations (V1__initial_schema.sql)
│   │   └── application.properties
│   ├── src/test/java/        # Unit & Policy Tests (AccessPolicy, ExamAudience, Scoring, Idempotency, ...)
│   ├── Dockerfile            # Multi-stage Docker build (builder, tester, runner)
│   └── pom.xml
│
├── frontend/                 # Mã nguồn React + TypeScript strict (Vite + Tailwind)
│   ├── src/
│   │   ├── api/              # API Client fetch wrapper
│   │   ├── components/       # Navbar, ClassroomHeader, UIStates
│   │   ├── context/          # AuthContext (JWT session & Quick Demo Switcher)
│   │   ├── pages/
│   │   │   ├── classroom/    # 8 Tabs: Feed, Learn, Exams, Leaderboard, Documents, Members, About, Store
│   │   │   └── studio/       # Studio: Overview, Courses, Exams, Grading, Staff, Segments, Store, Audit
│   │   └── types/            # TypeScript interfaces
│   ├── nginx.conf            # Nginx config với static fallback và API proxy
│   ├── Dockerfile            # Multi-stage Docker build (builder, tester, runner)
│   └── package.json
│
├── infra/
│   ├── compose.yaml          # Quản lý 6 services: mysql, mongodb, neo4j, minio, backend, frontend
│   └── .env
│
├── docs/                     # Bộ tài liệu kiến trúc & vận hành
│   ├── DECISIONS.md          # Quyết định kỹ thuật & giả định thiết kế
│   ├── IMPLEMENTATION_STATUS.md # Bảng theo dõi tiến độ chi tiết
│   ├── API.md                # Đặc tả hợp đồng REST API
│   ├── RUNBOOK.md            # Hướng dẫn vận hành & ứng phó sự cố
│   └── DOCKER.md             # Kiến trúc Docker containerization
│
├── .env.example              # Mẫu biến môi trường phát triển
└── README.md
```
