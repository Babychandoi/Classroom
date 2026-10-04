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

### D-10 (R13-10, LOW, FR-13): Descoping "About" multi-section/tab + image editor
- **Bối cảnh:** FR-13 says the OWNER configures "văn bản, hình, tab và nội quy" (text, images, tabs
  and rules) for the class About page, publishing a version after saving. The current
  implementation (`ClassAbout`/`ClassAboutController`/`AboutTab.tsx`) already covers the text half:
  a single `contentMarkdown` + `rulesMarkdown` pair with `publishedVersion` tracking, editable by
  OWNER/STAFF. What is missing is the **structured, multi-section/tab layout with per-section
  optional images** (e.g. "Giới thiệu", "Giảng viên", "Lịch học" as separate tabs, each with its own
  title/content/image via the existing MEDIA upload flow with an ABOUT purpose).
- **Quyết định:** Descoped from this round (R13). This is a genuinely new, moderate-to-large
  surface, not a small addition to the existing single-document About page:
  - New domain model: an ordered collection of About sections (`class_about_sections`: id, classId,
    title, contentMarkdown, mediaAssetId, position), replacing/extending the current single-row
    `class_about` shape used by every existing About read/edit path.
  - A new Flyway migration, repository, service methods (CRUD + reorder + publish-version bump per
    section) and controller routes, mirroring the Course/Section pattern already used for Learn.
  - Frontend: a tabbed section editor in `AboutTab.tsx`/a new `StudioAbout` section-editor UI, an
    image-upload UI wired to the existing MEDIA module with a new `ABOUT` purpose value (a new enum
    member to thread through `MediaService`/`MediaAsset`/upload validation), and Studio-side
    reordering.
  - This is a full vertical slice (migration + domain + API + two frontend surfaces) comparable in
    size to Learn's Section/Lesson model, not something that fits safely alongside the other six
    R13 items in one pass without risking a rushed migration or an under-tested media-purpose change
    (MediaService's purpose enum is referenced by access-policy checks elsewhere — see
    `MediaService.generateAuthorizedDownloadUrl`).
  - **Kept as-is for this round:** the existing single-document About (`contentMarkdown` +
    `rulesMarkdown` + `publishedVersion`) continues to serve FR-13's text/rules/publish-version
    requirement; multi-section/tab layout and per-section images are tracked as follow-up work.
  - **Chủ thể chốt:** Product owner to confirm the desired section/tab taxonomy (fixed set vs.
    OWNER-defined arbitrary tabs) before this is implemented, since that materially changes the
    schema (a fixed enum of tab types vs. a free-form ordered list).

### D-11 (R14-13, LOW): Ngữ nghĩa lớp học `ARCHIVED`
- **Bối cảnh:** `ClassroomService.updateClassroomStatus` (chỉ OWNER) cho phép chuyển `ACTIVE <-> ARCHIVED`, nhưng
  trước đây chỉ có tác dụng ẩn lớp khỏi người ngoài và chặn `joinClassroom`; đơn hàng và lượt thi mới vẫn tạo được trong
  lớp đã lưu trữ - một lớp "đóng băng" vẫn phát sinh doanh thu và điểm xếp hạng mới.
