# UI/UX Styleguide, sitemap và wireframe dạng mô tả

**Dự án:** Nền tảng lớp học trực tuyến · **Phiên bản:** 0.1 · **Trạng thái:** BẢN NHÁP · **Ngày:** 23/09/2026

> Tài liệu thiết kế theo thông tin chủ sản phẩm đã cung cấp. Các mục `[CẦN CHỐT]`, `[ĐIỀN KHI THỰC HIỆN]` chưa được xác nhận. Bản nháp không phải biên bản đã ký, kết quả kiểm thử hay cam kết dịch vụ.

## 1. Sitemap
Public: `/classes`, `/classes/:slug/about`, `/classes/:slug/store`, `/login`. Học viên: `/classes/:slug/feed`, `/learn`, `/exams`, `/leaderboard`, `/documents`, `/members`, `/members/:userId`, `/store/products/:id`, `/learn/courses/:courseId/lessons/:lessonId`. Studio: `/studio/classes/:id/overview|feed|courses|exams|leaderboard|documents|members|segments|products|orders|about|settings|staff`.

## 2. Khung giao diện cần dựng trong Figma
| Màn | Thành phần bắt buộc | Trạng thái cần mockup |
|---|---|---|
| Trang lớp | Header lớp, tab, quyền truy cập, CTA học/mua | khách, FREE, PRO, OWNER |
| Bảng tin | composer, feed, bình luận, lọc | rỗng, chờ duyệt, lỗi |
| Góc học tập | danh sách khóa, chương, bài, tiến độ, Q&A | chưa mua, hết hạn, đã mua, đã hoàn thành |
| Luyện thi | thẻ kỳ thi, điều kiện, đồng hồ, bài làm, kết quả | chưa đến lịch, hết lượt, sai segment, chờ chấm |
| Bảng xếp hạng | vị trí, tổng điểm, bậc, bộ lọc kỳ | chưa có điểm, hòa điểm |
| Thành viên/profile | danh sách, hành trình, thành tích | profile riêng tư, giáo viên xem |
| Cửa hàng/checkout | giá, hạn sử dụng, sản phẩm được mở, thanh toán | pending, paid, failed, refunded |
| Studio | sidebar, class switcher, role-based actions | OWNER, STAFF ít quyền, không có quyền |

## 3. Quy chuẩn ban đầu
Responsive ưu tiên mobile cho học viên, desktop cho Studio nhưng không khóa trên mobile. Hệ màu, logo, typography, spacing, icon, component states `[CẦN THIẾT KẾ]`; không tự nhận đã có mockup pixel-perfect. Tối thiểu: tương phản đủ đọc, focus rõ, nhãn cho form, thao tác bàn phím, thông báo lỗi có cách khôi phục; không dựa riêng màu cho trạng thái.

## 4. Nguyên tắc hiển thị quyền
Khi khóa bị chặn, giải thích cụ thể: “Cần mua khóa này”, “Sản phẩm hết hạn ngày…”, “Cuộc thi dành cho học viên khóa…”. Không lộ đáp án, URL file riêng hoặc dữ liệu profile nhạy cảm trong HTML/API. Ẩn nút trong UI chỉ là tiện ích; backend vẫn kiểm quyền.

## 5. Đầu ra thiết kế chưa thể tự hoàn tất
Figma URL: `[ĐIỀN SAU KHI THIẾT KẾ]`; người phê duyệt và ngày: `[ĐIỀN]`. File này là thông số để dựng wireframe và mockup; không giả định các bản vẽ đã tồn tại.
