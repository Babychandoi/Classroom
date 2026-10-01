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

### 2.3 Chạy bộ E2E trên trình duyệt thật (Playwright)
Bộ `e2e/` lái một Chrome thật qua giao diện để bắt các lỗi mà unit test không thấy: CSP chặn tài nguyên (font, media MinIO), race khởi tạo phiên, tab/bố cục vỡ ở màn hình hẹp, đăng xuất nhiều tab. Chi tiết từng script, biến môi trường và ý nghĩa PASS/FAIL nằm ở [`e2e/README.md`](../e2e/README.md).
```bash
# Điều kiện: stack đang chạy tại http://localhost:3000 và máy có Chrome (hoặc `npx playwright install chromium`)
cd e2e
npm install
npm run all        # BASE_URL=http://localhost:3000 mặc định; in PASS/FAIL theo từng hành trình
```
> **Không bao giờ chạy trên production.** Mỗi lần chạy tạo người dùng `e2e.*@example.com`, lớp `e2e-class-*`, khóa học, đề thi, tệp trên MinIO... và không dọn dẹp. Các script từ chối `BASE_URL` không phải máy cục bộ (trừ khi đặt `E2E_ALLOW_REMOTE=1` cho một môi trường thử nghiệm riêng). Muốn xóa dữ liệu sau khi chạy trên stack cục bộ: `docker compose -f infra/compose.yaml down -v` (mục 1.3).

### 2.4 Kiểm thử tải / lỗi trên stack drill (`loadtest/`) — R20
Bộ `loadtest/` (chỉ cần Node 20+ hoặc Docker) tái hiện các sự cố hiệu năng của vòng R20 và kiểm tra ngưỡng. Chi tiết từng kịch bản, biến môi trường và ngưỡng: [`loadtest/README.md`](../loadtest/README.md).
```powershell
# 1. Dựng stack drill (tên project KHÁC, cổng 13000/18080; xem mục 5.3). Tùy chọn: thêm -f infra/compose.drill-diag.yaml để in thống kê pool Hikari.
docker compose -p classroom-drill -f infra/compose.yaml -f infra/compose.demo.yaml -f infra/compose.drill.yaml --env-file infra/.env up -d --build
# 2. Kiểm tra nhanh (~1 phút, 40 học viên nộp bài cùng lúc) - dùng cho CI; hoặc: cd loadtest; npm run smoke (khi Node cục bộ có đường tới drill)
.\loadtest\run-in-drill.ps1 smoke.js
# 3. Đầy đủ: 200 học viên, sau đó bảng tin
.\loadtest\run-in-drill.ps1 seed.js --users 200 --questions 20 --exams 3 --posts 30 --comments 20
.\loadtest\run-in-drill.ps1 scenarios\exam-burst.js --users 200 --duration 20
.\loadtest\run-in-drill.ps1 scenarios\feed-read.js --sessions 100 --duration 30
.\loadtest\run-in-drill.ps1 scenarios\auth-nat.js --users 200 --window 30      # cả lớp từ MỘT IP: đăng nhập/đăng ký/làm mới vẫn qua, vét cạn vẫn bị chặn (R20-02, mục 4.7)
bash loadtest/scenarios/outbox-lag.sh 200                                     # 200 người vào MỘT lớp: độ trễ chiếu sang MongoDB/Neo4j (R20-05, mục 4.9)
bash loadtest/sql/outbox-backlog.sh 300000                                    # 300 000 hàng outbox PROCESSED giả: chi phí một lần thăm dò rảnh (R20-05)
bash loadtest/scenarios/outbox-outage.sh mongodb 120                          # dừng MongoDB drill 120 s: tác vụ nền cốt lõi vẫn đúng hạn, không DEAD_LETTER (R20-04)
bash loadtest/scenarios/outbox-outage.sh neo4j 120
# 4. Dọn dẹp - CHỈ project drill
docker compose -p classroom-drill -f infra/compose.yaml -f infra/compose.demo.yaml -f infra/compose.drill.yaml --env-file infra/.env down -v
```
> **Chỉ chạy trên drill/dev.** Kịch bản tạo hàng trăm người dùng `lt.*@example.com` và bắn tải đột biến; script từ chối `BASE_URL` không phải máy cục bộ (`LOADTEST_ALLOW_REMOTE=1` để bỏ qua) và từ chối cổng mặc định của stack thật 3000/8080 (`LOADTEST_ALLOW_MAIN_STACK=1`). **Không bao giờ chạy tải/lỗi trên stack thật.**
> **Ngưỡng đạt (drill, máy 8 nhân):** 200 lượt nộp đồng thời → 200/200 thành công, p95 nộp ≤ 5 s (thực đo 0,9–1,3 s), readiness luôn 200 (p99 ≤ 1 s), bảng xếp hạng khớp sau ≤ 15 s (thực đo 0,5–1,1 s); bắt đầu làm bài đồng thời p95 ≤ 1,5 s; tự lưu p95 ≤ 300 ms; bảng tin trang đầu p95 ≤ 300 ms dưới 100 phiên. Trước khi sửa (R20): 6/200 thành công, ~60 s, readiness treo 30 s.

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

### 4.2 Lỗi kết nối NoSQL (MongoDB hoặc Neo4j gián đoạn) — R20-04
- Nghiệp vụ cốt lõi và kiểm soát quyền hạn (RBAC, Entitlement, AccessPolicy) hoàn toàn hoạt động trên MySQL (Source of Truth); MongoDB và Neo4j chỉ là kho chiếu được nạp từ bảng outbox.
- **Khi một kho ngừng, không cần thao tác gì.** Tác vụ nền cốt lõi (chốt lượt thi quá hạn, tính lại xếp hạng, dọn media, dọn token) vẫn chạy đúng hạn vì mọi I/O tới MongoDB/Neo4j nằm trên executor riêng và scheduler dùng chung là một pool; các lỗi do kho ngừng là **tạm thời**: không tính vào giới hạn thử lại, **không bao giờ** đưa sự kiện vào `DEAD_LETTER`, và worker ngừng gọi kho đó (chỉ thăm dò 2 → 4 → 8 → 15 s) cho tới khi nó trả lời; khi kho trở lại, backlog tự dồn (đo trên drill: 18 s với MongoDB, 24 s với Neo4j sau khi khởi động lại kho, trước đây MongoDB 108 s và Neo4j không tự phục hồi — cần replay thủ công).
- Sự kiện thật sự "độc" (lỗi tất định) mới đi vào `DEAD_LETTER` sau 5 lần, chỉ chặn aggregate của nó, được tự replay có giới hạn và có thể replay thủ công (Studio). Chi tiết, cấu hình và giám sát: mục 4.9.

### 4.3 Webhook thanh toán không ghi nhận được (`PAYMENT_SETTLE_FAILED`) — R19-03
- Khi `PAYMENT_SUCCESS` không lưu được (lỗi CSDL, khóa xung đột kéo dài), API trả **500 `PAYMENT_SETTLE_FAILED`** (không còn 400 chung chung) để cổng thanh toán thật **gửi lại** webhook. Giao dịch đã rollback hoàn toàn: đơn vẫn `PENDING`, chưa cấp quyền truy cập. Deadlock / lock-wait / trùng khóa duy nhất được backend tự chạy lại tối đa 3 lần trước khi trả 500.
- Mỗi lần thất bại để lại dấu vết trong **giao dịch riêng**: một bản ghi audit `PAYMENT_SETTLE_FAILED` (Studio → Nhật ký, chi tiết: loại sự kiện, lý do) và một sự kiện outbox `PAYMENT_SETTLE_FAILED` cho đơn đó. Đối soát: tìm đơn `PENDING` có audit này, sửa nguyên nhân rồi gửi lại webhook (hoặc dùng mô phỏng sandbox); `PAYMENT_SUCCESS` gửi lại cho cùng đơn/`providerRef` là idempotent.
- Đơn mà quyền truy cập cộng dồn sẽ vượt **50 năm** kể từ hôm nay bị từ chối bằng **400** với thông báo "vượt quá giới hạn cho phép" (ngay khi tạo đơn; nếu đơn đã tạo trước đó thì khi tất toán, kèm audit/outbox như trên) — cần đối soát/hoàn tiền thủ công.

### 4.4 Migration V30 (TIMESTAMP → DATETIME, R19-03)
- `V30__timestamp_to_datetime_beyond_2038.sql` đổi các cột chứa **ngày tương lai** (`entitlements.starts_at/expires_at`, `product_prices.access_starts_at`, `order_items.access_starts_at_snapshot`, `exams.schedule_start/end`, `exam_attempts.ends_at`, `revoked_tokens/refresh_tokens.expires_at`, `leaderboard_recalc_jobs.next_attempt_at`) sang `DATETIME(6)` để vượt giới hạn năm 2038 của `TIMESTAMP`. Giá trị hiện có được giữ nguyên: script ghim `time_zone` của phiên về `+00:00` trong lúc chuyển đổi rồi khôi phục lại.
- Mỗi bảng được xây lại một lần (ALGORITHM=COPY) nên chặn ghi trong thời gian sao chép — chạy lúc ít tải. Mọi lệnh đều là `MODIFY` idempotent: nếu bị dừng giữa chừng, `flyway repair` rồi chạy lại. Các cột `created_at`/`updated_at` (thời điểm đã xảy ra) được giữ nguyên `TIMESTAMP`.
- **Chỉ đúng khi máy chủ MySQL chạy UTC (R20-08).** Chú thích "giá trị không bị lệch" của V30 chỉ đúng khi `@@global.time_zone` là UTC; ở máy chủ khác UTC mọi giá trị bị lệch đúng bằng độ lệch múi giờ. Vì V30 không sửa được, backend kiểm tra múi giờ **trước khi** Flyway chạy và từ chối khởi động nếu V30 đang chờ mà máy chủ không phải UTC (ghi đè bằng `APP_DB_ALLOW_NON_UTC_MIGRATION=true`) — mục 7.4.
- **Thời gian khóa ghi (R20-11):** đo được 102,9 s cho 1 triệu `entitlements` + 1 triệu `revoked_tokens` + 1 triệu `refresh_tokens` + 300 000 `exam_attempts`; ước tính 12–15 phút cho ~1 triệu lượt thi thực tế. Chạy trong cửa sổ bảo trì với backend đã dừng — mục 7.5.

### 4.5 Đăng nhập báo 403 "Invalid CORS request" — R19-02
- Nguyên nhân cũ: backend chỉ thấy `Host: localhost` (nginx làm rơi cổng) nên POST cùng nguồn từ `localhost:13000` (drill), `FRONTEND_PORT` tùy chỉnh, IP LAN hoặc tên miền bị coi là khác nguồn và bị từ chối. Đã sửa: nginx chuyển tiếp `Host`, `X-Forwarded-Host/Proto/Port` nguyên vẹn và backend chỉ tin chúng từ proxy trong `APP_SECURITY_TRUSTED_PROXIES` (cùng danh sách với `X-Real-IP`). Các header `Forwarded`/`X-Forwarded-Prefix` do client gửi bị nginx xóa.
- Nếu vẫn gặp 403 trên một tên miền: origin công khai khác `http://localhost:<FRONTEND_PORT>` (thường là `https://...` vì TLS kết thúc ở proxy phía trước, nginx chỉ thấy HTTP). Đặt `APP_CORS_ALLOWED_ORIGINS=https://ten-mien-cua-ban` trong `infra/.env` rồi `docker compose -f infra/compose.yaml up -d --no-deps backend`. Kiểm tra nhanh (phải là 401/200, không phải 403): `curl -i -X POST -H "Origin: https://ten-mien-cua-ban" -H "Content-Type: application/json" -d "{}" http://127.0.0.1:${FRONTEND_PORT:-3000}/api/v1/auth/login`; một origin lạ (`https://evil.example`) vẫn phải trả 403.
- Bảo mật: `X-Forwarded-*` chỉ có giá trị khi peer TCP là proxy tin cậy, nên **không** đặt `HOST_BIND_ADDRESS` ra ngoài loopback cho backend và không thêm địa chỉ không phải proxy vào `APP_SECURITY_TRUSTED_PROXIES`.

