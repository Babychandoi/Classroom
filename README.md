# Nền Tảng Lớp Học Trực Tuyến (Online Classroom Platform)

Hệ thống quản lý lớp học trực tuyến nhiều giáo viên (multi-classroom), phân quyền Studio OWNER/STAFF, phân cấp học viên FREE/PRO, quản lý khóa học có phí/miễn phí, **lớp riêng tư (vào bằng liên kết mời) và lớp thu phí vào lớp có thời hạn**, thi trắc nghiệm và tự luận có xếp hạng, phân khúc học viên (Segment Engine), lưu trữ media MinIO và tích hợp thanh toán giả lập có xác thực webhook & idempotency.

Toàn bộ hệ thống được xây dựng theo chuẩn **DOCKER-FIRST**: máy chủ host **KHÔNG CẦN** cài đặt Java, JDK, Maven, Node.js, npm, MySQL, MongoDB, Neo4j hoặc MinIO. Mọi quy trình build, compile, migration, test và runtime đều chạy trong Docker container.

---

## 1. Kiến trúc Công nghệ (Tech Stack)

- **Backend:** Java 21, Spring Boot 3.5.16 (REST API, Spring Security 6, JWT, JPA Hibernate, Outbox Pattern)
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

Toàn bộ lệnh `docker compose` trong tài liệu này dùng `-f infra/compose.yaml`, nên thư mục dự án Compose (project directory) là `infra/` — **chỉ `infra/.env` được Docker Compose tự động đọc**; file `.env` ở thư mục gốc repo không được compose sử dụng (nó chỉ tồn tại như bản sao tham khảo, không bắt buộc). Vì vậy bạn **bắt buộc phải tạo `infra/.env`**:

```bash
# Trên Windows PowerShell:
Copy-Item .env.example infra/.env

# Hoặc trên Linux/macOS:
cp .env.example infra/.env
```

**Bắt buộc phải sửa `infra/.env` sau khi sao chép**: `JWT_SECRET` và `MOCK_PAYMENT_WEBHOOK_SECRET` trong `.env.example` chỉ là placeholder giống hệt nhau (`replace-with-a-unique-secret-...`) — `DevSecretGuard` (backend) sẽ từ chối khởi động nếu phát hiện placeholder chưa thay, hoặc nếu hai secret này trùng nhau (mỗi secret phải là một chuỗi duy nhất, tối thiểu 32 ký tự, và **khác nhau**). Mở `infra/.env`, tìm hai dòng `JWT_SECRET=` và `MOCK_PAYMENT_WEBHOOK_SECRET=` (dòng tương ứng với dòng 37 và 47 trong `.env.example`) rồi thay bằng hai giá trị sinh ngẫu nhiên riêng biệt:

```powershell
# Windows PowerShell 5.1 (tương thích ngược — không cần .NET 5+/[Convert]::ToHexString):
$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); ($b | ForEach-Object { $_.ToString('x2') }) -join ''
```

```bash
# Linux/macOS/Git Bash:
openssl rand -hex 32
```

