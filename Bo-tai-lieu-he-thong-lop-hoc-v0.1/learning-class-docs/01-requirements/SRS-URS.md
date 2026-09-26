# SRS/URS — Đặc tả yêu cầu phần mềm và người dùng

**Dự án:** Nền tảng lớp học trực tuyến · **Phiên bản:** 0.1 · **Trạng thái:** BẢN NHÁP · **Ngày:** 23/09/2026

> Tài liệu thiết kế theo thông tin chủ sản phẩm đã cung cấp. Các mục `[CẦN CHỐT]`, `[ĐIỀN KHI THỰC HIỆN]` chưa được xác nhận. Bản nháp không phải biên bản đã ký, kết quả kiểm thử hay cam kết dịch vụ.

## 1. Vai trò và nguyên tắc
`OWNER` quản lớp mình; `STAFF` có tập permission của lớp và phạm vi khóa được giao; `STUDENT` có trạng thái FREE/PRO tính từ quyền sản phẩm hiệu lực; `PLATFORM_ADMIN` quản trị nền tảng (quyền hệ thống này [CẦN CHỐT]). Người dùng có thể là OWNER của lớp A và học viên của lớp B. API luôn lấy danh tính từ phiên đăng nhập; không tin `userId`, `classId` hay `isPro` do client tự gửi để quyết định quyền.

## 2. Yêu cầu chức năng và điều kiện nghiệm thu
| ID | Chức năng | Tiêu chí chấp nhận tóm tắt |
|---|---|---|
| FR-01 | Tài khoản và lớp | Tạo lớp có OWNER; chỉ thành viên được truy cập vùng nội bộ; một user tham gia nhiều lớp |
| FR-02 | Nhân sự Studio | OWNER cấp/thu hồi VIEW, CREATE, EDIT, PUBLISH, GRADE... theo module; thay đổi hiệu lực ở request kế tiếp |
| FR-03 | Bảng tin | Đăng/sửa/xóa bài và bình luận theo quyền; nội dung hiển thị đúng PUBLIC/FREE/PRO/PRODUCT_OWNER/SEGMENT |
| FR-04 | Khóa học | OWNER/STAFF có quyền tạo khóa → chương → bài; bài hỗ trợ video, tóm tắt, tài liệu, bài tập, hỏi đáp |
| FR-05 | Tiến độ | Ghi hoàn thành bài, hiển thị tiến độ theo khóa; không đánh dấu bài của người khác |
| FR-06 | Tài liệu | Upload và cấp quyền tài liệu; file riêng không lộ URL truy cập vô hạn |
| FR-07 | Sản phẩm và quyền | Mỗi sản phẩm có giá, thời hạn; chỉ đơn đã xác nhận mới phát entitlement; hết hạn/hoàn tiền thu hồi truy cập |
| FR-08 | Hạng PRO | PRO từ entitlement trả phí hiệu lực trong lớp; PRO mở nội dung PRO chung, không mở khóa bán riêng chưa mua |
| FR-09 | Cuộc thi | Cấu hình đề, thời gian, lượt thi, đối tượng ALL/PRO/COURSE/SEGMENT; từ chối người không đủ điều kiện cả khi gọi API trực tiếp |
| FR-10 | Chấm thi | Lưu từng attempt; chấm tự động câu trắc nghiệm; câu tự luận chờ STAFF có quyền GRADE [CẦN CHỐT loại câu hỏi] |
| FR-11 | Xếp hạng | Quy đổi điểm thi theo ngưỡng thành điểm bảng và bậc; chỉ kết quả công bố được tính; tránh cộng trùng attempt |
| FR-12 | Thành viên/profile | Trang thành viên và hành trình học/thi theo quyền riêng tư; giáo viên xem nhiều dữ liệu hơn học viên |
| FR-13 | Giới thiệu | OWNER cấu hình văn bản, hình, tab và nội quy; công bố phiên bản sau khi lưu |
| FR-14 | Studio | Dashboard, nội dung, thi, segment, thành viên, sản phẩm/đơn, cài đặt, audit thao tác quan trọng |

## 3. Quy tắc kiểm quyền bắt buộc
- `canManage(user, class, action, resource)`: OWNER của lớp hoặc STAFF có permission và đúng phạm vi resource.
- `canLearn(user, course)`: OWNER; hoặc khóa miễn phí cho thành viên hợp lệ; hoặc entitlement đúng khóa còn hiệu lực; STAFF xem thử chỉ khi được cấp `COURSE_PREVIEW`.
- `canEnterExam(user, exam, now)`: lớp hợp lệ, trạng thái/publish, trong lịch, còn lượt, đáp ứng audience rule ở thời điểm bắt đầu attempt. OWNER/STAFF chạy thử qua đường preview riêng không vào xếp hạng.
- `canReadProContent(user, class)`: OWNER hoặc học viên PRO của lớp; STAFF theo permission nội dung.
- Điểm xếp hạng tính từ một attempt đã công bố cho mỗi kỳ thi theo chính sách; không tính attempt preview hoặc bị hủy.

## 4. Yêu cầu phi chức năng — mục tiêu dự thảo, cần kiểm chứng tải
| ID | Yêu cầu/đề xuất đo lường |
|---|---|
| NFR-01 | HTTPS; mật khẩu băm; chống truy cập chéo lớp và IDOR; phân quyền backend; audit thay đổi quyền, điểm, đơn |
| NFR-02 | 95% API đọc thông thường dưới 500 ms ở 100 phiên đồng thời [CẦN CHỐT tải thực]; loại trừ video CDN/object storage |
| NFR-03 | Hoạt động phù hợp mobile/web, hỗ trợ bàn phím, thông báo lỗi rõ, đáp ứng WCAG 2.1 AA ở luồng chính [CẦN CHỐT] |
| NFR-04 | Không mất đơn/quyền khi webhook lặp; idempotency; backup và diễn tập khôi phục |
| NFR-05 | Không giữ URL MinIO dài hạn trong DB; URL ký hạn ngắn sau khi kiểm entitlement |
| NFR-06 | Dữ liệu cá nhân và lịch sử thi tuân chính sách lưu/xóa [CẦN CHỐT pháp lý, thời hạn] |

## 5. Trạng thái và tình huống biên
Product: DRAFT → PUBLISHED → ARCHIVED. Order: PENDING → PAID / FAILED / CANCELLED / REFUNDED. Entitlement: SCHEDULED → ACTIVE → EXPIRED / REVOKED. Exam: DRAFT → PUBLISHED → OPEN → CLOSED → ARCHIVED. Attempt: IN_PROGRESS → SUBMITTED → GRADING → GRADED → PUBLISHED, có nhánh CANCELLED. Tình huống phải xử lý: mua cùng sản phẩm nhiều lần; hoàn tiền sau khi đã học/thi; hết hạn giữa lúc thi; đổi segment giữa hai lần thi; sửa ngưỡng xếp hạng sau khi công bố; khóa học bị gỡ bán nhưng người mua còn hạn. Quy tắc cụ thể ghi Decision Log trước khi phát triển.

## 6. Ma trận truy vết
BG-01 → FR-01/02/14 → TC-AUTH, TC-STAFF; BG-02 → FR-04/05/09/10/11 → TC-LEARN, TC-EXAM; BG-03 → FR-07/08 → TC-ORDER, TC-ACCESS. Mọi FR có ít nhất một test case trong `../04-testing/Test-Cases.md`.
