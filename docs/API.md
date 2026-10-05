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

- `GET /api/v1/classes?page=0&size=50`: Danh sách lớp học người gọi được phép thấy, mới nhất trước. **Lớp `PRIVATE` không bao giờ có mặt** với khách/người ngoài (D-19, mục 2.1). **Luôn phân trang (R16-08):** mặc định `size=50`, tối đa 100; trang ngắn hơn `size` là trang cuối. Mỗi lớp kèm `isMember`, `userRole` và `memberState`. **D-27:** tham số tùy chọn `q` (tìm không phân biệt hoa thường trong tên/mô tả, cắt khoảng trắng, tối đa 100 ký tự; `%`/`_` gõ vào là chữ thường, không phải ký tự đại diện) và `sort=newest|popular` (mặc định `newest` như cũ; `popular` = số thành viên ACTIVE hiện tại giảm dần). `page`/`size` giữ nguyên. Sai định dạng → 400. Tìm kiếm không bao giờ làm lộ lớp người gọi không được thấy. **D-28:** thêm `category=<một giá trị trong danh sách>` (khớp chính xác, sai → 400), kết hợp được với `q`/`sort`.
- `GET /api/v1/classes/categories` (công khai, D-28): `string[]` danh mục cố định theo thứ tự hiển thị.
- `POST /api/v1/classes`: Tạo lớp học mới (Body: `title`, `slug?`, `description`, `visibility?`, `category?`, `requireApproval?`, `coverPosition?`, `avatarPosition?`) — mục 2.2 (D-28)
- `PUT /api/v1/classes/{id}`: Cập nhật cài đặt lớp (`CLASS:EDIT`; Body: `title`, `description`, `coverImageUrl`, `visibility?`, `coverMediaId?`). **D-27 `coverMediaId`:** vắng/`null` = giữ nguyên, chuỗi rỗng = bỏ ảnh bìa, còn lại phải là ảnh `UPLOADED` của chính lớp với purpose `CLASS_COVER` (sai → 400).
- **`ClassroomDto` (D-27) có thêm:** `coverUrl` (URL ký sẵn ngắn hạn của ảnh bìa đã tải, hoặc `null`; chỉ sinh cho người đã qua kiểm tra quyền thấy lớp), `coverMediaId`, `ownerAvatarUrl`, `upcomingEventCount` (số sự kiện `SCHEDULED` chưa kết thúc). Vẫn giữ `coverImageUrl`; giao diện hiển thị `coverUrl ?? coverImageUrl ?? ô màu`.
- **`ClassroomDto.status` (D-29)** có thêm giá trị `SUSPENDED` (quản trị nền tảng tạm khóa; chỉ chủ lớp còn thấy lớp, chỉ đọc) và hai trường `suspendedReason`, `suspendedAt` chỉ có giá trị cho chủ lớp của lớp đang tạm khóa (còn lại `null`). `PUT /classes/{id}/status` trên lớp tạm khóa → 409. Xem mục 12.
- `PUT /api/v1/classes/{id}/access`: Đổi hình thức thu phí `FREE`/`PAID` và giá (chủ lớp, hoặc `STORE:EDIT` + `CLASS:EDIT`) — mục 2.1
- `POST|GET /api/v1/classes/{id}/invites`, `DELETE /api/v1/classes/{id}/invites/{inviteId}`, `GET /api/v1/classes/invites/{code}` (công khai), `POST /api/v1/classes/invites/{code}/join`: mã mời — mục 2.1
- `GET /api/v1/classes/{id}`: Chi tiết lớp học theo ID
- `GET /api/v1/classes/slug/{slug}`: Chi tiết lớp học theo slug. **`memberState` (R16-01)** = trạng thái thành viên của người gọi: `ACTIVE` (hoặc chủ lớp), `REMOVED` (bị xóa, được tự tham gia lại), `BLOCKED` (bị chặn, chỉ Studio mở khóa được) hoặc `NONE` (chưa từng tham gia / khách). `isMember=true` và `userRole=STUDENT/STAFF` chỉ khi `ACTIVE`; `REMOVED`/`BLOCKED` có `isMember=false`, `userRole=GUEST`.
- `POST /api/v1/classes/{id}/join`: Tham gia lớp học (thành viên `REMOVED` được kích hoạt lại; `BLOCKED` bị từ chối 403). **D-19:** lớp `PAID` → 402 `PAYMENT_REQUIRED`, lớp `PRIVATE` → 404 (người không thấy được) hoặc 403 `INVITE_REQUIRED` — bảng ở mục 2.1
- `GET /api/v1/classes/{id}/members`: Danh sách thành viên lớp học
- `GET /api/v1/classes/{id}/about`: Lấy nội dung giới thiệu & nội quy
- `PUT /api/v1/classes/{id}/about`: Cập nhật trang giới thiệu (OWNER/STAFF)

### 2.2 Trang "Tạo lớp học": danh mục, ảnh đại diện, vị trí ảnh, duyệt thành viên (D-28)

**Trường mới của lớp** (cột trong `classrooms`, V46):

