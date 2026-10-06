# Chuyển Classroom sang server mới

## Trong bundle có gì

| File | Nội dung |
|---|---|
| `source.tar.gz` | Mã nguồn (commit ghi trong `MANIFEST.txt`) để build trên server mới |
| `data/` | Bản sao lưu mới: MySQL, MongoDB, Neo4j (`neo4j.dump`), MinIO (toàn bộ file đã upload) + `backup.json` (checksum từng phần) |
| `env.bundle` | **File `.env` thật — chứa mật khẩu/khóa bí mật** (JWT, DB, MinIO). Quyền 600 |
| `images.tar.gz` | *Chỉ có ở bundle cũ.* MinIO/mc không còn image pull được nên `compose.yaml` tự **build** chúng từ `infra/minio/Dockerfile` (Go, ghim phiên bản) — chạy được trên cả arm64 và x86_64, chỉ cần Docker Hub + proxy.golang.org |
| `install.sh`, `install.ps1` | Trình cài đặt một lệnh (Linux/macOS, Windows PowerShell) |
| `MANIFEST.txt`, `bundle.conf`, `SHA256SUMS` | Phiên bản (commit, Flyway), số dòng từng bảng, digest image, kích thước |

## Yêu cầu server mới

Linux, macOS hoặc Windows 10/11 (Docker Desktop, backend WSL2; xem mục Windows bên dưới).
Docker + Compose v2 đang chạy, RAM cho Docker ≥ 6 GB, trống ≥ 10 GB, có Internet (kéo image gốc và thư viện Maven/npm), `openssl`, `curl`, `tar`.
Các cổng 13000 (web), 8080, 3307, 27017, 7474, 7687, 9000, 19001 phải trống (hoặc đổi bằng `--port`, `--set KEY=VALUE`).

## Trên server CŨ (tạo bundle)

```bash
cd /đường/dẫn/Classroom
infra/scripts/make-migration-bundle.sh            # thêm --encrypt để mã hóa AES-256 bằng passphrase
```
Kết quả: `~/Classroom-migration/classroom-bundle-<ngày-giờ>.tar.gz` (+ `.sha256`, `install.sh`). Có thể chạy lại bất cứ lúc nào (mỗi lần tạo bản sao lưu mới). Dừng ghi dữ liệu mới (hoặc chấp nhận mất thay đổi sau lúc sao lưu) trước khi chuyển hẳn.

## Chép sang server mới và cài (MỘT lệnh, Linux/macOS)

```bash
scp ~/Classroom-migration/classroom-bundle-*.tar.gz* ~/Classroom-migration/install.sh user@server-moi:~/
ssh user@server-moi
chmod +x install.sh && ./install.sh classroom-bundle-<...>.tar.gz --dir /opt/classroom
```
Tùy chọn hay dùng: `--port 3000`, `--origin https://lop.example.com` (đặt CORS + cookie Secure), `--bind 0.0.0.0` (mặc định chỉ `127.0.0.1`), `--no-demo`, `--no-restore`, `--yes` (không hỏi), `--passphrase` (bundle mã hóa; hoặc biến `BUNDLE_PASSPHRASE`). Xem `./install.sh --help`.

Installer làm lần lượt: kiểm tra môi trường → xác thực checksum bundle + `backup.json` → giải nén vào `--dir` → đặt `.env` → nạp image → build/khởi động MySQL, MongoDB, Neo4j, MinIO → **khôi phục dữ liệu trước khi backend chạy** → build/khởi động backend + frontend → chờ `/api/v1/health/readiness` UP → smoke test (đăng nhập, số dòng từng bảng = bundle, lớp `lop-demo-day-du`). Chạy lại an toàn: nếu đã có dữ liệu, nó hỏi (hoặc cần `--yes`), tạo bản sao lưu an toàn trong `<dir>/backups/pre-install` rồi mới ghi đè; không bao giờ xóa volume.

## Sau khi cài — kiểm tra

- `<dir>/infra/scripts/server-up.sh status` → các container `healthy`, readiness `UP`.
- Mở `http://localhost:13000`, đăng nhập `owner@classroom.local` / `Password123!` (dữ liệu demo), mở lớp trưng bày, kiểm tra ảnh/video tải được.
- Hàng ngày/tuần: `server-up.sh backup`. Cập nhật mã: thay mã nguồn rồi `server-up.sh update`.

## Bảo mật — đọc kỹ

- Bundle = **mật khẩu thật + dữ liệu cá nhân**. Chỉ chuyển qua SSH (scp/rsync), `chmod 600`, xóa sau khi chuyển xong; dùng `--encrypt` nếu đi qua kênh khác.
- Dữ liệu demo có tài khoản cố định mật khẩu `Password123!` và thanh toán sandbox. **Không mở ra Internet.** Muốn chạy thật: `--no-demo` rồi đổi mật khẩu/khóa tài khoản demo (`infra/scripts/secure-server-accounts.*`, `docs/RUNBOOK.md`) và xoay bí mật (`infra/scripts/rotate-secrets.ps1`).
- Mở ra ngoài cần: reverse proxy HTTPS (Caddy/nginx) + DNS + tường lửa chỉ mở 80/443; `--origin https://...`; MinIO chỉ nghe `127.0.0.1` nên link ảnh/video (presigned) cần `--minio-endpoint https://minio.domain` trỏ tới proxy cho MinIO, nếu không trình duyệt từ xa không tải được media.

