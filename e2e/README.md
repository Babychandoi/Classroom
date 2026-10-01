# Bộ kiểm thử E2E trình duyệt thật (Playwright)

Bộ script này lái một trình duyệt Chrome thật qua giao diện của hệ thống (đăng ký, tạo lớp, soạn khóa học/đề thi, học viên vào lớp và làm bài, chấm bài, đăng xuất giữa nhiều tab, giao diện điện thoại...). Nó bổ sung cho unit test (`frontend-test`, `backend-test`) và integration test: chỉ E2E mới bắt được lỗi chỉ xuất hiện trên trình duyệt thật như CSP chặn tài nguyên, race khi khởi tạo phiên, hay bố cục vỡ ở màn hình hẹp.

> **CẢNH BÁO — chỉ chạy trên môi trường cục bộ/thử nghiệm dùng-rồi-bỏ. KHÔNG BAO GIỜ trỏ vào production.**
> Mỗi lần chạy **tạo dữ liệu thật** trên hệ thống đích và **không dọn dẹp**: người dùng `e2e.*@example.com` (mật khẩu cố định `E2ePassw0rd!`), lớp `e2e-class-<run>`, khóa học, đề thi, sản phẩm, tài liệu, tệp tải lên MinIO, bài đăng, bài làm và điểm. Các script từ chối chạy nếu `BASE_URL` không phải `localhost`/`127.x.x.x`/`::1`/`*.local` (đặt `E2E_ALLOW_REMOTE=1` chỉ khi đó là một môi trường thử nghiệm riêng của bạn). Muốn sạch dữ liệu: `docker compose -f infra/compose.yaml down -v`.

## Yêu cầu

1. **Stack đang chạy** và truy cập được tại `http://localhost:3000` (hoặc đặt `BASE_URL`):
   ```bash
   docker compose -f infra/compose.yaml up -d --build
   ```
   Cấu hình mặc định là đủ: bộ E2E tự đăng ký tài khoản, không cần tài khoản demo hay lớp phủ `compose.demo.yaml`. Cổng MinIO mặc định `9000` (đổi bằng `E2E_MINIO_PORT`).
   **Ngoại lệ:** `e2e5.js` (D-19: lớp riêng tư / trả phí / mã mời / hết hạn) cần stack **có lớp phủ demo** — hạt giống "Lớp Trả Phí" / "Lớp Riêng Tư (mã mời)", tài khoản `owner@classroom.local` / `student.free@classroom.local` và cổng thanh toán sandbox:
   ```bash
   docker compose -f infra/compose.yaml -f infra/compose.demo.yaml up -d --build
   ```
   Trên stack không có lớp phủ demo script đó **tự bỏ qua** (in lý do, mã thoát 0) nên `npm run all` vẫn chạy được ở mọi stack; đặt `E2E_DEMO=1` nếu muốn việc thiếu lớp phủ demo bị coi là lỗi (mã thoát 2).
2. **Node.js 20+** trên máy chạy script.
3. **Một trình duyệt**, một trong hai cách:
   - Dùng **Google Chrome đã cài trên máy** (mặc định, `channel: 'chrome'`) — không phải tải thêm gì.
   - Hoặc tải Chromium của Playwright: `npx playwright install chromium`, rồi chạy với `E2E_CHANNEL=chromium`.
     Dùng Edge: `E2E_CHANNEL=msedge`.

## Cài đặt

```bash
cd e2e
npm install
```

(`node_modules/` và `shots/` đã được `e2e/.gitignore` loại khỏi git.)

## Chạy

```bash
npm run all          # chạy lần lượt cả 10 script bên dưới rồi in bảng PASS/FAIL tổng kết (e2e5 tự bỏ qua trên stack không có lớp phủ demo)
# hoặc từng script:
npm run e2e          # node e2e.js       - hành trình chính J1-J6 (PHẢI chạy đầu tiên)
npm run race         # node race.js      - race khởi tạo phiên
npm run followups    # node e2e2.js      - kiểm tra bổ sung
npm run netwatch     # node netwatch.js  - theo dõi mạng / CSP / font
npm run mobnav       # node mobnav.js    - điều hướng trên điện thoại
npm run round18      # node e2e3.js      - vòng 18: hộp thoại, thứ tự khóa, bàn phím, khách, mạng, độ trễ refresh
npm run round20      # node e2e4.js      - R20-07: tự lưu bài làm qua 502/mất mạng (page.route thay cho restart backend), 'online' lưu ngay, F5 khôi phục, API xác nhận
npm run round22      # node e2e5.js     - D-19: lớp riêng tư, lớp trả phí, mã mời (/join/:code), thành viên hết hạn; Studio thiết lập + quản lý mã mời (CẦN lớp phủ demo, nếu không thì SKIP)
npm run origin       # node origin.js    - R19-02: đăng ký/đăng nhập cùng nguồn tại đúng BASE_URL (mọi cổng/tên miền), Origin lạ vẫn 403
npm run a11y         # node a11y.js      - quét trợ năng axe-core (tùy chọn)
npm run check        # chỉ kiểm tra cú pháp các script (không cần stack)
```