| Trường DTO | Quy tắc |
|---|---|
| `category` | Một trong danh sách cố định (khớp chính xác, sai → 400): `Nấu ăn`, `Ăn chay`, `Sức khoẻ`, `Chạy bộ`, `Thể hình`, `YouTube`, `Kinh doanh`, `Tiếng Anh`, `Ôn thi`, `AI`, `Âm nhạc`, `Phát triển bản thân`. `null` với lớp cũ. |
| `avatarMediaId` / `avatarUrl` | Ảnh đại diện vuông. Purpose media `CLASS_AVATAR` (chỉ ảnh, ≤ 5 MB, cần `CLASS:EDIT` — như `CLASS_COVER`). `avatarUrl` là URL ký sẵn ngắn hạn, chỉ sinh sau khi qua kiểm tra quyền thấy lớp, lấy theo lô trong danh sách (như `coverUrl`). |
| `coverPosition`, `avatarPosition` | CSS `object-position` dạng `"X% Y%"`, X/Y từ 0 đến 100 (ví dụ `"50% 30%"`); sai → 400; `null` = giữa. |
| `requireApproval` | Duyệt từng người trước khi vào (mặc định `false`). |
| `pendingRequestCount` | Số yêu cầu `PENDING`; chỉ điền cho người có `MEMBER:VIEW` (chủ lớp, nhân sự được cấp), người khác luôn `0`. |

**Tạo / sửa.**
- `POST /classes`: `slug` **tùy chọn**. Vắng hoặc toàn khoảng trắng → máy chủ sinh từ tên lớp: bỏ dấu tiếng Việt (đ→d), chữ thường, ký tự ngoài `[a-z0-9]` thành `-`, gộp/cắt `-` hai đầu, tối đa 60 ký tự, tối thiểu 3 (thêm tiền tố `lop-`; tên rỗng → `lop`); trùng thì thêm `-2` … `-6`, sau đó hậu tố ngẫu nhiên 6 ký tự. Slug gửi lên vẫn theo quy tắc cũ (chữ thường/số/`-`, 3..100 ký tự → 400; trùng → 409). Thêm `category?`, `requireApproval?`, `coverPosition?`, `avatarPosition?`. Ảnh bìa/ảnh đại diện không gửi được khi tạo (luồng media cần id lớp): client tạo lớp, tải ảnh, rồi gọi `PUT`.
- `PUT /classes/{id}` (`CLASS:EDIT`) nhận thêm `category`, `requireApproval`, `coverPosition`, `avatarPosition`, `avatarMediaId`. Trường vắng/`null` = giữ nguyên; `""` = xóa (`category`, vị trí ảnh, `avatarMediaId`). Đổi `requireApproval` được ghi audit `CLASS_APPROVAL_SETTING`. Tắt duyệt KHÔNG tự duyệt các yêu cầu đang chờ (vẫn `PENDING` tới khi Studio xử lý; người đó gọi lại `join` thì vào ngay).
- Lớp trả phí vẫn dùng `PUT /classes/{id}/access` (`{accessType:"PAID", price, currency:"VND", durationDays:30}` cho "Phí mỗi tháng").

**Duyệt thành viên (`requireApproval`).**
- Trạng thái thành viên mới `PENDING`: **không** phải thành viên (`isMember=false`, `userRole=GUEST`); lớp hiện ra đúng như với người ngoài. Lớp `PRIVATE` vẫn 404 với người có hàng `PENDING` — duyệt không bao giờ làm lộ lớp riêng tư (lớp riêng tư vẫn vào bằng mã mời). `ClassroomDto.memberState` có thể là `PENDING`.
- `POST /classes/{id}/join` trên lớp `PUBLIC` + `FREE` có `requireApproval=true`: tạo (hoặc chuyển hàng `REMOVED`/`EXPIRED` thành) `PENDING`, trả lớp với `memberState:"PENDING"` (200). Gọi lại khi đang chờ: idempotent. `BLOCKED` vẫn 403. Lớp `PAID` vẫn 402, lớp `PRIVATE` vẫn 404/`INVITE_REQUIRED` như cũ.
- Bỏ qua bước duyệt: tham gia bằng mã mời hợp lệ, và mua quyền truy cập (thanh toán xong → `ACTIVE`). Hàng `PENDING` khi đó thành `ACTIVE`.
- `DELETE /classes/{id}/join-request` (đã đăng nhập): rút yêu cầu `PENDING` của chính mình (xóa hàng → `memberState:"NONE"`, gửi lại được). Idempotent. Trả `ClassroomDto`; nếu người gọi có yêu cầu nhưng nay không còn thấy lớp thì vẫn rút được và `data` vắng mặt (không trả dữ liệu lớp). Không có yêu cầu và không thấy lớp → 404 (lớp riêng tư) / 403 như `GET`.
- Studio:
  - `GET /classes/{classId}/studio/members?state=PENDING` (`MEMBER:VIEW`): danh sách yêu cầu; mỗi dòng có `requestedAt` (chỉ dòng `PENDING` có giá trị). Bộ lọc `ALL` cũng gồm dòng `PENDING`.
  - `POST /classes/{classId}/studio/members/{userId}/approve` (`MEMBER:EDIT`) → `ACTIVE` (không hạn), phát sự kiện outbox `MEMBER_JOINED` như tham gia thường, audit `MEMBER_APPROVE`; trả `ClassMemberDto`. Không có yêu cầu → 404; hàng không ở `PENDING` → 400; lớp không còn `ACTIVE` hoặc đã chuyển `PAID` → 409.
  - `POST /classes/{classId}/studio/members/{userId}/reject` (`MEMBER:EDIT`) → xóa hàng, audit `MEMBER_REJECT`; người đó gửi lại được sau. Trả `ClassMemberDto` của yêu cầu vừa từ chối (state `PENDING`).
  - `unblock` một hàng `PENDING` → 400 (dùng approve/reject); `remove`/`block` vẫn chỉ cho hàng `ACTIVE`/`EXPIRED`.

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
- `PUT /api/v1/classes/{classId}/staff/{userId}/permissions`: Cấp quyền chi tiết (Chỉ OWNER). **D-27:** thêm module `BLOG` (`VIEW`, `CREATE`, `EDIT`, `PUBLISH`, `DELETE`) và `EVENT` (`VIEW`, `CREATE`, `EDIT`, `DELETE`); wildcard `*` vẫn áp dụng như các module khác. Hai module này chỉ cấp theo phạm vi toàn lớp (không theo khóa học).
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
- `GET /api/v1/classes/{classId}/leaderboard/tiers`: Các bậc xếp hạng của lớp, `minPoints` tăng dần. Cùng quyền với `GET .../leaderboard`: thành viên ACTIVE, chủ lớp, nhân sự; khách 401, người ngoài / thành viên `REMOVED` của lớp công khai 403, lớp `PRIVATE` không quan hệ 404 (như id không tồn tại); thành viên hết hạn 403 `MEMBERSHIP_EXPIRED`. Phản hồi `[{ name, tierName, minPoints, badgeUrl, description }]`: `name` là trường chính, `tierName` = `name` (cùng tên trường với `tiers` của `GET .../leaderboard/configuration`), `badgeUrl`/`description` có thể `null`. Lớp chưa cấu hình bậc → `[]`.
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
- **Purpose ảnh (D-22, D-27, D-28):** `ABOUT` (`ABOUT:EDIT`), `CLASS_COVER` và `CLASS_AVATAR` (`CLASS:EDIT`), `BLOG` (`BLOG:CREATE` hoặc `BLOG:EDIT` hoặc `MEDIA:CREATE`), `EVENT` (`EVENT:CREATE` hoặc `EVENT:EDIT` hoặc `MEDIA:CREATE`) — chỉ JPG/PNG/WebP/GIF, tối đa 5 MB (sai → 400). Quyền được kiểm tra lại khi `complete`.

