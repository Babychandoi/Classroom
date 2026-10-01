# Đặc tả Hợp đồng API (REST API Specification)

**Base URL:** `/api/v1`  
**Authentication:** Header `Authorization: Bearer <token>`  
**Định dạng phản hồi chuẩn:**
```json
{
  "success": true,
  "data": { ... },
  "requestId": "uuid",
  "timestamp": "ISO-8601"
}
```
**Định dạng lỗi chuẩn:**
```json
{
  "success": false,
  "error": {
    "code": "COURSE_ACCESS_REQUIRED",
    "message": "Cần mua khóa học để truy cập nội dung này",
    "requestId": "uuid"
  },
  "requestId": "uuid",
  "timestamp": "ISO-8601"
}
```

---

## 1. Định danh & Xác thực (Identity & Auth)

- `POST /api/v1/auth/login`: Đăng nhập hệ thống (Body: `email`, `password`)
- `POST /api/v1/auth/register`: Đăng ký tài khoản (Body: `email`, `password`, `fullName`)
- `POST /api/v1/auth/logout`: Đăng xuất
- `GET /api/v1/me`: Lấy thông tin tài khoản hiện tại

**Giới hạn tốc độ (R20-02).** Bốn endpoint công khai `login`, `register`, `refresh`, `logout` trả **429** (`error.code=RATE_LIMITED`, khung lỗi chuẩn) kèm header **`Retry-After`** (giây) khi bị chặn. Ngân sách gắn với thứ được bảo vệ chứ không chỉ IP nguồn để cả lớp sau một NAT dùng được: `login` theo cặp (IP, e-mail) + lùi theo cấp số nhân khi sai liên tiếp + trần lượt *sai* theo IP (đăng nhập thành công không bị tính); `register` theo IP (cao) và theo e-mail; `refresh`/`logout` theo phiên (cookie làm mới) và theo IP. Mật khẩu đúng gửi trong lúc bị khóa cũng bị 429. Giá trị mặc định và cách chỉnh: `docs/RUNBOOK.md` mục 4.7.

---

## 2. Lớp học & Thành viên (Classroom & Members)

- `GET /api/v1/classes?page=0&size=50`: Danh sách lớp học người gọi được phép thấy, mới nhất trước. **Lớp `PRIVATE` không bao giờ có mặt** với khách/người ngoài (D-19, mục 2.1). **Luôn phân trang (R16-08):** mặc định `size=50`, tối đa 100; trang ngắn hơn `size` là trang cuối. Mỗi lớp kèm `isMember`, `userRole` và `memberState`.
- `POST /api/v1/classes`: Tạo lớp học mới (Body: `title`, `slug`, `description`, `visibility?`)
- `PUT /api/v1/classes/{id}`: Cập nhật cài đặt lớp (`CLASS:EDIT`; Body: `title`, `description`, `coverImageUrl`, `visibility?`)
- `PUT /api/v1/classes/{id}/access`: Đổi hình thức thu phí `FREE`/`PAID` và giá (chủ lớp, hoặc `STORE:EDIT` + `CLASS:EDIT`) — mục 2.1
- `POST|GET /api/v1/classes/{id}/invites`, `DELETE /api/v1/classes/{id}/invites/{inviteId}`, `GET /api/v1/classes/invites/{code}` (công khai), `POST /api/v1/classes/invites/{code}/join`: mã mời — mục 2.1
- `GET /api/v1/classes/{id}`: Chi tiết lớp học theo ID
- `GET /api/v1/classes/slug/{slug}`: Chi tiết lớp học theo slug. **`memberState` (R16-01)** = trạng thái thành viên của người gọi: `ACTIVE` (hoặc chủ lớp), `REMOVED` (bị xóa, được tự tham gia lại), `BLOCKED` (bị chặn, chỉ Studio mở khóa được) hoặc `NONE` (chưa từng tham gia / khách). `isMember=true` và `userRole=STUDENT/STAFF` chỉ khi `ACTIVE`; `REMOVED`/`BLOCKED` có `isMember=false`, `userRole=GUEST`.
- `POST /api/v1/classes/{id}/join`: Tham gia lớp học (thành viên `REMOVED` được kích hoạt lại; `BLOCKED` bị từ chối 403). **D-19:** lớp `PAID` → 402 `PAYMENT_REQUIRED`, lớp `PRIVATE` → 404 (người không thấy được) hoặc 403 `INVITE_REQUIRED` — bảng ở mục 2.1
- `GET /api/v1/classes/{id}/members`: Danh sách thành viên lớp học
- `GET /api/v1/classes/{id}/about`: Lấy nội dung giới thiệu & nội quy
- `PUT /api/v1/classes/{id}/about`: Cập nhật trang giới thiệu (OWNER/STAFF)