### 4.6 Pool kết nối cơ sở dữ liệu nghẽn / backend "treo" khi nhiều người cùng thao tác — R20-01
**Triệu chứng:** nhiều yêu cầu cùng lúc (điển hình: cả lớp bấm *Nộp bài*) trả 500 hoặc treo hàng chục giây; `/api/v1/health/readiness` trả 503 với lý do "Database connection pool exhausted"; log có `HikariPool-1 - Connection is not available, request timed out after ...ms`.
**Nguyên nhân đã sửa:** giao dịch `REQUIRES_NEW` (tính lại bảng xếp hạng, tạo dòng xếp hạng) chạy trong `afterCommit` của yêu cầu nộp bài, trong khi Spring vẫn giữ kết nối của yêu cầu đó → mỗi yêu cầu giữ 1 kết nối và chờ thêm 1–2 kết nối nữa → pool 10 kết nối cạn, mọi thứ chờ nhau. Nay `afterCommit` chỉ đẩy công việc sang hàng đợi chạy nền (`LeaderboardRecalcExecutor`, `classroom.leaderboard.recalc.workers`) và mọi giao dịch chỉ dùng **một** kết nối; sweeper `leaderboard_recalc_jobs` vẫn là lưới an toàn.
**Cấu hình pool (biến môi trường của backend, đã được `infra/compose.yaml` chuyển vào container):**

| Biến | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `DB_POOL_MAX_SIZE` | 20 | Số kết nối tối đa của mỗi backend (giữ nhỏ hơn `max_connections` của MySQL chia cho số bản backend) |
| `DB_POOL_MIN_IDLE` | 5 | Số kết nối nhàn rỗi giữ sẵn |
| `DB_POOL_CONNECTION_TIMEOUT_MS` | 10000 | Chờ kết nối tối đa trước khi báo lỗi nhanh |
| `DB_POOL_LEAK_DETECTION_MS` | 0 (tắt); drill: 30000 | Ghi stack trace khi một kết nối bị giữ lâu hơn ngưỡng (chẩn đoán giữ kết nối qua I/O chậm hoặc lồng nhau) |
| `READINESS_TIMEOUT_MS` | 2000 | `/health/readiness` chờ kết nối tối đa từng này ms rồi trả 503 |
| `LEADERBOARD_RECALC_WORKERS`, `LEADERBOARD_RECALC_QUEUE` | 3, 2000 | Số luồng nền và độ dài hàng đợi tính lại xếp hạng |

**Chẩn đoán:** dựng lại backend của drill với `-f infra/compose.drill-diag.yaml` để log thống kê pool (dòng `classroom-pool - Before cleanup stats (total, active, idle, waiting)`) mỗi 30 s; `jstack`/`kill -3` cho thấy các luồng `http-nio` cùng dừng ở `HikariPool.getConnection`. Kịch bản tái hiện: `loadtest/run-in-drill.ps1 smoke.js` (mục 2.4). Quy tắc khi sửa code: **không mở giao dịch/kết nối thứ hai (`REQUIRES_NEW`, `TransactionTemplate`) khi giao dịch hiện tại còn giữ kết nối, và không làm việc với cơ sở dữ liệu trong `afterCommit`**.

### 4.7 Giới hạn tốc độ xác thực: cả lớp sau một NAT bị 429 — R20-02
**Triệu chứng cũ:** một lớp 200 học viên dùng chung một địa chỉ IP của trường (NAT) → 190/200 lượt đăng nhập, 190/200 lượt đăng ký và 140/200 lượt làm mới phiên nhận `429` (bộ giới hạn cũ tính 10/10/60 lần mỗi phút **cho mỗi IP**, nên cả lớp mất ~20 phút mới vào hết; một người xấu trong cùng NAT khóa cả lớp).
**Thiết kế hiện tại** (`AuthRateLimitFilter` + `AuthThrottle`; ngân sách gắn với *thứ cần bảo vệ*, không chỉ với IP):

| Endpoint | Cơ chế (mặc định) |
| --- | --- |
| `POST /auth/login` | (1) cửa sổ theo cặp **(IP, e-mail chuẩn hóa)**: 10 lượt/phút; (2) **lùi theo cấp số nhân khi thất bại liên tiếp**: 5 lần sai liên tiếp của một cặp → khóa 60 s, mỗi lần sai tiếp theo nhân đôi (120, 240, 480 s) tới trần 15 phút; đăng nhập đúng xóa bộ đếm; 30 lần sai liên tiếp của cùng e-mail từ mọi IP → khóa e-mail (đoán phân tán); (3) trần **lượt SAI** theo IP: 200/phút (chống nhồi thông tin đăng nhập nhiều tài khoản) — **đăng nhập thành công không bao giờ tính vào trần này**, và cặp (IP, e-mail) đã đăng nhập thành công gần đây (6 giờ) vẫn dùng được khi ai đó sau cùng NAT làm cạn trần; (4) trần mọi lượt theo IP 1000/phút — chỉ để chặn đốt CPU băm mật khẩu |
| `POST /auth/register` | 300 lượt/phút mỗi IP (một lớp đăng ký cùng phút vẫn đủ) + 10 lượt/phút mỗi e-mail (chống gửi trùng) |
| `POST /auth/refresh`, `/auth/logout` | 60 lượt/phút mỗi **phiên** (băm SHA-256 của cookie làm mới) + 600 lượt/phút mỗi IP; hai endpoint có xô riêng |

- Phản hồi khi bị chặn: **429** đúng khung lỗi chuẩn (`error.code=RATE_LIMITED`, thông báo tiếng Việt UTF-8) kèm `Retry-After` (giây còn lại của cửa sổ / thời gian khóa). Mật khẩu đúng gửi trong lúc bị khóa cũng bị 429 (kẻ tấn công không có "oracle").
- **Không lộ e-mail nào có tài khoản:** khóa dựa trên chuỗi e-mail người gọi gửi, không dựa trên việc tài khoản tồn tại; mọi phản hồi 401/429 giống hệt nhau; `AuthService.login` còn băm mật khẩu với hash giả khi e-mail không tồn tại để thời gian phản hồi không khác nhau.
- **Bộ nhớ có giới hạn cứng, không khóa/DNS trên đường xử lý yêu cầu:** mỗi kho giữ tối đa `AUTH_RL_MAX_TRACKED_KEYS` (mặc định 20 000) khóa; mục hết hạn được quét tối đa 1 lần/giây và khi tràn thì loại bớt (tài khoản đang bị khóa được giữ lại sau cùng). Danh sách proxy tin cậy cho `X-Real-IP` giữ nguyên thiết kế R18-02 (giải tên nền, ảnh chụp bất biến).
- **Trạng thái nằm trong RAM của từng bản backend** (không dùng kho chung để khỏi thêm điểm hỏng): chạy N bản sau bộ cân bằng tải thì trần hiệu dụng ≈ N × giá trị cấu hình, và khởi động lại backend xóa mọi khóa. Đó là lý do lớp ingress vẫn nên có giới hạn thô riêng. Trần lượt sai theo IP tính khi yêu cầu **hoàn tất**, nên một loạt yêu cầu bùng nổ đồng thời có thể vượt trần tối đa bằng số yêu cầu đang chạy cùng lúc.
- Đánh đổi có chủ ý: kẻ tấn công biết e-mail nạn nhân vẫn có thể khiến cặp (IP, e-mail) bị khóa tạm (tối đa 15 phút, mỗi lần chỉ cần vài lượt sai) — nạn nhân đăng nhập từ **IP khác** hoặc chờ `Retry-After`. Nếu điều này gây phiền, tăng `AUTH_RL_LOGIN_LOCK_THRESHOLD` hoặc giảm `AUTH_RL_LOGIN_LOCK_MAX_SECONDS`.

**Cấu hình** (`infra/.env` → `infra/compose.yaml` → `application.properties` `app.security.rate-limit.*`; đã có mặc định an toàn, giá trị `<= 0` tắt riêng kiểm tra đó):

| Biến môi trường | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `AUTH_RL_LOGIN_PER_ACCOUNT_PER_MINUTE` | 10 | lượt đăng nhập/phút cho một cặp (IP, e-mail) |
| `AUTH_RL_LOGIN_PER_IP_FAILURES_PER_MINUTE` | 200 | lượt đăng nhập **sai**/phút cho một IP |
| `AUTH_RL_LOGIN_PER_IP_ATTEMPTS_PER_MINUTE` | 1000 | mọi lượt đăng nhập/phút cho một IP (bảo vệ CPU) |
| `AUTH_RL_LOGIN_LOCK_THRESHOLD` / `_LOCK_BASE_SECONDS` / `_LOCK_MAX_SECONDS` | 5 / 60 / 900 | số lần sai liên tiếp tới khi khóa, độ dài khóa đầu, trần độ dài khóa |
| `AUTH_RL_LOGIN_ACCOUNT_LOCK_THRESHOLD` | 30 | lần sai liên tiếp của cùng e-mail (mọi IP) tới khi khóa e-mail |
| `AUTH_RL_REGISTER_PER_IP_PER_MINUTE` / `_PER_EMAIL_PER_MINUTE` | 300 / 10 | đăng ký/phút theo IP / theo e-mail |
| `AUTH_RL_REFRESH_PER_SESSION_PER_MINUTE` / `_PER_IP_PER_MINUTE` | 60 / 600 | làm mới + đăng xuất/phút theo phiên / theo IP |
| `AUTH_RL_MAX_TRACKED_KEYS` | 20000 | số khóa tối đa mỗi kho (giới hạn bộ nhớ) |

**Kiểm chứng trên drill:** `loadtest/run-in-drill.ps1 scenarios/auth-nat.js --users 200 --window 30` (cả lớp từ một IP, rồi vét cạn và nhồi thông tin đăng nhập vẫn bị chặn — mục 2.4). Nếu vẫn thấy 429 cho người dùng hợp lệ: đọc `Retry-After`, kiểm tra `APP_SECURITY_TRUSTED_PROXIES` (nếu `X-Real-IP` không được tin thì mọi người bị coi là cùng một IP của proxy) và nâng đúng biến ở bảng trên.