**Kho tệp không với tới được (R20-12).** Khi MinIO ngừng hoạt động / quá thời gian / trả 5xx, `complete`, `download-url` và `download` trả **503 `SERVICE_UNAVAILABLE`** (khung lỗi chuẩn, thông báo tiếng Việt) kèm `Retry-After: 5` — thao tác *có thể thử lại* (yêu cầu `complete` thất bại được hoàn về `PENDING`). Đối tượng thật sự không tồn tại vẫn là 400 ("tệp chưa được tải lên") / 404. `PUT` tới URL ký sẵn đi thẳng tới MinIO nên lỗi kết nối xuất hiện ở trình duyệt (`fetch` bị từ chối), không qua khung lỗi của API; giao diện Studio hiển thị thông báo thử lại tương ứng.

---

## 10. Blog (D-27)

Mọi endpoint dưới `/api/v1`, khung phản hồi chuẩn. Thời gian là ISO-8601 UTC. "Người xem lớp" = người `AccessPolicy` cho phép thấy lớp (kể cả khách với lớp `PUBLIC` + `ACTIVE`). Lớp `PRIVATE` mà người gọi không có quan hệ → **404** ở mọi endpoint, giống hệt một id không tồn tại. Lớp không thấy được nhưng không riêng tư (nháp/lưu trữ) → 401 cho khách, 403 cho người đã đăng nhập. Lớp `ARCHIVED` chỉ đọc: không tạo bài mới, không xuất bản (409 `CONFLICT`).

```ts
interface PersonSummary { id: string; fullName: string; avatarUrl?: string | null }
type BlogAudience = 'PUBLIC' | 'MEMBERS';   // PUBLIC: mọi người xem lớp; MEMBERS: thành viên ACTIVE (+ chủ lớp / nhân sự có quyền BLOG)
type BlogStatus = 'DRAFT' | 'PUBLISHED';
interface BlogPost {
  id: string; classId: string;
  title: string;                   // 1..200
  excerpt?: string | null;         // <= 300
  category?: string | null;        // <= 60 ("Chuyên mục")
  contentMarkdown?: string | null; // <= 100000; null trong danh sách VÀ khi locked
  coverMediaId?: string | null;
  coverUrl?: string | null;        // URL ký sẵn ngắn hạn (sinh theo từng phản hồi) hoặc null
  audience: BlogAudience; status: BlogStatus;
  locked: boolean;                 // thấy thẻ nhưng không thấy nội dung (bài MEMBERS, người không phải thành viên)
  readingMinutes: number;          // ceil(số từ / 200), tối thiểu 1, máy chủ tính từ contentMarkdown
  author: PersonSummary;
  publishedAt?: string | null; createdAt: string; updatedAt: string;
}
interface BlogPostPage { items: BlogPost[]; nextCursor?: string | null }
```