Biến môi trường:

| Biến | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `BASE_URL` | `http://localhost:3000` | Địa chỉ frontend (nginx) của stack cần kiểm thử |
| `E2E_CHANNEL` | `chrome` | `chrome`, `msedge`, hoặc `chromium` (bản tải bằng `playwright install`) |
| `HEADED` | (không đặt) | `HEADED=1` để hiện cửa sổ trình duyệt |
| `E2E_MINIO_PORT` | `9000` | Cổng MinIO công khai (dùng để nhận diện upload/presigned URL và host hợp lệ) |
| `E2E_DEMO` | (không đặt) | `1` = `e2e5.js` coi việc stack thiếu lớp phủ demo là lỗi (mã thoát 2) thay vì bỏ qua |
| `E2E_ALLOW_REMOTE` | (không đặt) | `1` để cho phép `BASE_URL` không phải máy cục bộ — xem cảnh báo trên |
| `E2E_RUN`, `E2E_CLASS_SLUG`, `E2E_CLASS_ID` | lấy từ `shots/last-run.json` | Chạy các script phụ trên dữ liệu của một lần chạy `e2e.js` cụ thể |
| `E2E_PAGING_FILL` | (không đặt) | `1` để `e2e2.js` tự tạo thêm lớp cho đến khi vượt 50 và kiểm phân trang (chậm) |
| `NETWATCH_MS` | `6000` | Độ dài cửa sổ theo dõi mạng của `netwatch.js` (ms) |

Mã thoát: `0` = mọi bước đạt, `1` = có bước hỏng, `2` = lỗi môi trường/cấu hình. Mỗi script in bảng **PASS/FAIL theo từng hành trình** ở cuối; bước hỏng được chụp ảnh `FAIL-*.png`. Ảnh và `report.json` nằm trong `shots/`.

## Mỗi script kiểm gì