### 2.1 Lớp riêng tư, lớp trả phí và mã mời (D-19)

Mỗi lớp có hai thuộc tính độc lập trong `ClassroomDto`: `visibility` (`PUBLIC` mặc định | `PRIVATE`) và `accessType` (`FREE` mặc định | `PAID`). Thiết kế đầy đủ, máy trạng thái thành viên và các ca biên: `docs/DECISIONS.md` mục D-19.

**Trường mới của `ClassroomDto`** (mọi endpoint trả lớp: danh sách, theo id, theo slug, `join`, `PUT`…):

| Trường | Ý nghĩa |
|---|---|
| `visibility` | `PUBLIC` \| `PRIVATE` |
| `accessType` | `FREE` \| `PAID` |
| `accessProduct` | Lớp `PAID`: `{ "id", "price", "currency": "VND", "durationDays", "lifetime" }` (`durationDays = null` và `lifetime = true` = trọn đời; `id` là `productId` của `POST /orders`). Lớp `FREE`: `null` |
| `memberState` | thêm giá trị **`EXPIRED`** (thành viên lớp trả phí đã hết hạn): `ACTIVE` \| `EXPIRED` \| `REMOVED` \| `BLOCKED` \| `NONE`. `isMember=false`, `userRole=GUEST` khi `EXPIRED` |
| `accessExpiresAt` | Hạn truy cập của **người gọi** (`null` = không hết hạn hoặc không phải thành viên); khi `memberState = EXPIRED` là thời điểm đã hết hạn |

`memberCount` chỉ đếm thành viên **đang** hoạt động (không tính `EXPIRED`, `REMOVED`, `BLOCKED`).

**Lớp `PRIVATE` không tồn tại với người không có quan hệ.** Khách, người lạ, người `REMOVED` và `BLOCKED` nhận đúng câu trả lời của một id không tồn tại: HTTP **404**, `error.code = NOT_FOUND`, cùng thông báo — trên `GET /classes/{id}`, `/classes/slug/{slug}`, `/classes/{id}/about|products|posts` (các route không cần đăng nhập) và mọi route theo lớp khác. Không xuất hiện trong `GET /classes`. Xem được: chủ lớp, nhân sự đang hoạt động, thành viên `ACTIVE` **và `EXPIRED`**.

**Cập nhật cài đặt:** `PUT /classes/{id}` nhận thêm `visibility` (`PUBLIC`/`PRIVATE`, không phân biệt hoa thường; bỏ trống = giữ nguyên; quyền `CLASS:EDIT`; audit `CLASS_SETTINGS_UPDATE` có `visibilityBefore/After`). `POST /classes` cũng nhận `visibility` tùy chọn.

#### `PUT /api/v1/classes/{id}/access` — đổi hình thức thu phí
Quyền: chủ lớp, hoặc nhân sự có **đồng thời** `STORE:EDIT` và `CLASS:EDIT` (thiếu một trong hai → 403 `STAFF_PERMISSION_DENIED`). Audit `CLASS_ACCESS_UPDATE` (`before`/`after`).
```json
{ "accessType": "PAID", "price": 199000, "currency": "VND", "durationDays": 30 }
```
- `accessType`: `FREE` | `PAID`. `PAID` cần `price` > 0 (số nguyên đồng); `currency` mặc định và duy nhất hỗ trợ `VND`; `durationDays` 1…3650, **bỏ trống hoặc `0` = truy cập trọn đời**.
- Trả `ClassroomDto` đã cập nhật (có `accessProduct`). Lỗi: 400 (giá/thời hạn/loại tiền sai), 403 `STAFF_PERMISSION_DENIED`, 404.
- `FREE → PAID`: thành viên hiện có được giữ nguyên **không cần mua, không hết hạn** (grandfather). `PAID → FREE`: không ai mất quyền, thành viên hết hạn chỉ vào lại khi tự `join`. Đổi giá chỉ áp dụng cho đơn mới.