| Phương thức + đường dẫn | Ai | Ghi chú |
|---|---|---|
| `GET /classes/{classId}/blog-posts?category&cursor&size&status` | người xem lớp (khách được với lớp công khai) | Chỉ `PUBLISHED`, `publishedAt` mới nhất trước, phân trang keyset (`cursor` mờ), `size` mặc định 12, tối đa 50. Người có `BLOG:VIEW/CREATE/EDIT/PUBLISH` (hoặc `DELETE`) truyền `status=DRAFT` hoặc `status=ALL` để xem cả nháp (người khác → 403 `STAFF_PERMISSION_DENIED`). Bài `MEMBERS` vẫn trả về cho người ngoài với `locked=true`. Cursor sai → 400. |
| `GET /classes/{classId}/blog-categories` | người xem lớp | `string[]` các chuyên mục khác nhau của bài đã xuất bản |
| `GET /blog-posts/{id}` | người xem lớp | `DRAFT` → 404 trừ khi người gọi có một quyền `BLOG`. `MEMBERS` + không phải thành viên → 200, `locked=true`, `contentMarkdown=null`. |
| `POST /classes/{classId}/blog-posts` | `BLOG:CREATE` | Body `{title, excerpt?, category?, contentMarkdown?, coverMediaId?, audience}`; luôn tạo `DRAFT`; tác giả = người gọi |
| `PUT /blog-posts/{id}` | `BLOG:EDIT` | Cùng body, mọi trường tùy chọn. Trường **vắng** = giữ nguyên; `excerpt`/`category`/`coverMediaId` gửi `null` hoặc `""` = xóa. Bài đã xuất bản không được làm rỗng nội dung (400). |
| `POST /blog-posts/{id}/publish` | `BLOG:PUBLISH` | `PUBLISHED`; `publishedAt` = lúc xuất bản **lần đầu** (giữ nguyên khi gỡ rồi xuất bản lại); nội dung rỗng → 400 |
| `POST /blog-posts/{id}/unpublish` | `BLOG:PUBLISH` | về `DRAFT` |
| `DELETE /blog-posts/{id}` | `BLOG:DELETE` | xóa hẳn |

Ảnh bìa: tải qua luồng media với `purpose: "BLOG"`; `coverMediaId` phải là ảnh `UPLOADED` của cùng lớp, purpose `BLOG` (sai → 400). Ghi nhật ký audit `BLOG_POST_CREATE/UPDATE/PUBLISH/UNPUBLISH/DELETE` (đối tượng `BLOG_POST`).

---

## 11. Sự kiện (D-27)

Cùng quy tắc nhìn lớp như mục 10.

```ts
type EventFormat = 'ONLINE' | 'OFFLINE';
type EventStatus = 'SCHEDULED' | 'CANCELLED';
interface ClassEvent {
  id: string; classId: string;
  classTitle?: string; classSlug?: string; // chỉ có ở GET /events/upcoming
  title: string;                 // 1..200
  description?: string | null;   // <= 20000
  forWhom?: string | null;       // "Dành cho ai" <= 500
  takeaways: string[];           // "Mang về gì", <= 8 ý, mỗi ý <= 200
  format: EventFormat;
  location?: string | null;      // địa chỉ (OFFLINE) / tên nền tảng (ONLINE) <= 300
  meetingUrl?: string | null;    // CHỈ trả cho người quản lý (EVENT:VIEW/EDIT, chủ lớp) và người đã đăng ký mà HIỆN VẪN đủ điều kiện tham gia (thành viên ACTIVE với sự kiện MEMBERS / lớp PAID / PRIVATE; chưa bị BLOCKED với sự kiện mở); còn lại null
  startsAt: string; endsAt: string; // endsAt > startsAt, kéo dài <= 7 ngày
  capacity?: number | null;      // null = không giới hạn; 1..100000
  registeredCount: number; isRegistered: boolean; isFull: boolean;
  host: PersonSummary;           // mặc định người tạo; có thể là chủ lớp hoặc nhân sự ACTIVE (hostUserId)
  coverMediaId?: string | null; coverUrl?: string | null; // ảnh purpose "EVENT"
  audience: 'PUBLIC' | 'MEMBERS'; // MEMBERS: chỉ thành viên đăng ký được (người khác vẫn thấy thẻ)
  status: EventStatus; createdAt: string;
}
interface EventRegistrant { user: PersonSummary; registeredAt: string }
```