### 4.8 Kho tệp (MinIO) hoặc CSDL ngừng hoạt động tạm thời — R20-12
- **MinIO không với tới được:** `POST /media/{id}/complete`, `GET /media/{id}/download-url` và `GET /media/{id}/download` trả **503 `SERVICE_UNAVAILABLE`** (khung lỗi chuẩn, tiếng Việt, header `Retry-After: 5`) thay vì `400 "tệp chưa được tải lên"` sau 5 s hay `500` không có nội dung; đối tượng thật sự không tồn tại vẫn là 400/404. Yêu cầu `complete` thất bại được **hoàn lại trạng thái `PENDING`** nên chỉ cần gọi lại khi MinIO trở lại. Studio hiển thị "Kho lưu trữ tệp tạm thời không khả dụng…" (cả khi `PUT` tới URL đã ký thất bại ở trình duyệt), không còn "Failed to fetch". Proxy `/download` mở đối tượng **trước** khi bắt đầu phản hồi nên lỗi được ánh xạ đúng 503.
- Timeout máy khách MinIO: kết nối 3 s, đọc/ghi 30 s (`MINIO_CONNECT_TIMEOUT_MS`, `MINIO_READ_TIMEOUT_MS`, `MINIO_WRITE_TIMEOUT_MS`; mặc định của SDK là 5 **phút**). Bộ nhớ đệm DNS của JVM: lỗi tra tên 5 s, thành công 10 s (`DNS_NEGATIVE_TTL_SECONDS`, `DNS_POSITIVE_TTL_SECONDS`; mặc định JVM 10 s / 30 s) nên sau khi container `minio` chạy lại, backend hồi phục sau vài giây thay vì chờ hết `UnknownHost minio` được nhớ đệm.
- **CSDL ngừng:** `GET /api/v1/health/readiness` trả **503 trong ~2 s** (không đợi pool), và healthcheck của container `backend` dùng chính endpoint này (`interval 5s`, `retries 4`, trước đây 15): backend báo `unhealthy` sau **~24 s** MySQL dừng (đo trên drill; trước đây 15 lần thử ≈ 105 s nên đợt ngừng 40 s không bao giờ được báo); khi MySQL trở lại, lần kiểm tra thành công đầu tiên đưa về `healthy` (đo được 3–12 s sau khi MySQL khởi động) — không cần thao tác. `unhealthy` **không** khởi động lại container (chỉ tín hiệu cho giám sát / `depends_on`), nên một lần CSDL chập chờn không gây vòng lặp khởi động lại.

### 4.9 Outbox: chiếu sang MongoDB / Neo4j — thiết kế, cấu hình, giám sát, xử lý sự cố — R20-04, R20-05
Mọi thay đổi nghiệp vụ ghi một dòng vào `outbox_events` (MySQL) **trong cùng giao dịch**; `OutboxWorker` chiếu chúng sang MongoDB (`learning_events`, mọi sự kiện) và Neo4j (đồ thị thành viên lớp: `MEMBER_JOINED`, `MEMBER_REMOVED`). MySQL là nguồn sự thật; hai kho kia là kho chiếu **được phép trễ** — chúng ngừng thì tính năng cốt lõi vẫn chạy đúng hạn (thi, xếp hạng, media, token).

**Thiết kế**
- **Không chặn tác vụ nền cốt lõi (R20-04a).** Trước đây *mọi* `@Scheduled` (chốt lượt thi quá hạn, sweeper xếp hạng, dọn media, dọn token, outbox) dùng **một** luồng scheduler; khi MongoDB ngừng, mỗi sự kiện outbox chặn luồng đó ~30 s (timeout chọn máy chủ mặc định của driver) nên lượt thi quá hạn và việc tính lại xếp hạng không được xử lý suốt đợt ngừng. Nay: pool scheduler dùng chung `SCHEDULER_POOL_SIZE` (mặc định 10, luồng `classroom-sched-N`; một unit test bảo đảm pool ≥ số tác vụ `@Scheduled`), còn **mọi I/O chặn tới MongoDB/Neo4j nằm trên executor riêng có giới hạn** `outbox-worker-N` (`OUTBOX_WORKERS`, mặc định 4). Tác vụ `@Scheduled` của outbox chỉ tra MySQL (một lần tra chỉ mục khi rảnh) rồi giao việc; nó không bao giờ gọi MongoDB/Neo4j. Timeout máy khách ngắn: MongoDB chọn máy chủ 3 s / kết nối 2 s / đọc 10 s (`MONGO_SERVER_SELECTION_TIMEOUT_MS`, `MONGO_CONNECT_TIMEOUT_MS`, `MONGO_SOCKET_TIMEOUT_MS`; mặc định driver 30 s/10 s/không giới hạn); Neo4j kết nối 2 s, lấy kết nối từ pool 3 s, thử lại giao dịch 3 s, kiểm tra kết nối nhàn rỗi sau 5 s (`NEO4J_CONNECT_TIMEOUT`, `NEO4J_ACQUIRE_TIMEOUT`, `NEO4J_MAX_TX_RETRY_TIME`, `NEO4J_IDLE_TEST`; mặc định driver 30 s/60 s/30 s).
- **Thứ tự theo aggregate được giữ nguyên.** Sự kiện của một aggregate (`aggregate_type` + `aggregate_id`, ví dụ mọi thành viên của một lớp) vẫn được chiếu tuần tự đúng `sequence_no` và chỉ khi mọi sự kiện sớm hơn đã `PROCESSED` (cổng thứ tự kiểm tra lại **trước mỗi sự kiện**). Mỗi aggregate tối đa **một** tác vụ tại một thời điểm trong JVM (single-flight); giữa nhiều bản backend, thao tác nhận việc `PENDING → PROCESSING` là nguyên tử.
- **Thông lượng (R20-05).** Một lượt xử lý lấy *đầu* aggregate cùng tối đa 50 sự kiện `PENDING` liên tiếp phía sau (`OUTBOX_MAX_EVENTS_PER_AGGREGATE`), chiếu lần lượt và **dừng ở sự kiện đầu tiên không hoàn tất**. Trước đây mỗi aggregate chỉ được **một** sự kiện mỗi chu kỳ 2 s: 200 người vào một lớp trễ ~400 s, 2000 người ~67 phút. Chu kỳ thăm dò `OUTBOX_POLL_MS` mặc định 500 ms.
- **Chỉ mục (V36).** `(status, sequence_no)`, `(status, processed_at)`, `(aggregate_type, aggregate_id, status, sequence_no)` khớp đúng các truy vấn của worker (dựng ONLINE: `ALGORITHM=INPLACE, LOCK=NONE`). Trước đó mỗi lần thăm dò rảnh quét toàn bảng: với 300 000 hàng `PROCESSED` tốn ~1009 ms MySQL (590 ms `SELECT` + hai `UPDATE` ~160 ms), nay 0,3 ms.
- **Retention.** Hàng `PROCESSED` cũ hơn `OUTBOX_RETENTION_DAYS` (mặc định 7 ngày) bị xóa theo lô 1000 hàng (tìm id qua chỉ mục, xóa theo khóa chính, nghỉ 100 ms giữa các lô, tối đa 15 s mỗi lần chạy, chạy mỗi phút). `PENDING`/`PROCESSING`/`FAILED`/`DEAD_LETTER` không bao giờ bị xóa. Tắt bằng `OUTBOX_RETENTION_ENABLED=false`.

**Phân loại lỗi (R20-04b)** — mỗi lần chiếu thất bại được phân loại (`OutboxFailureClassifier`, duyệt cả chuỗi nguyên nhân rồi mới đến nội dung thông báo):

| Loại | Ví dụ | Hệ quả |
| --- | --- | --- |
| `TRANSIENT` (lỗi của *phụ thuộc*) | kết nối bị từ chối/không phân giải được tên, timeout, `MongoTimeoutException`/`MongoSocketException`, `ServiceUnavailableException`/`SessionExpiredException`/`TransientException`, "Unable to acquire connection", `DataAccessResourceFailureException`, `TransientDataAccessException` | **Không** tăng `retry_count`, **không bao giờ** `DEAD_LETTER`. Sự kiện về lại `PENDING` (`failure_kind=TRANSIENT`), ngắt mạch của kho đó mở ra. |
| `PERMANENT` (lỗi của *sự kiện*) | JSON hỏng, `IllegalArgumentException`, vi phạm ràng buộc/khóa duy nhất, `Neo.ClientError.*`, mọi lỗi không nhận diện được | Tăng `retry_count`, lùi 2/4/8/16 s; đủ **5** lần (`max-retries`) thì `DEAD_LETTER` và chặn aggregate của nó (đúng thiết kế thứ tự); các aggregate khác không bị ảnh hưởng. |

**Ngắt mạch theo từng kho (`SinkBreaker`)**: lỗi `TRANSIENT` đầu tiên *mở* ngắt mạch của kho đó — không sự kiện nào cần kho đó được nhận việc hay gọi kho nữa (Neo4j ngừng thì các sự kiện chỉ cần MongoDB vẫn chạy; câu SQL chọn việc loại sẵn `MEMBER_JOINED`/`MEMBER_REMOVED`). Sau 2 s một lần **thăm dò** duy nhất được phép; thăm dò lỗi thì chờ gấp đôi (2 → 4 → 8 → 15 s, trần `OUTBOX_BREAKER_MAX_MS`, tối đa nên đặt ≤ 60000); thăm dò thành công đóng ngắt mạch và backlog được dồn ngay. Vì vậy một kho phục hồi được phát hiện trong tối đa `OUTBOX_BREAKER_MAX_MS` (+ thời gian dồn). Chỉ **một dòng log mỗi lần đổi trạng thái**:
```
WARN  Outbox: Neo4j is UNAVAILABLE (Neo4j: ... Unable to connect to neo4j:7687 ...). Projections to it are suspended and no event will be dead-lettered while it is down; probing again every 2.0-15.0 s until it answers.
INFO  Outbox: Neo4j is reachable again after 127 s (7 failed probe(s)); resuming projections and draining the backlog
```

**Tự động replay `DEAD_LETTER` (`OutboxRedriveJob`)** — chạy mỗi phút, mỗi lần tối đa 20 sự kiện, chỉ với sự kiện **đứng đầu** aggregate (đứng sau một sự kiện kẹt thì vô nghĩa): (a) nguyên nhân `TRANSIENT` (`failure_kind` hoặc — với hàng cũ trước V36 — nhận ra từ nội dung `error_message`) sau khi chết ≥ 60 s; (b) bất kể nguyên nhân sau khi chết ≥ 30 phút (bản sửa lỗi/khôi phục kho/sửa dữ liệu được cơ hội tự gỡ). Mỗi sự kiện tối đa **3** lần tự replay (`auto_replay_count`); hết lượt thì nằm lại `DEAD_LETTER` và chỉ replay thủ công mới hồi sinh (replay thủ công đặt lại số lượt). Sự kiện "độc" vì thế chỉ chặn aggregate của nó, không lặp vô hạn. Tắt: `OUTBOX_REDRIVE_ENABLED=false`.

