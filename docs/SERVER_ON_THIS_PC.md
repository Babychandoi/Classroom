# Chạy web trên máy Windows hiện tại

Ngày kiểm tra: 02/10/2026. Đây là web responsive cho máy tính/điện thoại; không có app Android/iOS riêng.

## Địa chỉ và tài khoản

- Server chính: **https://192.168.1.7** hoặc **https://localhost** ngay trên máy này. Mặc định một backend theo phép đo trên PC này; chế độ hai backend dùng chung MySQL, JWT, rate limit và outbox, Nginx cân bằng yêu cầu.
- Demo: http://localhost:13000, chỉ bind loopback. Server chính tắt demo login/seed, sandbox và checkout giả lập; demo vẫn có các luồng này để kiểm thử.
- Mật khẩu mới cho sáu tài khoản mẫu của server chính nằm tại `.artifacts/server/accounts.json`, ACL chỉ người dùng Windows hiện tại và SYSTEM. Không commit, gửi chat hoặc đưa file này lên GitHub. Mã tài khoản, quyền và lớp hiện có được giữ nguyên. Tài khoản admin là `admin@classroom.local`; chủ lớp là `owner@classroom.local`.
- HTTP localhost:3000 vẫn phục vụ kiểm tra nội bộ, nhưng sử dụng HTTPS để đăng nhập/refresh vì cookie chính là Secure.

## Khởi động và truy cập LAN

```powershell
cd D:\ClassRoom
powershell -NoProfile -ExecutionPolicy Bypass -File infra/scripts/start-server.ps1
```

Script build một lần, đợi backend rồi frontend healthy; không build lại qua dependency làm backend khởi động hai lần. Khi về một node, chuyển routing trước rồi dừng node phụ đúng project, giữ volume. Xuất **chứng chỉ CA công khai** tại `.artifacts/server/classroom-root.crt`. Khóa riêng chỉ nằm trong volume Caddy. Import chứng chỉ công khai này vào kho Trusted Root của từng thiết bị trước khi truy cập; không chuyển khóa riêng. `server-smoke.js` dùng Chrome với xác minh TLS bật sau khi máy chủ đã tin cậy CA; client Node riêng cũng kiểm tra chuỗi CA thật. Các lượt E2E trước khi import CA dùng context thử riêng, được ghi trong báo cáo.

Để bật hai node: `start-server.ps1 -TwoBackends`. Kiểm tra JWT trên cả hai bằng `SERVER_BACKEND_PORTS=8080,28080` khi chạy `e2e/server-smoke.js`, rồi chạy `e2e/multinode.js` để kiểm tra budget đăng nhập chung. Không mặc định rằng thêm một JVM trên cùng PC làm tăng khả năng chịu tải; xem `PERFORMANCE_REVIEW.md`.

Đã chạy script dưới quyền Administrator qua UAC ngày 01/10/2026: firewall `Classroom-LAN-HTTPS` bật cho LocalSubnet, CA đã có trong LocalMachine Root. Curl HTTPS tới IP máy chủ xác minh được CA mà không dùng `--cacert`. Chưa kiểm chứng từ thiết bị LAN thứ hai. Để cấu hình lại, chạy trong **PowerShell mở bằng Run as Administrator**:

```powershell
cd D:\ClassRoom
powershell -NoProfile -ExecutionPolicy Bypass -File infra/scripts/enable-lan-access.ps1
```

Firewall chỉ mở TCP 80, 443, 9443 cho mạng nội bộ, kể cả khi Wi-Fi đang ở profile Public; database, console MinIO và demo giữ loopback. Chưa kiểm chứng từ một thiết bị LAN thứ hai. Khi DHCP đổi IP, chạy `start-server.ps1 -Address <IP mới>` và `register-server-tasks.ps1 -Address <IP mới>`; nên đặt DHCP reservation tại router.

## Tự chạy, giám sát và sao lưu

Docker Desktop hiện bật AutoStart. Ba Scheduled Task đã đăng ký cho user Windows hiện tại:

| Task | Lịch | Việc làm |
|---|---|---|
| Classroom-ResumeServer | Đăng nhập Windows | Đợi Docker tối đa 5 phút, bật lại server không build |
| Classroom-HealthCheck | Mỗi 5 phút | HTTPS/CA + readiness MySQL, ghi `.artifacts/server/health.jsonl` |
| Classroom-DailyBackup | 03:00 hằng ngày | Backup bốn kho; SHA-256 manifest; giữ 30 ngày, ít nhất hai backup thành công |