| Phương thức + đường dẫn | Ai | Ghi chú |
|---|---|---|
| `GET /classes/{classId}/events?scope=upcoming\|past\|all` | người xem lớp (khách được với lớp công khai) | `upcoming` (mặc định) = `endsAt >= now`, `startsAt` tăng dần; `past` = `endsAt < now`, giảm dần; `all` giảm dần. Gồm cả `CANCELLED` (giao diện hiện nhãn). Tối đa 100 dòng. |
| `GET /events/{id}` | người xem lớp | |
| `GET /events/upcoming?size` | công khai | Sự kiện `SCHEDULED` sắp tới của lớp `PUBLIC` + `ACTIVE`, `startsAt` tăng dần, `size` mặc định 6, tối đa 20, có `classTitle`/`classSlug`; `meetingUrl` luôn `null` |
| `POST /classes/{classId}/events` | `EVENT:CREATE` | Body `{title, description?, forWhom?, takeaways?, format, location?, meetingUrl?, startsAt, endsAt, capacity?, hostUserId?, coverMediaId?, audience}`; `meetingUrl` phải là http(s) |
| `PUT /events/{id}` | `EVENT:EDIT` | Cùng body, mọi trường tùy chọn. Trường vắng = giữ nguyên; với `description/forWhom/takeaways/location/meetingUrl/capacity/coverMediaId`, `null` (hoặc `""`) = xóa — `capacity: null` = không giới hạn. Giảm `capacity` dưới `registeredCount` → 409. |
| `POST /events/{id}/cancel` | `EVENT:EDIT` | `CANCELLED`; giữ các lượt đăng ký |
| `DELETE /events/{id}` | `EVENT:DELETE` | xóa sự kiện và các lượt đăng ký |
| `POST /events/{id}/registrations` | đã đăng nhập; thành viên ACTIVE với sự kiện `MEMBERS` và với mọi sự kiện của lớp `PAID`/`PRIVATE`; sự kiện `PUBLIC` của lớp `PUBLIC` miễn phí: bất kỳ ai đăng nhập và thấy được lớp (trừ người bị `BLOCKED`) | Idempotent (đã đăng ký → 200 cùng payload) — nhưng điều kiện tham gia được kiểm tra TRƯỚC: người đã đăng ký nay mất điều kiện (hết hạn, bị gỡ, bị chặn) nhận 403, không nhận lại `meetingUrl`. Hết chỗ → 409 `CONFLICT` "Sự kiện đã đủ chỗ" (kiểm tra dưới khóa dòng sự kiện); đã hủy, đã kết thúc hoặc lớp lưu trữ → 409. Trả về `ClassEvent` mới. Thành viên hết hạn → 403 `MEMBERSHIP_EXPIRED`. |
| `DELETE /events/{id}/registrations/me` | đã đăng nhập | Idempotent. **Có lượt đăng ký:** luôn hủy (kể cả khi lớp nay đã ẩn với người gọi) rồi trả `ClassEvent` mới nếu người gọi còn thấy lớp, ngược lại chỉ trả `{ id, isRegistered: false }`. **Không có lượt đăng ký:** áp quy tắc nhìn lớp như `GET /events/{id}` (404 lớp riêng tư ẩn / id lạ, 401/403 lớp nháp/lưu trữ) rồi trả `ClassEvent`. |
| `GET /events/{id}/registrations` | `EVENT:VIEW` hoặc `EVENT:EDIT` | `EventRegistrant[]` theo `registeredAt` |

Lớp `ARCHIVED`: không tạo sự kiện, không đăng ký (409). Audit `EVENT_CREATE/UPDATE/CANCEL/DELETE` (đối tượng `CLASS_EVENT`); đăng ký/hủy đăng ký là thao tác của chính thành viên nên không ghi audit (giống tham gia lớp).

## 12. Quản trị nền tảng (D-29)

Mọi endpoint dưới `/api/v1/admin/**`, khung phản hồi chuẩn, `Cache-Control: no-store`. Thời gian ISO-8601 UTC.

**Quyền.** Chỉ vai trò nền tảng `PLATFORM_ADMIN`, đọc lại từ MySQL ở mỗi request (D-26): khách → 401 `UNAUTHORIZED`, mọi người khác → 403 `FORBIDDEN`, kể cả khi id không tồn tại. Hai lớp chặn: luật URL trong `SecurityConfig` và `@PreAuthorize("hasRole('PLATFORM_ADMIN')")` trên controller. Hạ quyền có hiệu lực ngay từ request kế tiếp. Quản trị nền tảng **không** phải chủ lớp: các endpoint này không cấp quyền Studio, không trả đáp án, nội dung đề/bài, tin nhắn, bí mật thanh toán hay mật khẩu băm — chỉ metadata và số đếm.

**Ghi.** Mọi thao tác ghi bắt buộc `reason` (1..500 ký tự, thiếu/rỗng/quá dài → 400) và ghi một dòng `audit_events`: action `ADMIN_USER_BAN` / `ADMIN_USER_UNBAN` / `ADMIN_USER_ROLE` (đối tượng `USER`, `class_id = NULL`) hoặc `ADMIN_CLASS_SUSPEND` / `ADMIN_CLASS_RESTORE` (đối tượng `CLASSROOM`, `class_id` = lớp); `details_json` dựng bằng ObjectMapper, luôn có `reason` (cùng trạng thái/vai trò trước–sau). Thao tác ghi trả về dòng mới (`AdminUserRow` / `AdminClassRow`). Không tự khóa/tự hạ quyền chính mình (400); không khóa hoặc hạ quyền quản trị `ACTIVE` cuối cùng (409, kiểm tra khi đang giữ khóa dòng của mọi quản trị `ACTIVE`).

```ts
interface Page<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number }
interface PersonRef { id: string; fullName: string; email: string }
interface AuditRow {
  id: string; createdAt: string; action: string; targetType: string; targetId?: string | null;
  classId?: string | null; classTitle?: string | null;
  actor?: PersonRef | null;
  details: object | null;   // details_json đã parse; dòng cũ không phải JSON hợp lệ trả { raw: "<chuỗi gốc>" }
}
```

### 12.1 Tổng quan — `GET /admin/overview`

