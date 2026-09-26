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

---

## 2. Lớp học & Thành viên (Classroom & Members)

- `GET /api/v1/classes`: Danh sách tất cả lớp học
- `POST /api/v1/classes`: Tạo lớp học mới (Body: `title`, `slug`, `description`)
- `GET /api/v1/classes/{id}`: Chi tiết lớp học theo ID
- `GET /api/v1/classes/slug/{slug}`: Chi tiết lớp học theo slug
- `POST /api/v1/classes/{id}/join`: Tham gia lớp học
- `GET /api/v1/classes/{id}/members`: Danh sách thành viên lớp học
- `GET /api/v1/classes/{id}/about`: Lấy nội dung giới thiệu & nội quy
- `PUT /api/v1/classes/{id}/about`: Cập nhật trang giới thiệu (OWNER/STAFF)

---

## 3. Nhân sự & Phân quyền Studio (Staff & Permissions)

- `GET /api/v1/classes/{classId}/staff`: Danh sách trợ giảng và phân quyền
- `PUT /api/v1/classes/{classId}/staff/{userId}/permissions`: Cấp quyền chi tiết (Chỉ OWNER)
- `DELETE /api/v1/classes/{classId}/staff/{userId}`: Thu hồi quyền trợ giảng (Chỉ OWNER)

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
- `POST /api/v1/exams/{examId}/attempts`: Bắt đầu lượt làm bài
- `POST /api/v1/attempts/{attemptId}/submit`: Nộp bài thi (Idempotent, tự động chấm trắc nghiệm)
- `GET /api/v1/attempts/{attemptId}/result`: Xem kết quả bài thi
- `POST /api/v1/attempts/{attemptId}/grade`: Giáo viên chấm bài tự luận & ghi nhận điểm

---

## 7. Bảng Xếp Hạng & Điểm Thưởng (Leaderboard & Ranking)

- `GET /api/v1/classes/{classId}/leaderboard`: Lấy bảng xếp hạng điểm tích lũy và danh hiệu
- `POST /api/v1/classes/{classId}/leaderboard/rebuild`: Tái tạo lại toàn bộ bảng xếp hạng

---

## 8. Cửa hàng & Thanh toán (Commerce & Payment)

- `GET /api/v1/classes/{classId}/products`: Danh sách gói dịch vụ mở bán
- `POST /api/v1/classes/{classId}/products`: Tạo sản phẩm mới
- `POST /api/v1/orders`: Tạo đơn hàng (Body: `classId`, `productId`, `idempotencyKey`)
- `GET /api/v1/orders/{id}`: Xem chi tiết đơn hàng
- `POST /api/v1/payments/{provider}/webhook`: Nhận callback thanh toán (Xác thực chữ ký `X-Signature`, idempotent)
- `POST /api/v1/payments/mock/simulate`: Sandbox mô phỏng webhook (`PAYMENT_SUCCESS`, `PAYMENT_FAILED`, `PAYMENT_REFUNDED`)