#### Mã mời
| Endpoint | Quyền | Ghi chú |
|---|---|---|
| `POST /api/v1/classes/{id}/invites` | chủ lớp hoặc `MEMBER:EDIT` | Body (tùy chọn): `{ "expiresAt": "2026-12-31T00:00:00Z", "maxUses": 50 }`. **Trả mã đầy đủ đúng một lần** |
| `GET /api/v1/classes/{id}/invites` | như trên | Không bao giờ có mã đầy đủ, chỉ `codeHint` (4 ký tự cuối) |
| `DELETE /api/v1/classes/{id}/invites/{inviteId}` | như trên | Thu hồi (idempotent); mã vẫn nằm trong danh sách với `status = REVOKED` |
| `GET /api/v1/classes/invites/{code}` | **công khai** (không cần đăng nhập) | Xem trước thẻ lớp; giới hạn tần suất theo địa chỉ |
| `POST /api/v1/classes/invites/{code}/join` | đăng nhập | Tham gia; giới hạn tần suất theo địa chỉ |

Tạo — phản hồi (mã chỉ xuất hiện ở đây):
```json
{ "success": true, "data": { "id": "9f3c…", "code": "q8r0vKc2Lw1nZ5uYtH7eXb3GjM9dPaSf", "codeHint": "PaSf",
  "createdAt": "2026-10-01T03:00:00Z", "expiresAt": null, "maxUses": 50, "usedCount": 0, "status": "ACTIVE", "createdBy": "uuid" } }
```
Danh sách: mỗi phần tử như trên nhưng **không có** `code`; `status` ∈ `ACTIVE`, `REVOKED`, `EXPIRED`, `EXHAUSTED` (ưu tiên REVOKED > EXPIRED > EXHAUSTED).

Xem trước (mã hợp lệ):
```json
{ "success": true, "data": { "classId": "uuid", "slug": "lop-rieng-tu-ma-moi", "title": "Lớp Riêng Tư (mã mời)", "description": "…",
  "coverImageUrl": null, "accessType": "PAID", "price": 199000, "currency": "VND", "durationDays": 30, "lifetime": false, "ownerName": "Thầy Nguyễn Chủ Nhiệm" } }
```
(lớp `FREE`: không có `price/currency/durationDays/lifetime`). **Mã không dùng được — không tồn tại, sai định dạng, đã thu hồi, hết hạn, hết lượt, lớp đã lưu trữ — luôn là cùng một phản hồi: 404 `NOT_FOUND` "Không tìm thấy lớp học".** Mã là chuỗi 16–128 ký tự `[A-Za-z0-9_-]` (mã sinh ra dài 32 ký tự, 192 bit).

`join` — lớp `FREE`: trả `ClassroomDto` (`memberState = ACTIVE`), tốn một lượt của mã; người `REMOVED` vào lại được; người đã thuộc lớp, chủ lớp và nhân sự không tốn lượt; `BLOCKED` → 403 `FORBIDDEN`. Lớp `PAID` chưa được phủ → **402** `PAYMENT_REQUIRED` (xem dưới); người `REMOVED` còn gói đang chạy vào lại không phải trả lần hai.

**Giới hạn tần suất** (theo địa chỉ khách; `429 RATE_LIMITED` + `Retry-After`): `AUTH_RL_INVITE_PER_IP_PER_MINUTE` (600 lượt/phút) và `AUTH_RL_INVITE_PER_IP_FAILURES_PER_MINUTE` (30 câu trả lời 404/phút; lượt hợp lệ không tính).

#### `POST /api/v1/classes/{id}/join` (tham gia theo id) — thay đổi
| Tình huống | Kết quả |
|---|---|
| Lớp không tồn tại, hoặc lớp `PRIVATE` mà người gọi không thấy được | 404 `NOT_FOUND` |
| Lớp không `ACTIVE` (đã lưu trữ) | 403 `FORBIDDEN` "Lớp học hiện không mở đăng ký thành viên" |
| Đã là thành viên (hoặc chủ lớp) | 200, trả lại lớp |
| `BLOCKED` | 403 `FORBIDDEN` |
| Lớp `PAID` (người chưa được phủ) | **402** `PAYMENT_REQUIRED` |
| Lớp `PRIVATE` (người gọi thấy được nhưng chưa vào) | **403** `INVITE_REQUIRED` "Lớp riêng tư, cần mã mời" |
| Lớp `FREE` công khai | 200, thành viên `ACTIVE` (`REMOVED` vào lại được) |