```ts
interface AdminOverview {
  users: { total: number; active: number; banned: number; deleted: number; admins: number; newLast7Days: number; newLast30Days: number };
  classes: { total: number; active: number; archived: number; suspended: number; public: number; private: number; paid: number; newLast7Days: number };
  members: { activeMemberships: number; pendingRequests: number };
  content: { courses: number; publishedExams: number; blogPostsPublished: number; upcomingEvents: number };
  commerce: { paidOrdersLast30Days: number; revenueLast30Days: number; currency: 'VND'; pendingOrders: number };
  privacy: { openRequests: number };
  outbox: { pending: number; deadLetter: number };
  signupsByDay: { date: string; count: number }[];   // 30 ngày UTC gần nhất (gồm hôm nay), cũ → mới, yyyy-MM-dd, ngày trống = 0
}
```

Định nghĩa: `admins` = `PLATFORM_ADMIN` đang `ACTIVE`; `activeMemberships` = dòng thành viên `ACTIVE` còn hạn, **không** tính dòng chủ lớp; `pendingRequests` = yêu cầu tham gia `PENDING` (D-28); `publishedExams` = đề ở trạng thái `PUBLISHED`/`OPEN`/`CLOSED`; `upcomingEvents` = sự kiện `SCHEDULED` chưa kết thúc; doanh thu = tổng `total_amount` các đơn `PAID` có `paid_at` trong 30 ngày (đơn `REFUNDED` không còn là `PAID` nên bị loại); `privacy.openRequests` = yêu cầu chưa `COMPLETED`/`REJECTED`; `outbox.pending` = `PENDING`/`PROCESSING`/`FAILED`. Mỗi khối là một câu lệnh COUNT/SUM, không đọc từng dòng.

### 12.2 Người dùng

```ts
interface AdminUserRow {
  id: string; email: string; fullName: string; avatarUrl?: string | null;
  role: 'USER' | 'PLATFORM_ADMIN'; status: 'ACTIVE' | 'BANNED' | 'DELETED';
  createdAt: string;
  ownedClassCount: number;     // mọi lớp người này sở hữu (mọi trạng thái)
  membershipCount: number;     // dòng thành viên ACTIVE còn hạn, không tính dòng chủ lớp
  lastLoginAt?: string | null; // thời điểm phát refresh token gần nhất (đăng nhập hoặc làm mới phiên); null nếu không còn token
}
interface AdminUserDetail extends AdminUserRow {
  ownedClasses: { id: string; slug: string; title: string; status: string }[]; // tối đa 50, mới nhất trước
  recentAudit: AuditRow[];                                                     // 20 dòng gần nhất có actor HOẶC target là người này
}
```

| Phương thức + đường dẫn | Ghi chú |
|---|---|
| `GET /admin/users?q&status&role&sort&page&size` | `q` khớp e-mail hoặc tên (không phân biệt hoa thường, ≤ 100 ký tự, `%`/`_` là chữ thường); `status` `ACTIVE`/`BANNED`/`DELETED`; `role` `USER`/`PLATFORM_ADMIN`; `sort=newest` (mặc định) / `oldest` / `name`; `size` mặc định 20, tối đa 100 (lớn hơn bị kẹp về 100). Giá trị lọc/sắp xếp sai → 400. Số đếm theo lô (3 truy vấn gộp cho cả trang). |
| `GET /admin/users/{id}` | `AdminUserDetail`; id lạ → 404. |
| `POST /admin/users/{id}/ban` body `{reason}` | `ACTIVE → BANNED` và thu hồi **mọi** họ refresh token của người đó trong cùng giao dịch; access token cũ bị từ chối (401) ngay request kế tiếp vì bộ lọc JWT đòi `ACTIVE`. Tự khóa → 400; `DELETED` → 409; đã `BANNED` → 409; quản trị `ACTIVE` cuối cùng → 409. Không đụng tới lớp, đơn hàng hay nội dung của người đó. |
| `POST /admin/users/{id}/unban` body `{reason}` | `BANNED → ACTIVE` (người dùng đăng nhập lại; phiên cũ vẫn đã bị thu hồi). Trạng thái khác → 409. |
| `PUT /admin/users/{id}/role` body `{role, reason}` | `role` = `USER` hoặc `PLATFORM_ADMIN` (khác → 400). Tự đổi vai trò → 400; đã có vai trò đó → 409; cấp `PLATFORM_ADMIN` cho tài khoản không `ACTIVE` → 409; hạ quyền quản trị `ACTIVE` cuối cùng → 409. |

### 12.3 Lớp học

```ts
interface AdminClassRow {
  id: string; slug: string; title: string;
  owner: PersonRef;
  status: 'ACTIVE' | 'ARCHIVED' | 'SUSPENDED';
  visibility: 'PUBLIC' | 'PRIVATE'; accessType: 'FREE' | 'PAID'; category?: string | null;
  memberCount: number;            // cùng quy tắc ClassroomDto.memberCount (dòng ACTIVE còn hạn, gồm dòng chủ lớp)
  pendingRequestCount: number; createdAt: string;
  coverUrl?: string | null; avatarUrl?: string | null;   // URL ký ngắn hạn như nơi khác
  suspendedReason?: string | null; suspendedAt?: string | null;   // chỉ khi SUSPENDED
}
interface AdminClassDetail extends AdminClassRow {
  counts: { courses: number; exams: number; blogPosts: number; events: number; products: number; paidOrders: number };
  recentAudit: AuditRow[];        // 20 dòng gần nhất của lớp
}
```

