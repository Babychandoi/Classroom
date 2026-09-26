# Quyết định Kỹ thuật và Giả định Thiết kế (Technical Decisions & Assumptions)

Tài liệu này ghi nhận các quyết định kiến trúc, công nghệ và các giả định cho các mục `[CẦN CHỐT]` trong tài liệu thiết kế gốc.

---

## 1. Công nghệ & Phiên bản

| Thành phần | Công nghệ đã chọn | Phiên bản | Lý do quyết định |
|---|---|---|---|
| **Backend Runtime** | Eclipse Temurin OpenJDK | 21-jre / 21-jdk | JDK 21 LTS hiện đại, hỗ trợ Virtual Threads, Pattern Matching |
| **Backend Framework**| Spring Boot | 3.4.3 | Phiên bản Spring Boot GA ổn định nhất hỗ trợ JDK 21 và Spring Security 6.x |
| **Build Tool** | Apache Maven | 3.9.x (trong Docker builder) | Tiêu chuẩn dự án Java, hỗ trợ multi-stage caching tốt |
| **Relational DB** | MySQL Server | 8.4 LTS | Nguồn sự thật (Source of Truth) cho giao dịch, đơn hàng, điểm thi, quyền hạn |
| **Document DB** | MongoDB Server | 7.0 | Lưu trữ event log học tập (`learning_events`) dạng activity projection |
| **Graph DB** | Neo4j Community | 5.20 | Lưu trữ đồ thị xã hội học tập, quan hệ theo dõi, chủ đề yêu thích |
| **Object Storage** | MinIO | RELEASE.2024-05-10T01-41-38Z | Tương thích AWS S3 API, hỗ trợ presigned URL ngắn hạn cho tài liệu/video |
| **Database Migration**| Flyway Core | 10.x | Quản lý schema versioned deterministic, tương thích Spring Boot |
| **Frontend Framework**| React + Vite | React 18.3, Vite 5.4 | Hiệu năng cao, build nhanh, hệ sinh thái phong phú |
| **Frontend Language** | TypeScript | 5.5+ (Strict mode) | Type-safe toàn diện, giảm thiểu lỗi runtime |
| **Frontend Styling** | Tailwind CSS | 3.4 | Tiện lợi, nhất quán, hỗ trợ responsive & design system |
| **Icons** | Lucide React | 0.441+ | Đầy đủ icon chuẩn UI, dung lượng nhẹ |
| **Web Server (Prod)**| Nginx Alpine | 1.27-alpine | Phục vụ static SPA, hỗ trợ route fallback về `index.html` và reverse proxy |

---

## 2. Giả định Nghiệp vụ (Resolving `[CẦN CHỐT]`)

### D-01: Quy tắc Hạng PRO khi Entitlement Hết hạn
- **Quyết định:** Một học viên được xếp hạng `PRO` trong lớp $C$ tại thời điểm $T$ nếu và chỉ nếu tồn tại ít nhất một `Entitlement` có trạng thái `ACTIVE` thuộc lớp $C$ thỏa mãn:
  $$starts\_at \le T < expires\_at$$
- Khi entitlement hết hạn hoặc bị hoàn tiền (`REVOKED`), trạng thái PRO lập tức trở về `FREE` ở lần kiểm tra tiếp theo.
- Lịch sử học tập, câu hỏi, điểm thi và thứ hạng cũ vẫn được bảo toàn trọn vẹn trong cơ sở dữ liệu.

### D-02: Tích hợp Thanh toán (Payment Integration)
- **Quyết định:** Do chưa chọn nhà cung cấp thanh toán thực tế (VNPAY, MoMo, Stripe, v.v.), hệ thống thiết kế interface mở rộng `PaymentProvider`.
- Triển khai mặc định: `MockPaymentProvider` (Sandbox).
- Hỗ trợ đầy đủ luồng:
  - Tạo phiên thanh toán (Checkout URL / Transaction Ref)
  - Mô phỏng Webhook thành công (`PAID`), thất bại (`FAILED`), hoàn tiền (`REFUNDED`)
  - Xác thực chữ ký webhook giả lập (`X-Signature: sha256(...)`)
  - Xử lý Idempotency: Webhook lặp nhiều lần với cùng `provider_ref` chỉ cập nhật đơn và cấp entitlement đúng 1 lần duy nhất trong database transaction.
- **Ràng buộc bảo mật (2026-09-25):** Sandbox giả lập bị tắt mặc định (`PAYMENT_SANDBOX_ENABLED=false`,
  `MOCK_PAYMENT_CHECKOUT_ENABLED=false`) kể cả trong Docker Compose, và chỉ bật thủ công cho môi trường demo cục bộ.
  Khi bật, endpoint `POST /api/v1/payments/mock/simulate` chỉ dành cho OWNER lớp hoặc nhân sự có quyền `STORE:EDIT`.
  Người mua **không bao giờ** được tự chuyển đơn của chính mình sang `PAID`; đơn hàng chờ quản trị lớp xác nhận.

### D-03: Mua nhiều lần & Gia hạn (Renewal & Stacking)
- **Quyết định:** Nếu người dùng mua tiếp một sản phẩm khi quyền cũ vẫn còn hiệu lực (`now < expires_at`), hệ thống áp dụng cơ chế cộng nối (extend):
  $$new\_expires\_at = old\_expires\_at + duration\_days$$
  Nếu quyền cũ đã hết hạn, quyền mới bắt đầu từ thời điểm hiện tại:
  $$new\_expires\_at = now + duration\_days$$

