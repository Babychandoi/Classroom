# Chính sách dữ liệu và quy trình xử lý quyền của chủ thể

Cập nhật 01/10/2026. Áp dụng cho bản web chạy trên máy chủ của người vận hành. Đây là chính sách kỹ thuật/vận hành; không phải chứng nhận tuân thủ pháp luật.

## Dữ liệu, mục đích, người được truy cập

Email/tên/hồ sơ phục vụ định danh và lựa chọn hiển thị PRIVATE/CLASS/PUBLIC. Dữ liệu thành viên, tiến độ, bài làm và điểm phục vụ lớp học. Đơn hàng/entitlement phục vụ cấp quyền và xử lý khiếu nại. Audit và bộ đếm bảo vệ hệ thống. MySQL là nguồn chính; MongoDB/Neo4j là bản chiếu, MinIO lưu tệp. Không gửi dữ liệu cho cổng thanh toán thật trong phiên bản này.

## Thời gian lưu

| Dữ liệu | Chính sách |
|---|---|
| Hồ sơ/thành viên/học tập | Theo thời gian cung cấp dịch vụ; rà soát hàng năm và khi có yêu cầu xóa, không tự xóa điểm/bài làm đang tranh chấp |
| Đơn hàng/chứng từ | Giữ theo nghĩa vụ thực tế của đơn vị vận hành; không hard-delete user vì FK hiện có sẽ cascade đơn hàng |
| Audit | Chỉ giữ theo mục đích bảo mật/khiếu nại và nghĩa vụ thực tế; quyết định giữ có căn cứ, người duyệt và thời hạn |
| Refresh token | Tối đa TTL cấu hình 30 ngày; thu hồi khi đóng tài khoản |
| Rate limit | Hết cửa sổ là hết hiệu lực; cleanup mỗi 60 giây, lô 2000 |
| Outbox PROCESSED | 7 ngày; không dọn sự kiện chưa xử lý/dead letter theo retention này |
| Backup | Đề xuất 30 ngày; chỉ xóa bản cũ đã có manifest thành công và bản mới khôi phục được; không xóa backup có legal hold |

30 ngày backup là chính sách của dự án, không được hiểu là thời hạn pháp luật áp dụng cho mọi loại dữ liệu. Sau khôi phục phải phát lại các yêu cầu đóng tài khoản đã hoàn tất sau thời điểm backup; lưu ledger xử lý quyền dữ liệu ngoài backup được phục hồi.

## Quyền dữ liệu trên web

- `/privacy`: chính sách công khai. `/me/profile`: sửa hồ sơ/phạm vi hiển thị, tải bản sao và gửi yêu cầu đóng/xóa thông tin tài khoản.
- `POST /api/v1/privacy/me/export`: mật khẩu hiện tại + offset; từng nhóm tối đa 1000 dòng/trang. Không xuất mật khẩu, token, chữ ký webhook, question/grading snapshot hay điểm chưa công bố. Không nhận user ID từ client.
- `POST /privacy/me/deletion-requests`: lưu PENDING bền vững, idempotent theo user. `GET /privacy/me/requests`: chỉ yêu cầu của mình.
- PLATFORM_ADMIN xem/giải quyết tại panel dữ liệu trong hồ sơ, hoặc `GET /privacy/requests`, `PUT /privacy/requests/{userId}`. Kết quả ON_HOLD/REJECTED/COMPLETED cần giải thích; mọi quyết định có audit.
- COMPLETED ẩn danh hóa hồ sơ, vô hiệu đăng nhập/refresh, gỡ thành viên/nhân sự, xóa nội dung bài/bình luận/hỏi đáp do người dùng viết, phát outbox gỡ quan hệ. Không xóa chứng từ hay tài khoản vật lý. Chủ lớp ACTIVE phải chuyển quyền/lưu trữ trước.
- Bài thi/bài nộp, tệp và nội dung lớp đã công bố có thể chứa dữ liệu cần rà soát riêng. Admin phải rà soát và thực hiện xóa/redact bổ sung hoặc ghi căn cứ giữ trước khi chọn COMPLETED. Không coi ẩn danh hóa hồ sơ là đã xóa mọi dữ liệu.

## Căn cứ và công việc của đơn vị vận hành

Nguồn chính thức: [Luật Bảo vệ dữ liệu cá nhân 91/2025/QH15](https://vanban.chinhphu.vn/?classid=1&docid=214590&pageid=27160&typegroup=), ban hành 26/06/2025, hiệu lực 01/01/2026. Người vận hành phải xác định mình là bên kiểm soát/xử lý nào, công bố danh tính/liên hệ thực tế, căn cứ xử lý, hồ sơ đánh giá tác động và việc chuyển dữ liệu nếu có; xử lý dữ liệu trẻ em và nghĩa vụ chứng từ theo trường hợp thực tế. Chưa có danh tính pháp lý của đơn vị vận hành trong yêu cầu này nên không tự điền tên công ty hay tuyên bố đạt pháp lý.