**Giám sát**
- **Health (Actuator):** `GET /actuator/health` có thành phần `outbox` (luôn `UP` — outbox chậm không được rút backend khỏi bộ cân bằng tải); chi tiết `pending`, `processing`, `failed`, `deadLetter`, `oldestPendingSeconds`, `attention` và trạng thái `mongo`/`neo4j` (`UP`/`DOWN` kèm `downSince`, `nextProbeAt`, `lastError`) chỉ hiện cho vai trò OPS (`management.endpoint.health.show-details=when-authorized`).
- **Log:** mỗi phút một dòng `Outbox: pending=… processing=… failed=… deadLetter=… oldestPendingSeconds=… mongo=UP neo4j=DOWN` — mức `WARN` khi có `DEAD_LETTER`/`FAILED` hoặc một kho đang ngừng, `INFO` khi đang có hàng đợi, im lặng khi rảnh.
- **Studio (chủ lớp / nhân sự có quyền `OUTBOX:REPLAY`):** `GET /api/v1/studio/classes/{classId}/outbox/status` trả số `pending`/`processing`/`failed`/`deadLetter` *của lớp đó* và tình trạng hai kho; `POST /api/v1/studio/classes/{classId}/outbox/replay` đưa tối đa 50 sự kiện `DEAD_LETTER`/`FAILED` của lớp về hàng đợi (đặt lại `retry_count` và số lượt tự replay).
- **SQL:** `SELECT status, failure_kind, COUNT(*), MIN(created_at) FROM outbox_events WHERE status <> 'PROCESSED' GROUP BY status, failure_kind;`

**Cấu hình** (`infra/.env` → `infra/compose.yaml`; mặc định trong ngoặc):

| Biến | Ý nghĩa |
| --- | --- |
| `SCHEDULER_POOL_SIZE` (10) | số luồng của scheduler dùng chung |
| `OUTBOX_POLL_MS` (500) · `OUTBOX_WORKERS` (4) · `OUTBOX_MAX_EVENTS_PER_AGGREGATE` (50) | chu kỳ thăm dò · luồng chiếu · số sự kiện liên tiếp của một aggregate trong một lượt |
| `OUTBOX_BREAKER_MAX_MS` (15000) | trần khoảng cách giữa hai lần thăm dò kho đang ngừng |
| `OUTBOX_RETENTION_DAYS` (7) · `OUTBOX_RETENTION_ENABLED` (true) | thời gian giữ hàng `PROCESSED` |
| `OUTBOX_REDRIVE_ENABLED` (true) | tự replay `DEAD_LETTER` |
| `MONGO_SERVER_SELECTION_TIMEOUT_MS` (3000) · `MONGO_CONNECT_TIMEOUT_MS` (2000) · `MONGO_SOCKET_TIMEOUT_MS` (10000) | timeout MongoDB (≤ 0: dùng giá trị trong URI/driver) |
| `NEO4J_CONNECT_TIMEOUT` (2s) · `NEO4J_ACQUIRE_TIMEOUT` (3s) · `NEO4J_MAX_TX_RETRY_TIME` (3s) · `NEO4J_IDLE_TEST` (5s) | timeout Neo4j |
| `OUTBOX_ENABLED` (true) | công tắc tắt vòng chiếu (chỉ để bảo trì) |

**Xử lý sự cố:** (1) kho ngừng → không cần làm gì; sửa/khởi động lại kho, backlog tự dồn trong vòng `OUTBOX_BREAKER_MAX_MS` + thời gian chiếu; (2) `deadLetter > 0` kéo dài → xem `error_message` (`SELECT id, aggregate_type, event_type, retry_count, failure_kind, error_message FROM outbox_events WHERE status = 'DEAD_LETTER'`), sửa nguyên nhân, đợi tự replay hoặc replay thủ công qua Studio; (3) `oldestPendingSeconds` tăng mà không có `DEAD_LETTER` và cả hai kho `UP` → kiểm tra `docker logs classroom-backend | grep -i outbox` và số hàng `PROCESSING` (một worker chết để lại hàng `PROCESSING` được thu hồi sau 120 s).

**Kiểm chứng trên drill** (mục 2.4; đã đo, Docker Desktop 8 nhân):
```powershell
loadtest\run-in-drill.ps1 seed.js --users 200 --questions 3 --exams 1
bash loadtest/scenarios/outbox-lag.sh 200            # R20-05: độ trễ chiếu khi 200 người vào MỘT lớp
bash loadtest/sql/outbox-backlog.sh 300000            # R20-05: chi phí một lần thăm dò rảnh với 300 000 hàng PROCESSED
bash loadtest/scenarios/outbox-outage.sh mongodb 120  # R20-04: dừng container drill 120 s, ép một lượt thi hết hạn + một việc tính lại xếp hạng
bash loadtest/scenarios/outbox-outage.sh neo4j 120
```
| Chỉ số (drill, Docker Desktop 8 nhân, 2026-09-30) | Trước khi sửa | Sau khi sửa |
| --- | --- | --- |
| 200 người vào MỘT lớp: độ trễ chiếu sau lượt tham gia cuối (`outbox-lag.sh 200`) | **415 s** (mỗi chu kỳ 2 s chỉ chiếu 1 sự kiện của aggregate) | **6 s** (4 lượt xử lý × 50 sự kiện) |
| Chi phí MySQL của một lần thăm dò rảnh với 300 000 hàng `PROCESSED` (`outbox-backlog.sh`) | **1009 ms** (642 + 165 + 202; quét toàn bảng) | **0,3 ms** (0,26 + 0,04 + 0,03; với 405 000 hàng: 0,22 ms) |
| Migration V36 trên bảng 300 000 hàng (chỉ mục ONLINE + hai cột INSTANT) | — | **2,8 s**, ghi vẫn chạy |
| Retention: hàng `PROCESSED` cũ hơn 7 ngày | không bao giờ dọn | 195 000 hàng dọn trong 2 lượt ~15 s (theo lô 1000, nghỉ 100 ms) |
| Dừng MongoDB 120 s — lượt thi hết hạn được chốt sau (thăm dò A: cài lúc bắt đầu; B: cài giữa đợt ngừng) | **123 s / 67 s** (chỉ sau khi MongoDB trở lại: luồng scheduler duy nhất bị outbox chặn ~30 s mỗi sự kiện) | **4 s / 6 s**, ngay trong lúc MongoDB ngừng |
| Dừng MongoDB 120 s — việc tính lại xếp hạng được xử lý sau | 123 s / 67 s | 4 s / 1 s |
| Dừng MongoDB 120 s — `DEAD_LETTER` | 0 | 0 (mọi sự kiện `PENDING`, `retry_count = 0`) |
| Dừng MongoDB 120 s — dồn hết backlog (53 sự kiện) sau khi MongoDB chạy lại | 108 s | **18 s** |
| Dừng Neo4j 120 s — `DEAD_LETTER` / sự kiện của lớp bị chặn | **1 `DEAD_LETTER` + 30 sự kiện `PENDING` kẹt** (sau 90+ s vẫn kẹt, chỉ replay thủ công mới gỡ) | **0**; 53/53 sự kiện `PROCESSED` |
| Dừng Neo4j 120 s — dồn hết backlog sau khi Neo4j chạy lại | không tự phục hồi | **24 s** (gồm ~20 s Neo4j tự khởi động) |
| Sau nâng cấp: sự kiện đã `DEAD_LETTER` bởi bản cũ (lỗi "Unable to connect") | cần replay thủ công | tự replay ~30 s sau khi backend khởi động, 30 sự kiện bị chặn dồn xong trong 4 s |
| Thứ tự trong aggregate (`processed_at` không giảm theo `sequence_no`) | 0 vi phạm | 0 vi phạm |
| readiness trong suốt các đợt ngừng | luôn 200 | luôn 200 (≤ 20 ms) |

### 4.10 Lớp riêng tư, mã mời, lớp trả phí và hết hạn thành viên — D-19
Thiết kế và các quyết định: `docs/DECISIONS.md` D-19; API: `docs/API.md` mục 2.1. Phần này là những gì người vận hành cần biết.

**Cấu hình** (`infra/.env` → `infra/compose.yaml` → `application.properties`; chỉ là tinh chỉnh, không có bí mật, đã có mặc định an toàn):

| Biến môi trường | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `AUTH_RL_INVITE_PER_IP_PER_MINUTE` | 600 | mọi lượt gọi `GET /classes/invites/{code}` và `POST …/join` từ một địa chỉ mỗi phút (chặn đốt CSDL; cả lớp sau một NAT vẫn vừa) |
| `AUTH_RL_INVITE_PER_IP_FAILURES_PER_MINUTE` | 30 | câu trả lời "không có mã dùng được" (404) mỗi phút từ một địa chỉ — bộ chặn dò mã; lượt hợp lệ không bao giờ tính; vượt thì `429` + `Retry-After` |
| `MEMBERSHIP_EXPIRY_ENABLED` | true | bật bộ quét chuyển thành viên hết hạn sang `EXPIRED` |
| `MEMBERSHIP_EXPIRY_INTERVAL_SECONDS` | 60 | giây giữa hai lượt quét |
| `MEMBERSHIP_EXPIRY_BATCH_SIZE` | 200 | số dòng mỗi giao dịch (mỗi lượt tối đa 50 lô) |

**Bộ quét hết hạn.** Chạy trên **luồng riêng** `membership-expiry-N` (không phải nhóm `classroom-sched-N` — nhóm này được cố định bằng số việc `@Scheduled`), mỗi lô là một giao dịch ngắn; chạy nhiều bản backend song song vẫn đúng (khóa dòng). Quyền truy cập bị cắt **ngay khi quá hạn** dù bộ quét chưa chạy (`AccessPolicy.isMember` kiểm tra ngày); bộ quét chỉ đưa trạng thái lưu, danh sách thành viên, bảng xếp hạng và cạnh Neo4j cho kịp. Log: `Membership expiry sweep: N member(s) moved to EXPIRED`. Kiểm tra tồn đọng (nên ≈ 0 sau một lượt quét):
```sql
SELECT COUNT(*) FROM class_members WHERE state = 'ACTIVE' AND access_expires_at IS NOT NULL AND access_expires_at <= UTC_TIMESTAMP(6);
```
Nếu con số này cứ lớn dần: xem `MEMBERSHIP_EXPIRY_ENABLED`, log `Membership expiry sweep failed…` (mất kết nối CSDL — lượt sau tự thử lại) và chỉ mục `ix_class_members_expiry_sweep` (V37) có tồn tại. Tắt bộ quét không làm ai vào lại được lớp trả phí; chỉ làm trạng thái lưu chậm cập nhật.

**Sự kiện `MEMBER_EXPIRED`** (outbox, aggregate = lớp): chiếu vào MongoDB `learning_events` như mọi sự kiện và **gỡ cạnh `MEMBER_OF`** ở Neo4j (giống `MEMBER_REMOVED`); gia hạn phát lại `MEMBER_JOINED` đặt cạnh trở lại, đúng thứ tự theo `sequence_no`. Khi Neo4j ngừng, nó bị giữ lại cùng `MEMBER_JOINED/REMOVED` (mục 4.9); xử lý dead-letter và replay như mọi sự kiện khác.

