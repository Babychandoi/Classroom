# Bộ kiểm thử tải / lỗi (loadtest)

Các kịch bản tải tái hiện những lỗi đã tìm thấy ở vòng đánh giá R20 (nghẽn pool kết nối khi nộp bài đồng thời, N+1 ở bảng tin và danh sách thành viên, khóa dòng kỳ thi khi bắt đầu làm bài đồng thời, tự lưu bài làm) để **chứng minh trước/sau khi sửa** và để chạy lại mỗi khi đổi code liên quan. Chỉ dùng Node 20+, không cần cài thêm gói nào.

> **CẢNH BÁO — chỉ chạy trên stack drill hoặc stack dev dùng-rồi-bỏ. KHÔNG BAO GIỜ trỏ vào stack thật (cổng 3000/8080), staging dùng chung hay production.**
> Mỗi kịch bản **tạo dữ liệu thật** (hàng trăm người dùng `lt.*@example.com`, lớp `loadtest-*`, kỳ thi, bài viết, tệp MinIO) và **bắn tải đột biến** (200 yêu cầu cùng lúc) có thể làm treo backend trong thời gian dài nếu có lỗi. Các script tự từ chối chạy khi `BASE_URL`/`API_URL`:
> - không phải máy cục bộ/mạng nội bộ (`localhost`, `127.x`, dải riêng RFC1918, tên dịch vụ Docker như `frontend`, `*.local`) — trừ khi đặt `LOADTEST_ALLOW_REMOTE=1`;
> - là cổng mặc định của **stack thật** (`3000`/`8080` trên loopback) — trừ khi đặt `LOADTEST_ALLOW_MAIN_STACK=1` (chỉ khi bạn chắc đó là stack dev dùng một lần).
>
> Dọn dẹp sau khi chạy: hạ toàn bộ stack drill (`down -v` **chỉ với `-p classroom-drill`**, xem bên dưới). Không có bước dọn dữ liệu riêng vì dữ liệu nằm trong volume của drill.

## Điều kiện cần

1. **Stack drill đang chạy** (dữ liệu tách hoàn toàn khỏi stack thật; cổng frontend `13000`, backend `18080` — xem `docs/RUNBOOK.md` mục 5.3):
   ```powershell
   docker compose -p classroom-drill -f infra/compose.yaml -f infra/compose.demo.yaml -f infra/compose.drill.yaml `
       --env-file infra/.env up -d --build
   # tùy chọn: log thống kê pool Hikari (active/idle/waiting) mỗi 30 giây -> docker logs classroom-drill-backend 2>&1 | findstr "cleanup stats"
   #   thêm  -f infra/compose.drill-diag.yaml  vào lệnh trên
   ```
2. **Node.js 20+** hoặc Docker (dùng image `node:20-alpine`).
3. Bộ giới hạn xác thực (`AuthRateLimitFilter`, R20-02) tính theo **cặp IP + tài khoản**, lượt đăng nhập SAI theo IP và phiên làm mới — **không** còn là "10 lần/phút/IP" (khiến 190/200 học viên sau một NAT bị 429), nên `seed.js` đăng ký/đăng nhập 200 học viên từ **một** IP vẫn được (trần đăng ký mặc định 300 lần/phút/IP). Chạy **bên trong mạng Docker của drill** với `--cap-add NET_ADMIN` (script `run-in-drill.*` làm sẵn) vẫn tốt hơn vì mỗi "học viên" có IP nguồn phụ riêng như một lớp học thật ngồi ở nhiều máy; kịch bản `scenarios/auth-nat.js` thì cố ý dùng **một** IP. Nếu chạy bằng Node cục bộ (một IP) với `--users` lớn hơn trần đăng ký, `seed.js` giãn theo `Retry-After`.

## Cách chạy

**Bằng Docker (khuyến nghị)** — `run-in-drill.ps1` (Windows) hoặc `run-in-drill.sh` (bash) chạy script bên trong mạng `classroom-drill_classroom-net`, `BASE_URL=http://frontend`:

```powershell
.\loadtest\run-in-drill.ps1 smoke.js                                        # kiểm tra nhanh (~1 phút, 40 học viên)
.\loadtest\run-in-drill.ps1 seed.js --users 200 --questions 20 --exams 3 --posts 30 --comments 20
.\loadtest\run-in-drill.ps1 scenarios\exam-burst.js --users 200 --duration 20
.\loadtest\run-in-drill.ps1 scenarios\feed-read.js --sessions 100 --duration 30
.\loadtest\run-in-drill.ps1 scenarios\auth-nat.js --users 200 --window 30    # cả lớp sau MỘT NAT (R20-02)
```
```bash
loadtest/run-in-drill.sh smoke.js
loadtest/run-in-drill.sh scenarios/exam-burst.js --users 200 --duration 20
```