#### Mã lỗi mới
| HTTP | `error.code` | Khi nào |
|---|---|---|
| 402 | `PAYMENT_REQUIRED` | Lớp trả phí cần mua gói truy cập. Có thêm `error.details` |
| 403 | `INVITE_REQUIRED` | Lớp riêng tư cần mã mời |
| 403 | `MEMBERSHIP_EXPIRED` | Thành viên lớp trả phí đã hết hạn truy cập — mọi thứ trừ thẻ lớp, Giới thiệu, Cửa hàng |

`PAYMENT_REQUIRED` mang dữ liệu để giao diện mở thanh toán ngay:
```json
{ "success": false,
  "error": { "code": "PAYMENT_REQUIRED", "message": "Lớp học trả phí; cần thanh toán để tham gia", "requestId": "uuid",
    "details": { "classId": "uuid", "accessType": "PAID",
      "accessProduct": { "id": "uuid", "price": 199000, "currency": "VND", "durationDays": 30, "lifetime": false } } },
  "requestId": "uuid", "timestamp": "2026-10-01T03:00:00Z" }
```
(`error.details` chỉ xuất hiện trong lỗi này; các lỗi khác giữ nguyên khung cũ.)

#### Mua gói truy cập lớp — `POST /api/v1/orders` (thay đổi)
Sản phẩm của lớp trả phí có `kind = "CLASS_ACCESS"` (`ProductDto.kind`; mọi sản phẩm khác `STANDARD`). Khác với sản phẩm thường (yêu cầu đã là thành viên), **người chưa là thành viên hoặc thành viên `EXPIRED` được tạo đơn** cho đúng sản phẩm `accessProduct.id` của lớp. Body thêm trường tùy chọn `inviteCode`: **bắt buộc** khi lớp là `PRIVATE` và người mua chưa có tên trong danh sách thành viên (thiếu/sai/hết hạn/hết lượt → 404 như lớp không tồn tại). Từ chối: chủ lớp và nhân sự (400), người `BLOCKED` (403), thành viên đã có quyền không hạn (400), lớp `ARCHIVED` (400, D-11), sản phẩm không phải gói truy cập hiện hành của lớp (400).

Sau khi webhook `PAYMENT_SUCCESS` tất toán đơn, **trong cùng giao dịch** người mua trở thành thành viên `ACTIVE` với `access_expires_at` = cuối chuỗi entitlement (gia hạn khi còn hạn thì cộng dồn — D-03). `PAYMENT_REFUNDED` thu hồi ngay và tính lại từ phần còn lại (hết thì `EXPIRED` ngay). Gói truy cập lớp **không** làm người mua thành PRO. Gói này chỉ quản lý qua `PUT /classes/{id}/access`: các endpoint `POST /products/{id}/publish|archive|restore` và `PUT /products/{id}` từ chối nó (400).

#### Thành viên hết hạn — hợp đồng cho giao diện
Thành viên `EXPIRED` **đọc được**: thẻ lớp (`GET /classes/{id}`, `/slug/{slug}`), `GET /classes/{id}/about`, `GET /classes/{id}/products`, `GET /me/orders`. **Mọi thứ khác** trả **403 `MEMBERSHIP_EXPIRED`**: bảng tin (kể cả phần công khai), khóa học/bài học, kỳ thi, xếp hạng, tài liệu, danh sách thành viên, media, đăng bài/bình luận. Hành động duy nhất họ cần là tạo đơn mua lại gói truy cập. Studio (`GET /classes/{id}/studio/members`) hiển thị `state = EXPIRED` (trạng thái hiệu lực: một dòng `ACTIVE` đã quá hạn cũng hiện là `EXPIRED` dù bộ quét chưa cập nhật), kèm `accessExpiresAt`, và lọc được `?state=EXPIRED`; Studio gỡ (`remove`) hoặc chặn (`block`) được thành viên `EXPIRED`; `unblock` một thành viên có hạn đã trôi qua đưa về `EXPIRED`.

---

## 3. Nhân sự & Phân quyền Studio (Staff & Permissions)