**Mã mời.** Mã đầy đủ chỉ hiện một lần lúc tạo; CSDL chỉ có SHA-256 và 4 ký tự cuối — **không khôi phục được mã đã mất**, hãy thu hồi và tạo mã mới. Người dùng báo "mã không dùng được" luôn nhận cùng một 404 (không phân biệt hết hạn/thu hồi/hết lượt/không tồn tại); để biết lý do hãy xem danh sách mã trong Studio (`status`, `usedCount`/`maxUses`) hoặc bảng `class_invites`. Nhiều người sau một NAT cùng nhập mã sai vượt 30 lần/phút sẽ cùng nhận 429 trong tối đa một phút — nâng `AUTH_RL_INVITE_PER_IP_FAILURES_PER_MINUTE` nếu thật sự cần. **Mã nằm trong đường dẫn URL** (`/classes/invites/{code}`, theo đặc tả) nên xuất hiện trong nhật ký truy cập của nginx/reverse proxy và có thể bị gửi qua header `Referer` nếu trang web của mã mời chứa liên kết ra ngoài: coi nhật ký truy cập là dữ liệu nhạy cảm, đặt hạn (`expiresAt`) và giới hạn lượt (`maxUses`) hợp lý, và thu hồi mã khi đã dùng xong (trang mã mời `/join/:code` của giao diện đã đặt `<meta name="referrer" content="no-referrer">` trong lúc mở, dùng `replace` khi rời đi và không gửi mã tới bên thứ ba; `Referrer-Policy` của nginx vẫn là `strict-origin-when-cross-origin` - xem "UI decisions (D-19 addendum)" trong `docs/DECISIONS.md`). Audit: `CLASS_INVITE_CREATE`, `CLASS_INVITE_REVOKE`, `CLASS_INVITE_JOIN` (người tham gia là `actor`). **Mã demo cố định** (`demo-invite-lop-rieng-tu-2026`, README §4) chỉ tồn tại khi bật lớp phủ demo; trước khi lên production hãy kiểm tra `SELECT COUNT(*) FROM class_invites ci JOIN classrooms c ON c.id = ci.class_id WHERE c.slug = 'lop-rieng-tu-ma-moi';` = 0 (mục 6.4 đã yêu cầu tắt `DEMO_SEED_ENABLED`).

**Lớp trả phí — xử lý sự cố thường gặp**
- *"Đã trả tiền nhưng chưa vào được lớp":* đơn phải ở `PAID` (nếu `PENDING`: sandbox cần chủ lớp xác nhận, mục 1.4; nếu không có webhook: mục 4.3). Dòng thành viên: `SELECT state, role, access_expires_at FROM class_members WHERE class_id = ? AND user_id = ?;`. Nếu người đó đang `BLOCKED`, khoản thanh toán **không** đưa họ vào lớp — audit có `CLASS_ACCESS_PAID_WHILE_BLOCKED`: hoàn tiền (webhook `PAYMENT_REFUNDED`) hoặc mở khóa trong Studio > Thành viên (thành viên có hạn còn hiệu lực sẽ vào lại `ACTIVE`).
- *Chuỗi entitlement và hạn phải khớp:* `access_expires_at` của thành viên = `MAX(expires_at)` của các entitlement `ACTIVE` của sản phẩm `CLASS_ACCESS` (trọn đời: entitlement kết thúc `9999-12-31` và `access_expires_at = NULL`). Đã được kiểm chứng đồng thời trên MySQL (tất toán × hoàn tiền × bộ quét).
- *Đổi `FREE ↔ PAID`:* `PUT /classes/{id}/access` (Studio > Cài đặt lớp > Thu phí). `FREE → PAID` không ảnh hưởng thành viên hiện có (không hết hạn); `PAID → FREE` bỏ hạn của thành viên đang hoạt động, sản phẩm được lưu trữ (không xóa). Đơn `PENDING` tạo trước đó vẫn tất toán và đưa người mua vào lớp.
- *Xóa cứng một lớp đã từng thu phí:* `classrooms.access_product_id` là khóa ngoại `RESTRICT` tới `products` (và `products.class_id` cascade về lớp) nên `DELETE FROM classrooms` bị từ chối; hệ thống chỉ lưu trữ lớp (`ARCHIVED`), không xóa. Nếu buộc phải xóa dữ liệu thử: `UPDATE classrooms SET access_product_id = NULL WHERE id = ?` trước.
- *Nâng cấp:* V37 chỉ thêm cột (`INSTANT`) và chỉ mục (`INPLACE`, không khóa ghi), không đổi hàng nào — không cần cửa sổ bảo trì; sau nâng cấp mọi lớp là `PUBLIC` + `FREE`, mọi sản phẩm là `STANDARD`, hành vi giữ nguyên.

---

## 5. Sao lưu và Diễn tập Khôi phục (Backup & Restore Drill) — R13-11(b), R14-06/07/08, NFR-04/HLD §5

Bộ script nằm trong `infra/scripts/`: `backup.ps1` / `restore.ps1` (Windows PowerShell 5.1 — bản đã được kiểm thử; `pwsh` chưa được kiểm thử) và `backup.sh` / `restore.sh` (bash). Hai họ script dùng chung định dạng `backup.json` và cách tính checksum nên **bản sao lưu do họ này tạo có thể được họ kia kiểm tra/khôi phục**. Mã dùng chung: `common.ps1`, `common.sh`, và `container/dbtool.sh` (chạy bên trong container mysql/mongodb).

Nguyên tắc thiết kế:
- Container được tìm qua **Compose project** (`docker compose -p <project> ps`), không hard-code `container_name`, nên cùng một script phục vụ stack thật và stack diễn tập.
- Thông tin đăng nhập MySQL/MongoDB/MinIO lấy từ **biến môi trường của chính container đích**, không xuất hiện trên dòng lệnh (`docker top`, `ps`) và không cần quote mật khẩu chứa `$`, dấu cách, dấu nháy. MySQL dùng `MYSQL_PWD`; `mongodump`/`mongorestore` đọc mật khẩu từ file `--config` quyền 0600 được xóa ngay khi xong; MinIO truyền `MC_HOST_myminio` qua môi trường tiến trình.
- Mọi thành phần lỗi thì cả lần chạy lỗi: exit code khác 0, **không** in "Backup complete"/"Restore complete".

### 5.1 Sao lưu (Backup)
```powershell
# Windows PowerShell (stack thật, project mặc định = tên trong compose.yaml = online-classroom)
powershell -NoProfile -ExecutionPolicy Bypass -File infra\scripts\backup.ps1
# kèm Neo4j (dừng neo4j vài giây) và thư mục đích riêng
powershell -NoProfile -ExecutionPolicy Bypass -File infra\scripts\backup.ps1 -IncludeNeo4jDump -OutDir D:\Backups
```
```bash
# Linux/macOS/Git Bash
infra/scripts/backup.sh
infra/scripts/backup.sh --include-neo4j-dump --out-dir /var/backups/classroom
```

| Tham số PowerShell / bash | Ý nghĩa |
|---|---|
| `-OutDir` / `--out-dir` | Thư mục gốc chứa thư mục timestamp. Mặc định `infra/backups`. |
| `-ProjectName` / `--project-name` | Compose project cần sao lưu. Mặc định lấy `name:` của `compose.yaml` (`online-classroom`). |
| `-InfraDir` / `--infra-dir` | Thư mục chứa `compose.yaml` và `.env`. Mặc định: thư mục cha của `scripts/`. |
| `-EnvFile` / `--env-file` | File env cho `docker compose --env-file` (lặp lại được). Mặc định `<InfraDir>/.env`. |
| `-OverlayFile` / `--overlay` | Compose file bổ sung, ví dụ `infra/compose.drill.yaml` khi sao lưu stack diễn tập. |
| `-MysqlContainer`, `-MongoContainer`, `-Neo4jContainer`, `-MinioContainer` / `--mysql-container`… | Ghi đè tên/ID container thay cho việc tra theo project. |
| `-Bucket` / `--bucket` | Bucket MinIO. Mặc định `MINIO_BUCKET` trong env file, rồi `classroom-media`. |
| `-IncludeNeo4jDump` / `--include-neo4j-dump` | Dừng neo4j, dump, khởi động lại (xem bên dưới). |

Mỗi lần sao lưu tạo thư mục `<OutDir>/<yyyyMMdd-HHmmss>/` gồm:
- `mysql-<db>.sql` — `mysqldump --single-transaction --routines --triggers` (không khóa bảng InnoDB); script kiểm tra có dòng `-- Dump completed`.
- `mongodb-<db>.archive.gz` — `mongodump --archive --gzip`.
- `neo4j.dump` — chỉ khi dùng cờ include-neo4j-dump.
- `minio-<bucket>/` — bản sao toàn bộ object qua `mc mirror`.
- `backup.json` — manifest: `timestamp`, `createdUtc`, `project` và từng thành phần (`name`, `type`, `path`, `bytes`, `files`, `sha256`; với thư mục MinIO là *tree digest* — sha256 của danh sách `sha256  đường-dẫn` đã sắp xếp). Restore kiểm tra manifest này trước khi đụng vào bất cứ thứ gì.

Nếu có thành phần lỗi (kể cả `mc mirror`), script thoát với mã khác 0, để lại file `backup.failed` trong thư mục và **không** ghi `backup.json`; restore từ chối thư mục như vậy.

**Neo4j.** `neo4j-admin database dump` yêu cầu database dừng. Với `-IncludeNeo4jDump`, script dừng container neo4j (thường dưới 20 giây), chạy **một** container dùng lại chính image và volume `/data` của neo4j, bind-mount thư mục sao lưu để `neo4j.dump` ghi thẳng ra máy host, rồi khởi động lại neo4j và chờ healthy — kể cả khi bước dump lỗi. Không có cờ này, bước Neo4j bị bỏ qua (xem 5.4 để làm thủ công trong cửa sổ bảo trì).

### 5.2 Khôi phục (Restore) — CẢNH BÁO: GHI ĐÈ dữ liệu của project đích
Script từ chối chạy nếu thiếu bất kỳ điều kiện nào sau đây:
1. `-ProjectName` / `--project-name` (bắt buộc, **không có mặc định**): project Compose sẽ bị ghi đè.
2. `-Force` / `--yes`.
3. Xác nhận tên project: gõ lại tên project khi được hỏi (tương tác), hoặc truyền `-ConfirmProject <tên>` / `--confirm-project <tên>` (không tương tác) — phải khớp chính xác `-ProjectName`.

```powershell
# Kiểm tra bản sao lưu, không thay đổi gì (không cần -ProjectName/-Force, không cần stack chạy)
powershell -NoProfile -ExecutionPolicy Bypass -File infra\scripts\restore.ps1 -BackupDir infra\backups\20260929-143000 -VerifyOnly

# Khôi phục vào stack diễn tập (hỏi gõ lại tên project)
powershell -NoProfile -ExecutionPolicy Bypass -File infra\scripts\restore.ps1 -BackupDir infra\backups\20260929-143000 `
    -ProjectName classroom-drill -OverlayFile infra\compose.drill.yaml -IncludeNeo4j -MirrorRemove -Force

# Không tương tác (CI/tác vụ định kỳ)
powershell -NoProfile -ExecutionPolicy Bypass -File infra\scripts\restore.ps1 -BackupDir infra\backups\20260929-143000 `
    -ProjectName classroom-drill -OverlayFile infra\compose.drill.yaml -Force -ConfirmProject classroom-drill
```
```bash
infra/scripts/restore.sh infra/backups/20260929-143000 --verify-only
infra/scripts/restore.sh infra/backups/20260929-143000 --project-name classroom-drill \
    --overlay infra/compose.drill.yaml --include-neo4j --mirror-remove --yes --confirm-project classroom-drill
```