**Bằng Node cục bộ** (từ thư mục `loadtest/`, đích là frontend của drill trên máy chủ):
```bash
cd loadtest
BASE_URL=http://127.0.0.1:13000 node seed.js --users 20 --questions 10 --exams 1
BASE_URL=http://127.0.0.1:13000 node scenarios/exam-burst.js --users 20 --duration 5
BASE_URL=http://127.0.0.1:13000 node scenarios/media-upload.mjs --parallel 50 --size 20   # PHẢI chạy từ máy chủ, xem bên dưới
```

Mã thoát: `0` = mọi ngưỡng đạt, `1` = có ngưỡng vi phạm (in `FAIL <chỉ số>: <giá trị> (ngưỡng ...)`), `2` = từ chối chạy do đích không an toàn / thiếu dữ liệu chuẩn bị.

## Các kịch bản

| Lệnh | Đo cái gì | Ngưỡng mặc định (đổi bằng cờ) |
| --- | --- | --- |
| `seed.js` | Chuẩn bị: N học viên + 1 chủ lớp (đăng ký qua API), 1 lớp, K kỳ thi đã công bố (M câu trắc nghiệm, quy tắc thưởng xếp hạng 90→100, 50→50, 0→10 điểm), tùy chọn bài viết + bình luận (`--posts`, `--comments`). Ghi `loadtest/.state/state.json` (đã `.gitignore`). `--reuse` thêm kỳ thi/bài viết vào lớp cũ. | — |
| `scenarios/exam-burst.js` | **R20-01 + R20-06.** N học viên cùng bấm *Bắt đầu làm bài* → tự lưu ~1 lần/giây trong D giây → cùng *Nộp bài* trong một thời điểm (kèm câu trả lời mới nhất trong body). Đo p50/p95/p99 từng pha, số lỗi 5xx, **readiness** (mỗi 100 ms, dùng 1 kết nối DB) suốt lúc bắn tải, đối chiếu câu trả lời server trả về với bản client đã gửi (mất/sai/thừa/nhân đôi), và **thời gian để bảng xếp hạng khớp điểm thưởng mong đợi** (tính lại nền, nhất quán sau vài giây). Mỗi kỳ thi dùng được **một lần** (attemptLimit=1). | bắt đầu p95 ≤ 1500 ms (`--max-start-p95`); tự lưu p95 ≤ 300 ms (`--max-save-p95`); nộp p95 ≤ 5000 ms (`--max-submit-p95`), **0 lỗi**; readiness luôn 200 và p99 ≤ 1000 ms (`--max-probe-p99`); 0 câu trả lời mất/sai; xếp hạng nhất quán ≤ 15 s (`--max-lb-lag`) |
| `scenarios/feed-read.js` | **R20-03.** V phiên học viên duyệt lớp (bảng tin trang 1–2, lớp theo slug, kỳ thi, xếp hạng, danh sách thành viên) có thời gian suy nghĩ T ms, cộng chủ lớp gọi danh sách thành viên Studio. Cần lớp có nội dung (`seed.js --posts 30 --comments 20`; lớp lớn 2000 thành viên / 40 000 bình luận: `loadtest/sql/seed-bulk.sh`). | bảng tin trang 1 p95 ≤ 300 ms (`--max-feed-p95`); mọi lượt đọc p95 ≤ 500 ms (`--max-read-p95`); 0 lỗi 5xx |
| `scenarios/auth-nat.js` | **R20-02.** Cả lớp sau **một NAT** (một IP nguồn duy nhất): N học viên đăng nhập, N người dùng mới đăng ký, N phiên làm mới — mỗi pha trải đều trong `--window` giây (mặc định 30) — rồi kiểm tra bảo vệ vẫn còn: 20 mật khẩu sai cho một tài khoản (chỉ 5 lượt được kiểm tra, các lượt sau 429 + `Retry-After`, mật khẩu đúng trong lúc khóa cũng bị chặn, học viên khác vẫn vào được) và nhồi thông tin đăng nhập (400 lượt sai trên 400 tài khoản → trần 200 lượt sai/phút/IP). Cần `seed.js --users N`. Chạy sau các kịch bản khác (pha cuối chặn IP nguồn tối đa 1 phút với tài khoản mới). | mỗi pha 1–3 ≥ 99 % thành công (`--min-ok-pct`); vét cạn ≤ 5 lượt được kiểm tra; nhồi thông tin ≤ 215 lượt sai được kiểm tra (`--max-stuffing-evaluated`) và ≥ 150 lượt bị 429; 0 lỗi 5xx |
| `scenarios/media-upload.mjs` | **R20-06 (`completeUpload`).** P luồng song song {xin URL → PUT SIZE MB vào MinIO → `complete` → URL tải}. Đo p95 của bước `complete` và readiness lúc đó. **Chạy từ máy chủ** (URL đã ký trỏ tới `MINIO_EXTERNAL_ENDPOINT`, drill: `http://localhost:19000`, không tới được từ trong mạng Docker). | complete p95 ≤ 3000 ms; readiness luôn 200, p99 ≤ 1000 ms |
| `scenarios/outbox-lag.sh [joins=200]` | **R20-05.** N người vào MỘT lớp => N sự kiện `MEMBER_JOINED` trên cùng aggregate; đo thời gian từ lượt tham gia cuối tới khi mọi sự kiện được chiếu sang MongoDB/Neo4j (`METRIC lag_after_last_join_s`) và kiểm tra thứ tự. Cần `seed.js --users N`. Bash + `docker exec` vào `classroom-drill-mysql` (từ chối tên không chứa "drill"). | lag < 30 s (trước khi sửa: ~2 s × N, 200 người ≈ 415 s); 0 vi phạm thứ tự |
| `sql/outbox-backlog.sh [rows=300000]` | **R20-05.** Nạp `rows` hàng outbox `PROCESSED` giả vào DB drill (`BACKLOG_SPREAD_DAYS`=20 ngày tuổi; 6 để giữ nguyên bảng lớn) rồi đo chi phí MySQL của một lần thăm dò rảnh (3 câu lệnh của worker, `sql/outbox-poll-bench.sql`). Hàng cũ hơn 7 ngày còn dùng để xem job retention dọn. | tổng 3 câu < 50 ms (trước V36: ~1 s với 300 000 hàng) |
| `scenarios/outbox-outage.sh <mongodb\|neo4j> [giây=120]` | **R20-04.** Dừng container drill của kho, sinh sự kiện (joins + nộp bài), cài hai đợt thăm dò tác vụ nền cốt lõi (ép một lượt thi hết hạn + cài một việc tính lại xếp hạng: lúc bắt đầu và giữa đợt ngừng), khởi động lại kho và đo thời gian dồn hàng đợi; in `METRIC …`. **Chỉ chạy trên drill** (từ chối container không tên `classroom-drill-*`). | lượt thi/việc xếp hạng xử lý trong ≲ 35 s ngay trong lúc kho ngừng; 0 `DEAD_LETTER`; dồn hết ≤ ~30 s sau khi kho chạy lại; 0 vi phạm thứ tự |
| `smoke.js` (`npm run smoke`) | Bản rút gọn cho CI của kịch bản nộp đồng thời: dựng `USERS` (mặc định **40**) học viên + 1 kỳ thi 10 câu rồi chạy `exam-burst` với `USERS` lượt nộp cùng lúc. Bắt lại lỗi deadlock pool kết nối (R20-01) chỉ trong ~1 phút. Dữ liệu ở `.state/smoke.json`. | như `exam-burst` |

