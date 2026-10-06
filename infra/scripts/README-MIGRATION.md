# Chuyển Classroom sang server mới

## Trong bundle có gì

| File | Nội dung |
|---|---|
| `source.tar.gz` | Mã nguồn (commit ghi trong `MANIFEST.txt`) để build trên server mới |
| `data/` | Bản sao lưu mới: MySQL, MongoDB, Neo4j (`neo4j.dump`), MinIO (toàn bộ file đã upload) + `backup.json` (checksum từng phần) |
| `env.bundle` | **File `.env` thật — chứa mật khẩu/khóa bí mật** (JWT, DB, MinIO). Quyền 600 |
| `images.tar.gz` | Hai image MinIO/mc ghim phiên bản mà `quay.io` từ chối pull (`docker save`). Image khác được kéo từ Docker Hub / Maven Central |
| `install.sh` | Trình cài đặt một lệnh |
| `MANIFEST.txt`, `bundle.conf`, `SHA256SUMS` | Phiên bản (commit, Flyway), số dòng từng bảng, digest image, kích thước |

## Yêu cầu server mới

Linux hoặc macOS (Windows: dùng **WSL2** + Docker Desktop và chạy `install.sh` trong WSL; chưa có `install.ps1`).
Docker + Compose v2 đang chạy, RAM cho Docker ≥ 6 GB, trống ≥ 10 GB, có Internet (kéo image gốc và thư viện Maven/npm), `openssl`, `curl`, `tar`.
Các cổng 13000 (web), 8080, 3307, 27017, 7474, 7687, 9000, 19001 phải trống (hoặc đổi bằng `--port`, `--set KEY=VALUE`).

## Trên server CŨ (tạo bundle)

```bash
cd /đường/dẫn/Classroom
infra/scripts/make-migration-bundle.sh            # thêm --encrypt để mã hóa AES-256 bằng passphrase
```
Kết quả: `~/Classroom-migration/classroom-bundle-<ngày-giờ>.tar.gz` (+ `.sha256`, `install.sh`). Có thể chạy lại bất cứ lúc nào (mỗi lần tạo bản sao lưu mới). Dừng ghi dữ liệu mới (hoặc chấp nhận mất thay đổi sau lúc sao lưu) trước khi chuyển hẳn.

## Chép sang server mới và cài (MỘT lệnh)

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

## Lưu ý kiến trúc CPU

Hai image MinIO/mc trong bundle được lưu theo CPU của server cũ (xem `MANIFEST.txt`, mục *Image arch*). Nếu server mới khác kiến trúc (ví dụ cũ arm64 → mới x86_64) installer sẽ tự thử `docker pull`; nếu thất bại, dùng `--minio-image <image> --mc-image <image>` (image pull được, installer gắn lại đúng tên mà `compose.yaml` cần).

## Quay lại / dọn dẹp

- Server cũ vẫn nguyên vẹn — chỉ tắt khi server mới đã chạy ổn. Muốn quay lại: khởi động lại server cũ (`docker compose ... start`).
- Cài lại từ đầu trên server mới: `server-up.sh stop`, rồi chạy lại `install.sh --yes` (ghi đè dữ liệu bằng bundle; bản trước vẫn nằm ở `backups/pre-install`).
- Gỡ hoàn toàn (XÓA DỮ LIỆU): `cd <dir>/infra && docker compose -p classroom-demo -f compose.yaml -f compose.demo.yaml --env-file .env down -v` rồi `rm -rf <dir>`.
- Xóa bundle sau khi xong: `shred -u` hoặc `rm` file `.tar.gz*`, `.sha256`.