Thứ tự thực hiện và ngữ nghĩa:
1. **Kiểm tra bản sao lưu** theo `backup.json` (kích thước + sha256 từng thành phần, tree digest cho MinIO). Sai lệch → dừng, chưa thay đổi gì. Bản sao lưu cũ không có `backup.json` chỉ khôi phục được với `-SkipManifestVerify` / `--skip-manifest-verify` (không kiểm tra toàn vẹn).
2. Tìm container đích theo project, in ra **bảng RESTORE TARGET** (project, tên container, bucket), rồi xác nhận như trên.
3. **Dừng backend** (bên ghi dữ liệu) trước khi ghi đè; **khởi động lại và chờ healthy** khi thành công. Nếu khôi phục **lỗi**, backend được **giữ ở trạng thái dừng có chủ đích** (không chạy ứng dụng trên dữ liệu khôi phục dở) và script in lệnh `docker start …`. `-KeepWritersStopped` / `--keep-writers-stopped` giữ backend dừng cả khi thành công.
4. **MySQL**: xóa toàn bộ bảng/view của database ứng dụng rồi nạp lại dump (bảng chỉ có ở schema hiện tại, ví dụ do migration mới hơn bản sao lưu, không còn sót lại làm lệch `flyway_schema_history`).
5. **MongoDB**: `mongorestore --drop` — collection có trong archive được thay thế; collection tạo *sau* bản sao lưu và không có trong archive được giữ nguyên.
6. **Neo4j** (chỉ khi `-IncludeNeo4j`): dừng neo4j, `neo4j-admin database load … --overwrite-destination=true` trong container mới dùng cùng volume và bind-mount **thư mục chứa `neo4j.dump` ở chế độ chỉ đọc** (trước đây dump được `docker cp` vào `/tmp` của container đã dừng nên container `run` mới không thấy file), rồi khởi động lại neo4j.
7. **MinIO**: tạo bucket nếu chưa có rồi `mc mirror --overwrite` từ bản sao lưu. Object tạo *sau* bản sao lưu vẫn còn, trừ khi thêm **`-MirrorRemove` / `--mirror-remove`** (`--remove`): khi muốn bucket **giống hệt** bản sao lưu.

Sau khi khôi phục xong stack thật (không phải drill), nếu image backend đã thay đổi, build lại: `docker compose -f infra/compose.yaml up -d --build backend`.

### 5.3 Diễn tập khôi phục định kỳ (Restore Drill) — thực thi được nguyên văn
Khuyến nghị hàng quý. `infra/compose.yaml` cố định `container_name` và cổng host, nên stack thứ hai cùng máy cần lớp phủ **`infra/compose.drill.yaml`**: đổi tên container thành `classroom-drill-<service>` (biến `DRILL_PREFIX`), đổi cổng host qua các biến `DRILL_*_PORT`, đặt `restart: "no"`. Volume và network tự tách theo tên project (`-p`).

| Dịch vụ | Cổng stack thật | Cổng drill (mặc định, đổi bằng biến) |
|---|---|---|
| MySQL | 3307 | 13307 (`DRILL_MYSQL_PORT`) |
| MongoDB | 27017 | 37017 (`DRILL_MONGO_PORT`) |
| Neo4j HTTP / Bolt | 7474 / 7687 | 17474 / 17687 (`DRILL_NEO4J_HTTP_PORT`, `DRILL_NEO4J_BOLT_PORT`) |
| MinIO API / Console | 9000 / 9001 | 19000 / 19001 (`DRILL_MINIO_PORT`, `DRILL_MINIO_CONSOLE_PORT`) |
| Backend | 8080 | 18080 (`DRILL_BACKEND_PORT`) |
| Frontend (tùy chọn) | 3000 | 13000 (`DRILL_FRONTEND_PORT`) |

Đăng nhập trên drill (R19-02): danh sách CORS của backend drill mặc định là `http://localhost:${DRILL_FRONTEND_PORT:-13000}` và `http://127.0.0.1:${DRILL_FRONTEND_PORT:-13000}` (đổi bằng `DRILL_APP_CORS_ALLOWED_ORIGINS`), nên đăng nhập qua `http://localhost:13000` chạy được mà không sửa gì; muốn dựng cả frontend cho drill thì thêm `frontend` vào danh sách dịch vụ ở bước 2 (hoặc bỏ danh sách để dựng đủ).

Các bước (PowerShell, chạy tại thư mục gốc dự án; dùng chung `infra/.env` với stack thật):
```powershell
# 1. Sao lưu stack thật (kèm Neo4j). Ghi lại đường dẫn thư mục in ra ở dòng cuối.
powershell -NoProfile -ExecutionPolicy Bypass -File infra\scripts\backup.ps1 -IncludeNeo4jDump
$backup = (Get-ChildItem infra\backups -Directory | Sort-Object Name | Select-Object -Last 1).FullName

# 2. Dựng stack diễn tập song song (tên project KHÁC, cổng KHÁC).
docker compose -p classroom-drill -f infra/compose.yaml -f infra/compose.drill.yaml --env-file infra/.env `
    up -d --build mysql mongodb neo4j minio minio-init backend

# 3. Khôi phục vào stack diễn tập (không tương tác).
powershell -NoProfile -ExecutionPolicy Bypass -File infra\scripts\restore.ps1 -BackupDir $backup `
    -ProjectName classroom-drill -OverlayFile infra\compose.drill.yaml `
    -IncludeNeo4j -MirrorRemove -Force -ConfirmProject classroom-drill
```
4. Xác minh (so stack thật với drill):
   - Sức khỏe: `curl http://127.0.0.1:18080/api/v1/health/readiness` → `{"database":"UP","status":"UP",...}`.
   - Số bản ghi MySQL (người dùng, đơn hàng, entitlement, lượt thi, media) — chạy cho cả `classroom-mysql` và `classroom-drill-mysql`, kết quả phải bằng nhau. (Câu lệnh dùng dấu nháy đơn lồng nhau vì Windows PowerShell 5.1 làm hỏng dấu nháy kép bên trong đối số của lệnh native; mật khẩu lấy từ biến môi trường của container, không nằm trên dòng lệnh.)
     ```powershell
     foreach ($c in 'classroom-mysql','classroom-drill-mysql') {
       docker exec $c sh -c 'MYSQL_PWD=$MYSQL_PASSWORD mysql -u$MYSQL_USER $MYSQL_DATABASE -B -e ''select (select count(*) from users) as users, (select count(*) from orders) as orders, (select count(*) from entitlements) as entitlements, (select count(*) from exam_attempts) as exam_attempts, (select count(*) from media_assets) as media_assets, (select md5(group_concat(id, email order by id)) from users) as users_md5''' }
     ```
   - MongoDB: dòng `N document(s) restored successfully. 0 document(s) failed to restore.` do `mongorestore` in ra khi khôi phục phải có N bằng số document `mongodump` báo lúc sao lưu (`done dumping … (N documents)`).
   - Neo4j: số node phải bằng nhau (`cypher-shell` đọc `NEO4J_USERNAME`/`NEO4J_PASSWORD` từ môi trường):
     ```powershell
     foreach ($c in 'classroom-neo4j','classroom-drill-neo4j') {
       docker exec $c sh -c 'NEO4J_USERNAME=neo4j NEO4J_PASSWORD=${NEO4J_AUTH#neo4j/} cypher-shell --format plain ''MATCH (n) RETURN count(n) AS nodes''' }
     ```
   - MinIO: sao lưu chính stack drill rồi so `sha256` của thành phần `minio` trong hai `backup.json` — phải giống nhau (tree digest phủ toàn bộ nội dung mọi object):
     ```powershell
     powershell -NoProfile -ExecutionPolicy Bypass -File infra\scripts\backup.ps1 -ProjectName classroom-drill -OverlayFile infra\compose.drill.yaml
     ```
   - Đăng nhập được bằng tài khoản đã tồn tại trước khi sao lưu; tài liệu/media tải được qua presigned URL (trỏ tới cổng MinIO của drill, `DRILL_MINIO_PORT`).
5. Ghi lại kết quả diễn tập (ngày, người thực hiện, Pass/Fail, thời gian khôi phục) vào `docs/IMPLEMENTATION_STATUS.md` hoặc `WALKTHROUGHS_HISTORY.md` của dự án liên quan.
6. **Dọn dẹp — CHỈ với `-p classroom-drill`** (không bao giờ `down -v` với project thật `online-classroom`):
   ```powershell
   docker compose -p classroom-drill -f infra/compose.yaml -f infra/compose.drill.yaml --env-file infra/.env down -v
   ```

Kết quả lần diễn tập đầu tiên (2026-09-29, Docker Desktop, dữ liệu thật của stack cục bộ): sao lưu 4 s (không Neo4j) / 23 s (kèm Neo4j, neo4j ngừng dưới 20 s); khôi phục cả bốn kho vào stack drill 43 s. Đối chiếu: 106 users, 4 orders, 7 entitlements, 24 exam_attempts, 9 media_assets, MD5 danh sách users trùng khớp, 35/35 câu lệnh `INSERT` của dump drill giống hệt dump stack thật; MongoDB 183 documents; Neo4j 108 nodes; MinIO 9 object, tree digest `389b23a9f9c6…` giống nhau (một object 25 MiB được so riêng: sha256 trùng khớp). Bảng, node Neo4j và object MinIO tạo thêm sau bản sao lưu bị loại bỏ đúng như thiết kế (object MinIO chỉ bị xóa khi dùng `-MirrorRemove`). Kết quả: **Pass**.

### 5.4 Dump/Load Neo4j thủ công (khi không dùng cờ include)
Dùng đúng cơ chế mà script sử dụng: container mới, cùng image và volume `/data` của neo4j, bind-mount thư mục sao lưu.
```bash
docker compose -f infra/compose.yaml stop neo4j
mkdir -p infra/backups/manual
docker run --rm -v online-classroom_neo4j-data:/data -v "$PWD/infra/backups/manual:/backups" \
  neo4j:5.20-community neo4j-admin database dump neo4j --to-path=/backups
docker compose -f infra/compose.yaml start neo4j
# nạp lại (ghi đè):
docker compose -f infra/compose.yaml stop neo4j
docker run --rm -v online-classroom_neo4j-data:/data -v "$PWD/infra/backups/manual:/backups:ro" \
  neo4j:5.20-community neo4j-admin database load neo4j --from-path=/backups --overwrite-destination=true
docker compose -f infra/compose.yaml start neo4j
```
(Đổi `online-classroom_neo4j-data` theo tên volume thật: `docker volume ls`, hoặc `docker inspect classroom-neo4j`.)

### 5.5 Giới hạn đã biết
- Bốn kho được sao lưu tuần tự, không phải một snapshot nhất quán tuyệt đối; nên sao lưu vào thời điểm ít ghi (hoặc dừng backend) nếu cần nhất quán chặt.
- `mc mirror` ra thư mục thường chỉ lưu nội dung object: metadata tùy biến, phiên bản cũ và cấu hình bucket không được sao lưu (content-type suy ra theo phần mở rộng khi khôi phục).
- MongoDB không xóa collection nằm ngoài archive (xem 5.2, mục 5).
- Trên Windows, script `.ps1` phải là ASCII thuần (Windows PowerShell 5.1 đọc sai UTF-8 không có BOM); giữ nguyên khi chỉnh sửa.

