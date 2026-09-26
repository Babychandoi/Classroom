# BRD — Yêu cầu kinh doanh

**Dự án:** Nền tảng lớp học trực tuyến · **Phiên bản:** 0.1 · **Trạng thái:** BẢN NHÁP · **Ngày:** 23/09/2026

> Tài liệu thiết kế theo thông tin chủ sản phẩm đã cung cấp. Các mục `[CẦN CHỐT]`, `[ĐIỀN KHI THỰC HIỆN]` chưa được xác nhận. Bản nháp không phải biên bản đã ký, kết quả kiểm thử hay cam kết dịch vụ.

## 1. Mục tiêu và phạm vi
Xây nền tảng nhiều lớp học, mỗi lớp do một OWNER (giáo viên chủ nhiệm) sở hữu. Học viên học nội dung, thảo luận, làm bài thi, xem thành tích và mua sản phẩm. OWNER vận hành lớp qua Studio và phân quyền từng khu vực cho trợ lý/giáo viên giám sát. Mục tiêu đầu tiên là vận hành trọn vòng đời từ tạo lớp → học → thi → mua → kiểm soát quyền và theo dõi kết quả.

## 2. Các bên liên quan
| Bên | Nhu cầu | Quyết định cần xác nhận |
|---|---|---|
| Chủ sản phẩm | Phạm vi, thứ tự triển khai, doanh thu | Mô hình kinh doanh, chỉ số thành công |
| OWNER | Quản lớp, giao việc, bán khóa, tổ chức thi | Quyền nhân sự và quy trình xuất bản |
| STAFF | Quản lý phần được giao | Mức quyền theo module và theo khóa |
| Học viên | Học, thi, mua, theo dõi kết quả | Quyền riêng tư profile |
| Vận hành/CSKH | Hoàn tiền, xử lý đơn, sự cố | Quy trình hỗ trợ và đối soát |

## 3. Giá trị và chỉ số đề xuất
| Mã | Giá trị | Chỉ số đo lường đề xuất |
|---|---|---|
| BG-01 | Giáo viên tự mở và vận hành lớp | Số lớp hoạt động/tháng |
| BG-02 | Học viên học và đạt kết quả | Tỷ lệ vào bài đầu, hoàn thành khóa, tham gia thi |
| BG-03 | Bán nội dung có kiểm soát | Đơn thanh toán thành công, tỷ lệ mua, doanh thu thuần |
| BG-04 | Giảm công sức vận hành | Thời gian tạo khóa, chấm bài, xử lý quyền |
Các mục tiêu định lượng chưa được chủ sản phẩm xác nhận; không coi các chỉ số trên là KPI đã cam kết.

## 4. Phạm vi kinh doanh
**Trong phạm vi:** tài khoản; lớp; thành viên; bảng tin; khóa/chương/bài/video/tóm tắt/bài tập/hỏi đáp; tài liệu; cuộc thi; điểm và bảng xếp hạng; segment; profile/hành trình; cửa hàng; sản phẩm có giá và hạn; quyền FREE/PRO/quyền theo sản phẩm; Studio và phân quyền nhân sự; trang giới thiệu.

**Chưa chốt để đưa vào phạm vi:** tích hợp cổng thanh toán cụ thể, phát trực tiếp, chống gian lận nâng cao, mobile native, affiliate, AI gợi ý và chat realtime. Chỉ thêm bằng yêu cầu thay đổi đã đánh giá chi phí.

## 5. Quy tắc kinh doanh đã thống nhất
- OWNER toàn quyền đối với lớp mình và xem được mọi khóa học của lớp không cần mua.
- STAFF được OWNER cấp quyền theo vùng Studio và thao tác; việc quản lý không mặc nhiên cấp quyền xem nội dung mọi khóa.
- PRO xem nội dung PRO dùng chung trong lớp. Khóa bán riêng chỉ được học khi có quyền đúng sản phẩm còn hiệu lực; khóa miễn phí được học theo điều kiện lớp.
- Mỗi sản phẩm có giá, thời điểm bắt đầu và thời hạn quyền sử dụng. Hết quyền không xóa lịch sử học/thi.
- Cuộc thi có thể mở cho toàn lớp, PRO, người sở hữu một khóa cụ thể hoặc segment. Cuộc thi PRO mở cho toàn bộ PRO; cuộc thi theo khóa chỉ mở cho người có quyền đúng khóa.
- Điểm thi, điểm quy đổi xếp hạng và bậc thành tích là ba khái niệm khác nhau.

## 6. Quyết định còn mở và rủi ro
| Mã | Vấn đề | Giả định đang dùng | Chủ thể chốt |
|---|---|---|---|
| D-01 | PRO khi sản phẩm hết hạn | PRO chỉ tồn tại nếu còn ít nhất một entitlement trả phí hiệu lực | Chủ sản phẩm |
| D-02 | Thanh toán | Chưa chọn nhà cung cấp; phải đối soát webhook | Chủ sản phẩm |
| D-03 | Mua nhiều lần/gia hạn | Quyền cộng nối hoặc kéo dài theo chính sách sản phẩm | Chủ sản phẩm |
| D-04 | Kết quả thi nhiều lần | Lấy điểm cao nhất đã công bố để xếp hạng | Chủ sản phẩm |
| D-05 | Profile công khai | Chỉ hiện dữ liệu được chủ tài khoản cho phép | Chủ sản phẩm |
| D-06 | Quy mô, ngân sách, SLA | Chưa xác định | Chủ sản phẩm |

## 7. Tiêu chí chấp nhận kinh doanh
OWNER tạo lớp và ủy quyền được; học viên học miễn phí hoặc sản phẩm đã mua; quyền hết hạn bị khóa đúng lúc; thi theo PRO/khóa/segment được kiểm trên backend; kết quả công bố tạo thứ hạng đúng; giao dịch hoàn tiền thu hồi đúng quyền; lịch sử vẫn được bảo toàn.