| Phương thức + đường dẫn | Ghi chú |
|---|---|
| `GET /admin/classes?q&status&visibility&accessType&category&sort&page&size` | **Mọi** lớp, kể cả `PRIVATE`/`ARCHIVED`/`SUSPENDED`. `q` khớp tên, slug hoặc e-mail chủ lớp; `status` `ACTIVE`/`ARCHIVED`/`SUSPENDED`; `category` như D-28; `sort=newest` (mặc định) / `members` / `name`; `size` mặc định 20, tối đa 100. Sai → 400. |
| `GET /admin/classes/{id}` | `AdminClassDetail`, không có nội dung bài/đề. id lạ → 404. |
| `POST /admin/classes/{id}/suspend` body `{reason}` | `ACTIVE`/`ARCHIVED` → `SUSPENDED`; lưu trạng thái cũ vào `status_before_suspend`, `suspended_reason`, `suspended_at` (V47). Đã `SUSPENDED` → 409. |
| `POST /admin/classes/{id}/restore` body `{reason}` | `SUSPENDED` → đúng trạng thái trước đó (`ACTIVE` hoặc `ARCHIVED`), xóa lý do/thời điểm. Lớp không bị tạm khóa → 409. |

**Lớp `SUSPENDED` (D-29) — hợp đồng cho giao diện:**
- **Bị ẩn với mọi người trừ chủ lớp**, kể cả thành viên, nhân sự, người hết hạn: mọi endpoint trả đúng câu trả lời của một lớp không tồn tại (404 như lớp `PRIVATE` ẩn; route Studio/nhân sự trả 403 như với id lạ). Không có trong `GET /classes`, tìm kiếm, `/events/upcoming`. Dòng thành viên/nhân sự được giữ nguyên và hiệu lực trở lại khi khôi phục.
- **Chủ lớp xem chỉ đọc:** `ClassroomDto.status = 'SUSPENDED'` và — **chỉ cho chủ lớp** — `suspendedReason`, `suspendedAt` (giao diện hiện băng "Lớp đang bị tạm khóa bởi quản trị nền tảng: <reason>"). Các trang đọc (thành viên, Studio, hàng chờ chấm, danh sách mã mời...) vẫn mở.
- **Đóng băng mọi ghi:** mọi thao tác ghi của chủ lớp → **409 `CONFLICT`** "Lớp học đang bị tạm khóa bởi quản trị nền tảng; mọi thay đổi đều bị chặn" — gồm `PUT /classes/{id}/status` (chủ lớp không tự mở khóa được), cài đặt, thu phí, mã mời, Studio (khóa học, đề, sản phẩm, blog, sự kiện, thành viên, nhân sự, bảng xếp hạng...), bảng tin (bài, bình luận, sửa, xóa), hỏi đáp/tiến độ/nộp bài, tải tệp lên. Các chỗ đã đóng băng lớp `ARCHIVED` (D-11/D-27: đơn hàng mới 400, lượt thi mới `EXAM_NOT_OPEN`, sự kiện/đăng ký/bài blog/xuất bản 409) nay áp cho mọi lớp không `ACTIVE`, với thông điệp tạm khóa khi lớp `SUSPENDED`; thông điệp với lớp `ARCHIVED` giữ nguyên. Đơn `PENDING` đã tạo trước khi tạm khóa vẫn được webhook xử lý (như D-11, để không mất tiền người mua).

### 12.4 Nhật ký — `GET /admin/audit?action&actorId&classId&from&to&page&size`

`Page<AuditRow>` toàn nền tảng (mọi lớp + thao tác nền tảng), mới nhất trước. `action` khớp chính xác (không phân biệt hoa thường); `from`/`to` là instant ISO-8601 hoặc ngày `yyyy-MM-dd` UTC (`to` dạng ngày = hết ngày đó, loại trừ); sai định dạng hoặc `from ≥ to` → 400. `size` mặc định 50, tối đa 200 (kẹp).

### 12.5 Hàng đợi quyền dữ liệu

Giữ nguyên `GET /privacy/requests` và `PUT /privacy/requests/{userId}` (đã yêu cầu `PLATFORM_ADMIN`); giao diện quản trị chuyển hàng đợi này sang trang riêng. Đóng tài khoản (COMPLETED) coi lớp `SUSPENDED` từ `ACTIVE` như lớp đang hoạt động (phải chuyển quyền/lưu trữ trước).

## 13. Trang "của tôi" (D-30)

Ba endpoint đọc dưới `/api/v1/me/**`, chỉ cho người đã đăng nhập (khách → 401), khung phản hồi chuẩn. Chỉ trả những gì người gọi đã được thấy qua các endpoint thường (cùng luật nhìn lớp D-19/D-29): lớp `SUSPENDED` chỉ hiện với chủ lớp; lớp `PRIVATE` trả cho người thuộc về nó (đây là chỗ họ tìm lại lớp riêng tư). Phân trang `page` (từ 0, âm → 0) và `size` (mặc định 20, tối đa 50, kẹp về khoảng này); trả danh sách phẳng. Số truy vấn cố định, không phụ thuộc số dòng (`MeQueryCountTest`).

### 13.1 `GET /me/classes?page&size`