### Đọc kết quả
- **Nộp bài đồng thời**: trước khi sửa R20-01, 200 lượt nộp đồng thời → 6 thành công / 194 lỗi 500, ~60 s, readiness treo 30 s. Sau khi sửa: 200/200 thành công trong vài giây và readiness không bị ảnh hưởng.
- **Số câu lệnh SQL / yêu cầu** (không cần công cụ ngoài): chạy một yêu cầu GET duy nhất khi drill rảnh và lấy chênh lệch bộ đếm của MySQL:
  ```bash
  docker exec classroom-drill-mysql sh -c 'MYSQL_PWD=$MYSQL_ROOT_PASSWORD mysql -uroot -N -e "SHOW GLOBAL STATUS WHERE Variable_name IN ('"'"'Com_select'"'"','"'"'Com_insert'"'"','"'"'Com_update'"'"','"'"'Com_delete'"'"')"'
  ```
  (chạy trước và sau, cộng bốn số, trừ 1 cho chính câu lệnh đo). Bảng tin trang 10 bài: 819 → 11 câu lệnh; danh sách thành viên Studio của lớp 2001 người: 6011 → 5.
- **Pool kết nối**: với `compose.drill-diag.yaml`, `docker logs classroom-drill-backend 2>&1 | grep "cleanup stats"` cho `total=20, active=20, idle=0, waiting=N` khi pool bị nghẽn. Với `DB_POOL_LEAK_DETECTION_MS` (drill mặc định 30 000 ms) backend ghi stack trace khi một kết nối bị giữ quá lâu.