| Script | Phạm vi |
| --- | --- |
| `e2e.js` | **J1** đăng ký chủ lớp, tạo lớp, F5 giữ phiên, tab thứ hai còn đăng nhập, người đã đăng nhập mở `/login` được chuyển đi. **J2** Studio: tạo khóa học/chương/bài video (upload mp4 lên MinIO) hiện ngay không cần đóng-mở lại, xuất bản, tạo đề thi + câu hỏi trắc nghiệm/tự luận hiện ngay trong bảng soạn, công bố, tạo và xuất bản sản phẩm, tải tài liệu PDF. **J3** học viên: đăng ký, bấm "Tham gia lớp ngay" ngay khi trang tải, đăng bài, xem video từ MinIO (presigned GET + CSP), đánh dấu hoàn thành, tải tài liệu, làm bài thi (tự lưu, F5 giữa bài vẫn khôi phục, nộp bài, số lượt đã dùng). **J4** chấm bài tự luận, học viên thấy điểm và có mặt trên bảng xếp hạng, rồi duyệt mọi trang Studio (14) và mọi tab lớp (8): không `[role=alert]`, không lỗi console/CSP/mạng ngoài nhiễu đã biết. **J5** đăng xuất ở tab này thì tab kia cũng mất phiên (kể cả sau khi tải lại). **J6** giao diện 390px: không tràn ngang trên các trang học viên và Studio, nội dung Studio không bị đẩy xuống thấp. |
| `race.js` | Đua giữa khởi tạo phiên và hành động đầu tiên: nút "Tham gia lớp ngay" không hiện trước khi phiên khôi phục xong; với `/auth/refresh` chậm 800ms, người đã đăng nhập bấm tham gia ngay vẫn vào lớp (không bị đưa sang `/login`); mở thẳng trang lớp chỉ gọi 1 lần `GET /classes/slug/...`, 1 lần `/auth/refresh`, không 401; khách bấm tham gia → `/login` → đăng nhập xong quay lại đúng trang lớp. |
| `e2e2.js` | Studio thêm chương khi khóa đang mở (hiện ngay, nằm cuối, đổi thứ tự được); lịch thi không lệch múi giờ khi lưu không sửa và xóa được; bảng xếp hạng lọc theo kỳ thi; phân trang danh sách lớp "Xem thêm lớp học" (chỉ kiểm được khi có > 50 lớp, nếu không sẽ ghi `SKIP`); không có dialog/lỗi console bất thường. Đọc dữ liệu từ `shots/last-run.json` do `e2e.js` ghi. |
| `netwatch.js` | Với bảng xếp hạng, tab Cửa hàng và Cửa hàng Studio: đếm request trong một cửa sổ thời gian (không request nào lặp > 3 lần; `/auth/refresh`, `/me`, `/payments/sandbox-status` tối đa 1 lần), không vi phạm CSP, không gọi host ngoài, và font Plus Jakarta Sans **tự host** đã được nạp và đang áp dụng. Dùng `node netwatch.js student /classes/<slug>/feed` để soi một trang bất kỳ. |
| `e2e3.js` | **Vòng 18** (chạy sau các script khác vì đổi dữ liệu lớp E2E: thêm một trợ giảng, xóa một khóa). **G1** hộp thoại phân quyền trợ giảng cao hơn cửa sổ (1366x768): ô chọn thành viên và nút "Lưu quyền" bấm được bằng chuột thật, lưu được quyền theo khóa, danh sách hiện tên khóa; lặp lại ở 390x844; Escape đóng và trả tiêu điểm. **G2** mọi hộp thoại còn lại (khóa học/kỳ thi/sản phẩm/phân khúc/tạo lớp/nhân sự) ở cửa sổ thấp 1024x420 vẫn cuộn được và nút cuối bấm được. **G3** thứ tự khóa học = thứ tự tạo, mũi tên đổi chỗ và giữ sau khi tải lại, trợ giảng theo khóa không thấy mũi tên. **G4** xóa khóa có quyền trợ giảng gắn riêng -> 409 nêu tên, chỉ hiện trong đúng thẻ khóa. **G5** Góc học tập: Tab tới từng thẻ khóa (nút thật, có vòng focus), Enter chọn. **G6** Cài đặt lớp: "Đã lưu cài đặt lớp học." hiện ra, trang không bị dựng lại. **G7** khách ở 5 tab dành cho thành viên thấy lời mời đăng nhập (không 401, không `/auth/refresh` thừa), đăng nhập quay lại đúng tab; lời mô tả hồ sơ PUBLIC. **G8** mất mạng -> thông báo tiếng Việt. **G9** 15 lần `POST /auth/refresh` liên tiếp cách nhau 5s (đặt `R18_REFRESH_SPACING_MS`) đều < 1s. |
| `e2e5.js` | **Vòng 22 / D-19** (chỉ trên stack có lớp phủ demo; tự tạo người dùng `e2e.*` và một lớp `e2e5-<run>` riêng, không đụng các lớp hạt giống ngoài việc mua/hoàn tiền/gia hạn của một người mua mới). **P1** khách: danh sách khám phá không có lớp riêng tư, lớp trả phí có nhãn "Trả phí · 199.000đ / 30 ngày", chip lọc Tất cả/Miễn phí/Trả phí; URL lớp riêng tư → "Không tìm thấy lớp học" (404); tường phí ở bảng tin và tab thành viên; "Đăng nhập để mua" quay lại đúng lớp. **P2** `/join/<mã demo>`: thẻ lớp, `<meta name="referrer" content="no-referrer">` chỉ tồn tại khi ở trang mời, mã không nằm trong tiêu đề, đăng nhập `student.free` → tham gia → bảng tin, nút Back không quay lại URL chứa mã, vào lại lớp bằng URL thường, mã sai/hỏng → "Mã mời không hợp lệ hoặc đã hết hạn" (mã hỏng không gọi máy chủ). **P3** người dùng mới mua lớp trả phí: tường phí → hộp thoại thanh toán → chủ lớp xác nhận sandbox trong Studio → thành viên (hạn ~30 ngày). **P4** hết hạn không sửa mã: chủ lớp *Hoàn tiền sandbox* → EXPIRED: banner "Gói thành viên lớp đã hết hạn ngày dd/MM/yyyy", chỉ còn tab Giới thiệu/Cửa hàng, tab thành viên hiện thẻ gia hạn (không 403 thô; API trả `MEMBERSHIP_EXPIRED`), Studio lọc "Đã hết hạn" có "Hết hạn dd/MM/yyyy" + nút xóa/chặn, rồi **Gia hạn** → vào lại. **P5** chủ lớp trong Studio: tạo lớp Riêng tư bằng hộp thoại, đổi Riêng tư ↔ Công khai **chỉ bằng bàn phím** (có xác nhận nêu hệ quả), tạo liên kết mời (7 ngày, 3 lượt) hiện **một lần** + cảnh báo + Sao chép (đọc lại clipboard) + Escape trả tiêu điểm, người mới tham gia bằng liên kết (lượt 1/3), đổi sang Trả phí 50.000đ / 7 ngày (xác nhận nêu "giữ quyền truy cập miễn phí trọn đời"; thành viên cũ không bị đụng), người mới **mua qua liên kết mời của lớp riêng tư + trả phí** (mã đi kèm đơn; chip "Sắp hết hạn · Gia hạn" vì còn ≤ 7 ngày), thu hồi liên kết → trang mời báo không hợp lệ, đặt hàng/tham gia bằng mã đã thu hồi → 404. **P6** axe-core (không lỗi critical) trên tường phí, trang mời (hợp lệ/không hợp lệ), trang 404, khám phá + hộp thoại tạo lớp, Cài đặt (cả hai khung xác nhận), quản lý mã mời (+ hộp thoại liên kết và thu hồi); bàn phím Tab+Enter tới nút chính. **P7** 390px: trang mời, tường phí, 404, khám phá, thẻ gia hạn, Studio Thành viên/Cài đặt không tràn ngang và nút chính nằm trọn trong màn hình. **P8** không có lỗi console/CSP/5xx/hộp thoại lạ ngoài các phản hồi 402/404 có chủ đích. Ảnh chụp ở `shots/d19-<run>/`. |
| `origin.js` | **R19-02** đăng ký, đăng xuất, đăng nhập lại qua giao diện tại đúng `BASE_URL` (cổng/tên miền nào cũng được: drill `13000`, `FRONTEND_PORT` tùy chỉnh, IP LAN) và không có lỗi 403/console; qua cổng frontend, `Origin` = `BASE_URL` với mật khẩu sai trả **401** (không phải 403), `Origin: https://evil.example` vẫn **403**, kể cả khi giả `X-Forwarded-Host`/`Forwarded`. `E2E_DEMO_LOGIN=1` thêm đăng nhập bằng tài khoản demo `owner@classroom.local` (stack có lớp phủ demo/drill). Tự đăng ký người dùng mới, không cần `last-run.json`. |
| `a11y.js` | axe-core (WCAG 2 A/AA) trên login, danh sách lớp, feed khách, lời mời đăng nhập, feed/Góc học tập/Luyện thi/hồ sơ của học viên, Studio khóa học/cài đặt/nhân sự (kể cả khi hộp thoại mở). Lỗi **critical** làm hỏng bước; **serious** in cảnh báo (đặt `A11Y_STRICT=1` để cũng làm hỏng). Tự bỏ qua nếu chưa cài `axe-core`; báo cáo JSON ở `shots/a11y-*/axe-report.json`. |
| `mobnav.js` | Ở 390x844: thanh Navbar (tên thương hiệu một dòng, không tràn), thanh tab của lớp (cuộn ngang được, có vùng mờ ở mép còn tab ẩn, tab đang mở nằm trong tầm nhìn), Studio (menu điều hướng thu gọn sau nút "Menu Studio", nội dung bắt đầu gần đầu trang). Lưu ảnh chụp để xem bằng mắt. |

Nhiễu trình duyệt đã biết và được bỏ qua có chủ đích (xem `unexpectedEvents` trong `lib.js`): `401` của lần khởi tạo phiên ẩn danh, và `404` của `GET /payments/sandbox-status` ở nơi không bật cổng thanh toán mô phỏng (mỗi lần tải trang chỉ hỏi một lần). Mọi 404/lỗi console khác đều làm hỏng bước.

## Cấu trúc

```
e2e/
├── lib.js            # BASE_URL, kiểm tra an toàn đích, khởi động trình duyệt, đăng ký/đăng nhập, ghi PASS/FAIL, lọc nhiễu
├── e2e.js  race.js  e2e2.js  netwatch.js  mobnav.js  e2e3.js  e2e4.js  e2e5.js  origin.js  a11y.js   # các script (xem bảng trên)
├── all.js            # chạy cả bộ và in bảng tổng kết
├── fixtures/         # tiny.mp4 (video 2 giây), tiny.pdf (PDF 1 trang) dùng để upload
└── shots/            # ảnh chụp + report.json + last-run.json (git-ignored, tự tạo)
```