---

## 6. Checklist trước khi deploy production — R16-11

Cụm dịch vụ mặc định là bản **phát triển cục bộ**. Trước khi đưa bất kỳ môi trường nào ra Internet hoặc dùng dữ liệu thật, hoàn thành **từng** mục dưới đây theo thứ tự. Không đánh dấu xong một mục nếu chưa kiểm chứng được kết quả.

### 6.1 Xoay (rotate) toàn bộ secret

Mọi giá trị trong `infra/.env` hiện có (kể cả giá trị từng nằm trong file mẫu, tài liệu hay lịch sử git) phải coi là **đã lộ** và được thay bằng giá trị sinh mới, riêng cho từng môi trường:

| Secret | Cách đổi | Lưu ý |
|---|---|---|
| `JWT_SECRET` | Sinh mới (`openssl rand -hex 32`), sửa `infra/.env`, tạo lại `backend` | Mọi access/refresh token đang có sẽ mất hiệu lực (người dùng phải đăng nhập lại) — mong muốn sau khi rotate |
| `MOCK_PAYMENT_WEBHOOK_SECRET` | Sinh mới, **khác** `JWT_SECRET` | Khi có nhà cung cấp thanh toán thật, secret webhook do nhà cung cấp cấp (xem 6.4) |
| `MYSQL_ROOT_PASSWORD`, `MYSQL_PASSWORD` | `ALTER USER` trong MySQL **trước**, rồi mới sửa `.env` | Xem cảnh báo bên dưới |
| `MONGO_INITDB_ROOT_PASSWORD` | `db.changeUserPassword("<user>", "<mật khẩu mới>")` trên DB `admin` **trước** | Xem cảnh báo bên dưới |
| `NEO4J_PASSWORD` | `ALTER CURRENT USER SET PASSWORD FROM '<cũ>' TO '<mới>'` (cypher-shell) **trước** | Xem cảnh báo bên dưới |
| `MINIO_ROOT_PASSWORD` | Đổi mật khẩu root MinIO (Console hoặc `mc admin user`/khởi động lại với biến mới theo tài liệu MinIO) **trước** | Xem cảnh báo bên dưới |

> **Cảnh báo — mật khẩu CSDL nằm bên trong volume dữ liệu.** Các biến `MYSQL_*`, `MONGO_INITDB_*`, `NEO4J_PASSWORD`, `MINIO_ROOT_*` chỉ được container **đọc khi khởi tạo volume lần đầu**; với volume đã tồn tại, đổi giá trị trong `.env` **không** đổi mật khẩu thật, và backend sẽ không đăng nhập được nữa. Vì vậy: (1) **lưu giá trị mới ở nơi bền vững trước** (trình quản lý mật khẩu / secret manager của tổ chức) — mất mật khẩu root đồng nghĩa mất dữ liệu; (2) chạy lệnh đổi mật khẩu **bên trong** hệ quản trị tương ứng bằng mật khẩu cũ (qua `docker compose exec`, để mật khẩu không xuất hiện trên dòng lệnh của host); (3) chỉ **sau đó** mới sửa `infra/.env`; (4) tạo lại đúng dịch vụ phụ thuộc (`docker compose -f infra/compose.yaml up -d backend`) và kiểm tra `GET /api/v1/health` = `UP` và `GET /api/v1/health/readiness` = `UP` (readiness kiểm tra kết nối MySQL; MongoDB/Neo4j/MinIO được xác nhận bằng cách đăng nhập console tương ứng hoặc chạy một luồng dùng thử — ví dụ tải lên media, đăng nhập). Trước khi đổi bất cứ thứ gì, chạy sao lưu (mục 5.1).

### 6.2 Lịch sử git và bí mật đã lộ

- Nếu `.env`, `infra/.env` hoặc bất kỳ secret nào **từng được commit/push** (kể cả lên repo nội bộ hoặc fork), xóa chúng khỏi **toàn bộ lịch sử** (`git filter-repo`/BFG), buộc mọi bản clone cũ được thay thế, và coi các giá trị đó là đã lộ — nghĩa là mục 6.1 là **bắt buộc**, không chỉ khuyến nghị. Xóa khỏi lịch sử không thay thế được việc rotate.
- Chỉ `.env.example` (chỉ placeholder) được nằm trong git; `.gitignore` phải chặn `.env`, `infra/.env` và `infra/backups/`.
- `DevSecretGuard` từ chối khởi động nếu phát hiện secret mẫu hoặc `JWT_SECRET` trùng `MOCK_PAYMENT_WEBHOOK_SECRET`; đừng vô hiệu hóa nó.

### 6.3 HTTPS, cookie và địa chỉ công khai

- Đặt hệ thống sau một reverse proxy/load balancer **HTTPS** (TLS 1.2+). Backend và frontend trong compose chỉ phục vụ HTTP; không mở trực tiếp cổng 8080/3000 ra Internet.
- Đặt `APP_COOKIE_SECURE=true` trong `infra/.env` để cookie `refresh_token` có cờ `Secure` (nếu để `false`, cookie truyền qua HTTP thường). Ngược lại, KHÔNG đặt `true` khi vẫn truy cập qua `http://` — trình duyệt sẽ bỏ cookie và mọi lần tải lại trang bị đăng xuất.
- Đặt `MINIO_EXTERNAL_ENDPOINT` thành URL **công khai** của MinIO mà trình duyệt truy cập được (ví dụ `https://files.example.com`), vì backend ký presigned URL theo giá trị này và CSP của frontend (`MINIO_PUBLIC_ORIGIN`) được suy ra từ nó. Để mặc định (`http://localhost:<cổng>`) thì trình duyệt của người dùng thật không tải/phát được media.
- Origin công khai (R19-02): nếu địa chỉ người dùng gõ vào không phải `http://localhost:<FRONTEND_PORT>` (thường là `https://ten-mien` sau TLS), đặt `APP_CORS_ALLOWED_ORIGINS=https://ten-mien` trong `infra/.env` — xem mục 4.5. Header `X-Forwarded-Host/Proto/Port` chỉ được tin từ các peer trong `APP_SECURITY_TRUSTED_PROXIES`; cổng backend chỉ bind loopback.
- Thời hạn access token (R19-07): `JWT_EXPIRATION_HOURS=1`. Nếu `infra/.env` đang có giá trị cũ `24`, **hạ xuống 1** rồi tạo lại backend (`docker compose -f infra/compose.yaml up -d --no-deps backend`); token đã phát hành trước đó vẫn sống đến hạn cũ của chúng, sau đó mọi thứ tự làm mới bằng cookie. Mặc định trong compose/`application.properties` đã là 1.
- Bộ giới hạn tần suất đăng nhập/đăng ký/làm mới phiên/đăng xuất chỉ tin header `X-Real-IP` từ các peer trong `APP_SECURITY_TRUSTED_PROXIES` (mặc định trong compose: loopback + service `frontend`). Tên host được phân giải ở tác vụ nền (không trên luồng xử lý request); tên không phân giải được chỉ bị ghi log một lần và bị bỏ qua. Chỉ liệt kê tên thật sự tồn tại trên mạng `classroom-net`.
- Giữ `HOST_BIND_ADDRESS=127.0.0.1` cho MySQL/MongoDB/Neo4j/MinIO (không publish cổng CSDL ra ngoài); chỉ reverse proxy được phép truy cập backend/frontend.

### 6.4 Tắt tính năng demo/sandbox

Trong `infra/.env` (và **không** dùng `infra/compose.demo.yaml`) bảo đảm:

```
DEMO_SEED_ENABLED=false
PAYMENT_SANDBOX_ENABLED=false
MOCK_PAYMENT_CHECKOUT_ENABLED=false
VITE_ENABLE_DEMO_LOGIN=false
```

Kiểm tra sau khi lên: không tồn tại tài khoản `owner@classroom.local`/`Password123!`, giao diện không có nút chuyển vai trò, và `GET /api/v1/payments/sandbox-status` / `POST /api/v1/payments/mock/simulate` trả 404 (controller mô phỏng không được nạp khi `PAYMENT_SANDBOX_ENABLED=false`). **Thanh toán:** hệ thống hiện chỉ có `MockPaymentProvider`; để nhận tiền thật cần tích hợp một nhà cung cấp thanh toán thật (thay `PaymentProvider`, xác thực chữ ký webhook do nhà cung cấp cấp, đối soát đơn hàng) — chưa có trong bản này.

### 6.5 Sao lưu, diễn tập khôi phục và kiểm tra cuối

0. Nếu triển khai lên một CSDL **đã có dữ liệu** (nâng cấp từ phiên bản cũ): làm theo mục 7 (script `infra/scripts/preflight-upgrade.sql`, múi giờ MySQL phải là UTC, cửa sổ bảo trì cho V30) **trước** bước 1.
1. Chạy sao lưu đầy đủ (mục 5.1) và **diễn tập khôi phục** trên stack tạm (mục 5.3) bằng chính bản sao lưu vừa tạo; chỉ tin bản sao lưu đã khôi phục thành công. Lưu bản sao lưu ngoài máy chủ và mã hóa nếu chứa dữ liệu cá nhân.
2. Chạy lại toàn bộ bộ kiểm thử (mục 2, kể cả Integration Test) trên đúng commit sẽ deploy.
3. Sau khi lên: `GET /api/v1/health` và `/api/v1/health/readiness` = `UP`, đăng nhập/đăng xuất/tải lại trang giữ được phiên qua HTTPS, tải lên và phát một media thử, thực hiện một luồng mua hàng thử (khi đã có nhà cung cấp thanh toán thật) và kiểm tra bản ghi trong Studio > Nhật ký (Audit).
4. Ghi lại ngày rotate secret và người thực hiện; đặt lịch xoay định kỳ.

---

## 7. Nâng cấp từ phiên bản cũ (Upgrade checklist) — R20-08, R20-09, R20-10, R20-11

Áp dụng khi nâng cấp một CSDL **đã có dữ liệu** lên bản backend mới. Các migration đã áp dụng (V1…V35) **không bao giờ được sửa** (Flyway lưu checksum); mọi sửa chữa đi qua migration mới (V36+) hoặc — với dữ liệu bẩn của bản cũ — qua script kiểm tra/dọn dẹp dưới đây. Mỗi mục dưới đây được viết để chạy được nguyên văn.

### 7.1 Trình tự
1. **Sao lưu** (mục 5.1) và, với dữ liệu quan trọng, diễn tập khôi phục (mục 5.3).
2. **Xác định phiên bản schema hiện tại:**
   ```sql
   SELECT MAX(CAST(version AS UNSIGNED)) AS schema_version FROM flyway_schema_history WHERE success = 1;
   ```
3. **Chạy script kiểm tra trước nâng cấp (chỉ đọc, không cần dừng ứng dụng):**
   ```bash
   docker exec -i classroom-mysql sh -c 'MYSQL_PWD=$MYSQL_ROOT_PASSWORD mysql -uroot $MYSQL_DATABASE -t' < infra/scripts/preflight-upgrade.sql
   ```
   Mỗi truy vấn kiểm tra phải trả **0 dòng** (mục 7.2, 7.3). Có dòng nào thì xử lý bằng các khối `CLEANUP` (đã bình luận sẵn trong script) rồi chạy lại cho tới khi sạch.
