# Kiến trúc Docker & Môi trường Độc lập (Docker Architecture)

Hệ thống được thiết kế theo tôn chỉ **DOCKER-FIRST**: tuyệt đối không phụ thuộc vào bất kỳ runtime hoặc toolchain nào trên máy host (không cần Java, JDK, Maven, Node.js, npm, MySQL, MongoDB, Neo4j, MinIO).

---

## 1. Cấu trúc Multi-stage Builds

### 1.1 Backend Multi-stage Build (`backend/Dockerfile`)
```
[Stage 1: builder]
FROM maven:3.9.9-eclipse-temurin-21-alpine
  -> COPY pom.xml
  -> RUN mvn dependency:resolve -B
  -> COPY src ./src
  -> RUN mvn clean package -DskipTests -B

[Stage 2: tester]
FROM builder AS tester
  -> CMD ["mvn", "test", "-B"]

[Stage 3: runner]
FROM eclipse-temurin:21-jre-alpine AS runner
  -> RUN apk add --no-cache curl wget bash
  -> COPY --from=builder /app/target/*.jar app.jar
  -> HEALTHCHECK http://127.0.0.1:8080/api/v1/health
  -> ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 1.2 Frontend Multi-stage Build (`frontend/Dockerfile`)
```
[Stage 1: builder]
FROM node:20-alpine AS builder
  -> COPY package*.json ./
  -> RUN npm install
  -> COPY . .
  -> RUN npm run build

[Stage 2: tester]
FROM builder AS tester
  -> CMD ["npm", "run", "test:ci"]

[Stage 3: runner]
FROM nginx:1.27-alpine AS runner
  -> COPY nginx.conf /etc/nginx/conf.d/default.conf
  -> COPY --from=builder /app/dist /usr/share/nginx/html
  -> EXPOSE 80
```

---

## 2. Dịch vụ & Mạng (Docker Compose Services)

Tất cả các container giao tiếp qua mạng bridge riêng `online-classroom_classroom-net`:

| Tên Service | Image | Cổng Container | Cổng Host | Vai trò |
|---|---|---|---|---|
| `mysql` | `mysql:8.4.3` | 3306 | 3307 | Nguồn dữ liệu chuẩn (Source of Truth) cho nghiệp vụ và quyền |
| `mongodb` | `mongo:7.0.14` | 27017 | 27017 | Lưu trữ sự kiện học tập dạng NoSQL document projection |
| `neo4j` | `neo4j:5.20-community` | 7474, 7687 | 7474, 7687 | Lưu trữ đồ thị xã hội học tập & tương tác |
| `minio` | build từ `infra/minio` (target `minio`, MinIO ghim bản `RELEASE.2024-05-10T01-41-38Z`) | 9000, 9001 | 9000, 9001 | Lưu trữ tài liệu, video và media S3-compatible |
| `minio-init`| build từ `infra/minio` (target `mc`) | - | - | Tự động tạo bucket `classroom-media` khi khởi động |
| `backend` | build từ `backend/` | 8080 | 8080 | Cung cấp REST API Spring Boot (Java 21) |
| `frontend`| build từ `frontend/`| 80 | 3000 | Nginx phục vụ React SPA và Reverse Proxy `/api/` |

### Chế độ thanh toán local

Compose mặc định bật `PAYMENT_SANDBOX_ENABLED` và `MOCK_PAYMENT_CHECKOUT_ENABLED` để luồng local có thể tạo đơn và buyer mô phỏng thanh toán thành công cho chính đơn `PENDING` của mình. Đây là sandbox giả lập, không phải cổng thanh toán và không được dùng cho triển khai production. Với môi trường không phải local, đặt cả hai biến thành `false` và cấu hình provider thanh toán thật trước khi mở bán.

---

## 3. Quản lý Dữ liệu Bền vững (Named Volumes)

- `mysql-data`: Giữ dữ liệu quan hệ, bảng người dùng, đơn hàng, điểm thi.
- `mongodb-data`: Giữ các bản ghi hoạt động học tập.
- `neo4j-data`: Giữ dữ liệu đồ thị nút và cạnh.
- `minio-data`: Giữ các tệp media và tài liệu học tập.