## MinIO / mc được build tại chỗ

MinIO không còn phát hành image pull được (Docker Hub từ chối, quay.io cần đăng nhập). `docker compose up` tự build `classroom/minio` và `classroom/mc` từ `infra/minio/Dockerfile` (Go 1.22 + `go install` đúng tag `RELEASE.2024-05-10T01-41-38Z` / `RELEASE.2024-05-09T17-04-24Z`): lần đầu mất vài phút, cần Internet (Docker Hub, proxy.golang.org). Muốn dùng image có sẵn khác: `--minio-image <img> --mc-image <img>` (installer `docker pull` rồi gắn lại tên `classroom/...`).

## Chỉ có git clone (không có bundle) — stack rỗng

```bash
git clone <repo> classroom && cd classroom
./infra/scripts/install.sh            # thêm --demo để bật tài khoản demo (chỉ dùng cục bộ)
```
Tạo `infra/.env` từ `.env.example` với bí mật **ngẫu nhiên**, rồi `docker compose up -d --build`.

## Windows (PowerShell 5.1) — Docker Desktop

1. Docker Desktop (WSL2): tạo `%UserProfile%\.wslconfig` với `[wsl2]` / `memory=8GB` (tối thiểu 6 GB), chạy `wsl --shutdown`, mở lại Docker Desktop và chờ "Engine running". Cần ≥ 10 GB trống; dùng đường dẫn ngắn như `C:\classroom` (giới hạn 260 ký tự).
2. Cài từ bundle (chép `classroom-bundle-*.tar.gz[.enc]`, `.sha256`, `install.ps1` vào một thư mục):
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\install.ps1 .\classroom-bundle-<...>.tar.gz -Dir C:\classroom
# bundle mã hóa: thêm -PassphraseFile .\pass.txt (hoặc đặt $env:BUNDLE_PASSPHRASE, hoặc để nó hỏi); giải mã bằng .NET, không cần openssl
```
   Cờ tương tự bash: `-Port 3000 -Origin https://lop.example.com -Bind 0.0.0.0 -NoDemo -NoRestore -Yes -Project NAME -Set KEY=VALUE`.
3. Không có bundle, chỉ clone:
```powershell
git clone <repo> C:\classroom ; cd C:\classroom
powershell -NoProfile -ExecutionPolicy Bypass -File infra\scripts\install.ps1          # -Demo để bật demo cục bộ
```
4. Điều khiển: `powershell -NoProfile -ExecutionPolicy Bypass -File infra\scripts\server-up.ps1 status|logs|stop|start|backup|update`. Tạo bundle trên Windows: `infra\scripts\make-migration-bundle.ps1 [-Encrypt]`.
5. Lưu ý: cổng 80/443/3000/8080 hay bị IIS, Skype, dịch vụ khác chiếm (installer báo cổng bận; đổi bằng `-Port` / `-Set BACKEND_PORT=...`); muốn truy cập từ máy khác phải `-Bind 0.0.0.0` **và** mở Windows Firewall (inbound TCP đúng cổng) — không làm với dữ liệu demo. File `.sh` trong repo luôn LF (`.gitattributes`), không sửa bằng trình soạn thảo đổi sang CRLF.
6. Nếu lỗi: đọc dòng `LOI:` (installer dừng ngay, không xóa volume); xem log `docker compose -p <project> logs --tail 100 <service>`; sửa rồi chạy lại cùng lệnh (an toàn). Lỗi thường gặp: Docker chưa "Engine running"; RAM < 6 GB; cổng bận; `tar.exe` không có (Windows cũ hơn 10 1803); không có Internet khi build; chưa chạy PowerShell với `-ExecutionPolicy Bypass`.

*Ghi chú:* các script `.ps1` mới được kiểm tra cú pháp bằng PowerShell 7 (trong container) và phần giải mã AES/PBKDF2 đã được đối chiếu với `openssl`; chưa chạy thật trên Windows PowerShell 5.1 — chạy thử trước khi dựa vào chúng.

## Quay lại / dọn dẹp

- Server cũ vẫn nguyên vẹn — chỉ tắt khi server mới đã chạy ổn. Muốn quay lại: khởi động lại server cũ (`docker compose ... start`).
- Cài lại từ đầu trên server mới: `server-up.sh stop`, rồi chạy lại `install.sh --yes` (ghi đè dữ liệu bằng bundle; bản trước vẫn nằm ở `backups/pre-install`).
- Gỡ hoàn toàn (XÓA DỮ LIỆU): `cd <dir>/infra && docker compose -p classroom-demo -f compose.yaml -f compose.demo.yaml --env-file .env down -v` rồi `rm -rf <dir>`.
- Xóa bundle sau khi xong: `shred -u` hoặc `rm` file `.tar.gz*`, `.sha256`.