Lớp người gọi sở hữu, là nhân sự `ACTIVE`, hoặc có thành viên `ACTIVE` / `EXPIRED` / `PENDING` (yêu cầu tham gia đang chờ duyệt vẫn hiện để người gửi theo dõi; `REMOVED` và `BLOCKED` không hiện). Riêng `PENDING` chỉ hiện khi lớp vẫn `ACTIVE` và không `PRIVATE` (yêu cầu chờ không bao giờ làm lộ lớp). Thứ tự: lớp sở hữu trước, rồi theo hàng thành viên mới nhất (`joinedAt` giảm dần), rồi lớp mới nhất. Mỗi phần tử là `ClassroomDto` đầy đủ như `GET /classes` (coverUrl/avatarUrl/category/visibility/accessType/memberState/userRole/accessExpiresAt/pendingRequestCount/upcomingEventCount), dựng theo lô, ảnh ký một lượt.

### 13.2 `GET /me/courses?page&size`

```ts
interface MyCourse {
  id: string; classId: string; classTitle: string; classSlug: string; classAvatarUrl?: string | null;
  title: string; description?: string | null; coverImageUrl?: string | null;
  accessMode: 'FREE' | 'PURCHASE_REQUIRED';
  totalLessons: number; completedLessons: number;   // chỉ bài không lưu trữ (và chương không lưu trữ), như tiến độ trang khóa học
  progressPercent: number;                          // số nguyên 0..100 (làm tròn)
  lastActivityAt?: string | null;                   // lần cập nhật tiến độ bài học gần nhất của người gọi; null nếu chưa học
  nextLessonId?: string | null;                     // bài đầu tiên chưa hoàn thành theo thứ tự giáo trình (chương rồi bài); null khi đã xong hết
  started: boolean;                                 // completedLessons > 0 hoặc có bất kỳ dòng tiến độ nào
}
```

Khóa học `PUBLISHED` mà người gọi học được (`LearningPolicy.canLearn`: miễn phí cho thành viên `ACTIVE` còn hạn / chủ lớp, hoặc có quyền mua còn hiệu lực với khóa trả phí), trong lớp họ là chủ lớp hoặc thành viên `ACTIVE` còn hạn (không `EXPIRED`, không lớp bị tạm khóa trừ chủ lớp). Thứ tự: khóa đã bắt đầu trước (`lastActivityAt` giảm dần), sau đó theo tên lớp (không phân biệt hoa thường) rồi vị trí khóa. Nháp, khóa trả phí chưa mua, lớp mà người gọi chỉ đang chờ duyệt / hết hạn không hiện.

### 13.3 `GET /me/events?scope=upcoming|past|all&page&size`

Sự kiện người gọi đã đăng ký, dạng `ClassEvent` của §11, có `classTitle` / `classSlug`; `isRegistered` luôn `true`. `meetingUrl` chỉ có khi người đó vẫn đủ điều kiện (cùng luật `GET /events/{id}`: thành viên `ACTIVE` với sự kiện MEMBERS và mọi sự kiện lớp PAID/PRIVATE, hoặc chưa bị chặn với sự kiện PUBLIC của lớp công khai; chủ lớp luôn có). Chỉ sự kiện của lớp người gọi còn nhìn thấy. `scope` mặc định `upcoming` (`endsAt >= now`, `startsAt` tăng dần); `past` (`endsAt < now`, `startsAt` giảm dần); `all` = upcoming trước rồi past. Sự kiện `CANCELLED` vẫn có (giao diện gắn nhãn). `scope` khác → 400.

## Round 23: About, phụ đề và quyền dữ liệu

PUT /classes/{classId}/about nhận contentMarkdown, rulesMarkdown, publishedVersion và sections (tối đa 12: title, contentMarkdown, imageUrl HTTPS hoặc mediaAssetId ABOUT, imageAlt). Phiên bản cũ trả 409; ảnh upload có URL ký ngắn hạn và theo quyền thấy lớp. GET có private/no-store. Upload ABOUT yêu cầu ABOUT:EDIT, JPG/PNG/WebP/GIF tối đa 5 MB.

Lesson tạo/sửa có captionsVtt WebVTT tối đa 1 triệu ký tự. contentText là bản chép lời/mô tả cho VIDEO; phụ đề chỉ có trong DTO mà người dùng có quyền học.

POST /privacy/me/export?offset=0 và POST /privacy/me/deletion-requests yêu cầu password hiện tại; GET /privacy/me/requests chỉ của chính mình. PLATFORM_ADMIN: GET /privacy/requests, PUT /privacy/requests/{userId} với status ON_HOLD/REJECTED/COMPLETED và resolution. Export không chứa credential, đáp án/snapshot hay điểm chưa công bố. Xem DATA_POLICY.md về phạm vi đóng tài khoản và hồ sơ cần giữ.

## V43: bản đề khi công bố

Không thêm endpoint. Công bố đề lưu bản người học và bản chấm điểm trong cùng giao dịch; lỗi ghi làm rollback trạng thái công bố. Bắt đầu bài sao chép bản công bố vào lượt thi, DTO chỉ có nội dung được phép thấy. Mọi lần bắt đầu vẫn kiểm tra trạng thái tài khoản, lớp/thành viên, lịch, audience và hạn lượt hiện tại. Xem thử dùng nội dung hiện hành. Đề cũ được chuẩn bị theo batch lúc khởi động; đề không có câu hỏi vẫn bị từ chối bắt đầu. Không xuất bản snapshot chấm điểm qua API.