### D-04: Xếp hạng khi Làm bài Thi Nhiều lần (Exam Attempts Policy)
- **Quyết định:** Một kỳ thi cho phép cấu hình số lần làm bài (`attempt_limit`).
- Xếp hạng chỉ xét các bài thi đã được công bố (`PUBLISHED`).
- Lấy điểm số cao nhất (`MAX(score)`) trong các lần thi hợp lệ đã công bố để tính điểm quy đổi và tổng điểm tích lũy vào Leaderboard. Các bài thi preview của Staff/Owner hoặc bài bị hủy (`CANCELLED`) không được đưa vào bảng xếp hạng.

### D-05: Quyền Riêng tư Profile (Profile Privacy)
- **Quyết định (Privacy-First Default):**
  - Mặc định học viên khác chỉ xem được Tên hiển thị, Avatar, Huy hiệu/Bậc thành tích đạt được.
  - Chi tiết lịch sử hoàn thành từng bài học, nhật ký thi và điểm số chi tiết chỉ hiển thị cho chính học viên đó, và hiển thị cho OWNER/STAFF có quyền quản trị lớp.

### D-06: Segment Engine (Phân khúc An toàn)
- **Quyết định:** Tuyệt đối cấm người dùng nhập biểu thức SQL tùy ý.
- Sử dụng mô hình quy tắc có cấu trúc (Rule Engine) với whitelist:
  - **Operators:** `EQUALS`, `NOT_EQUALS`, `GREATER_THAN`, `GREATER_THAN_OR_EQUAL`, `LESS_THAN`, `LESS_THAN_OR_EQUAL`, `IN`, `CONTAINS`
  - **Criteria:** `IS_PRO`, `COURSE_OWNED`, `COMPLETED_LESSONS_COUNT`, `AVG_EXAM_SCORE`, `DAYS_SINCE_JOINED`
  - **Logical Combinators:** `AND`, `OR`

### D-07: Media Storage & Presigned URLs
- **Quyết định:** Database không bao giờ lưu URL dài hạn của MinIO/S3.
- Bảng `media_assets` chỉ lưu `object_key`, `bucket_name`, `mime_type`, `file_size_bytes`.
- Client yêu cầu tải/xem media qua endpoint `/api/v1/media/{id}/download-url`. Backend kiểm tra quyền truy cập (nội dung FREE, PRO, hoặc đã mua khóa học) rồi mới sinh presigned URL ngắn hạn (thời hạn 15 phút).

### D-08: Transaction & Outbox Eventual Consistency
- Mọi thao tác thay đổi trạng thái kinh doanh chính (Tạo đơn -> Thanh toán -> Cấp Entitlement) được thực thi trong một MySQL Transaction duy nhất kèm theo việc ghi bản ghi vào bảng `outbox_events`.
- Một Background Scheduled Worker định kỳ đọc `outbox_events` và đồng bộ chiếu (projection) sang MongoDB (`learning_events`) và Neo4j (quan hệ lớp/thành viên/chủ đề).
- Nếu MongoDB hoặc Neo4j tạm gián đoạn, dịch vụ cốt lõi và kiểm soát quyền hạn trên MySQL vẫn hoạt động chính xác 100%.

### D-09: Căn chỉnh Yêu cầu Phiên bản Spring Boot (Spring Boot Version Alignment & Change Approval)
- **Bối cảnh:** Tài liệu đặc tả ban đầu (BRD/HLD/SRS v0.1) ghi Spring Boot 4.x / JDK 21. Tuy nhiên, trên hệ sinh thái Maven Central hiện tại, phiên bản Spring Boot GA mới nhất là nhánh 3.4.x (cụ thể 3.4.3). Spring Boot 4.x chưa được phát hành chính thức.
- **Quyết định (Approved Requirement Change):** Chính thức phê duyệt yêu cầu thay đổi (Change Request) căn chỉnh phiên bản backend: chuẩn hóa sử dụng Spring Boot 3.4.3 trên nền tảng Eclipse Temurin OpenJDK 21 LTS (hỗ trợ đầy đủ Virtual Threads, Pattern Matching, Spring Security 6.x và Spring Data JPA). Khi Spring Boot 4.x được VMware/Pivotal phát hành chính thức trong tương lai, dự án sẽ lập kế hoạch nâng cấp tương ứng.

---

## 3. Quy trình Review Độc lập & Cấu hình Fallback Agent

- **Bối cảnh:** Môi trường OpenCode CLI runtime chỉ hỗ trợ 2 loại subagent tích hợp: `general` và `explore`. Các tên agent chuyên biệt (`omo-native-code-reviewer`, `omo-native-qa-executor`, `omo-native-gate-reviewer`) không được hệ thống hỗ trợ đăng ký trực tiếp.
- **Quyết định (Fallback Clause per Master Prompt):**
  - Không bao giờ bỏ qua các bước Code Review, QA Execution, và Final Gate.
  - Sử dụng subagent độc lập (hoặc session độc lập với persona phân vai nghiêm ngặt) để thực hiện:
    1. **Independent Code Reviewer (Codex Role):** Thực hiện kiểm tra độc lập mã nguồn, migration, permission, logic bảo mật và evidence kiểm thử theo phong cách adversarial review của OpenAI Codex.
    2. **QA Executor (Gemini Role):** Thực hiện kiểm thử trực tiếp trên các container Docker đang chạy (live HTTP API calls, full user journey verification).
    3. **Final Gate Reviewer (Codex Role):** Kiểm tra đối chiếu toàn bộ tiêu chí chấp nhận chất lượng (Quality Gate) và đưa ra verdict cuối cùng: `APPROVE` hoặc `REJECT`.
  - Mọi bằng chứng, kết quả kiểm tra và mã lỗi được ghi nhận minh bạch vào `docs/REVIEW_FINDINGS.md` và `docs/QUALITY_GATE.md`.