4. **Múi giờ MySQL phải là UTC** (mục 7.4).
5. Nếu schema < V30: **đặt cửa sổ bảo trì** cho V30 (mục 7.5).
6. Dựng lại backend (`docker compose -f infra/compose.yaml up -d --build --no-deps backend`), theo dõi log: `Database time zone: … (UTC …)`, `Migrating schema … to version "36 …"`, rồi `GET /api/v1/health/readiness` = `UP`.

### 7.2 Lượt thi trùng số thứ tự — V17 (R20-10)
V17 điền `attempt_number` cho các lượt thi cũ của học viên bằng `ROW_NUMBER()` bắt đầu từ **1**, nên nếu cùng `(exam_id, user_id)` đã có lượt được đánh số 1, 2… thì va chạm với khóa duy nhất `uq_ea_exam_user_attempt` (V3) và migration **thất bại** (`Duplicate entry '<exam>-<user>-1' for key 'exam_attempts.uq_ea_exam_user_attempt'`). Chỉ xảy ra với CSDL đang ở schema **< V17** có lượt thi `attempt_number IS NULL` lẫn lượt đã đánh số. Script kiểm tra: phần **A** (A0 tổng số hàng NULL, A1 các cặp (exam, user) có cả hai loại, A2 chính xác những hàng sẽ bị gán trùng). **Dọn dẹp:** đánh số các lượt NULL tiếp nối sau số lớn nhất đã có (khối `CLEANUP A` — một `UPDATE … JOIN` dùng `MAX(attempt_number) + ROW_NUMBER()`); sau đó V17 không còn gì để đánh số và áp dụng sạch. (Đã thử: trước dọn dẹp V17 lỗi `Duplicate entry 'e1-u1-1'`; sau dọn dẹp V17 chạy, ràng buộc `chk_ea_learner_attempt_number` được tạo.)

### 7.3 Bản ghi mồ côi — V18 (R20-09)
V18 thêm 8 khóa ngoại; nếu còn bản ghi trỏ tới hàng đã bị xóa thì `ALTER TABLE … ADD CONSTRAINT` **thất bại** giữa chừng (`Cannot add or update a child row: a foreign key constraint fails`), để lại một phần khóa ngoại đã tạo. Chỉ với CSDL ở schema **< V18**. Script kiểm tra: phần **B** (B0 số hàng mồ côi theo từng khóa ngoại, B1–B8 liệt kê tối đa 200 hàng mỗi loại):

| Khóa ngoại (V18) | Bản ghi mồ côi | Xử lý gợi ý (khối `CLEANUP B`) |
| --- | --- | --- |
| `fk_staff_perm_course` | `staff_permissions.scope_course_id` → khóa học đã xóa | **XÓA** hàng. Không đặt `NULL`: biến quyền giới hạn theo khóa học thành quyền toàn lớp (V19 cấm đúng điều này) |
| `fk_lesson_media` | `lessons.media_asset_id` → tệp đã xóa | đặt `NULL` (bài học mất tệp, chữ vẫn còn) |
| `fk_doc_media` | `document_assets.media_asset_id` (NOT NULL) | khôi phục `media_assets` từ bản sao lưu, hoặc xóa tài liệu hỏng |
| `fk_oi_product` | `order_items.product_id` → sản phẩm đã xóa | **chứng từ tài chính, không xóa**: khôi phục sản phẩm, hoặc tạo lại hàng `products` (id cũ, `ARCHIVED`) — có câu lệnh mẫu |
| `fk_ent_product` | `entitlements.product_id` (NOT NULL) | như trên nếu người mua đã trả tiền; dữ liệu thử thì xóa |
| `fk_ent_course` | `entitlements.target_course_id` → khóa học đã xóa | đặt `NULL` |
| `fk_exam_course` | `exams.target_course_id` | đặt `NULL` (kỳ thi thành toàn lớp) |
| `fk_exam_segment` | `exams.target_segment_id` | đặt `NULL` — **chú ý** kỳ thi `audienceScope = SEGMENT` đổi đối tượng, xem lại từng kỳ thi |

(Đã thử trên MySQL 8.4 với dữ liệu có đủ 8 loại mồ côi: trước dọn dẹp V18 lỗi ở khóa ngoại đầu tiên; sau khi chạy các khối `CLEANUP B`, cả 8 khóa ngoại được tạo.)

### 7.4 Múi giờ máy chủ MySQL phải là UTC — R20-08
V30 đổi `TIMESTAMP` → `DATETIME(6)` dưới `SET SESSION time_zone = '+00:00'`. Ứng dụng luôn ghi/đọc mọi thời điểm ở UTC (URL JDBC có `serverTimezone=UTC`), nhưng nếu **máy chủ MySQL** chạy múi giờ khác (ví dụ `+07:00`) thì các giá trị `TIMESTAMP` cũ được lưu lệch đúng bằng độ lệch đó, và phép chuyển đổi ghim `+00:00` **làm mọi giá trị ngày-giờ tương lai (thời hạn quyền truy cập, hạn nộp bài, hạn token…) lệch bấy nhiêu giờ** — trái với chú thích đầu file V30. V30 đã áp dụng ở nhiều nơi nên **không sửa**; thay vào đó backend **kiểm tra trước khi Flyway chạy** (`DatabaseTimeZonePreflight`, nối qua `FlywayMigrationStrategy`):
- Luôn ghi log múi giờ CSDL khi khởi động: `Database time zone: @@global.time_zone=…, @@session.time_zone=…, @@system_time_zone=…, offset from UTC=…`; cảnh báo nếu không phải UTC (URL JDBC nói UTC nên máy chủ DB cũng nên UTC; `CURRENT_TIMESTAMP(6)` làm mặc định của cột `DATETIME` được tính theo múi giờ máy chủ).
- Nếu trong các migration **đang chờ** có V30 (hoặc bất kỳ script nào mang dòng chú thích đánh dấu `requires-utc-server` trong 40 dòng đầu — cách để migration tương lai tham gia kiểm tra) mà máy chủ **không** phải UTC, backend **từ chối khởi động** với thông báo song ngữ Việt–Anh nêu múi giờ hiện tại, cách sửa và cách ghi đè; không migration nào chạy.
- **Cách sửa (khuyến nghị):** đặt `default-time-zone='+00:00'` trong `my.cnf` (image `mysql` của stack này đã là UTC; với MySQL tự cài: thêm dòng đó vào `[mysqld]`, hoặc `SET GLOBAL time_zone='+00:00'` rồi khởi động lại MySQL và backend). Kiểm tra: `SELECT @@global.time_zone, @@session.time_zone, @@system_time_zone, TIMEDIFF(NOW(), UTC_TIMESTAMP);` → `UTC`/`SYSTEM`(=UTC)/`+00:00` và `00:00:00`. Nếu dữ liệu cũ đã được ghi khi máy chủ ở múi giờ khác, tự đánh giá độ lệch trước khi chuyển.
- **Ghi đè (chỉ sau khi tự kiểm tra dữ liệu):** `APP_DB_ALLOW_NON_UTC_MIGRATION=true` trong `infra/.env` (thuộc tính `app.db.allow-non-utc-migration`); backend vẫn ghi cảnh báo. Khi V30 đã được áp dụng (không còn chờ) thì kiểm tra chỉ còn là cảnh báo. Lưu ý: một CSDL còn trống trên máy chủ không-UTC cũng bị từ chối (V30 đang chờ) — đặt múi giờ UTC hoặc ghi đè.
- Ghi chú này cũng nằm trong phần chú thích đầu `V36__outbox_indexes_failure_kind_and_retention.sql`.

### 7.5 V30 khóa ghi trong lúc sao chép — cửa sổ bảo trì (R20-11)
V30 dùng `ALGORITHM=COPY` cho 8 bảng (`entitlements`, `product_prices`, `order_items`, `exams`, `exam_attempts`, `revoked_tokens`, `refresh_tokens`, `leaderboard_recalc_jobs`): mỗi bảng được **sao chép hoàn toàn** sang cấu trúc mới và **chặn ghi** (đọc vẫn được; mọi yêu cầu ghi vào bảng đó đứng chờ) cho tới khi xong. Số đo (Docker Desktop, MySQL 8.4): **102,9 s** cho 1 000 000 `entitlements` + 1 000 000 `revoked_tokens` + 1 000 000 `refresh_tokens` + 300 000 `exam_attempts`. Thời gian tỉ lệ với **dung lượng** bảng, không chỉ số hàng: ước tính **12–15 phút** cho ~1 triệu lượt thi thực tế (hàng rộng hơn nhiều hàng thử). Khuyến nghị:
- Chạy trong **cửa sổ bảo trì**, với backend **đã dừng** (`docker compose -f infra/compose.yaml stop backend`) rồi bật lại bản mới; không chạy giữa giờ thi.
- Phần **C** của `infra/scripts/preflight-upgrade.sql` in dung lượng các bảng này để ước lượng; cần dung lượng đĩa trống ≥ bảng lớn nhất (bản sao tạm) cộng nhật ký nhị phân.
- Mọi lệnh V30 là `MODIFY` idempotent: nếu bị ngắt giữa chừng, `flyway repair` rồi chạy lại (mục 4.4).
- V36 (chỉ mục outbox) **không** cần cửa sổ bảo trì: cột thêm bằng `ALGORITHM=INSTANT`, chỉ mục dựng `INPLACE, LOCK=NONE` (đo được 2,8 s cho bảng 300 000 hàng, ghi vẫn chạy).

## 8. Server Windows hiện tại — Round 23

Quy trình khởi động HTTPS, CA nội bộ, LAN/firewall, một backend mặc định/hai node tùy chọn, lịch backup/health/logon và cấu hình Internet nằm trong [SERVER_ON_THIS_PC.md](SERVER_ON_THIS_PC.md). Chính sách phục hồi sau yêu cầu xóa nằm trong cùng tài liệu; phải áp dụng lại quyết định đóng tài khoản trước khi mở dữ liệu phục hồi cũ.

### 8.1 V43 và dọn dữ liệu kiểm thử

V43 thêm bảng snapshot của đề công bố. Lúc khởi động, backend chuẩn bị đề cũ theo batch 100 ID, có khóa giao dịch/idempotency; cần chờ readiness trước khi mở giờ thi. Đề cũ không có câu hỏi không được bắt đầu. Không chỉnh migration đã áp dụng hoặc sửa trực tiếp nội dung công bố để thay đề của lượt thi; xem API.md và D-25.

`python infra/scripts/disable-test-accounts.py` chỉ xem trước tài khoản/class fixture trên đúng project chính. `--apply` lưu trạng thái trước trong `.artifacts/server`, ghi audit, vô hiệu hóa tài khoản, thu hồi refresh và lưu trữ lớp của chính fixture. Không xóa hồ sơ học/thi/thanh toán. Chạy sau E2E/load đã được phép và trước backup kết thúc đợt; không dùng lại bộ seed đã bị vô hiệu hóa. Nếu restore backup cũ hơn, phải rà soát trạng thái fixture cùng ledger đóng tài khoản trước khi mở dịch vụ.