Chạy lệnh trên **hai lần** (một lần cho `JWT_SECRET`, một lần khác cho `MOCK_PAYMENT_WEBHOOK_SECRET` — hai giá trị khác nhau, không dùng chung), dán kết quả vào đúng hai dòng đó trong `infra/.env`. Nếu bỏ qua bước này, container `backend` sẽ khởi động rồi tự thoát ngay (crash loop) với thông báo lỗi nêu rõ biến nào đang sai và vì sao.

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
| **MinIO Web Console** | [http://localhost:9001](http://localhost:9001) | User: giá trị `MINIO_ROOT_USER` / Pass: giá trị `MINIO_ROOT_PASSWORD` trong `infra/.env` (mặc định mẫu: `minio_admin` / `local-dev-minio-...`) |
| **Neo4j Browser** | [http://localhost:7474](http://localhost:7474) | User: `neo4j` / Pass: giá trị `NEO4J_PASSWORD` trong `infra/.env` |
| **MySQL Database** | `localhost:${MYSQL_PORT}` (mặc định `3307`) | User: giá trị `MYSQL_USER` / Pass: giá trị `MYSQL_PASSWORD` / DB: giá trị `MYSQL_DATABASE` trong `infra/.env` |

**R19-02 — Địa chỉ truy cập (origin) và CORS:** trình duyệt gửi header `Origin` trong mọi yêu cầu POST/PUT/DELETE, kể cả cùng nguồn. nginx (`frontend`) chuyển tiếp nguyên `Host` có cổng (ví dụ `localhost:13000`) cùng `X-Forwarded-Host/Proto/Port`, và backend — **chỉ** với yêu cầu đến từ proxy tin cậy (`APP_SECURITY_TRUSTED_PROXIES`, xem `TrustedForwardedHeaderFilter`) — dùng các giá trị đó để nhận ra yêu cầu cùng nguồn. Nhờ vậy đăng nhập hoạt động từ bất kỳ địa chỉ nào vào cổng frontend: `http://localhost:${FRONTEND_PORT}`, cổng drill `13000`, IP mạng LAN... Danh sách `APP_CORS_ALLOWED_ORIGINS` chỉ cần cho lời gọi **khác nguồn**; mặc định `http://localhost:${FRONTEND_PORT}` và `http://127.0.0.1:${FRONTEND_PORT}`. **Triển khai sau tên miền HTTPS** (TLS kết thúc ở proxy phía trước, nginx chỉ thấy HTTP): đặt `APP_CORS_ALLOWED_ORIGINS=https://ten-mien-cua-ban` (nhiều origin cách nhau dấu phẩy) và `APP_COOKIE_SECURE=true` trong `infra/.env`. Cổng backend (`8080`) chỉ publish trên loopback (`HOST_BIND_ADDRESS`); không mở nó ra ngoài — mọi lời gọi từ Internet phải đi qua nginx.

**R9-03 — CSP và origin MinIO công khai:** trình duyệt tải/phát media qua URL MinIO đã ký trước (presigned), nên Content-Security-Policy của frontend (`connect-src`/`media-src`/`frame-src`/`object-src`) phải cho phép đúng origin MinIO mà trình duyệt truy cập được. Giá trị này (`MINIO_PUBLIC_ORIGIN`) được `infra/compose.yaml` tự suy ra từ `MINIO_EXTERNAL_ENDPOINT`/`MINIO_PORT` (cùng giá trị backend dùng để ký URL), rồi được `envsubst` chèn vào `frontend/nginx.conf.template` lúc container khởi động (xem `frontend/Dockerfile`). Nếu bạn đổi `MINIO_EXTERNAL_ENDPOINT` trong `infra/.env`, CSP sẽ tự cập nhật theo mà không cần sửa `nginx.conf` thủ công.

---

## 3.1. Phiên đăng nhập bền vững qua tải lại trang (Session Persistence)

Access token (JWT) chỉ tồn tại trong bộ nhớ của tab trình duyệt (`frontend/src/api/client.ts`), **không** lưu vào `localStorage`/`sessionStorage`. Để tải lại trang, mở tab mới, hoặc mở liên kết sâu (deep link) không tự động đăng xuất người dùng, hệ thống dùng cơ chế refresh token dạng cookie HttpOnly:

- Khi đăng nhập/đăng ký, backend đặt thêm cookie `refresh_token` (HttpOnly, SameSite=Strict, giới hạn `Path=/api/v1/auth`, và `Secure` khi triển khai qua HTTPS — cấu hình bằng `APP_COOKIE_SECURE`, biến môi trường `APP_COOKIE_SECURE` trong `infra/.env`, mặc định `false`).
- Khi ứng dụng React khởi động (`AuthContext`), nó gọi `POST /auth/refresh` để đổi cookie lấy access token mới trước khi hiển thị các trang cần đăng nhập (có trạng thái "Đang xác thực..." thay vì chớp nháy chuyển hướng `/login`).
- Mỗi lần làm mới, refresh token được **xoay vòng** (rotation): token cũ bị thu hồi, token mới được phát hành cùng "family". Thời hạn refresh token cấu hình bằng `JWT_REFRESH_TOKEN_TTL_DAYS` (mặc định 30 ngày). **R19-07:** access token sống **1 giờ** (`JWT_EXPIRATION_HOURS`, mặc định 1; nếu `infra/.env` cũ vẫn đặt 24 thì hạ xuống 1) — người dùng không nhận thấy vì `api/client.ts` tự làm mới bằng cookie khi gặp 401.
- **Cửa sổ ân hạn (grace window) — R9-01:** nhiều tab cùng làm mới phiên gần như đồng thời (ví dụ tab B gửi cookie cũ ngay sau khi tab A đã xoay vòng) không còn bị coi là đánh cắp token. Trong vòng `JWT_REFRESH_TOKEN_GRACE_WINDOW_SECONDS` giây (mặc định 60s) kể từ khi một token bị xoay vòng, việc dùng lại token đó sẽ tiếp nối cùng một chuỗi (family) thay vì thu hồi toàn bộ — người dùng hợp lệ mở nhiều tab không bao giờ bị đăng xuất ngoài ý muốn. Việc dùng lại token đã xoay vòng **ngoài** cửa sổ ân hạn, hoặc dùng lại token đã bị thu hồi hẳn (đăng xuất/phát hiện đánh cắp trước đó), vẫn thu hồi toàn bộ family như cũ. **R19-07:** mỗi token đã xoay vòng chỉ được **một** lần cấp lại trong cửa sổ ân hạn (cột `grace_minted_at`): lần dùng lại thứ hai bị từ chối 401 — nếu xảy ra ngay sau lần đầu (đua giữa các tab) thì family được giữ nguyên, nếu muộn hơn vài giây thì coi là đánh cắp và thu hồi cả family. Xem `docs/DECISIONS.md` D-17. Đồng thời, thao tác làm mới được đồng bộ giữa các tab bằng Web Locks API (`navigator.locks`) ở phía trình duyệt để hạn chế race ngay từ đầu.
- `api/client.ts` tự động gọi `/auth/refresh` một lần khi gặp lỗi 401 rồi thử lại yêu cầu gốc; nhiều yêu cầu 401 đồng thời chỉ dùng chung một lần gọi refresh (single-flight). Lỗi `429`/`5xx`/mất mạng khi khởi động phiên (bootstrap) sẽ thử lại một vài lần thay vì lập tức coi là đã đăng xuất (xem R9-05 bên dưới).
- Đăng xuất sẽ thu hồi cả access token và toàn bộ family refresh token, đồng thời phát tín hiệu qua `BroadcastChannel` để các tab khác cùng đăng xuất.
- **Giới hạn tần suất (rate limit) `/auth/refresh` — R9-05:** endpoint này dùng ngưỡng riêng, cao hơn nhiều so với `login`/`register` (vốn vẫn giữ ngưỡng chặt để chống dò mật khẩu), vì một phiên hợp lệ có thể gọi refresh nhiều lần trong các thao tác bình thường (nhiều tab, tải lại trang liên tục).

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

**Lớp demo cho tính năng lớp riêng tư / lớp trả phí (D-19)** — chủ lớp là `owner@classroom.local`; `student.free`, `student.pro` và `student.expired` **chưa** là thành viên của hai lớp này:

| Lớp | Thiết lập | Cách thử |
|---|---|---|
| **Lớp Riêng Tư (mã mời)** (`lop-rieng-tu-ma-moi`) | `PRIVATE` + `FREE`, không hiện trong danh sách với khách/học viên; có sẵn **một mã mời đang hiệu lực** | Đăng nhập học viên rồi mở trang tham gia bằng mã `demo-invite-lop-rieng-tu-2026` (hoặc gọi `GET /api/v1/classes/invites/demo-invite-lop-rieng-tu-2026`, `POST …/join`). Không có mã thì `GET /classes/{id}` của lớp này trả 404 |
| **Lớp Trả Phí** (`lop-tra-phi`) | `PUBLIC` + `PAID`: **199.000đ / 30 ngày** (sản phẩm `CLASS_ACCESS`) | Học viên mở lớp → thấy Giới thiệu/Cửa hàng → tạo đơn → chủ lớp xác nhận thanh toán (sandbox) → học viên thành thành viên đến hết 30 ngày |

> **Mã mời demo chỉ dùng cho demo.** `demo-invite-lop-rieng-tu-2026` là một chuỗi **cố định, ai đọc README cũng biết**. Nó chỉ được tạo bởi `DataSeedRunner` — thứ chỉ tồn tại khi bật lớp phủ demo (`DEMO_SEED_ENABLED=true` **và** profile `dev/test/docker/integration`); môi trường thật (không bật demo) **không bao giờ** có lớp này hay mã này. Mã mời thật do chủ lớp tạo trong Studio là 32 ký tự ngẫu nhiên (192 bit), chỉ hiện đúng một lần và chỉ lưu dưới dạng băm SHA-256. Giới hạn tần suất của hai endpoint mã mời: `AUTH_RL_INVITE_PER_IP_PER_MINUTE`, `AUTH_RL_INVITE_PER_IP_FAILURES_PER_MINUTE`; bộ quét thành viên hết hạn: `MEMBERSHIP_EXPIRY_ENABLED/INTERVAL_SECONDS/BATCH_SIZE` (xem `docs/RUNBOOK.md` mục 4.10).

#### Trải nghiệm lớp riêng tư, lớp trả phí và mã mời trên giao diện (D-19)

Tất cả các bước dưới đây chạy trên stack có **lớp phủ demo**; tài khoản là các tài khoản demo ở trên (nút "Đăng nhập nhanh" ở trang đăng nhập). Thiết kế: [`docs/DECISIONS.md` D-19 và "UI decisions (D-19 addendum)"](docs/DECISIONS.md), API: [`docs/API.md` mục 2.1](docs/API.md).

| Muốn thử | Làm thế nào |
|---|---|
| **Lớp riêng tư bị ẩn** | Chưa đăng nhập, mở `/classes`: chỉ thấy lớp công khai (lớp riêng tư **không** có trong danh sách). Mở thẳng `/classes/lop-rieng-tu-ma-moi` → trang "Không tìm thấy lớp học" (máy chủ trả 404, không để lộ rằng lớp có tồn tại). Đăng nhập `owner@classroom.local` thì thấy lớp kèm nhãn **Riêng tư**. |
| **Vào lớp riêng tư bằng mã mời demo** | Mở `/join/demo-invite-lop-rieng-tu-2026` (mã chỉ dùng cho demo, xem ghi chú bên dưới): thấy thẻ lớp, bấm "Đăng nhập để tham gia", đăng nhập `student.free@classroom.local` (trở lại đúng trang mời), bấm **Tham gia lớp** → vào bảng tin của lớp. Từ đó mở lớp bằng địa chỉ thường không cần mã. Mã sai/hỏng/đã thu hồi đều ra cùng một trang "Mã mời không hợp lệ hoặc đã hết hạn". |
| **Mua lớp trả phí** | Mở `/classes/lop-tra-phi` (khách cũng xem được): tường phí **199.000đ / 30 ngày**. Đăng nhập học viên → **Mua để tham gia** → hộp thoại thanh toán (chờ xác nhận). Đăng nhập `owner@classroom.local` → Studio → *Sản phẩm & Đơn hàng* → **Xác nhận thanh toán sandbox**. Học viên bấm "Làm mới trạng thái đơn hàng" → thành viên đến hết 30 ngày (còn ≤ 7 ngày: chip "Sắp hết hạn · Gia hạn"). |
| **Hết hạn và gia hạn** | Không cần chờ 30 ngày: chủ lớp bấm **Hoàn tiền sandbox** ở đơn đó → học viên thành *hết hạn*: banner "Gói thành viên lớp đã hết hạn ngày dd/MM/yyyy" + **Gia hạn**, chỉ còn tab Giới thiệu / Cửa hàng, các tab thành viên hiện lời nhắc gia hạn. Studio → *Thành viên* → bộ lọc **Đã hết hạn** liệt kê người đó. |
| **Chủ lớp tự thiết lập** | Tạo lớp mới → chọn **Công khai / Riêng tư**. Studio → *Cài đặt lớp* → "Hiển thị & tham gia" (đổi công khai/riêng tư, có xác nhận nêu hệ quả) và "Hình thức vào lớp" (miễn phí / trả phí, giá VND, số ngày hoặc trọn đời). Studio → *Thành viên* → "Mời thành viên": tạo liên kết (hạn dùng, số lượt), **liên kết chỉ hiện một lần** (nút Sao chép), thu hồi khi xong. Lớp riêng tư + trả phí: người được mời vẫn phải mua gói (mã mời đi kèm đơn hàng). |

Bộ E2E `e2e/e2e5.js` (`npm run round22`) chạy đúng các kịch bản này trên trình duyệt thật; trên stack không có lớp phủ demo nó tự bỏ qua.

*Mẹo: Khi chạy với lớp phủ demo, giao diện web có nút chuyển nhanh vai trò 1-click tại góc trên bên phải để dễ dàng kiểm thử các trường hợp phân quyền. Ở bản mặc định, nút này bị loại bỏ khỏi bundle.*

---

## 5. Các Lệnh Điều hành Chính (Commands)

```bash
# Khởi động dịch vụ nền:
docker compose -f infra/compose.yaml up -d --build

# Dừng hệ thống:
docker compose -f infra/compose.yaml down

# Xem trạng thái sức khỏe các container:
docker compose -f infra/compose.yaml ps

# Xem nhật ký hoạt động (Logs):
docker compose -f infra/compose.yaml logs -f backend

# Chạy toàn bộ Unit & Domain Policy Test (Backend) — service backend-test nằm trong profile "test":
docker compose -f infra/compose.yaml --profile test run --rm --build backend-test

# Chạy TypeScript Strict Typecheck & Build Test (Frontend) — service frontend-test cũng nằm trong profile "test":
docker compose -f infra/compose.yaml --profile test run --rm --build frontend-test

# Chạy Integration Test (Backend, dùng MySQL/MongoDB/Neo4j/MinIO thật trong một project Compose
# riêng, đọc biến môi trường từ infra/.env):
docker compose --env-file infra/.env -f infra/compose.integration.yaml run --rm --build backend-integration-test
# Dọn dẹp project integration dùng-rồi-bỏ này sau khi chạy xong:
docker compose --env-file infra/.env -f infra/compose.integration.yaml down -v

# Tái tạo môi trường sạch từ zero-state (Clean Reset):
docker compose -f infra/compose.yaml down -v
docker compose -f infra/compose.yaml up -d --build
```

> **Kiểm thử E2E trên trình duyệt thật:** thư mục [`e2e/`](e2e/README.md) chứa bộ Playwright (`cd e2e && npm install && npm run all`) chạy các hành trình đăng ký → tạo lớp → soạn nội dung → học/làm bài → chấm bài, cùng kiểm tra CSP, race khởi tạo phiên và giao diện điện thoại. Cần stack đang chạy ở `http://localhost:3000` (đổi bằng `BASE_URL`) và Chrome cài sẵn. Kịch bản lớp riêng tư / trả phí / mã mời (`e2e5.js`, D-19) chỉ chạy khi stack có lớp phủ demo (nếu không, tự bỏ qua). Bộ này **tạo dữ liệu `e2e.*` thật** trên hệ thống đích — chỉ chạy trên môi trường cục bộ/dùng-rồi-bỏ, **không bao giờ** trỏ vào production.

> **Kiểm thử tải / lỗi (loadtest):** thư mục [`loadtest/`](loadtest/README.md) chứa các kịch bản tải đã dùng để tìm và chứng minh sửa các lỗi R20 (nộp bài đồng thời làm nghẽn pool kết nối, N+1 ở bảng tin và danh sách thành viên, khóa dòng kỳ thi khi bắt đầu làm bài đồng thời). `npm run smoke` (trong `loadtest/`, hoặc `loadtest/run-in-drill.ps1 smoke.js`) chạy bản rút gọn 40 lượt nộp đồng thời trong ~1 phút để dùng trong CI. **Chỉ chạy trên stack drill/dev** (`-p classroom-drill`, cổng 13000/18080) — các script từ chối đích không phải máy cục bộ và cổng của stack thật; xem [`docs/RUNBOOK.md` mục 2.4](docs/RUNBOOK.md). Kịch bản gián đoạn MongoDB/Neo4j và độ trễ outbox (R20-04/R20-05): `loadtest/scenarios/outbox-outage.sh`, `outbox-lag.sh`, `loadtest/sql/outbox-backlog.sh` — xem [`docs/RUNBOOK.md` mục 4.9](docs/RUNBOOK.md).

> **Sao lưu / khôi phục dữ liệu:** dùng `infra/scripts/backup.*` và `infra/scripts/restore.*`; quy trình đầy đủ, kể cả diễn tập khôi phục trên stack tạm, nằm ở [`docs/RUNBOOK.md` mục 5](docs/RUNBOOK.md).

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
│   │   ├── db/migration/     # Flyway migrations V1..V29 (V1__initial_schema.sql ... V29__products_target_course_foreign_key.sql); chỉ thêm file mới, không sửa file đã áp dụng
│   │   └── application.properties
│   ├── src/test/java/        # Unit & Policy Tests (AccessPolicy, ExamAudience, Scoring, Idempotency, ...)
│   ├── Dockerfile            # Multi-stage Docker build (builder, tester, runner)
│   └── pom.xml
│
├── frontend/                 # Mã nguồn React + TypeScript strict (Vite + Tailwind)
│   ├── src/
│   │   ├── api/              # API Client fetch wrapper
│   │   ├── components/       # Navbar, ClassroomHeader, UIStates (font Plus Jakarta Sans được tự host qua @fontsource-variable — không gọi Google Fonts, CSP giữ nguyên chặt)
│   │   ├── context/          # AuthContext (JWT session & Quick Demo Switcher)
│   │   ├── pages/
│   │   │   ├── classroom/    # 8 Tabs: Feed, Learn, Exams, Leaderboard, Documents, Members, About, Store
│   │   │   └── studio/       # Studio (14 trang): Overview, Courses, Exams, Grading, Leaderboard, Store, Segments, Staff, Members, Settings, Community (Feed, Documents, About), Audit
│   │   └── types/            # TypeScript interfaces
│   ├── nginx.conf.template   # Nginx config (envsubst tại lúc container khởi động — xem R9-03 ở mục 3)
│   ├── Dockerfile            # Multi-stage Docker build (builder, tester, runner)
│   └── package.json
│
├── infra/
│   ├── compose.yaml              # Cụm dịch vụ chính: mysql, mongodb, neo4j, minio, minio-init, backend, frontend (+ profile "test": backend-test, frontend-test)
│   ├── compose.demo.yaml         # Lớp phủ bật tài khoản demo/thanh toán mô phỏng (mục 4) — chỉ dùng cục bộ
│   ├── compose.integration.yaml  # Project Compose riêng cho Integration Test (mục 5)
│   ├── compose.drill.yaml        # Lớp phủ diễn tập khôi phục: chạy bản sao tạm thời của stack cạnh stack thật (tên container/cổng riêng) — chỉ dùng với `-p` khác project thật (RUNBOOK mục 5.3)
│   ├── scripts/                  # Sao lưu & khôi phục dữ liệu (RUNBOOK mục 5)
│   │   ├── backup.ps1 / backup.sh     # Sao lưu MySQL, MongoDB, MinIO (tùy chọn Neo4j) ra infra/backups/
│   │   ├── restore.ps1 / restore.sh   # Kiểm tra checksum rồi khôi phục từ một bản sao lưu
│   │   ├── common.ps1 / common.sh     # Hàm dùng chung (tìm container theo Compose project, checksum, định dạng backup.json)
│   │   └── container/dbtool.sh        # Chạy BÊN TRONG container mysql/mongodb để mật khẩu không lộ trên dòng lệnh
│   ├── backups/                  # Bản sao lưu do script tạo ra — git-ignored (chứa dữ liệu cá nhân), không commit/không chia sẻ
│   └── .env                      # Không nằm trong git (đã có trong .gitignore) — tạo từ .env.example (Bước 1, mục 2). Nếu .env từng bị commit/push, hãy xoay (rotate) toàn bộ secret và xóa khỏi lịch sử git.
│
├── loadtest/                 # Kịch bản tải/lỗi (nộp bài đồng thời, bảng tin, tệp lớn) + smoke cho CI (README.md riêng) — chỉ dùng với stack drill/dev
├── e2e/                      # Bộ kiểm thử E2E Playwright chạy trên trình duyệt thật (README.md riêng) — chỉ dùng với stack cục bộ
│   ├── e2e.js, race.js, e2e2.js, netwatch.js, mobnav.js, e2e3.js, e2e4.js, e2e5.js, origin.js, a11y.js, all.js, lib.js
│   └── fixtures/             # tiny.mp4, tiny.pdf để upload
│
├── docs/                     # Bộ tài liệu kiến trúc & vận hành
│   ├── DECISIONS.md          # Quyết định kỹ thuật & giả định thiết kế
│   ├── IMPLEMENTATION_STATUS.md # Bảng theo dõi tiến độ chi tiết
│   ├── API.md                # Đặc tả hợp đồng REST API
│   ├── RUNBOOK.md            # Hướng dẫn vận hành & ứng phó sự cố; mục 5 = sao lưu & diễn tập khôi phục
│   └── DOCKER.md             # Kiến trúc Docker containerization
│
├── .env.example              # Mẫu biến môi trường phát triển
└── README.md
```

## Server trên máy Windows này (Round 23)

Web chính chạy HTTPS, hỗ trợ hai backend và quyền dữ liệu cá nhân. Xem [hướng dẫn vận hành](docs/SERVER_ON_THIS_PC.md), [chính sách dữ liệu](docs/DATA_POLICY.md) và [nghiệm thu](WALKTHROUGHS_HISTORY.md).