## Kết quả tham chiếu (drill, Docker Desktop 8 nhân, 2026-09-30, 200 học viên)

| Chỉ số | Trước khi sửa (R20) | Sau khi sửa |
| --- | --- | --- |
| 200 lượt nộp bài đồng thời | 5–6 thành công / 194–195 lỗi 500, ~60 s, readiness treo 30 s | **200/200**, p95 0,89 s (khởi động nguội 1,26 s), tổng 0,9–1,3 s, readiness luôn 200 (p99 0,17 s) |
| Bảng xếp hạng khớp điểm thưởng | (chưa kịp: 195 lượt thất bại) | sau 0,5–1,1 s |
| 200 lượt bắt đầu làm bài đồng thời | p95 6,7 s (8,1 s ở đánh giá), tổng 6,9 s | p95 1,09 s (nguội 1,97 s), tổng 1,14 s |
| Tự lưu ~200 lần/giây | p95 279 ms (864 ms ở đánh giá); ~17 câu SELECT mỗi lần lưu | p95 34 ms (nguội 113 ms); 7–8 câu lệnh mỗi lần lưu |
| Bảng tin 10 bài x 20 bình luận | 819 câu lệnh, 0,73 s (trang 50 bài: 3738 câu lệnh) | 13 câu lệnh (kể cả 2 câu của bộ lọc JWT), độ dài không phụ thuộc số bình luận |
| Danh sách thành viên Studio (2001 người) | 6011 câu lệnh, 3,0 s | 7 câu lệnh, trang 50 người |
| 100 phiên đọc, bảng tin trang 1 | p95 2757 ms | p95 66 ms (mọi lượt đọc p95 78 ms) |
| 200 người vào MỘT lớp: độ trễ chiếu MongoDB/Neo4j (R20-05) | 415 s | 6 s |
| Thăm dò rảnh của outbox với 300 000 hàng `PROCESSED` (R20-05) | 1009 ms MySQL | 0,3 ms |
| Dừng MongoDB 120 s: lượt thi hết hạn được chốt sau (R20-04) | 123 s / 67 s (chỉ khi MongoDB trở lại) | 4 s / 6 s (ngay trong đợt ngừng) |
| Dừng MongoDB 120 s: dồn backlog sau khi chạy lại | 108 s | 18 s |
| Dừng Neo4j 120 s (R20-04) | 1 `DEAD_LETTER` + 30 sự kiện kẹt, không tự phục hồi | 0 `DEAD_LETTER`, dồn xong sau 24 s |
| 50 tệp 20 MB `complete` song song | readiness p99 6,7 s | readiness p99 0,9 s (đo đối chứng `/health` không dùng DB: 0,14 s) |

## Biến môi trường

| Biến | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `BASE_URL` | `http://127.0.0.1:13000` | Địa chỉ frontend (nginx) của drill; trong mạng Docker là `http://frontend` |
| `API_URL` | `BASE_URL/api/v1` | Ghi đè địa chỉ API |
| `LOADTEST_ALLOW_REMOTE` | (không đặt) | `1` cho phép đích không phải máy cục bộ — xem cảnh báo |
| `LOADTEST_ALLOW_MAIN_STACK` | (không đặt) | `1` cho phép cổng mặc định của stack thật — xem cảnh báo |
| `LOADTEST_STATE`, `LOADTEST_STATE_DIR` | `state.json`, `loadtest/.state` | Tên/thư mục file trạng thái (smoke dùng `smoke.json`) |
| `USERS` | `40` | Số học viên của `smoke.js` |
| `LOADTEST_NETWORK` | `classroom-drill_classroom-net` | Mạng Docker mà `run-in-drill.*` gắn vào (phải chứa "drill") |

## Dữ liệu lớn cho kịch bản đọc

`loadtest/sql/seed-bulk.sh` nạp thêm ~1800 thành viên, 2000 bài viết và 40 000 bình luận vào lớp trong `.state/state.json` bằng SQL trực tiếp vào `classroom-drill-mysql` (mật khẩu MySQL lấy từ biến môi trường **của container**, không đọc `.env`; script từ chối container không có "drill" trong tên).

## Dọn dẹp
```powershell
docker compose -p classroom-drill -f infra/compose.yaml -f infra/compose.demo.yaml -f infra/compose.drill.yaml --env-file infra/.env down -v
```
(Chỉ với `-p classroom-drill`. Không bao giờ `down -v` với project thật `online-classroom`.)