- `GET /api/v1/classes/{classId}/staff`: Danh sách trợ giảng và phân quyền
- `PUT /api/v1/classes/{classId}/staff/{userId}/permissions`: Cấp quyền chi tiết (Chỉ OWNER)
- `DELETE /api/v1/classes/{classId}/staff/{userId}`: Thu hồi quyền trợ giảng (Chỉ OWNER)
- `GET /api/v1/studio/classes/{classId}/outbox/status`: **(R20-04)** Số sự kiện chiếu sang MongoDB/Neo4j đang chờ của lớp (`pending`, `processing`, `failed`, `deadLetter`, `truncated`) và tình trạng hai kho (`sinks.mongo`/`sinks.neo4j` = `UP`/`DOWN`/`DISABLED`). Cần quyền `OUTBOX:REPLAY` (OWNER hoặc nhân sự được cấp). Số liệu toàn hệ thống nằm ở `GET /actuator/health` (thành phần `outbox`, chi tiết cho vai trò OPS).
- `POST /api/v1/studio/classes/{classId}/outbox/replay`: Đưa tối đa 50 sự kiện `DEAD_LETTER`/`FAILED` của lớp về hàng đợi (đặt lại số lần thử và số lượt tự replay). Sự kiện `DEAD_LETTER` còn được tự replay có giới hạn - xem `docs/RUNBOOK.md` mục 4.9.

---

## 4. Khóa học & Bài giảng (Learning & Courses)

- `GET /api/v1/classes/{classId}/courses`: Danh sách khóa học (kèm cờ `canLearn` và tiến độ cá nhân)
- `POST /api/v1/classes/{classId}/courses`: Tạo khóa học mới (OWNER/STAFF)
- `GET /api/v1/courses/{courseId}`: Chi tiết khóa học và cấu trúc chương mục
- `POST /api/v1/courses/{courseId}/sections`: Thêm chương học
- `POST /api/v1/sections/{sectionId}/lessons`: Thêm bài học mới
- `GET /api/v1/lessons/{lessonId}`: Xem bài học (kiểm tra `LearningPolicy`, cấp URL video ngắn hạn)
- `PUT /api/v1/lessons/{lessonId}/progress`: Đánh dấu hoàn thành bài học
- `GET /api/v1/lessons/{lessonId}/questions`: Lấy danh sách câu hỏi Q&A
- `POST /api/v1/lessons/{lessonId}/questions`: Đặt câu hỏi trong bài
- `POST /api/v1/questions/{questionId}/answers`: Trả lời câu hỏi Q&A

---

## 5. Bảng tin & Cộng đồng (Feed & Posts)

- `GET /api/v1/classes/{classId}/posts`: Lấy bài viết bảng tin (kiểm soát hiển thị theo PUBLIC/FREE/PRO/COURSE)
- `POST /api/v1/classes/{classId}/posts`: Đăng bài viết mới
- `POST /api/v1/posts/{postId}/comments`: Bình luận bài viết
- `DELETE /api/v1/posts/{postId}`: Xóa bài viết (Tác giả hoặc OWNER/STAFF)

---

## 6. Kỳ thi & Làm bài (Exams & Assessment)

- `GET /api/v1/classes/{classId}/exams`: Danh sách kỳ thi (kèm cờ `canEnter` và số lượt còn lại)
- `POST /api/v1/classes/{classId}/exams`: Tạo kỳ thi mới (OWNER/STAFF)
- `GET /api/v1/exams/{examId}`: Chi tiết kỳ thi và câu hỏi (KHÔNG lộ đáp án cho học viên)
- `POST /api/v1/exams/{examId}/attempts`: Bắt đầu lượt làm bài (`preview=true`: xem thử của chủ lớp/nhân sự `EXAM:PREVIEW|EDIT`). Kỳ thi chưa có câu hỏi trả **422** (không tạo lượt nào - R19-05).
- `POST /api/v1/attempts/{attemptId}/submit`: Nộp bài thi (Idempotent, tự động chấm trắc nghiệm)
- `GET /api/v1/attempts/{attemptId}/result`: Xem kết quả bài thi. **Lượt xem thử (R19-04):** chỉ người đã chạy nó đọc được, và chỉ khi còn quyền xem thử; nếu người đó không xem được đáp án (không phải chủ lớp / không có `EXAM:EDIT` tường minh) thì `submit`, `result`, `my-attempts`, `grade`, `cancel` trả bài "như đã nộp": `score=null`, `pointsAwarded=null`, `totalPoints=0`, `status=SUBMITTED`, `resultHidden=true` và `notice="Chế độ xem thử: không hiển thị điểm/đáp án cho quyền của bạn"`.
- `POST /api/v1/attempts/{attemptId}/grade`: Giáo viên chấm bài tự luận & ghi nhận điểm

