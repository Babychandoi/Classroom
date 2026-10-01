# Làm sạch lịch sử Git

Secret cũ đã được vô hiệu hóa bằng việc xoay mật khẩu/JWT/webhook trên hai stack; file `.env` gốc và backup cấu hình cũ đã dọn. `infra/.env` hiện tại có ACL riêng và bị bỏ qua bởi Git. Cấu hình ứng dụng không còn fallback dùng các mật khẩu cũ.

Toàn bộ source, migration, test và tài liệu được stage/commit; backup dữ liệu, credential, artifact và log được giữ ngoài Git. Bản sao lịch sử sạch được chuẩn bị riêng trong `.artifacts/git-cleanup/sanitized.git`, loại hai đường dẫn `.env`, `infra/.env` và các giá trị secret đã nhận diện khỏi toàn bộ object lịch sử. Báo cáo `.artifacts/git-cleanup/result.json` ghi SHA, tree đã kiểm thử, số object kiểm tra và kết quả so sánh tree.

Trước khi thay lịch sử GitHub, kiểm tra tree của bản sạch khớp tuyệt đối với tree đã commit và kiểm thử; không còn đường dẫn môi trường/giá trị secret trong object được xuất bản. Bundle khôi phục trước thao tác được giữ ở thư mục có ACL hạn chế; tuyệt đối không push bundle hoặc file thay thế secret.

Force push chỉ thực hiện sau xác nhận riêng trên SHA cụ thể, theo yêu cầu trong bản bàn giao. Dùng `--force-with-lease=refs/heads/main:<expectedRemoteMain>`; nếu branch remote đã thay đổi, dừng để kiểm tra. Sau khi push, đồng bộ branch local với SHA sạch, xử lý các ref/reflog còn trỏ về lịch sử cũ và hướng dẫn người cộng tác clone lại. Không force push toàn bộ ref/tags một cách mù quáng.
