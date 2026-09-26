# Test Cases — Kịch bản kiểm thử chức năng và quyền

**Dự án:** Nền tảng lớp học trực tuyến · **Phiên bản:** 0.1 · **Trạng thái:** BẢN NHÁP · **Ngày:** 23/09/2026

> Tài liệu thiết kế theo thông tin chủ sản phẩm đã cung cấp. Các mục `[CẦN CHỐT]`, `[ĐIỀN KHI THỰC HIỆN]` chưa được xác nhận. Bản nháp không phải biên bản đã ký, kết quả kiểm thử hay cam kết dịch vụ.

| ID/P | Tiền điều kiện | Thao tác | Kết quả mong đợi |
|---|---|---|---|
| TC-01/P0 | OWNER lớp A | Tạo lớp, thêm STAFF, chỉ cấp `EXAM_GRADE` | STAFF chấm được bài A nhưng không sửa giá, phân quyền, lớp B |
| TC-02/P0 | OWNER lớp A | Mở khóa trả phí A không có đơn | Xem được toàn bộ A; không cần PRO/entitlement |
| TC-03/P0 | FREE lớp A | Mở khóa FREE và khóa trả phí A | FREE được học; A bị từ chối qua UI và API |
| TC-04/P0 | Học viên mua A | Mở A, khóa trả phí B, nội dung PRO lớp A | Được A và PRO; B bị khóa |
| TC-05/P0 | Entitlement A sắp hết hạn | Giả lập trước/sau mốc UTC | Trước hạn vào A; sau hạn bị chặn; lịch sử học/điểm còn |
| TC-06/P0 | Đơn A đã PAID | Gửi webhook lặp hoặc giả chữ ký | Một entitlement duy nhất; webhook giả bị từ chối |
| TC-07/P0 | Đã hoàn tiền A | Tải file riêng và mở bài A | Bị chặn nếu không có quyền khác; log giao dịch còn |
| TC-08/P0 | PRO do mua B | Vào thi `COURSE_OWNERS(A)` | Không thấy nút tham gia hoặc có giải thích; API start trả 403 |
| TC-09/P0 | PRO do mua B | Vào thi `PRO_MEMBERS` | Tham gia nếu lịch/lượt hợp lệ |
| TC-10/P0 | Thuộc segment X, không mua A | Thi có rule `X AND COURSE_A` | Bị từ chối; preview STAFF không ghi điểm |
| TC-11/P0 | Có 2 attempts cùng exam | Công bố/cập nhật điểm | Chỉ attempt hợp lệ theo chính sách được cộng một lần |
| TC-12/P1 | Học viên ngoài lớp | Đổi classId trong URL/API để xem profile/đề/file | Không lộ dữ liệu; backend trả 403/404 thích hợp |
| TC-13/P1 | STAFF có COURSE_PREVIEW khóa A | Xem bài A, sửa khóa B | Xem trước A; sửa B bị chặn |
| TC-14/P1 | File chưa hoàn tất upload | Gọi download-url | Không cấp URL; object mồ côi được dọn theo lịch |
| TC-15/P1 | Kỳ thi đang mở | Nộp cùng attempt hai lần | Một kết quả; không nhân đôi điểm |
| TC-16/P1 | Học viên profile riêng tư | Học viên khác và OWNER cùng mở profile | Người khác chỉ thấy công khai; OWNER thấy theo quyền nghiệp vụ |
| TC-17/P1 | Đơn đang PENDING | Gọi thẳng URL học/thi | Chưa cấp quyền; UI thể hiện chờ xác nhận |
| TC-18/P1 | Segment thay đổi | Mở trang rồi bấm tham gia thi sau thay đổi | Backend kiểm lại ở thời điểm bắt đầu attempt |

Mỗi lần chạy ghi commit, môi trường, dữ liệu, người thực hiện, Actual, Pass/Fail, ảnh/log làm bằng chứng. Đây là các kịch bản cần chạy; **chưa có kết quả thực tế**.