- **Quyết định (chọn phương án an toàn hơn):** lớp `ARCHIVED` bị **đóng băng đối với hoạt động MỚI**, nhưng **vẫn cho đọc**:
  - **Vẫn giữ nguyên:** tư cách thành viên, phân quyền nhân sự, entitlement đã mua, toàn bộ nội dung học/tài liệu/bảng tin (chỉ đọc
    theo quyền hiện có), lịch sử điểm và bảng xếp hạng. Lớp chỉ hiển thị cho OWNER, nhân sự ACTIVE và thành viên hiện có
    (`AccessPolicy.isClassVisibleToUser`); người ngoài không thấy và không thể tham gia (`joinClassroom` chỉ nhận lớp `ACTIVE`).
  - **Chặn tạo đơn hàng MỚI** (`CommerceService.createOrderTransactional`, HTTP 400, "Lớp học đã được lưu trữ; không thể tạo đơn hàng
    mới"). Một request replay theo `Idempotency-Key` của đơn đã tồn tại vẫn trả đơn cũ; đơn `PENDING` đã tạo vẫn được xử lý
    webhook/xác nhận bình thường để không mất tiền người mua.
  - **Chặn lượt làm bài MỚI** (`ExamAudiencePolicy.enforceEnterExam`, `EXAM_NOT_OPEN`, "Lớp học đã được lưu trữ; không thể bắt đầu lượt
    làm bài mới") và `canEnterExam` chỉ trả `true` khi học viên đang có một lượt `IN_PROGRESS` còn hạn để **tiếp tục**. Lượt làm đang chạy
    vẫn được tiếp tục, tự lưu và nộp đến hạn của chính nó. Xem trước (preview) của OWNER/nhân sự không bị chặn.
  - Bỏ lưu trữ (`ARCHIVED -> ACTIVE`) khôi phục ngay việc tạo đơn/làm bài mới; không có dữ liệu nào bị thay đổi khi lưu trữ.
- Nguồn sự thật duy nhất của quy tắc: `AccessPolicy.isClassArchived(classId)`.

### D-12 (R14-01, R14-11, R14-13, LOW-HIGH): Vòng đời thành viên - gỡ, chặn, tham gia lại
- **Trạng thái thành viên:** `ACTIVE` -> `REMOVED` ("kick") hoặc `BLOCKED` (chặn) do Studio (`MEMBER:EDIT`); `unblock` đưa về `ACTIVE`.
- **Tham gia lại:** thành viên `REMOVED` **được phép tự tham gia lại** qua `POST /classes/{id}/join` (kích hoạt lại đúng dòng cũ) - vì đây
  là "mời ra khỏi lớp", không phải kỷ luật cứng. Thành viên `BLOCKED` **không thể** tự tham gia lại (HTTP 403); chỉ một thao tác
  `unblock` từ Studio mới khôi phục quyền. Lớp `ARCHIVED` không nhận tham gia (D-11), kể cả với thành viên `REMOVED`.
- **Gỡ/chặn nhân sự là quyền của OWNER:** `MemberService.removeMember/blockMember` yêu cầu `MEMBER:EDIT`, và nếu mục tiêu là **STAFF**
  (vai trò `STAFF` hoặc còn `staff_assignments`) thì **bắt buộc là OWNER** (`enforceOwner`, HTTP 403) - giống `StaffService.removeStaff`.
  Trước đây người có `MEMBER:EDIT` gỡ được nhân sự khác (kéo theo xóa assignment + permissions) và vượt qua quy trình chỉ-OWNER.
  Không ai được tự gỡ/tự chặn chính mình qua endpoint Studio này (HTTP 400); OWNER lớp không bao giờ bị gỡ/chặn.
- **Bảng xếp hạng & hành trình:** thành viên không `ACTIVE` (`REMOVED`/`BLOCKED`) **không xuất hiện** trên bảng xếp hạng toàn lớp và theo
  kỳ thi (thứ hạng tính lại sau khi lọc), và không có "hành trình học tập" (`UserService.getJourney` yêu cầu `ACTIVE`). Điểm/lượt thi
  cũ **không bị xóa**: nếu được `unblock` hoặc tham gia lại thì hiển thị lại.
- Lượt thi `IN_PROGRESS` của thành viên bị gỡ/chặn chuyển `CANCELLED` (xem D-13: không tính vào giới hạn lượt thi).

### D-13 (R14-05, R14-14, R14-15): Ngữ nghĩa đóng kỳ thi, đếm lượt thi và xem thử
- **Đóng kỳ thi (`closeExam`: `PUBLISHED/OPEN -> CLOSED`)** chỉ chặn lượt làm **MỚI**. Lượt `IN_PROGRESS` **bắt đầu trước `closedAt`** vẫn được
  **tiếp tục (resume), tự lưu và nộp** đến hạn `endsAt` của chính nó - đúng cam kết "left alone" của `closeExam`. Trước đây resume trả
  422 `EXAM_NOT_OPEN` trong khi autosave/submit vẫn chạy, khiến học viên kẹt giữa chừng.
  - Thực thi: `ExamAudiencePolicy.enforceResumeAttempt` chấp nhận kỳ thi `CLOSED` (và `ARCHIVED` sau khi đã đóng) khi lượt thi là `IN_PROGRESS`,
    không phải preview và `startedAt <= exam.closedAt` (`canResumeAfterClose`); `canEnterExam` trả `true` cho học viên đang có lượt như vậy để nút
    "Tiếp tục làm bài" hiện ra. `submitAttempt`/`saveAnswers` không kiểm tra trạng thái kỳ thi; bộ quét timeout (`finalizeExpiredAttempts`) chỉ chốt
    lượt khi hết `endsAt` hoặc qua `scheduleEnd`, không phụ thuộc trạng thái `CLOSED`.
  - Lượt **mới** vẫn bị chặn với `CLOSED`/`ARCHIVED` (`enforceEnterExam` -> `EXAM_NOT_OPEN`). Lượt bắt đầu sau `closedAt`, hoặc kỳ thi `CLOSED` không
    có `closedAt`, không được resume (fail closed).
- **Giới hạn lượt (`attemptLimit`)** chỉ đếm lượt học viên thật **không `CANCELLED`** (loại preview): `ExamAttemptRepository.countAttemptsTowardLimit`.
  Lượt bị nhân sự hủy hoặc bị hủy khi thành viên bị gỡ/chặn không tiêu hao lượt làm bài. Vì `attempt_number` là duy nhất theo (kỳ thi, học viên), số
  thứ tự lượt mới = `max(số lượt đã đếm, attempt_number lớn nhất từng dùng) + 1` (`findMaxAttemptNumber`), tránh trùng khóa với lượt đã hủy.
- **"Chạy thử" (preview) tạo đúng một lượt:** nút Studio chỉ điều hướng tới `/attempt?preview=1`; trang làm bài gọi một lần
  `POST /exams/{id}/attempts?preview=true`. Backend trả lại lượt preview `IN_PROGRESS` còn hạn của cùng user+kỳ thi thay vì tạo lượt mới,
  **trừ khi** đề đã đổi so với bản chụp (tác giả sửa câu hỏi rồi chạy thử lại): khi đó lượt cũ được đặt `CANCELLED` và tạo lượt mới để thấy đúng nội dung.
  Lượt preview hết hạn được chốt như lượt thường trước khi tạo lượt mới.

### D-14 (R14-02, R14-03, R14-04, R14-12): Chương trình học hiển thị cho học viên
- **Ẩn nội dung đã lưu trữ:** một bài học bị ẩn với học viên khi chính nó `archived` **hoặc** chương chứa nó `archived`
  (`LearningPolicy.isLessonHiddenFromLearner`, một định nghĩa duy nhất). Áp dụng trên **mọi** đường dành cho học viên: chi tiết bài học, đánh dấu
  tiến độ, đọc/hỏi/trả lời Q&A, nộp và xem bài nộp bài tập, tải media của bài học, và danh sách chương trình. Người có `COURSE:EDIT`
  (kể cả OWNER) vẫn mở được mục đã lưu trữ để khôi phục. Học viên nhận 404 (không lộ sự tồn tại); quy tắc cấp khóa học (DRAFT/ARCHIVED, entitlement)
  vẫn do `canLearn/enforceLearn` xử lý trước.
- **Tiến độ khóa học:** mẫu số và tử số chỉ tính **bài học hiển thị** (bài không lưu trữ trong chương không lưu trữ), bằng các truy vấn gom nhóm
  một lần cho cả lớp (không N+1) - nên luôn đạt được 100% và không bị thổi phồng bởi bài đã lưu trữ. `journey` và tiêu chí segment
  `COMPLETED_LESSONS_COUNT` dùng cùng cách đếm.
- **Xóa an toàn:** `assignment_submissions.lesson_id` là `ON DELETE CASCADE` nên xóa bài học/chương/khóa học có bài nộp sẽ hủy bài đã chấm. Vì vậy xóa cứng
  chỉ được phép khi không có tiến độ, hỏi đáp **và bài nộp**; ngược lại phải lưu trữ (HTTP 409).
- **Quyền truy cập khóa học theo thời điểm:** `accessReason` thêm `OWNED_UPCOMING` (đã mua nhưng entitlement `SCHEDULED` chưa bắt đầu) kèm `accessStartsAt`
  để UI hiện "Bắt đầu từ dd/MM/yyyy" và không mời mua lại; ngày hết hạn hiển thị bỏ qua entitlement `REVOKED` (hoàn tiền).

### D-15 (R16-01, R16-02, R16-03, R16-06, R16-07, R16-08): Vòng đời publish, khóa ngoại sản phẩm-khóa học, lịch thi, danh tính quản trị, phân trang lớp
- **Trạng thái thành viên trong DTO lớp (R16-01):** `ClassroomDto.memberState` = `ACTIVE | REMOVED | BLOCKED | NONE`. `isMember=true` và `userRole=STUDENT/STAFF`
  **chỉ** khi dòng `class_members` là `ACTIVE` (hoặc là OWNER); dòng `REMOVED`/`BLOCKED` (và `BANNED` cũ, coi như `BLOCKED`) cho `isMember=false`, `userRole=GUEST`.
  UI: `REMOVED` thấy banner "Tham gia lại" (gọi `POST /classes/{id}/join`, đúng D-12); `BLOCKED` chỉ thấy thông báo "Bạn đã bị chặn khỏi lớp học này", không có nút tham gia và không có tab/nội dung lớp.
- **Publish chỉ từ DRAFT (R16-02):** `publishCourse`/`publishProduct` chỉ chuyển `DRAFT -> PUBLISHED` (HTTP 400 nếu `ARCHIVED`: phải `restore` trước, vốn cần `*:EDIT`).
  Gọi lại trên đối tượng đã `PUBLISHED` là **no-op idempotent** (trả trạng thái hiện tại, không lỗi, không ghi audit lần hai — cùng cách `archive*` xử lý đối tượng đã `ARCHIVED`).
  Mỗi lần chuyển trạng thái ghi audit: `COURSE_PUBLISH`, `PRODUCT_PUBLISH` (cùng quy ước `<ĐỐI_TƯỢNG>_<HÀNH_ĐỘNG>` với `COURSE_ARCHIVE`, `EXAM_PUBLISH`...). `publishExam`/`closeExam`/`archiveExam` đã kiểm tra trạng thái và ghi audit từ trước.
- **Khóa ngoại `products.target_course_id` (R16-03):** V29 thêm `fk_prod_target_course ... ON DELETE RESTRICT` (NULL vẫn hợp lệ). Trước khi thêm, V29 chuẩn hóa dữ liệu cũ: `''` -> `NULL`; sản phẩm trỏ tới khóa học không còn tồn tại
  -> `ARCHIVED` + `target_course_id = NULL` (đơn/entitlement đã bán giữ nguyên `product_id`/`target_course_id_snapshot`). `deleteCourse` từ chối (HTTP 400) khi còn sản phẩm trỏ tới khóa học — hãy **lưu trữ** khóa học thay vì xóa.
- **Lịch thi là "thay thế toàn bộ" khi DRAFT (R16-06):** `PUT /exams/{id}` đặt `scheduleStart`/`scheduleEnd` đúng bằng giá trị gửi lên; `null` = chưa xếp lịch (trước đây `null` bị bỏ qua nên không xóa được lịch). Form Studio luôn gửi cả hai trường.
  Client khác gọi `PUT` chỉ với `title` sẽ **xóa lịch** — phải gửi lại lịch hiện có.
- **Danh tính với quản trị viên (R16-07):** ngoại lệ "OWNER/STAFF `MEMBER:VIEW` thấy hồ sơ PRIVATE" áp dụng cho **mọi dòng** `class_members` của lớp (kể cả `REMOVED`/`BLOCKED`) — `AccessPolicy.hasMembershipRecord` — để quản trị viên biết mình đã gỡ/chặn ai. Người chưa từng vào lớp vẫn ẩn; thành viên thường không đổi (không thấy dòng không `ACTIVE`).
- **`GET /classes` luôn phân trang (R16-08):** mặc định 50, tối đa 100, mới nhất trước (id làm khóa phụ); quyền nhìn thấy lớp áp dụng ngay trong truy vấn SQL nên trang không bị "thủng"; chủ lớp/số thành viên/vai trò/PRO của cả trang được nạp theo lô. Danh sách thành viên và bảng xếp hạng nạp user một lần (`findAllById`) và tính ngữ cảnh riêng tư của người xem một lần.

### D-16 (R18-02, R18-04, R18-07, R18-10): Proxy tin cậy, thứ tự khóa học, khóa ngoại quyền trợ giảng, khách và hồ sơ PUBLIC
- **Proxy tin cậy của bộ giới hạn xác thực (R18-02):** `app.security.trusted-proxies` (env `APP_SECURITY_TRUSTED_PROXIES`) mặc định chỉ là loopback
  (`127.0.0.1,0:0:0:0:0:0:0:1`); `infra/compose.yaml` thêm service `frontend` (nginx). Tên host được phân giải ở **tác vụ nền** thành ảnh chụp bất biến
  (thử lại mỗi 5 giây khi chưa phân giải được - bình thường ngay sau `compose up` vì frontend chỉ khởi động sau backend -, 30 giây khi đã ổn định); đường xử lý
  request chỉ đọc ảnh chụp, không DNS, không khóa. Tên không phân giải được chỉ ghi log **một lần** rồi bị bỏ qua; khi chưa tin cậy, `X-Real-IP` bị bỏ qua (an toàn:
  khóa theo địa chỉ socket).
- **Thứ tự khóa học (R18-04):** `createCourse` gán `position = max + 1` (client không gửi vị trí); danh sách sắp xếp theo `position, created_at, id` nên các khóa cũ cùng
  `position = 0` vẫn có thứ tự xác định. Studio có mũi tên Lên/Xuống chỉ với quyền `COURSE:EDIT` **toàn lớp** (endpoint nhận toàn bộ danh sách id của lớp). Giới hạn đã biết:
  trợ giảng có `COURSE:EDIT` toàn lớp nhưng không có `COURSE:PREVIEW` không thấy khóa cũ đã lưu trữ có phí, nên danh sách gửi lên thiếu id đó và server trả 400 - chủ lớp
  không bị ảnh hưởng.
- **Xóa khóa học có quyền trợ giảng gắn riêng (R18-07):** `staff_permissions.scope_course_id` có khóa ngoại RESTRICT (V19). `deleteCourse` kiểm tra trước và trả **409 CONFLICT** nêu
  tên trợ giảng (tối đa 3 người, còn lại đếm) cùng các quyền `MODULE:ACTION` và hướng xử lý (gỡ quyền ở "Studio > Trợ giảng" hoặc lưu trữ thay vì xóa). `GlobalExceptionHandler` map
  vi phạm khóa ngoại kiểu "bản ghi cha còn được tham chiếu" (MySQL 1451) sang 409 với thông báo chung "Thao tác bị chặn vì dữ liệu đang được tham chiếu…" thay vì 400 "dữ liệu không hợp lệ";
  lỗi phía con (1452) và NOT NULL vẫn là 400.
- **Khách ở tab dành cho thành viên và hồ sơ PUBLIC (R18-10):** SRS D-05/TC-12 không quy định hồ sơ công khai cho người ngoài lớp (mục "dữ liệu profile hiển thị công khai" vẫn `[CHƯA CHỐT]`),
  và `GET /classes/{id}/members/{userId}/profile`, danh sách thành viên, bảng xếp hạng, hành trình đều yêu cầu đăng nhập **và** là thành viên lớp. **Quyết định: giữ hành vi máy chủ, sửa lời
  mô tả** - `PUBLIC` nghĩa là *tên và ảnh đại diện* hiện với mọi người xem lớp, kể cả khách (ví dụ tác giả bài đăng công khai, xem `ProfileVisibilityPolicy`); trang hồ sơ, bảng xếp hạng và danh sách
  thành viên vẫn chỉ cho thành viên của lớp. UI: khách ở Góc học tập/Luyện thi/Bảng xếp hạng/Tài liệu/Thành viên/hồ sơ thành viên thấy lời mời "Đăng nhập để xem nội dung này"
  (quay lại đúng trang sau khi đăng nhập) thay vì gọi API và nhận 401; phiên hết hạn giữa chừng (người dùng trở thành khách) cũng hiện lời mời này.

### D-17 (R19-02, R19-04, R19-05, R19-07, R19-08, R19-10): Origin sau proxy, xem thử không lộ đáp án, đề rỗng, refresh một-lần, logout, mở khóa thành viên
- **Origin cùng nguồn sau nginx (R19-02):** trình duyệt gửi `Origin` cả với POST cùng nguồn; Spring coi yêu cầu là cùng nguồn khi `Origin` khớp `getScheme()/getServerName()/getServerPort()` (`CorsUtils.isCorsRequest`). nginx từng gửi `Host: $host` (mất cổng) và danh sách CORS bị cố định `localhost:3000` nên mọi origin khác (drill `13000`, `FRONTEND_PORT` tùy chỉnh, IP LAN, tên miền) nhận 403 "Invalid CORS request". Sửa ba lớp: (1) nginx chuyển tiếp `Host`/`X-Forwarded-Host` = host:port của trình duyệt, `X-Forwarded-Port`, `X-Forwarded-Proto`, và **xóa** `Forwarded`/`X-Forwarded-Prefix` do client gửi; (2) backend áp `X-Forwarded-Host/Proto/Port` qua `TrustedForwardedHeaderFilter`; (3) `APP_CORS_ALLOWED_ORIGINS` lấy từ biến môi trường, mặc định theo `FRONTEND_PORT` (drill: `DRILL_FRONTEND_PORT`). **Quyết định: không dùng `server.forward-headers-strategy=framework|native`.** Đã kiểm bytecode `ForwardedHeaderFilter` (Spring 6.2.3): chiến lược này (a) tin header từ **mọi** người gửi tới được cổng backend, và (b) ghi đè `getRemoteAddr()` bằng `X-Forwarded-For`/`Forwarded: for=`, làm vô hiệu phép kiểm tra "peer TCP có phải proxy tin cậy" mà bộ giới hạn tần suất dùng để chỉ tin `X-Real-IP` (R18-02) - kẻ gọi thẳng cổng backend sẽ tự chọn khóa giới hạn của mình. Bộ lọc riêng chỉ ghi đè **đúng ba giá trị** scheme/host/port, và chỉ khi peer nằm trong `app.security.trusted-proxies` (cùng tập với `X-Real-IP`); địa chỉ từ xa và mọi header giữ nguyên. Giá trị sai cú pháp bị bỏ qua chứ không áp một phần. Cổng backend chỉ bind loopback (`HOST_BIND_ADDRESS`) - ghi chú trong RUNBOOK 4.5/6.3. Sau TLS terminator (nginx chỉ thấy HTTP) origin công khai là `https://...` khác scheme nginx thấy, nên operator đặt `APP_CORS_ALLOWED_ORIGINS=https://ten-mien`. Origin lạ vẫn 403 trên mọi stack (đã kiểm chứng live ở stack chính và drill).
- **Xem thử không phải là oracle đáp án (R19-04):** lượt xem thử không có giới hạn, và điểm/`pointsAwarded` của một bài đã chấm **chính là** đáp án - nhân sự chỉ có `EXAM:PREVIEW` (không có `EXAM:EDIT` tường minh, là điều kiện của `AccessPolicy.canAccessAnswerKey`) có thể nộp lần lượt "toàn A", "toàn B"... rồi đọc đáp án từ kết quả. Quy tắc: với lượt xem thử, ai **không** xem được đáp án (chủ lớp hoặc `EXAM:EDIT` tường minh, cùng phạm vi khóa học) nhận bài "như đã nộp": không `score`, không `pointsAwarded`/`teacherFeedback`, `totalPoints=0`, `status=SUBMITTED` (không bao giờ `PUBLISHED`/`GRADING`), `resultHidden=true`, `notice="Chế độ xem thử: không hiển thị điểm/đáp án cho quyền của bạn"`; câu trả lời của chính họ vẫn giữ. Áp dụng cho `submit` (mọi nhánh: nộp, nộp lại/idempotent, hết giờ), `GET /attempts/{id}/result`, `GET /exams/{id}/my-attempts`, `grade` và `cancel`. Bài vẫn được chấm và lưu nội bộ (chủ lớp/`EDIT` giữ nguyên hành vi). **Không thêm hạn mức số lượt xem thử theo ngày:** khi kết quả đã bị che thì việc nộp lại không mang thông tin nào, còn hạn mức chỉ làm khó người kiểm thử hợp lệ. Quyền được kiểm **lại lúc dùng**: `saveAnswers`/`submit`/xem kết quả của lượt xem thử trả 403 nếu chủ lượt không còn là chủ lớp/`EXAM:PREVIEW`/`EXAM:EDIT` (nhân sự bị gỡ quyền khi lượt đang mở), `my-attempts` không liệt kê lượt xem thử của họ nữa, và lượt xem thử chỉ người chạy nó đọc được (`VIEW`/`GRADE` không đủ). UI: `ExamResultPage` hiện `notice` thay cho điểm.
- **Đề không có câu hỏi (R19-05):** `publishExam` vốn đã yêu cầu ≥ 1 câu hỏi; hạt giống demo lách qua bằng cách lưu thẳng đề `PUBLISHED` không có câu hỏi, nên bắt đầu làm bài tạo lượt có snapshot rỗng (mọi lời gọi sau đó 500, lượt kẹt `IN_PROGRESS`). `startAttempt` (học viên **và** xem thử) trả **422** "Kỳ thi chưa có câu hỏi nên chưa thể bắt đầu làm bài…" **trước khi** tạo bất kỳ thứ gì. Lượt `IN_PROGRESS` đã kẹt sẵn với snapshot rỗng bị hủy (`CANCELLED`, không tính vào giới hạn, số thứ tự lượt vẫn tiếp nối) rồi đi tiếp luồng bình thường. Hạt giống: đề COURSE có 1 câu trắc nghiệm hợp lệ, câu của đề PRO có đủ 4 lựa chọn; `DataSeedRunner.repairSeededExams` sửa CSDL đã seed bản cũ, idempotent và chỉ chạm đề seed nhận diện theo tên (không đụng đề do giảng viên tạo).
- **Refresh token: ân hạn chỉ cấp lại một lần (R19-07):** trước đây mọi lần dùng lại một token đã xoay vòng trong 60 s đều cấp thêm một phiên (kèm access JWT 24 giờ). Nay mỗi dòng token đã xoay vòng có `grace_minted_at` (V31), ghi trong **cùng giao dịch và cùng khóa hàng** với lần cấp lại, nên trong N lượt dùng lại đồng thời đúng **một** lượt thành công (các lượt còn lại 401). Lượt dùng lại thứ hai: trong `GRACE_REPLAY_RACE_SECONDS` (10 s) kể từ lần cấp lại là "thua cuộc đua giữa các tab" -> 401 **không** thu hồi family (token sống của người dùng thật được giữ); muộn hơn là coi như đánh cắp -> thu hồi cả family. **Đánh đổi:** không thể phân biệt tab hợp lệ với kẻ đánh cắp cùng phát lại một token trong cửa sổ ân hạn; một-lần-mỗi-dòng chặn thiệt hại ở đúng một phiên phụ (và phiên đó bị thu hồi ở lần làm mới hợp lệ kế tiếp sau ân hạn). Hệ quả chấp nhận: người dùng thật mở ≥ 3 tab cùng cookie cũ đúng lúc (trình duyệt không có Web Locks) có thể thấy tab thứ ba bị 401 - `navigator.locks` đã tuần tự hóa trường hợp này ở mọi trình duyệt hiện đại. Đồng thời access JWT mặc định **1 giờ** (`JWT_EXPIRATION_HOURS`, `application.properties`, compose): frontend làm mới bằng single-flight khi 401 cho mọi lời gọi qua `api/client.ts` (autosave đề thi cũng vậy); chỉ có PUT lên presigned MinIO là `fetch` trực tiếp và không dùng JWT (URL tự ký, phát video cũng vậy). Operator có `.env` cũ đặt 24 phải hạ xuống 1 (RUNBOOK 6.3).
- **Đăng xuất công khai (R19-08):** `/auth/logout` là `permitAll` (phải xóa cookie kể cả khi access token đã hết hạn) nhưng `revokeToken` từng ghi mọi chuỗi vào bảng thu hồi -> 20 token rác = 20 dòng do người gọi ẩn danh tạo. Nay chỉ token **chữ ký hợp lệ** mới được thu hồi (hết hạn/giả mạo/sai định dạng: không ghi gì vì đã vô dụng), và `/auth/logout` chịu giới hạn tần suất cùng ngân sách với refresh (60/phút/IP, bucket riêng). Khi kiểm chứng trực tiếp phát hiện thêm lỗi liền kề: access JWT không có `jti` nên hai token cấp cho cùng người dùng trong cùng một giây giống hệt nhau từng byte - đăng xuất (đưa token vào danh sách thu hồi) rồi đăng nhập lại ngay trong giây đó nhận về đúng token vừa bị thu hồi và bị 401. Nay mỗi access JWT mang `jti` ngẫu nhiên.
- **Mở khóa thành viên (R19-10):** chặn hoặc xóa nhân sự là owner-only (`requireRemovableMember`), nhưng thao tác đặt `role=STUDENT` và xóa phân công nhân sự, nên sau đó dòng thành viên **không còn dấu vết** đó; một người ủy quyền chỉ có `MEMBER:EDIT` có thể "mở khóa" (khôi phục) người mà chủ lớp đã chặn/xóa. Quy tắc: `unblockMember` hỏi nhật ký audit (`MEMBER_BLOCK`/`MEMBER_REMOVE` gần nhất của dòng thành viên): nếu người thực hiện là chủ lớp thì **chỉ chủ lớp** được mở khóa (403 nếu không); nếu không có bản ghi nào thì đóng an toàn (owner-only); nếu do người ủy quyền khác chặn thì người ủy quyền được mở. Chọn nhật ký thay vì cột mới để không cần migration/backfill (mọi lần chặn/xóa đều đi qua `MemberService` và đã ghi audit); giới hạn: nhân sự bị chủ lớp chặn rồi chuyển quyền chủ lớp cho người khác không còn được coi là "chủ lớp chặn".

### D-18 (R20-04, R20-05, R20-08, R20-13): Kho chiếu ngừng không lan vào tính năng cốt lõi; thông lượng outbox; múi giờ UTC; khớp đường dẫn bộ lọc
- **Cô lập I/O của outbox (R20-04a):** giữ `@Scheduled` cho việc *thăm dò* MySQL (rẻ, không bao giờ gọi MongoDB/Neo4j) và chuyển toàn bộ I/O chặn sang executor riêng có giới hạn (`outbox-worker-N`, 4 luồng, hàng đợi 32) thay vì tăng pool scheduler một mình: pool (10 luồng, `classroom-sched-N`) chỉ là lớp phòng thủ thứ hai. Lý do: một pool lớn hơn vẫn để mỗi sự kiện chặn một luồng ~30 s và có thể cạn khi kho ngừng lâu; executor riêng làm cho "tác vụ cốt lõi không bao giờ chờ I/O của kho chiếu" đúng theo cấu trúc, không theo kích thước. Timeout ngắn (Mongo 3 s/2 s/10 s, Neo4j 2 s/3 s/3 s) vì kho chiếu được phép trễ nhưng không được giữ luồng lâu.
- **Lỗi tạm thời không đếm vào giới hạn thử lại (R20-04b):** phân loại theo *kiểu ngoại lệ trước, nội dung thông báo sau*; mặc định của lỗi không nhận diện được là `PERMANENT` (vẫn phải tới `DEAD_LETTER`, không thử lại vô hạn). Mỗi kho có ngắt mạch riêng: khi Neo4j ngừng, sự kiện chỉ cần MongoDB vẫn chạy (loại `MEMBER_JOINED/REMOVED` ngay trong SQL để chúng không chiếm hết lô). Trần thăm dò **15 s** (không phải 60 s): một lần thăm dò chỉ là một lần thử ngắn nên rẻ, còn trần 60 s làm backlog chờ thêm tới một phút sau khi kho đã trở lại (mục tiêu dồn xong ≤ ~30 s); nâng `OUTBOX_BREAKER_MAX_MS` nếu một kho thật sự chập chờn. Tự replay `DEAD_LETTER` (mặc định bật, tối đa 3 lần mỗi sự kiện, 20 sự kiện/phút, chỉ sự kiện đứng đầu aggregate) để sự kiện bị bản cũ đẩy vào `DEAD_LETTER` trong đợt ngừng tự phục hồi sau nâng cấp mà không cần thao tác tay; sự kiện "độc" vẫn chỉ chặn aggregate của nó và replay thủ công (Studio) đặt lại lượt.
- **Thông lượng và bảng nhỏ (R20-05):** một lượt xử lý = đầu aggregate + tối đa 50 sự kiện `PENDING` liên tiếp, dừng ở sự kiện đầu tiên lỗi; cổng thứ tự vẫn kiểm tra từng sự kiện nên thứ tự theo `sequence_no` không đổi. V36 thêm ba chỉ mục khớp truy vấn của worker (dựng online) và hai cột (`failure_kind`, `auto_replay_count`, INSTANT); retention xóa `PROCESSED` > 7 ngày theo lô nhỏ. Đánh đổi: `recordEventIfNotExists` (chặn ghi trùng sự kiện cùng aggregate + loại) chỉ còn nhìn thấy 7 ngày gần nhất; nếu một đường xử lý (ví dụ chấm lại bài tự luận) lặp lại sau hơn 7 ngày thì sự kiện lịch sử có thể được ghi thêm một lần - phép chiếu chỉ thêm một dòng lịch sử (id sự kiện khác), không đổi trạng thái nghiệp vụ.
- **Múi giờ UTC (R20-08):** V30 đã áp dụng nên không sửa; kiểm tra chuyển thành *tiền kiểm khi khởi động* trước Flyway (từ chối khởi động nếu V30 hoặc migration có dấu `requires-utc-server` đang chờ mà MySQL không UTC, cho phép ghi đè có chủ ý). Không nới lỏng cho CSDL trống: cùng một quy tắc dễ giải thích hơn một ngoại lệ, và giá trị mặc định `CURRENT_TIMESTAMP(6)` của cột `DATETIME` cũng phụ thuộc múi giờ máy chủ.
- **Khớp đường dẫn bộ lọc (R20-13):** mọi bộ lọc quyết định "đây có phải endpoint của tôi không" bằng `RequestPaths.withinContext` (URI trừ context path), không dùng `getServletPath()` (rỗng dưới MockMvc / ánh xạ servlet khác, khiến bộ giới hạn im lặng thành fail-open).

### D-19: Lớp riêng tư (`PRIVATE`), lớp trả phí (`PAID`), mã mời và trạng thái thành viên `EXPIRED`
Hai thuộc tính độc lập của một lớp: **hiển thị** `visibility = PUBLIC | PRIVATE` và **hình thức vào lớp** `accessType = FREE | PAID`. Mọi lớp có sẵn là `PUBLIC` + `FREE` nên hành vi hiện tại không đổi (V37 chỉ thêm cột, không đổi hàng nào).

**1. Dữ liệu (Flyway V37, chỉ thêm, `ALGORITHM=INSTANT`/`INPLACE`, chạy lại được)**
- `classrooms.visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC'`, `access_type VARCHAR(16) NOT NULL DEFAULT 'FREE'`, `access_product_id` (FK `products`, `ON DELETE RESTRICT`; để xóa cứng một lớp phải gỡ liên kết này trước).
- `class_members.access_expires_at DATETIME(6) NULL` (+ chỉ mục `(class_id, state, access_expires_at)` cho thao tác theo lớp và `(state, access_expires_at)` cho bộ quét toàn hệ thống); `NULL` = không hết hạn.
- `class_invites` (id, class_id FK CASCADE, `code_hash CHAR(64)` duy nhất, `code_hint`, created_by, created_at, expires_at, max_uses, used_count, revoked_at, `version`); `orders.invite_id` (không FK: đơn hàng là chứng từ tài chính, không được bị xóa/chặn theo mã mời).
- `products.kind VARCHAR(24) NOT NULL DEFAULT 'STANDARD'`. **Chọn phương án ít xâm lấn nhất:** giữ nguyên mọi sản phẩm hiện có là `STANDARD` (gói PRO, sản phẩm khóa học — không backfill) và chỉ thêm `CLASS_ACCESS`; một cột tường minh thay vì đè nghĩa `target_course_id IS NULL` (vốn đã là "gói PRO").
- Mọi cột ngày mới là `DATETIME(6)`; `class_invites.created_at` **không** có mặc định `CURRENT_TIMESTAMP` (giá trị đó theo múi giờ máy chủ — R20-08) mà do ứng dụng ghi UTC; V37 không đổi/không so sánh ngày nên không mang dấu đánh dấu yêu cầu UTC của `DatabaseTimeZonePreflight`.

**2. `PRIVATE` — một quy tắc hiển thị duy nhất, và vì sao trả 404 (không phải 403)**
- `AccessPolicy.isClassVisibleToUser` là nguồn sự thật: lớp `ACTIVE` + `PUBLIC` thấy được với mọi người; lớp `PRIVATE` hoặc không `ACTIVE` chỉ thấy được với chủ lớp, nhân sự đang `ACTIVE` và thành viên `ACTIVE` **hoặc `EXPIRED`** (thành viên hết hạn vẫn phải thấy lớp để xem màn thanh toán/gia hạn). `REMOVED`, `BLOCKED` (và `BANNED` cũ), người ngoài và khách **không** thấy. Hai bản SQL của quy tắc (`ClassroomRepository.findPubliclyVisible` / `findVisibleToUser`) được khóa với bản Java bằng `AccessPolicySqlParityTest` (8 dạng lớp × 11 loại người xem), chạy cả trên MySQL trong bộ integration. `ClassroomService.isVisibleToUser` (bản tự viết lại) đã bị xóa — gọi thẳng `AccessPolicy`.
- Với lớp `PRIVATE` mà người xem không có quan hệ, **mọi** điểm vào đều trả đúng câu trả lời của một id không tồn tại: HTTP 404, cùng `error.code`, cùng thông báo (thông báo chỉ lặp lại id/slug chính người gọi đã gửi). Lý do chọn 404: 403/401 cho biết "có lớp này nhưng bạn không được vào" — đủ để dò tên lớp riêng tư bằng id hoặc slug. Áp dụng cho: chi tiết theo id và slug, `about`, `products`, `posts` (các route `permitAll`), các đọc dành cho thành viên (`members`, `leaderboard`, `documents`, `courses`, `exams`), ghi (`join`, `posts`), và `GET /users/{id}?classId=`/`/journey`. `AccessPolicy.enforceMember` là cổng chung (`membershipDenied`): lớp không tồn tại hoặc riêng tư-ẩn → 404; thành viên hết hạn → 403 `MEMBERSHIP_EXPIRED`; còn lại 403 như cũ. Các route Studio/quản trị (`enforceManage`) vốn trả cùng một 403 `STAFF_PERMISSION_DENIED` cho id không tồn tại, lớp riêng tư và lớp công khai không có quyền nên không lộ gì. `AuditController`, `outbox/status`, `segments`, `orders`… cùng loại. Danh sách `GET /classes` lọc ngay trong SQL nên trang không bị "thủng".
- Giới hạn chấp nhận: với id của **tài nguyên con** (tệp, tài liệu, khóa học…) — vốn là UUID 122 bit chỉ thành viên mới thấy — thông báo 404 có thể khác chữ ("Không tìm thấy lớp học" so với "Không tìm thấy tệp đính kèm") dù cùng HTTP 404 và cùng `error.code`; không đủ để dò vì cần đã biết UUID.
- **Hồ sơ PUBLIC (D-16/R18-10):** `PUBLIC` nghĩa là tên và ảnh hiện với mọi người *xem được lớp*. Người xem ngoài lớp riêng tư không xem được bảng tin/bình luận/danh sách nên tác giả có hồ sơ PUBLIC của lớp riêng tư **không** lộ cho khách. Xem trước mã mời chỉ có `ownerName` (tên hiển thị của chủ lớp, như thẻ lớp công khai).
- Chuyển đổi: `PUBLIC → PRIVATE` giữ nguyên mọi thành viên (họ chỉ ngừng bị người ngoài khám phá được; bài/tệp cũ không đổi); `PRIVATE → PUBLIC` đưa lớp vào danh sách và ai cũng tham gia được (mã mời còn hiệu lực nhưng không cần nữa). Đổi qua `PUT /classes/{id}` (`CLASS:EDIT`, audit `CLASS_SETTINGS_UPDATE` kèm `visibilityBefore/After`). Tạo lớp có thể gửi `visibility` (mặc định `PUBLIC`).
- `POST /classes/{id}/join` (tham gia theo id): lớp không tồn tại/riêng tư-ẩn → 404; lớp không `ACTIVE` → 403 (D-11); người đã thuộc lớp → trả lại lớp (idempotent); lớp `PAID` → 402 `PAYMENT_REQUIRED`; lớp `PRIVATE` mà người gọi **thấy được nhưng chưa vào** → 403 `INVITE_REQUIRED` ("Lớp riêng tư, cần mã mời"); `BLOCKED` → 403; còn lại vào lớp `FREE` (người `REMOVED` hoặc hết hạn-nhưng-lớp-đã-FREE vào lại được). Vì người ngoài luôn nhận 404, `INVITE_REQUIRED` chỉ gặp ở người thấy được lớp mà không có mặt (ví dụ thành viên hết hạn của lớp riêng tư đã chuyển sang FREE).

**3. Mã mời**
- **Sinh:** 24 byte từ `SecureRandom` (192 bit, hơn mức sàn 128), Base64-URL không đệm = 32 ký tự. **Lưu:** chỉ SHA-256 (64 hex) + 4 ký tự cuối (`code_hint`); mã đầy đủ chỉ có trong phản hồi của lệnh tạo, **một lần duy nhất**, không bao giờ trong danh sách/audit/log. **So sánh:** tra bằng chỉ mục duy nhất của băm, rồi so băm lưu với băm vừa tính bằng `MessageDigest.isEqual` (thời gian không đổi). Định dạng `[A-Za-z0-9_-]{16,128}` kiểm trước khi chạm CSDL.
- `POST /classes/{id}/invites` (`expiresAt` tương lai ≤ 10 năm, `maxUses` 1…100 000, đều tùy chọn), `GET` (id, createdAt, expiresAt, maxUses, usedCount, `status` `ACTIVE|REVOKED|EXPIRED|EXHAUSTED` với ưu tiên REVOKED > EXPIRED > EXHAUSTED, `codeHint`), `DELETE …/{inviteId}` (thu hồi, idempotent, giữ lại trong danh sách). Quyền: chủ lớp hoặc nhân sự có `MEMBER:EDIT` (cả tạo, xem, thu hồi). Audit mọi thay đổi: `CLASS_INVITE_CREATE`, `CLASS_INVITE_REVOKE`, `CLASS_INVITE_JOIN`; lệnh đọc (`GET`) không ghi audit. Lớp `ARCHIVED` không cho tạo mã mới.
- **Công khai:** `GET /classes/invites/{code}` (không cần đăng nhập) trả thẻ lớp tối thiểu `{classId, slug, title, description, coverImageUrl, accessType, price/currency/durationDays nếu trả phí, ownerName}`; `POST /classes/invites/{code}/join` (cần đăng nhập). **Mọi lý do mã không dùng được** — không tồn tại, sai định dạng, thu hồi, hết hạn, hết lượt, lớp đã lưu trữ — là **cùng một** 404 "Không tìm thấy lớp học", nên không thành oracle phân biệt lý do.
- **Chống dò mã:** ngoài không gian 192 bit, hai endpoint nằm trong họ `AuthRateLimitFilter`/`AuthThrottle`, theo **địa chỉ khách** (cùng cách tin `X-Real-IP` từ proxy tin cậy): tối đa `AUTH_RL_INVITE_PER_IP_PER_MINUTE` (600) lượt/phút và `AUTH_RL_INVITE_PER_IP_FAILURES_PER_MINUTE` (30) câu trả lời 404/phút; vượt → 429 + `Retry-After`. Lượt hợp lệ (200/402/403) không bao giờ tính vào trần dò 404, nên cả lớp sau một NAT tham gia cùng lúc không bị chặn.
- **Tham gia `FREE`:** khóa dòng mã (`FOR UPDATE`, READ_COMMITTED) **trước**, rồi dòng thành viên: tạo/kích hoạt lại thành viên `ACTIVE` (người `REMOVED` được vào lại; `BLOCKED` → 403, không tốn lượt), và **chỉ khi có người thật sự vào** mới tăng `used_count`; người đã thuộc lớp, chủ lớp và nhân sự không tốn lượt (và không cần mã). 20 luồng với `maxUses = 5` cho đúng 5 thành viên và `used_count = 5` (test trên MySQL).
- **Tham gia `PAID`:** người chưa được phủ trả về **402** `PAYMENT_REQUIRED` với `error.details = {classId, accessType, accessProduct{id, price, currency, durationDays, lifetime}}` để UI mở thanh toán; `BLOCKED` vẫn là 403 (không bảo người bị chặn đi trả tiền).
- **Lớp `PRIVATE` + `PAID`:** người *không* có trong danh sách thành viên (chưa từng vào, hoặc `REMOVED`) chỉ tạo được đơn mua gói truy cập khi gửi `inviteCode` hợp lệ trong `POST /orders` (nếu không: 404 như lớp không tồn tại) — để thu hồi mã mời cũng chặn luôn đường mua vòng qua. Một lượt dùng được **đặt trước khi tạo đơn** (dưới khóa dòng mã nên `maxUses` đúng tuyệt đối) và được **hoàn lại** nếu đơn bị hủy hoặc thanh toán thất bại; đơn đã trả giữ lượt. Thành viên hết hạn (đã có tên trong danh sách) gia hạn không cần mã.

**4. Lớp trả phí — "sản phẩm truy cập lớp" (`kind = CLASS_ACCESS`)**
- Cấu hình bằng `PUT /classes/{id}/access {accessType, price, currency, durationDays}`: **chủ lớp, hoặc nhân sự có đồng thời `STORE:EDIT` và `CLASS:EDIT`** (tiền + cài đặt lớp; chỉ một trong hai là không đủ); audit `CLASS_ACCESS_UPDATE` kèm `before`/`after`. Khóa lớp `FOR UPDATE` rồi khóa sản phẩm (READ_COMMITTED); không đường nào khóa sản phẩm rồi mới khóa dòng lớp.
- `FREE → PAID` tạo **một** sản phẩm `CLASS_ACCESS` (`PUBLISHED`, không gắn khóa học, `access_starts_at = EPOCH` = không giới hạn ngày bắt đầu); `PAID → FREE` **lưu trữ** nó (giữ `access_product_id` để lịch sử đơn/entitlement ở lại một sản phẩm) và `FREE → PAID` lần sau **dùng lại đúng sản phẩm đó**. Đổi giá/thời hạn khi đang `PAID` chỉ ảnh hưởng đơn **mới** (giá/thời hạn được chụp vào `order_items`, D-03). Giá theo R19-09 (`validatePrice`: số nguyên đồng, > 0); chỉ hỗ trợ `VND` (sandbox và UI đều chỉ VND) — loại tiền khác bị 400.
- **Thời hạn:** `durationDays` là 1…3650 ngày, hoặc **bỏ trống/`0` = trọn đời**. Trọn đời là một entitlement kết thúc ở mốc `9999-12-31` (so sánh chính xác, không qua kiểm tra 50 năm của D-03) và dòng thành viên **không có hạn**; không cộng dồn, hoàn tiền thì thu hồi.
- **Các endpoint sản phẩm chung từ chối `CLASS_ACCESS`** (`publish/update/archive/restore` → 400; không gắn được vào khóa học): vòng đời của nó do `PUT /access` quản, để không thể có lớp `PAID` mà sản phẩm duy nhất đã bị gỡ bán (không ai vào được).
- **Mua:** khác với mọi sản phẩm khác (vốn yêu cầu đã là thành viên), đơn `CLASS_ACCESS` được phép với người chưa là thành viên hoặc thành viên `EXPIRED`. Từ chối: lớp không bán sản phẩm này (FREE hoặc sản phẩm khác), lớp `ARCHIVED` (D-11), chủ lớp/nhân sự (không phải trả tiền), người `BLOCKED` (403), thành viên đang có quyền **không hạn** (được cho miễn phí, trọn đời — tránh lấy tiền vô ích). Thanh toán thành công (webhook hiện có, thứ tự khóa **đơn → sản phẩm → entitlement → dòng thành viên**) cấp entitlement **và** tạo/kích hoạt thành viên `ACTIVE` với `access_expires_at` = cuối chuỗi entitlement, **trong cùng giao dịch**; gia hạn khi còn hạn thì **cộng dồn** (D-03). Hoàn tiền thu hồi ngay: tính lại từ chuỗi còn lại, hết thì `EXPIRED` ngay (phát `MEMBER_EXPIRED`). Đơn đã tạo trước khi lớp chuyển FREE vẫn được tất toán và người mua vẫn vào lớp (không hạn); người bị chặn giữa chừng không được vào lớp, khoản thanh toán được ghi audit `CLASS_ACCESS_PAID_WHILE_BLOCKED` để vận hành hoàn tiền/mở khóa.
- **Độc lập với PRO:** entitlement của sản phẩm `CLASS_ACCESS` **không** làm thành viên thành PRO — `hasActiveProEntitlement`/`findClassIdsWithActiveEntitlement`/`findUserIdsWithActiveEntitlement` loại trừ nó bằng `NOT EXISTS (… kind = 'CLASS_ACCESS')`.
- **Chuyển đổi và các ca biên:**
  - *Thành viên hiện có khi `FREE → PAID`:* **grandfather** — `access_expires_at` vẫn `NULL`, không ai bị mất quyền hay phải mua; chỉ người mới phải trả. (Hệ quả: họ bị từ chối khi cố mua — xem trên.)
  - *`PAID → FREE`:* không ai mất gì — mọi thành viên `ACTIVE` bị xóa `access_expires_at` (một câu `UPDATE` theo lớp); thành viên `EXPIRED` giữ `EXPIRED` và chỉ thành `ACTIVE` khi **tự vào lại** (`join`); `REMOVED/BLOCKED` không đổi.
  - *Người bị `REMOVED` còn entitlement đang chạy:* khoản đã mua sống sót qua việc bị gỡ (D-12), nên khi họ vào lại lớp `PAID` (theo id hoặc theo mã mời) họ được khôi phục **không phải trả lần hai**, với cùng hạn; `unblock` cũng giữ nguyên hạn — nhưng nếu hạn đã trôi qua trong lúc bị chặn thì trả về `EXPIRED`, không hồi sinh quyền.
  - *Nhân sự:* gán `STAFF` xóa `access_expires_at` (nhân sự không bao giờ hết hạn, không thể bị "đá ra" bởi bộ quét); gỡ vai trò không khôi phục hạn. Thành viên `EXPIRED` không gán nhân sự được. Chủ lớp luôn là thành viên.
  - *Hoàn tiền của thành viên không hạn (grandfather):* không làm họ mất quyền (họ chưa từng cần gói); hoàn tiền gói trọn đời thì thu hồi.
  - *Gói trọn đời + mua thêm gói có hạn đồng thời:* lệnh mua sau bị từ chối khi tạo đơn; nếu hai đơn chờ tất toán cùng lúc, đơn hữu hạn bắt đầu từ thời điểm hiện tại thay vì vượt ngưỡng 50 năm.

**5. Máy trạng thái thành viên (`class_members.state`)**

| Từ | Sự kiện | Sang | Ghi chú |
|---|---|---|---|
| — | tham gia lớp FREE / mã mời FREE / tạo lớp (chủ) | `ACTIVE` | `access_expires_at = NULL`; `MEMBER_JOINED` |
| — | tất toán đơn truy cập lớp PAID | `ACTIVE` | `access_expires_at` = cuối chuỗi entitlement (`NULL` nếu trọn đời); `MEMBER_JOINED` |
| `ACTIVE` | đến `access_expires_at` | `EXPIRED` | kiểm tra **ngay tức thì** bằng ngày (mọi `isMember`, truy vấn đếm/bảng xếp hạng); bộ quét chỉ cập nhật trạng thái lưu, phát `MEMBER_EXPIRED` (Neo4j gỡ cạnh như `MEMBER_REMOVED`) |
| `ACTIVE` | hoàn tiền hết thời gian đã trả | `EXPIRED` | ngay lập tức, `access_expires_at = now`, `MEMBER_EXPIRED` |
| `EXPIRED` | tất toán gia hạn | `ACTIVE` | hạn mới; `MEMBER_JOINED` |
| `EXPIRED` | lớp đã FREE và người đó `join` | `ACTIVE` | không hạn |
| `ACTIVE` / `EXPIRED` | Studio gỡ (`remove`) | `REMOVED` | đơn đã mua vẫn còn giá trị |
| `ACTIVE` / `EXPIRED` | Studio chặn (`block`) | `BLOCKED` | không mua/tham gia được |
| `REMOVED` | `join` lớp FREE / mã mời / còn entitlement đang chạy / tất toán | `ACTIVE` | ngữ nghĩa D-12 giữ nguyên |
| `BLOCKED` | Studio `unblock` | `ACTIVE`, hoặc `EXPIRED` nếu hạn đã qua | chỉ chủ lớp nếu chính chủ lớp đã chặn (R19-10) |
| `BLOCKED` | `join`/mã mời/mua | — (403) | |

`BANNED` (cũ) được đọc như `BLOCKED`. **Hợp đồng cho giao diện khi `EXPIRED`:** `ClassroomDto` có `memberState = EXPIRED`, `accessExpiresAt`, `accessType`, `visibility` và (lớp PAID) `accessProduct {id, price, currency, durationDays, lifetime}`; `isMember=false`, `userRole=GUEST`. Thành viên hết hạn đọc được thẻ lớp, **Giới thiệu, Cửa hàng** và màn thanh toán; **mọi thứ khác** (bảng tin kể cả phần công khai, học tập, thi, xếp hạng, tài liệu, thành viên, media, đăng bài…) trả **403 `MEMBERSHIP_EXPIRED`**. Số thành viên, bảng xếp hạng, danh sách thành viên cho người cùng lớp và truy vấn "thành viên đang hoạt động" chỉ tính `ACTIVE` chưa hết hạn (một định nghĩa duy nhất: `ClassMember.isActiveAt` / hằng `ClassMemberRepository.ACTIVE_AT`); Studio thấy trạng thái hiệu lực và `accessExpiresAt`.

**6. Bộ quét hết hạn (`MembershipExpirySweeper`)**
Không phải `@Scheduled` (nhóm luồng chung có kích thước bằng số việc định kỳ và một việc kẹt không được làm trễ việc khác — R20-04a): luồng riêng đơn `membership-expiry-N`, `scheduleWithFixedDelay` (`MEMBERSHIP_EXPIRY_INTERVAL_SECONDS`, mặc định 60 s; `_BATCH_SIZE` 200; `_ENABLED`), mỗi lượt tối đa 50 lô. Mỗi lô là **một** giao dịch READ_COMMITTED (`MembershipExpiryService.sweepBatch`): chọn các dòng `ACTIVE` đã quá hạn `FOR UPDATE` (đọc hiện thời nên gia hạn vừa commit được thấy và dòng đó không còn khớp), đổi sang `EXPIRED`, ghi outbox — không `REQUIRES_NEW`, không chạy trong `afterCommit`. Idempotent và chạy được song song nhiều bản backend. Dòng nhân sự/chủ lớp vô tình mang hạn được sửa (xóa hạn) thay vì bị khóa. `MEMBER_EXPIRED` nằm trong `NEO4J_EVENT_TYPES` của `OutboxWorker` (bị giữ lại cùng `MEMBER_JOINED/REMOVED` khi Neo4j ngừng) và chiếu sang Mongo như mọi sự kiện. Profile `test`/`integration` tắt bộ hẹn giờ (test gọi trực tiếp; một test bật thật để chứng minh dây nối).

**7. Khóa và đồng thời** (mọi đường dùng khóa-trước; chuỗi thứ tự luôn cùng chiều)
Tất toán/hoàn tiền: đơn → sản phẩm (tăng dần theo id) → entitlement → dòng thành viên. Tham gia bằng mã: dòng mã → dòng thành viên. Tạo đơn: sản phẩm → dòng mã; hủy đơn: đơn → dòng mã. Đổi cách thu phí: lớp → sản phẩm. Bộ quét chỉ khóa các dòng thành viên (không bao giờ chờ khóa đơn/sản phẩm/entitlement) nên không tạo chu trình. Kiểm chứng trên MySQL thật: hai đơn cùng người tất toán đồng thời (chuỗi liền, một lần `MEMBER_JOINED`), tất toán × hoàn tiền × hoàn tiền (12 vòng), hoàn tiền × gia hạn (8 vòng), bộ quét × gia hạn (8 vòng), 20 luồng × mã `maxUses=5`, cùng một người 20 lần, thu hồi mã giữa lúc đang tham gia.

**8. Những điểm đã tự quyết (không có trong đặc tả)**
(a) Lớp riêng tư/ẩn trả **404** cả cho người đã đăng nhập (không 403). (b) Hết hạn = "thành viên đã có trong danh sách": thấy lớp riêng tư, gia hạn không cần mã mời, bị 403 `MEMBERSHIP_EXPIRED` ở mọi thứ khác — kể cả phần công khai của bảng tin. (c) Trọn đời = `durationDays` trống/0. (d) Chỉ VND. (e) Thành viên không-hạn không được mua (grandfather, trọn đời, nhân sự, chủ lớp). (f) Mua vào lớp riêng tư cần `inviteCode` cho người chưa có tên trong danh sách. (g) Mã mời của lớp có phí đặt chỗ lượt lúc tạo đơn và hoàn lại khi hủy/thất bại. (h) `PAID → FREE` xóa hạn của thành viên đang hoạt động. (i) Gán nhân sự xóa hạn. (j) `POST /join` của người đã là thành viên luôn thành công (idempotent), kể cả lớp riêng tư.
**Giới hạn đã biết:** mã mời nằm trong đường dẫn URL (theo đặc tả) nên lọt vào nhật ký truy cập của proxy và có thể vào `Referer` — giảm nhẹ bằng hạn/lượt dùng/thu hồi và `Referrer-Policy: no-referrer` ở trang mã mời (giao diện đã làm - xem "UI decisions (D-19 addendum)" bên dưới); không có cách nào khác trong khuôn khổ đường dẫn đã chốt. Đường `/classes/invites/{code}` ngang độ đặc hiệu với `/classes/{id}/members|courses|invites` đúng khi `code` trùng tên literal đó (7 ký tự; mã hợp lệ dài ≥ 16): Spring không chọn được và ném "Ambiguous handler methods" — `GlobalExceptionHandler` đổi riêng lỗi này thành 404 thay vì 500 (có test). Bộ đếm giới hạn tần suất là theo JVM (như D-16/R20-02).

#### UI decisions (D-19 addendum)
Giao diện của D-19 (frontend + E2E). Backend và hợp đồng API giữ nguyên; mọi quy tắc dưới đây là cách giao diện *dùng* hợp đồng đó.

**1. Lỗi và mã lỗi mới ở một chỗ**
- `ApiException` (`api/client.ts`) có `code`, `status`, `requestId` và `details` (`error.details`, hiện chỉ `PAYMENT_REQUIRED` mang `{classId, accessType, accessProduct}`). Giữ nguyên single-flight refresh và `NETWORK_ERROR`.
- Ba mã `INVITE_REQUIRED` / `PAYMENT_REQUIRED` / `MEMBERSHIP_EXPIRED` được đổi sang câu tiếng Việt thân thiện **một lần, ngay khi dựng `ApiException`** (`api/errorMessages.ts`), nên mọi màn hình đang hiện `err.message` (bảng tin, học tập, thi...) tự đúng mà không phải sửa từng nơi. Thông báo gốc của máy chủ vẫn dùng cho mọi mã khác. `api/errors.ts` thêm `isPaymentRequired`/`accessProductFromError`... để gọi thanh toán từ `details`.
- Mỗi lần máy chủ trả `MEMBERSHIP_EXPIRED`, `window` nhận sự kiện `classroom:membership-expired`; `ClassroomLayout` đọc lại lớp (im lặng, không dựng lại tab) nên một thành viên hết hạn *trong lúc đang mở trang* được chuyển sang lời nhắc gia hạn thay vì kẹt ở một banner lỗi.
- Lớp riêng tư là **404 (không phải 403)** với người ngoài nên `ClassroomLayout` có một trang "Không tìm thấy lớp học" riêng (tiêu đề cố định + gợi ý "lớp không tồn tại hoặc là lớp riêng tư; nếu được mời hãy mở lại liên kết mời" + về `/classes`), không bao giờ nói "phiên đã hết hạn" (chỉ 401 mới có câu đó). Lỗi 5xx vẫn là banner lỗi có "Thử lại".

**2. Danh sách khám phá**
- Nhãn trên thẻ: **Riêng tư** (chỉ khi máy chủ đã gửi lớp đó, tức người xem nhìn thấy được lớp; "Công khai" chỉ hiện ở Studio, nơi cả hai trạng thái đều có nghĩa), **Miễn phí** hoặc **Trả phí · 199.000đ / 30 ngày** ("trọn đời" khi không hết hạn). Màu nhãn luôn là nền đặc + chữ tối để đạt tương phản trên ảnh bìa.
- Ba chip **Tất cả / Miễn phí / Trả phí** lọc *phía máy khách trên các trang đã tải* (máy chủ không có bộ lọc học phí). Khi chip loại hết thẻ đang tải, trang nói rõ "bộ lọc chỉ áp dụng cho các lớp đã tải" và (nếu còn trang) nhắc "Xem thêm lớp học", thay vì báo "không có lớp nào".
- Hộp thoại tạo lớp có nhóm radio **Công khai** (mặc định) / **Riêng tư**, mỗi lựa chọn một câu giải thích; lỗi khi tạo hiện ngay trong hộp thoại (`role="alert"`), không còn `alert()`.

**3. Khung lớp (`ClassroomLayout`) theo trạng thái người xem**

| Người xem | Bảng tin | Giới thiệu / Cửa hàng | Tab thành viên (Học tập, Luyện thi, Xếp hạng, Tài liệu, Thành viên) |
|---|---|---|---|
| Chưa tham gia, lớp **PUBLIC + FREE** | banner "Tham gia lớp ngay" (như trước) | như trước | như trước (khách: lời mời đăng nhập) |
| Chưa tham gia, lớp **PUBLIC + PAID** (khách hoặc đã đăng nhập) | **thẻ tường phí** (giá, thời hạn/trọn đời, quyền lợi, nút) trên bài công khai | banner một dòng "Lớp học trả phí · 199.000đ / 30 ngày" + nút | thẻ tường phí thay cho tab |
| **EXPIRED** (đã từng là thành viên) | thẻ gia hạn thay cho tab | banner "Gói thành viên lớp đã hết hạn ngày dd/MM/yyyy" + **Gia hạn**, nội dung tab vẫn đọc được | thẻ gia hạn thay cho tab; thanh tab chỉ còn Giới thiệu / Cửa hàng; huy hiệu "Hết hạn" thay cho "GUEST" |
| EXPIRED nhưng lớp đã chuyển **FREE** | — | banner "Tham gia lại" (không cần mua) | thẻ "Nội dung dành cho thành viên" |
| REMOVED / BLOCKED | như R16-01 (BLOCKED: chỉ thông báo) | | |
| Thành viên còn ≤ 7 ngày | chip dịu "Sắp hết hạn · còn N ngày (đến dd/MM/yyyy) · Gia hạn" trong tiêu đề lớp (liên kết sang tab Cửa hàng); không hiện cho chủ lớp/nhân sự | | |

- **Một lời kêu gọi mỗi trang:** thẻ đầy đủ ở bảng tin và tab bị khóa, banner một dòng ở Giới thiệu/Cửa hàng; không bao giờ cả hai.
- `REMOVED` bấm "Tham gia lại" trên lớp PAID mà bị 402 thì mở thanh toán ngay với sản phẩm trong `error.details.accessProduct` (người bị gỡ còn entitlement đang chạy thì `join` thành công và không phải trả lần hai).
- Lớp riêng tư mà vẫn lọt tới người chưa tham gia (không xảy ra với API hiện tại; phòng thủ) không có nút tham gia, chỉ thông báo cần liên kết mời.
- **Thanh toán dùng chung:** luồng mua được tách khỏi `StoreTab` thành hook `hooks/useCheckout.ts` + `components/CheckoutDialog.tsx` và dùng ở Cửa hàng, tường phí/gia hạn (hộp thoại nằm ở *khung lớp* để không biến mất khi mua xong và tường phí tự gỡ) và trang mời. Ngữ nghĩa **idempotency (R3) giữ nguyên**: khóa được giữ khi lỗi mạng/5xx (yêu cầu có thể đã được nhận) và bỏ khi thành công hoặc 4xx dứt khoát; khóa nay tính theo **(sản phẩm, mã mời)**. Việc hỏi "môi trường này có thanh toán không" (`/payments/sandbox-status`, một lần mỗi lần tải trang) chỉ diễn ra khi có thể cần thanh toán, nên xem trang của một thành viên bình thường không phát sinh yêu cầu này; không có cổng thanh toán → nút "Tạm chưa hỗ trợ thanh toán" (vô hiệu).
- Sau khi đơn chuyển `PAID` (người mua bấm "Làm mới trạng thái đơn hàng" sau khi chủ lớp xác nhận) khung lớp đọc lại lớp: người mua thành thành viên, tường phí biến mất, hộp thoại vẫn hiện "Đã thanh toán thành công" với nút **Bắt đầu học**.
- **Cửa hàng:** sản phẩm `CLASS_ACCESS` hiện thành thẻ "Gói vào lớp" (thời hạn 0 = "trọn đời"); nút theo người xem: chưa tham gia "Mua để tham gia", hết hạn "Gia hạn", thành viên có hạn "Gia hạn thêm", thành viên **không hạn** (grandfather/trọn đời) và chủ lớp/nhân sự **không có nút mua** (máy chủ từ chối những đơn này). Trong lớp PAID, người chưa là thành viên thấy sản phẩm khác là "Cần là thành viên".

**4. Trang mã mời `/join/:code` (route công khai)**
- Hợp đồng chỉ cho xem trước thẻ lớp; **không có `productId`** và lớp riêng tư ẩn sản phẩm khỏi người ngoài, nên với lớp PAID nút "Mua để tham gia" gọi `POST /classes/invites/{code}/join` trước và lấy sản phẩm từ **402 `PAYMENT_REQUIRED`** (đúng mục đích của `error.details`), rồi mở thanh toán với `inviteCode` trong `POST /orders`. Vì vậy một phản hồi 402 ở bước này là *bình thường* (E2E liệt nó vào danh sách chủ ý).
- FREE: "Tham gia lớp" → `join` → `navigate(/classes/{slug}/feed, {replace:true})`. Người đã là thành viên/chủ lớp/nhân sự được vào thẳng (máy chủ không tốn lượt mã). 403 → thông báo "Bạn đã bị chặn khỏi lớp học này". Khách: "Đăng nhập để tham gia" (`state.from` giữ trang). Mã không dùng được (404 ở xem trước *hoặc* ở `join`/đơn hàng, gồm lớp đã lưu trữ) luôn là cùng một trang "Mã mời không hợp lệ hoặc đã hết hạn" + liên kết về `/classes`; 429 → "thử quá nhiều lần, đợi ít phút" kèm "Thử lại". Mã sai *định dạng* (không khớp `[A-Za-z0-9_-]{16,128}`) bị loại ngay ở giao diện, không gọi máy chủ (không tính vào bộ đếm dò mã).
- **Mã là bí mật mang theo URL:** (a) trang gắn `<meta name="referrer" content="no-referrer">` bằng effect (thêm khi vào, gỡ khi rời) và ảnh bìa ngoài có `referrerPolicy="no-referrer"`; (b) mã chỉ được gửi tới API cùng nguồn (`/api/v1/classes/invites/{code}[/join]` và trường `inviteCode` của `POST /orders`), không có yêu cầu bên thứ ba nào, không ghi log, không đưa vào `document.title`; (c) mọi điều hướng rời trang dùng `replace`: cả liên kết đăng nhập của khách (`/join/X` → `/login` → `/join/X` chỉ chiếm **một** mục lịch sử) và sau khi tham gia/mua xong, nên nút Back không quay lại URL chứa mã (E2E kiểm tra); (d) `nginx.conf.template` giữ nguyên `Referrer-Policy: strict-origin-when-cross-origin` (không nới, CSP không đổi) - `no-referrer` ở trang mời là lớp chặt hơn, chỉ áp cho trang đó. **Còn lại (không tránh được vì mã nằm trong URL theo đặc tả):** nhật ký truy cập nginx và lịch sử/thanh địa chỉ của chính trình duyệt người dùng; giảm nhẹ bằng hạn, số lượt và thu hồi.

**5. Studio**
- *Cài đặt lớp:* mục **Hiển thị & tham gia** (radio Công khai/Riêng tư, cần `CLASS:EDIT`) - đổi chỉ gửi `PUT /classes/{id}` với **giá trị đã lưu** của tên/mô tả/ảnh bìa (không mang theo phần đang sửa dở của biểu mẫu phía trên vì `PUT` thay thế các trường đó); chọn khác trạng thái hiện tại thì hiện khung xác nhận tại chỗ nêu hệ quả (PUBLIC→PRIVATE: bị ẩn khỏi khám phá, người ngoài mất địa chỉ lớp, thành viên hiện tại giữ quyền, người mới chỉ vào bằng liên kết mời; PRIVATE→PUBLIC: hiện công khai, ai cũng tham gia được, mã mời còn hiệu lực nhưng không còn cần). Mục **Hình thức vào lớp (thu phí)**: Miễn phí/Trả phí, giá nguyên đồng > 0 (kèm dòng "= 199.000đ"), thời hạn 1-3650 ngày hoặc "Trọn đời" (gửi `durationDays: null`); kiểm tra phía máy khách chặn giá/thời hạn sai *trước khi* gọi máy chủ, rồi khung xác nhận (FREE→PAID: thành viên hiện tại được giữ miễn phí trọn đời, chỉ người mới phải trả; PAID→FREE: ai cũng vào tự do, gói cũ được lưu trữ, thành viên không mất quyền; PAID→PAID: giá/thời hạn mới chỉ áp cho đơn mới). Quyền đổi hình thức thu phí: chủ lớp **hoặc** (`CLASS:EDIT` **và** `STORE:EDIT`) qua `permissions.ts` (hiểu cả `*`); người không đủ quyền vẫn xem được trạng thái (radio vô hiệu + giải thích). Lỗi máy chủ hiện trong chính mục đó.
- *Thành viên > Mời thành viên* (chủ lớp hoặc `MEMBER:EDIT`): chọn hạn (không hết hạn/1/7/30 ngày) và số lượt (trống = không giới hạn; 1..100.000). Tạo xong, hộp thoại hiện **liên kết đầy đủ đúng một lần** (ô `readonly` nhiều dòng để đọc trọn 60+ ký tự, tự chọn sẵn), cảnh báo "Chỉ hiển thị một lần", nút **Sao chép** (Clipboard API; thiếu/bị từ chối thì `execCommand('copy')`; không được thì báo và để liên kết đã chọn cho Ctrl+C). Đóng hộp thoại (nút, Escape) là bỏ mã khỏi bộ nhớ trang; trả tiêu điểm về nút "Tạo liên kết mời". Danh sách (3 cột để vừa 390px, không cuộn ngang): nhãn **Đang hiệu lực / Đã thu hồi / Hết hạn / Hết lượt**, `…abcd`, đã dùng/tối đa, hạn; **Thu hồi** có hộp thoại xác nhận. Có lời giải thích: lớp công khai thì liên kết chỉ là tùy chọn, lớp riêng tư thì là cách duy nhất.
- *Thành viên (danh sách):* bộ lọc **Đã hết hạn** (`?state=EXPIRED`), nhãn `EXPIRED`, dòng "Hết hạn dd/MM/yyyy" (hoặc "Hạn truy cập đến ..." cho thành viên ACTIVE có hạn); thành viên hết hạn vẫn xóa/chặn được như ACTIVE.
- *Sản phẩm & Đơn hàng:* `kind === 'CLASS_ACCESS'` bị loại khỏi danh sách, số đếm, sửa, gỡ bán, xuất bản (máy chủ cũng từ chối các endpoint đó); thay bằng một thẻ chỉ-đọc "Gói vào lớp" (giá, thời hạn, trạng thái) có liên kết sang Cài đặt lớp. Đơn hàng của gói này vẫn nằm trong "Lịch sử đơn hàng" để xác nhận/hoàn tiền sandbox.
- *Tiêu đề Studio và Tổng quan:* nhãn Công khai/Riêng tư + Miễn phí/Trả phí.

**6. Trợ năng và bố cục**
- Mọi nhóm lựa chọn là `<fieldset>`/`role="group"` thật với `legend`; chip lọc dùng `aria-pressed`; các khung xác nhận tại chỗ có `role="group"` và tên; hộp thoại dùng `Modal` (focus trap, Escape). axe-core: 0 lỗi critical **và 0 serious** trên tường phí, trang mời, 404, khám phá, hộp thoại tạo lớp, Cài đặt, quản lý mã mời và các hộp thoại của nó, thẻ gia hạn (E2E `e2e5.js`, P6/P7).
- Đã sửa hai lỗi bố cục tìm thấy khi xem ảnh 390px: phần tử `sr-only` trong bảng cuộn ngang (`position:absolute` thoát khỏi vùng cuộn) làm cả trang rộng thêm 75px - bảng nay nằm trong vùng `relative`; thanh tìm kiếm + bộ lọc của danh sách thành viên Studio tràn màn hình hẹp - nay xuống dòng.

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


## D-20 đến D-24 — Round 23 (01/10/2026)

- D-20: MySQL chia sẻ budget/lockout dùng giờ DB; khóa từng bucket, fail closed khi DB gián đoạn. Outbox dùng claim token khi finalize; membership Neo4j giữ sequence để worker cũ không đảo sự kiện mới.
- D-21: Máy Windows này chạy web HTTPS qua Caddy và nginx; mặc định một backend, hai node tùy chọn theo D-26. CA nội bộ cho LAN, ACME chỉ khi có DNS/router thật. Demo/DB/console giữ loopback.
- D-22: Giới thiệu có tối đa 12 tab/ảnh; ảnh ABOUT được cấp URL ngắn hạn sau khi kiểm tra quyền nhìn lớp; optimistic version chống ghi đè.
- D-23: Export chính chủ có xác nhận mật khẩu; yêu cầu xóa bền vững, PLATFORM_ADMIN phải ghi căn cứ giữ/xử lý. Đóng tài khoản ẩn danh hóa và thu hồi phiên; không tự xóa chứng từ hoặc tuyên bố đã đạt toàn bộ pháp lý.
- D-24: Video hỗ trợ WebVTT tiếng Việt và contentText làm bản chép lời/mô tả; người tạo nội dung chịu trách nhiệm độ chính xác của phụ đề và mô tả âm thanh/hình ảnh.

D-20 bổ sung: start/submit dùng UPSERT nguyên tử, trả counter của chính request qua MySQL OK packet. Autosave đặt trước tối đa 8 lượt trong transaction khóa bucket; tổng lượt đặt trước của các node không vượt 300/phút. Cache chỉ tiêu thụ lượt đã tính ở DB, dùng đồng hồ đơn điệu với hạn bảo thủ bắt đầu trước DB I/O. Eviction/restart bỏ lượt chưa dùng và không hoàn lại budget. DB lỗi trả 503. Kiểm thử nhiều connection chia sẻ chính xác ngân sách và hết hạn bằng giờ DB.

D-25: Khi công bố đề, chuẩn bị một bản JSON cho người học và một bản JSON chấm điểm trong cùng giao dịch khóa đề. V43 lưu hai bản này; mỗi lượt thi sao chép lại để giữ tính bất biến riêng. API người học chỉ nhận DTO không có đáp án. Xem thử vẫn dùng nội dung hiện hành, nên sửa đề nháp không bị bản công bố che khuất. Backend nâng cấp đề cũ theo từng nhóm 100 ID; đề cũ không có câu hỏi vẫn bị từ chối bắt đầu. Lỗi ghi snapshot làm giao dịch công bố rollback. Không thay đổi quyền tham gia, lịch, hạn lượt hoặc kiểm tra thu hồi tài khoản.

D-26: Mặc định một backend JVM trên máy i5/RAM 16 GB này, dựa trên phép đo tải; `start-server.ps1 -TwoBackends` vẫn hỗ trợ kiểm tra phân tán. Xác thực đọc trạng thái/quyền hiện tại từ MySQL cho mọi request bằng projection nhỏ; truy vấn thu hồi token vẫn đọc database. Không cache trạng thái tài khoản hoặc tin quyền đã cũ trong JWT. Ngưỡng tải không được nới để nghiệm thu.

D-25 bổ sung vòng đời ORM: ExamAttempt có UUID được gán trước, dùng Persistable với cờ transient, PostPersist/PostLoad để insert trực tiếp bản mới và merge bản đã tải. Không đưa cờ vào JSON. Integration thật xác minh insert, tải lại và cập nhật cùng ID. Căn cứ: [Spring Data JPA — Persisting Entities](https://docs.spring.io/spring-data/jpa/reference/jpa/entity-persistence.html).

D-25 bổ sung: cache prepared statement chỉ giữ câu lệnh SQL (250/connection, SQL ≤ 2048), dùng prepared statement phía MySQL và trạng thái session/autocommit do driver theo dõi. Cấu hình này nằm trên URL JDBC của cả Compose ứng dụng và integration; không thay isolation, kiểm tra quyền hay độ bền ghi. Căn cứ: [HikariCP MySQL Configuration](https://github.com/brettwooldridge/HikariCP/wiki/MySQL-Configuration), [Connector/J Performance Extensions](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-performance-extensions.html). Số lượt thi được đọc một lần dưới khóa học viên và dùng lại trong policy; không đọc lại cùng số. Kiểm tra nhân sự đọc assignment trước, membership/expiry vẫn bắt buộc với assignment ACTIVE.

D-27: Blog và sự kiện của lớp (V44). (1) Quyền: hai module staff mới `BLOG` (VIEW/CREATE/EDIT/PUBLISH/DELETE) và `EVENT` (VIEW/CREATE/EDIT/DELETE), chỉ cấp toàn lớp; backend không có danh sách trắng module nên chỉ cần AccessPolicy.canManage, chủ lớp luôn qua. (2) Nhìn lớp: mọi endpoint đọc dùng `AccessPolicy.requireVisibleClass` (cùng luật D-19 của feed/About) - lớp PRIVATE không quan hệ là 404 giống id không tồn tại, kể cả khi truy cập bài/sự kiện theo id riêng; khách đọc được lớp PUBLIC + ACTIVE. Bài MEMBERS vẫn hiện thẻ nhưng `locked=true`, không có nội dung; nháp chỉ tồn tại với người có một quyền BLOG. (3) Phân trang blog keyset trên (publishedAt, id) như feed, cursor mờ giữ cả phần micro giây vì cột là DATETIME(6); `publishedAt` chỉ đặt ở lần xuất bản đầu để thứ tự công khai ổn định; `readingMinutes` = ceil(từ/200) tính khi ghi và lưu cột. (4) Sức chứa: `class_events.registered_count` là bộ đếm phi chuẩn hóa, chỉ thay đổi khi đang giữ khóa dòng sự kiện (`SELECT ... FOR UPDATE` là lần đọc ĐẦU TIÊN của dòng trong giao dịch, để persistence context không giữ bản cũ), cùng với bảng đăng ký có khóa duy nhất (event_id, user_id); đăng ký/hủy là idempotent; hết chỗ 409. Danh sách không cần COUNT. (5) `meetingUrl` chỉ trả cho người đã đăng ký và người quản lý (chủ lớp, EVENT:VIEW/EDIT), không bao giờ trên rail `/events/upcoming`; rail chỉ gồm lớp PUBLIC + ACTIVE. Ai được đăng ký: thành viên ACTIVE với sự kiện MEMBERS và mọi sự kiện của lớp PAID/PRIVATE; sự kiện PUBLIC của lớp PUBLIC miễn phí cho mọi người đăng nhập thấy được lớp, trừ người bị BLOCKED. (6) D-11: lớp ARCHIVED không nhận bài mới, xuất bản, sự kiện mới hay đăng ký mới (409); đọc, sửa, gỡ, hủy, xóa vẫn được. (7) Ảnh bìa: purpose `CLASS_COVER`/`BLOG`/`EVENT` theo đúng khuôn ABOUT (ảnh, ≤ 5 MB, quyền theo đối tượng, kiểm tra lại khi complete); entity chỉ trỏ tới ảnh UPLOADED cùng lớp và đúng purpose; URL ký ngắn hạn (TTL R8-01) sinh theo từng phản hồi SAU khi qua kiểm tra nhìn lớp, ký cục bộ nên danh sách chỉ thêm một truy vấn ảnh. (8) Danh tính: byline tác giả, người dẫn và danh sách người đăng ký (chỉ người quản lý xem) hiển thị tên/ảnh thật - tác giả/người dẫn là chủ lớp/nhân sự hành động thay lớp (như `ownerName` trên thẻ lớp), người đăng ký chia sẻ danh tính với ban tổ chức khi đăng ký; không áp luật riêng tư đồng học của danh sách thành viên (mặc định PRIVATE sẽ biến mọi byline thành "ẩn danh"). (9) Audit tạo/sửa/xuất bản/gỡ/xóa bài và tạo/sửa/hủy/xóa sự kiện, JSON dựng bằng ObjectMapper; đăng ký là thao tác của chính thành viên nên không audit (như tham gia lớp); không phát outbox. (10) `GET /classes` thêm `q` (LIKE chữ thường trên tên/mô tả, ký tự đại diện người gõ bị vô hiệu) và `sort=popular` (số thành viên ACTIVE hiện tại, SQL native vì sắp theo count tương quan); lọc nhìn lớp vẫn nằm trong truy vấn.

D-27 bổ sung (review): `meetingUrl` của người đã đăng ký chỉ hiện khi người đó vẫn đủ điều kiện tại thời điểm đọc (tính một lần cho mỗi danh sách); `POST .../registrations` kiểm tra điều kiện trước câu trả lời idempotent. Mọi thao tác ghi cả dòng sự kiện (sửa, hủy, xóa, đăng ký, hủy đăng ký) đọc dòng qua khóa `FOR UPDATE` trước tiên. Bài blog có cột `version` (V45, khóa lạc quan): sửa/xuất bản/gỡ đồng thời thì bên thua nhận 409. `DELETE .../registrations/me` luôn trả chỗ khi có lượt đăng ký, kể cả khi lớp đã ẩn với người gọi (khi đó chỉ trả `{id, isRegistered:false}`). Đóng tài khoản trả lại chỗ ở sự kiện chưa kết thúc; bài viết/sự kiện đã dẫn ở lại làm nội dung lớp (DATA_POLICY.md).

D-28: Trang "Tạo lớp học" (V46). (1) Danh mục là danh sách cố định 12 chuỗi trong mã (`ClassCategories`), khớp chính xác, lộ qua `GET /classes/categories` để giao diện không tự viết cứng; lọc `category` nằm trong cùng truy vấn nhìn lớp của `q`/`sort`. (2) Slug tùy chọn: máy chủ sinh từ tên (bỏ dấu, đ→d, ≤ 60, ≥ 3), trùng thì `-2`..`-6` rồi hậu tố ngẫu nhiên; khóa duy nhất chặn va chạm đồng thời (409). Slug gửi lên giữ quy tắc cũ. (3) Ảnh đại diện lớp theo đúng khuôn ảnh bìa D-27 (purpose `CLASS_AVATAR`, `CLASS:EDIT`, ảnh ≤ 5 MB, chỉ trỏ tới ảnh UPLOADED cùng lớp, URL ký ngắn hạn sinh sau kiểm tra nhìn lớp, theo lô). Vị trí ảnh là chuỗi CSS `"X% Y%"` 0..100 kiểm tra phía máy chủ. (4) Duyệt thành viên: trạng thái `PENDING` trong `class_members` (không cần DDL), không phải thành viên ở mọi kiểm tra (isMember chỉ nhận ACTIVE còn hạn; SQL nhìn lớp chỉ nhận ACTIVE/EXPIRED — test parity có thêm người xem PENDING), nên lớp riêng tư không bao giờ lộ qua yêu cầu. Chỉ tham gia theo id của lớp PUBLIC + FREE mới thành yêu cầu; mã mời và mua quyền truy cập bỏ qua duyệt (quyết định của chủ dự án). Từ chối và rút yêu cầu đều xóa hàng (memberState NONE, gửi lại được) — từ chối không phải là chặn; audit giữ quyết định. Duyệt cần `MEMBER:EDIT`, xem hàng chờ cần `MEMBER:VIEW`; duyệt bị từ chối (409) nếu lớp đã lưu trữ hoặc đã chuyển trả phí, để duyệt không thành đường vào miễn phí. `unblock` không áp cho hàng PENDING. Tắt duyệt không tự duyệt ai. (5) `pendingRequestCount` chỉ cho người có `MEMBER:VIEW`; danh sách lớp tính bằng một truy vấn gộp từ quyền đã nạp sẵn (khớp wildcard như AccessPolicy.canManage). (6) Đóng tài khoản xóa các yêu cầu PENDING (không để lại hàng REMOVED); bản sao dữ liệu vẫn gồm chúng trong `memberships`.