---

## 7. Bảng Xếp Hạng & Điểm Thưởng (Leaderboard & Ranking)

- `GET /api/v1/classes/{classId}/leaderboard`: Lấy bảng xếp hạng điểm tích lũy và danh hiệu
- `POST /api/v1/classes/{classId}/leaderboard/rebuild`: Tái tạo lại toàn bộ bảng xếp hạng

---

## 8. Cửa hàng & Thanh toán (Commerce & Payment)

- `GET /api/v1/classes/{classId}/products`: Danh sách gói dịch vụ mở bán
- `POST /api/v1/classes/{classId}/products`: Tạo sản phẩm mới
- `POST /api/v1/orders`: Tạo đơn hàng (Body: `classId`, `productId`, `inviteCode?` — chỉ cho gói truy cập lớp riêng tư, mục 2.1; **khóa idempotency bắt buộc**, gửi bằng header `Idempotency-Key`, bằng trường body `idempotencyKey`, hoặc cả hai — nếu gửi cả hai phải trùng nhau, khác nhau → 400; thiếu cả hai → 400. R20-12)
- `GET /api/v1/orders/{id}`: Xem chi tiết đơn hàng
- `POST /api/v1/payments/{provider}/webhook`: Nhận callback thanh toán (Xác thực chữ ký `X-Signature`, idempotent)
- `POST /api/v1/payments/mock/simulate`: Sandbox mô phỏng webhook (`PAYMENT_SUCCESS`, `PAYMENT_FAILED`, `PAYMENT_REFUNDED`)

---

## 9. Tệp & Media (Object storage)

- `POST /api/v1/classes/{classId}/media/upload-intents`: Xin URL ký sẵn để `PUT` tệp thẳng lên kho tệp (Body: `filename`, `mimeType`, `sizeBytes`, `purpose`, `scopeCourseId?`)
- `POST /api/v1/media/{id}/complete`: Xác nhận tải lên (kiểm tra kích thước/kiểu/magic bytes rồi chuyển sang `UPLOADED`); gọi lại an toàn (idempotent)
- `GET /api/v1/media/{id}/download-url`: URL tải ký sẵn, sống ngắn
- `GET /api/v1/media/{id}/download`: Tải qua proxy backend (hỗ trợ `Range`)

**Kho tệp không với tới được (R20-12).** Khi MinIO ngừng hoạt động / quá thời gian / trả 5xx, `complete`, `download-url` và `download` trả **503 `SERVICE_UNAVAILABLE`** (khung lỗi chuẩn, thông báo tiếng Việt) kèm `Retry-After: 5` — thao tác *có thể thử lại* (yêu cầu `complete` thất bại được hoàn về `PENDING`). Đối tượng thật sự không tồn tại vẫn là 400 ("tệp chưa được tải lên") / 404. `PUT` tới URL ký sẵn đi thẳng tới MinIO nên lỗi kết nối xuất hiện ở trình duyệt (`fetch` bị từ chối), không qua khung lỗi của API; giao diện Studio hiển thị thông báo thử lại tương ứng.

## Round 23: About, phụ đề và quyền dữ liệu

PUT /classes/{classId}/about nhận contentMarkdown, rulesMarkdown, publishedVersion và sections (tối đa 12: title, contentMarkdown, imageUrl HTTPS hoặc mediaAssetId ABOUT, imageAlt). Phiên bản cũ trả 409; ảnh upload có URL ký ngắn hạn và theo quyền thấy lớp. GET có private/no-store. Upload ABOUT yêu cầu ABOUT:EDIT, JPG/PNG/WebP/GIF tối đa 5 MB.

Lesson tạo/sửa có captionsVtt WebVTT tối đa 1 triệu ký tự. contentText là bản chép lời/mô tả cho VIDEO; phụ đề chỉ có trong DTO mà người dùng có quyền học.

POST /privacy/me/export?offset=0 và POST /privacy/me/deletion-requests yêu cầu password hiện tại; GET /privacy/me/requests chỉ của chính mình. PLATFORM_ADMIN: GET /privacy/requests, PUT /privacy/requests/{userId} với status ON_HOLD/REJECTED/COMPLETED và resolution. Export không chứa credential, đáp án/snapshot hay điểm chưa công bố. Xem DATA_POLICY.md về phạm vi đóng tài khoản và hồ sơ cần giữ.
