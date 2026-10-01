# Kiểm toán trợ năng — Round 23, cập nhật 02/10/2026

Phạm vi kỹ thuật: web desktop/mobile, WCAG 2.0/2.1 A+AA và WCAG 2.2 AA qua axe-core trong Chrome thật. Đây là bằng chứng kiểm tra ứng dụng, không phải chứng nhận toàn bộ WCAG hoặc kiểm tra độ chính xác của mọi nội dung giáo viên tải lên.

## Kết quả chạy

- Hai lượt quét (demo HTTP và server chính HTTPS), mỗi lượt **29 trang/trạng thái**, **0 violations** ở tất cả các mức axe báo cáo. Bộ script có 30 bước vì bước đầu đăng nhập hai vai trò.
- Vòng lớp PRIVATE/PAID kiểm tra thêm paywall, mời hợp lệ/sai, xác nhận cài đặt, danh sách mã mời và dialog tạo/thu hồi: **0 serious/critical**.
- Keyboard: Tab/Enter tới CTA, Escape đóng dialog, focus được trả lại, tab About hỗ trợ ArrowLeft/Right/Home/End. E2E kiểm tra focus trong dialog và cửa sổ thấp 1024×420.
- Layout: 390px/375px, cửa sổ thấp, không tràn ngang ở các luồng được quét; nội dung About tiếp tục dùng được ở chiều rộng tương đương zoom 200%.
- Video: Chrome đọc cue từ WebVTT lưu trong DB, hiển thị captions và bản chép lời, sửa rồi reload vẫn đúng; không có CSP violation.

Bản V43 được quét lại đầy đủ: `e2e/shots/a11y-muq0govs/axe-report.json` (HTTPS chính), `e2e/shots/a11y-muq0jom0/axe-report.json` (demo); mỗi báo cáo 29 trang, 0 violation. Log `a11y-v43-https-full-validation.log` và `a11y-v43-demo-full-validation.log`: 30/30 bước mỗi lượt. Vòng tấn công và captions được chạy lại: `round23-v43-demo-validation.log` 16/16 và `captions-v43-demo-validation.log` PASS. Artifact nằm trên máy, được bỏ qua khi commit; báo cáo cũ giữ làm lịch sử.

## Sửa trong lượt kiểm toán

| Tiêu chí | Thay đổi | Bằng chứng |
|---|---|---|
| 1.4.3 Contrast (Minimum) | Đậm hơn chữ phụ ở nút demo login và trạng thái thi bị khóa | Hai lượt axe không còn contrast violation |
| 2.5.8 Target Size (Minimum) | Nút sắp xếp khóa/chương/bài tối thiểu 24px; checkbox quyền 24×24px | Studio courses và dialog staff qua WCAG 2.2 |
| 2.1.1 Keyboard, 2.4.3 Focus Order | Tab About dùng roving focus/arrow keys; dialog giữ cơ chế focus hiện có | Browser E2E + AboutSections test |
| 1.1.1 Non-text Content | Ảnh About bắt buộc mô tả khi có ảnh; không chấp nhận SVG/script URL | Round23 upload/GET + validator |
| 1.2.2 Captions (Prerecorded) | WebVTT tạo/sửa cùng bài học, track tiếng Việt | `e2e/captions.js` đọc cue trong Chrome |
| 1.2.3 Audio Description or Media Alternative | contentText hiển thị như bản chép lời/mô tả video | Kiểm tra caption/transcript thực |
| 3.3.1/3.3.2 Error Identification, Labels | Lỗi inline và label cho mục/ảnh/phụ đề/dữ liệu cá nhân | Quét trang và kiểm tra thao tác |
| 4.1.2 Name, Role, Value; 4.1.3 Status Messages | Tab/tabpanel, aria-selected và thông báo status | axe + test bàn phím |

## Giới hạn cần người vận hành nghiệm thu

Chưa thực hiện một phiên NVDA/JAWS/VoiceOver với người dùng công nghệ hỗ trợ, chưa thử mọi thiết bị/zoom 400% và mọi tổ hợp bài thi do giáo viên cấu hình. Captions/bản chép lời có công cụ tạo và quyền truy cập đúng; nội dung thật vẫn cần kiểm tra độ chính xác, âm thanh quan trọng, mô tả hình ảnh (1.2.5) và tài liệu PDF dễ tiếp cận. Không đánh dấu các mục đó đạt chỉ dựa vào axe. Không có video/live-stream trực tiếp trong sản phẩm hiện tại.

Trước khi xuất bản nội dung học thật: kiểm tra toàn bộ thao tác bằng bàn phím, đọc màn hình, zoom 200%/400%, high contrast, tiếng Việt/nhãn/heading; không dùng màu làm tín hiệu duy nhất; bổ sung phụ đề và mô tả âm thanh/hình ảnh cần thiết. Kết quả kiểm tra này phải được lưu theo lớp/nội dung thực tế.
