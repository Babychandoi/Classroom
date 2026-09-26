# API Documentation — Hợp đồng API dự thảo

**Dự án:** Nền tảng lớp học trực tuyến · **Phiên bản:** 0.1 · **Trạng thái:** BẢN NHÁP · **Ngày:** 23/09/2026

> Tài liệu thiết kế theo thông tin chủ sản phẩm đã cung cấp. Các mục `[CẦN CHỐT]`, `[ĐIỀN KHI THỰC HIỆN]` chưa được xác nhận. Bản nháp không phải biên bản đã ký, kết quả kiểm thử hay cam kết dịch vụ.

## 1. Quy ước
Prefix `/api/v1`; JSON UTF-8; UUID/ULID ID; phân trang `page,size,sort`; UTC ISO-8601; `Idempotency-Key` cho tạo đơn/submit; `requestId` trong response lỗi. Auth qua phiên/cookie HTTP-only hoặc token `[CẦN CHỐT]`; OpenAPI được sinh từ code sau implementation. Ví dụ lỗi: `{ "code":"COURSE_ACCESS_REQUIRED", "message":"Cần mua khóa học", "requestId":"..." }`. Trả 401 chưa đăng nhập, 403 không đủ quyền, 404 resource ngoài phạm vi hoặc không tồn tại, 409 conflict, 422 vi phạm nghiệp vụ.

## 2. Bản đồ endpoint
| Nhóm | Endpoint dự kiến | Quyền/ghi chú |
|---|---|---|
| Auth | `POST /auth/login`, `POST /auth/logout`, `GET /me` | hạn tốc độ; không trả hash |
| Lớp | `GET /classes/{id}`, `GET /classes/{id}/members`, `POST /classes` | quản trị lớp qua owner/staff |
| Nhân sự | `GET/PUT /classes/{id}/staff/{userId}/permissions` | chỉ OWNER; audit |
| Bảng tin | `GET/POST /classes/{id}/posts`, `POST /posts/{id}/comments` | policy nội dung |
| Học | `GET /classes/{id}/courses`, `GET /courses/{id}/lessons/{lessonId}`, `PUT /lessons/{id}/progress` | khóa mua riêng cần entitlement |
| Hỏi đáp | `GET/POST /lessons/{id}/questions` | quyền học bài |
| Tệp | `POST /media/upload-intents`, `POST /media/{id}/complete`, `GET /media/{id}/download-url` | ký URL sau auth |
| Thi | `GET /classes/{id}/exams`, `POST /exams/{id}/attempts`, `PUT /attempts/{id}/answers`, `POST /attempts/{id}/submit` | kiểm audience và lượt |
| Kết quả | `GET /attempts/{id}/result`, `GET /classes/{id}/leaderboard` | chỉ công bố/đúng quyền |
| Segment | `GET/POST /classes/{id}/segments`, `POST /segments/{id}/preview` | chỉ staff được cấp |
| Bán hàng | `GET /classes/{id}/products`, `POST /orders`, `GET /orders/{id}`, `POST /payments/{provider}/webhook` | webhook verify signature |
| Profile | `GET /classes/{id}/members/{userId}/profile` | projection theo người xem |

## 3. Ví dụ nghiệp vụ
`POST /exams/{id}/attempts` → `{ "attemptId":"...", "endsAt":"...", "questionSetVersion":3 }`; không trả `correctAnswer`. `POST /orders` nhận `{ "classId":"...", "productId":"..." }`; giá/hạn lấy từ server và lưu snapshot, không nhận giá do client gửi. `GET /courses/{id}/access` trả `{ "allowed":false, "reason":"PRODUCT_REQUIRED", "expiresAt":null }`; phản hồi này phục vụ UX, mọi API bài vẫn phải kiểm quyền.

## 4. Còn phải đặc tả trước implement
Request/response schema chi tiết từng endpoint, pagination chuẩn, auth cookie/token/CSRF, rate limit, webhook provider, cơ chế tải video/range, cách cập nhật attempt đồng thời, version API và mã lỗi đầy đủ `[CẦN CHỐT]`. Dùng OpenAPI làm hợp đồng có thể kiểm thử sau khi chốt.