Task chạy khi user đăng nhập, có StartWhenAvailable; không chạy khi Windows tắt/ngủ hay chưa có Docker Desktop. Không đã kiểm thử reboot thật trong phiên này. Tắt sleep tự động trong Windows nếu muốn phục vụ liên tục; máy vẫn cần điện, mạng và dung lượng đĩa. Không coi Docker Desktop là dịch vụ chạy trước màn hình đăng nhập.

`backup-maintenance.ps1` bỏ qua thư mục backup có file `legal-hold`. Dữ liệu backup chứa thông tin cá nhân; không publish. Restore dùng project riêng và xác nhận chính xác tên project. Khi phục hồi backup cũ hơn yêu cầu xóa, dùng `-KeepWritersStopped`, đối chiếu nhật ký/yêu cầu mới nhất và áp dụng lại quyết định đóng tài khoản trước khi mở server cho người dùng.

Sau restore có tài khoản mẫu từ thời còn mật khẩu công khai, chạy `secure-server-accounts.ps1 -Reapply` trước khi mở server để áp dụng lại mật khẩu đang được lưu trong file được bảo vệ và thu hồi refresh token cũ. Script này cần Python + bcrypt (đã có trên máy hiện tại); runtime ứng dụng vẫn chạy trong Docker.

`recreate-stacks.ps1` giữ volume và bật lại cấu hình HTTPS nếu Caddy đang chạy. Khi đổi secret DB, phải đổi account trong DB hiện có trước; chỉ sửa `.env` không đổi mật khẩu trên volume.

## Internet từ chính máy này

Chưa có tên miền, cấu hình NAT/router hoặc bằng chứng IP công khai không bị CGNAT trong yêu cầu. Server hiện đã chạy tại máy; **chưa được xác nhận truy cập từ Internet**. Cấu hình chuẩn bị sẵn: `Caddyfile.public` và `compose.server-public.yaml` dùng chứng chỉ ACME khi có hostname thật, DNS trỏ đúng IP và TCP 80/443/9443 được chuyển đến máy này. Không mở port database/console/demo.

```powershell
$env:SERVER_ADDRESS = 'lop-hoc.ten-mien-cua-ban.vn'
$env:TLS_EMAIL = 'email-that-cua-ban@example.com'
docker compose --env-file infra/.env -f infra/compose.yaml -f infra/compose.server.yaml -f infra/compose.multinode.yaml -f infra/compose.multinode-server.yaml -f infra/compose.server-public.yaml up -d --wait
```

Đây là ví dụ cấu hình; chưa chạy vì hostname/router thật chưa được cung cấp. Firewall LAN không cho Internet: cần rule theo phạm vi người dùng thực tế sau khi cấu hình router. Nếu CGNAT, cần IP công khai hoặc tunnel do người vận hành chọn. Đọc `DATA_POLICY.md` và điền danh tính/liên hệ đơn vị vận hành trước khi mở dịch vụ ngoài nhóm thử nghiệm.

## Kiểm thử vận hành

Backend `backend-test`, integration `backend-integration-test`, frontend Node 22; `e2e/server-smoke.js`, `multinode.js`, `round23.js`, `captions.js`. Toàn bộ E2E demo:

```powershell
cd D:\ClassRoom\e2e
$env:BASE_URL='http://localhost:13000'
$env:E2E_DEMO='1'
$env:E2E_MINIO_PORT='19000'
$env:A11Y_STRICT='1'
$env:A11Y_ALL='1'
npm run all
```

Không chạy E2E vào dữ liệu production: bộ test đăng ký tài khoản, tạo lớp và nộp bài thật.

Sau một đợt kiểm thử được phép trên server chính, `python infra/scripts/disable-test-accounts.py` chỉ xem trước. Thêm `--apply` để vô hiệu hóa đúng mẫu tài khoản E2E/load dùng example.com và tên giả, lưu trữ lớp thử thuộc các tài khoản này, thu hồi refresh token và ghi audit. Hồ sơ học/thi/thanh toán vẫn được giữ; trạng thái trước lưu dưới `.artifacts/server` có ACL hạn chế. Script kiểm tra project container trước khi ghi; không thao tác demo. Không dùng state load cũ sau khi vô hiệu hóa.

Gate tải hiện tại và giới hạn phần cứng/kết nối: `PERFORMANCE_REVIEW.md`. Có hỗ trợ hai backend để kiểm tra quota/JWT chung, nhưng một backend là mặc định trên máy này.
