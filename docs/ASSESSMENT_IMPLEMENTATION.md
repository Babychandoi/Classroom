# Question Builder — đối chiếu và triển khai 13/10/2026

Căn cứ: `Dac_ta_Question_Builder_Lop_1_12.txt`, bản thiết kế `Classroom - Lop Hoc Truc Tuyen (offline).html` và source trên đĩa (kể cả chưa commit). Người dùng xác nhận làm toàn bộ P0–P3 ngày 13/10/2026. Các mục hiện trạng/kế hoạch dưới đây giữ mốc bắt đầu; kết quả triển khai và kiểm chứng mới nằm ở phần cuối. Web chạy trên PC này; thanh toán thật ngoài phạm vi.

## Hiện trạng khi bắt đầu

- Có catalog, enum 28 kiểu, model cây, phần lớn graders P0; thiếu AssessmentPolicy, validation server, repository/service/controller cho authoring/assignment/attempt/grading.
- Có contract API frontend, reducer/model, input P0, audio/recorder; sáu trang assessment chỉ có placeholder “Đang tải…”. Frontend có lỗi TypeScript ở icon và thao tác cây.
- V50–V52 chưa commit; phải kiểm tra Flyway runtime trước khi quyết định migration bổ sung. Không sửa migration đã chạy.
- Shell/token và các commit mới của người dùng là nền tảng hiện tại. Không khôi phục hoặc ghi đè những thay đổi riêng này; không commit/push trong đợt triển khai khi chưa được yêu cầu.

## Phạm vi xác nhận

| Phần | Công việc | Kiểm chứng yêu cầu |
|---|---|---|
| P0 | Lớp/môn/chủ đề, ngân hàng, đề cây Section/Group/Item, Q01–Q12/Q13/Q21/Q22, nghe/nói, media, preview, giao/làm/nộp/chấm | Đầu-cuối, snapshot, quyền, autosave/reload, transcript, microphone fallback |
| P1 | Q14–Q20, hotspot/nhãn/phân loại/bảng/đồ thị/biểu thức/nhiều bước; rubric và mobile | Input/editor/grade từng loại; bàn phím thay kéo thả; không chạy biểu thức tùy ý |
| P2 | Q23–Q28, canvas/file/video/code/mô phỏng/dự án | Media riêng tư; sandbox code có giới hạn/cô lập; ghi nhận minh chứng và chấm rubric |
| P3 | Đề theo ma trận, import/export định dạng nội bộ/Word/Excel/QTI, biến số, gợi ý từ ngân hàng, báo cáo kỹ năng và trao đổi LMS | Preview import trước lưu, giới hạn dung lượng/không thực thi macro; ma trận đủ tiêu chí; báo cáo dựa trên bài thật |
| Thiết kế | Sáu trang assessment, builder ba cột/cây bên trái/editor giữa/config bên phải, sidebar/topbar đã có | Đối chiếu HTML thật trên desktop/mobile; focus/error/empty/loading |

TTS/STT/AI bên ngoài chỉ dùng khi có dịch vụ/quyền sử dụng; không giả lập kết quả AI thành chấm chính thức. Giao thức trao đổi LMS phải được phân biệt với việc đã kết nối một nhà cung cấp cụ thể.

## Kiến trúc và tính bất biến

Module assessment song song exam. Snapshot khi giao bài giữ nội dung/đáp án/rubric/media; sửa bản nháp/ngân hàng không thay đề đã giao. Server dựng DTO người học theo whitelist, không gửi answerKey/transcript/feedback chưa công bố. Quyền ASSESSMENT kiểm tra lớp/thành viên hiện tại; mọi write kiểm tra lớp bị khóa và audit.

Lưu nháp dùng revision; chặn ghi đè bản mới bằng request cũ. Bắt đầu/ghi câu trả lời/nộp/lượt nghe/ghi âm serialize bằng khóa đúng phạm vi. Nộp và chấm không nhân đôi bài/điểm. Media yêu cầu MIME thực, owner/class/reference; mọi truy cập chính chủ hoặc giáo viên có quyền chấm.

## Kế hoạch kiểm thử

Chạy typecheck và backend compile để xác định baseline; thêm kiểm thử grading/validation/projection/permission/concurrency quan trọng; chạy unit/frontend và integration MySQL/MinIO thật với migration mới. Kiểm tra các luồng giáo viên/học viên bằng Chrome, bao gồm import, ma trận, nhóm audio, Speaking, reload và xuất/in. Cập nhật WALKTHROUGHS_HISTORY.md bằng số thực, tách kết quả đã chạy và giới hạn môi trường.

## Kết quả triển khai P0–P3

| Phần | Chức năng trên source hiện tại |
|---|---|
| P0 | Danh mục khối 1–12/môn/ngôn ngữ; ngân hàng theo lớp; Section → Group → Item; Q01–Q12, nhóm Q13/Q21; giao lớp/phân khúc; thời gian, số lượt, trộn câu, hạn nộp; lưu nháp/phục hồi/nộp/chấm/trả bài |
| Audio/Speaking | Audio cấp đề/phần/nhóm/câu/phương án; upload/thay tệp giữ câu con; chính sách nghe kế thừa; Q22 microphone hoặc tải tệp, nghe lại/xóa/ghi lại; server kiểm tra thời lượng thật; quota ghi giữ sau xóa/reload; transcript công bố theo chính sách |
| P1 | Q14 hotspot, Q15 nhãn, Q16 phân loại, Q17 bảng, Q18 đồ thị, Q19 biểu thức/hoá học, Q20 nhiều bước; kéo thả có thao tác thay thế; parser biểu thức giới hạn; rubric trọng số/mức đạt và Đạt/Chưa đạt |
| P2 | Q23 canvas, Q24 nộp tệp, Q25 video, Q26 Python/JavaScript sandbox, Q27 mô phỏng, Q28 dự án/minh chứng; giáo viên mở/nghe minh chứng riêng tư và chấm rubric |
| P3 | Import preview JSON/CSV/XLSX/DOCX/QTI; mẫu Word/Excel/CSV; ma trận dạng câu/mức độ/kỹ năng/khối/môn, seed đổi bộ câu; biến số đề; gợi ý từ ngân hàng; báo cáo câu/nhóm/phần/kỹ năng và từng học viên; CSV kết quả/QTI trao đổi LMS |
| Thiết kế | Sáu trang thực tế thay placeholder; builder ba cột desktop/chuyển vùng mobile; preview; block nội dung an toàn, ảnh/bảng/công thức; in đề giữ ngữ liệu nhóm một lần |

Ngân hàng theo lớp và quyền ASSESSMENT; gợi ý dùng dữ liệu thật, không gọi AI. Chủ đề/yêu cầu cần đạt nhập theo nội dung dạy; catalog seed không được coi là bộ quản trị mọi phiên bản chương trình quốc gia. V50–V54 đã chạy trên MySQL hiện tại và không bị sửa sau áp dụng.

Code nộp bài chạy ngoài transaction qua queue MySQL/worker; container giới hạn CPU/RAM/time/output, filesystem chỉ đọc và không mạng. Chạy thử chỉ dùng test công khai; test chấm riêng không trả về người học. Generic media URL không mở tệp assessment. Upload-complete kiểm tra quyền/binding/uploader trước trả kết quả idempotent; ffprobe kiểm tra thời lượng thực. Bản sao dữ liệu cá nhân gồm bài làm/minh chứng mới, ẩn điểm/nhận xét chưa công bố và toàn bộ grading snapshot.

## Kiểm chứng trên máy

| Kiểm chứng | Bằng chứng |
|---|---|
| Backend toàn bộ | `.artifacts/assessment-backend-complete-tests.log`: 1.217 test, 0 failure/error/skip; gồm hai test SQL privacy assessment |
| Integration toàn bộ | `.artifacts/assessment-integration-tests.log`: 101 test, 0 failure/error/skip, database/container thật trong project dùng một lần đã dọn |
| Frontend toàn bộ | `.artifacts/assessment-frontend-release-720-tests.log`: 98 file, 720 test qua; gồm regression flush bài học chữ trước “Tiếp tục” |
| TypeScript + Vite | `.artifacts/assessment-frontend-final-build.log`: build thành công; còn cảnh báo bundle lớn |
| API + MySQL thật | `.artifacts/assessment-api-acceptance.log`: catalog 28, quyền, 5 start đồng thời, đáp án/test ẩn, autosave/reload/409, snapshot, chấm sandbox, báo cáo, XLSX round-trip, ma trận đủ/thiếu, privacy own-only |
| MinIO/audio thật | `.artifacts/assessment-media-complete.log`: 5 play đồng thời chỉ 2 qua; chặn URL đường vòng; metadata thời lượng giả; binding/owner; idempotency và quota ghi/xóa/reload; công bố transcript/kỹ năng/review audio; privacy ẩn điểm/nhận xét chưa công bố |
| Chrome thật | `.artifacts/assessment-browser-final.log`: Q01–Q28, autosave, import, ma trận tạo nháp, báo cáo, desktop/mobile 390px không tràn; audio phát; microphone ghi/upload/nghe lại/reload; mô phỏng từ chối microphone có fallback |
| Trợ năng mới | `.artifacts/assessment-browser/axe-report.json`: 8 trang/trạng thái, 0 violation cho các rule WCAG A/AA được chạy; ảnh desktop/mobile trong cùng thư mục |
| Sandbox tấn công thử | `.artifacts/assessment-sandbox-verification.log`: Python/JS, timeout/memory/network/filesystem/token/output/language |
| Dependency runtime frontend | `.artifacts/assessment-production-audit.json`: `npm audit --omit=dev`, 0 advisory; source-map-js đã lên 1.2.2 |
| Nền tảng cũ trong Chrome | 9 script trên server 13000 đạt tổng 199 bước; `.artifacts/assessment-existing-e2e-acceptance.log` cho 6 script đạt, `.artifacts/assessment-live-e2e-rechecks.log` cho 3 script đã sửa và chạy lại đạt; lần hỏng ban đầu không tính là pass |
| Lớp riêng tư/trả phí | Cùng image release, stack QA riêng 23000: `e2e5.js` 30/30 và `round23.js` 16/16; `.artifacts/assessment-demo-qa/{private-paid-e2e,security-round23}.log`. Server giữ dữ liệu vẫn tắt demo seed/payment sandbox |
| Trợ năng toàn nền tảng | `e2e/shots/a11y-mv5wa71k/axe-report.json`: 35 trạng thái, 0 violation; tách với 8 trạng thái assessment |
| Triển khai/dọn dữ liệu/backup | App đã kiểm thử ở release `20261013-063537`; manifest sau xoay secret `20261013-121220` giữ cùng nội dung image. 22 tài khoản thử INACTIVE, 10 lớp thử ARCHIVED, JWT 200 → 401; backup ba kho `20261013-064310`, bốn kho trước rotation `20261013-115607` và ba kho sau `20261013-121231`, VerifyOnly hash đạt |

Một lượt static detector lưu tại `.artifacts/assessment-design-detector.json`: 7 nhận xét. Cảnh báo heuristic màu cùng dòng JSX và viền ô trống được đối chiếu với ảnh/prototype; không phải lỗi axe. Đã sửa tương phản mã Qxx, nhãn và role thật được axe phát hiện. Kết quả E2E nền tảng, privacy, triển khai cuối và dọn fixture được ghi trong WALKTHROUGHS_HISTORY; không dùng lần chạy hỏng giữa rebuild làm kết quả nghiệm thu.

## Giới hạn kiểm chứng

- Chrome desktop thật và viewport điện thoại đã kiểm tra; chưa có iPhone/iPad/Android vật lý hoặc phiên VoiceOver/NVDA. Axe không phải chứng nhận toàn bộ WCAG.
- DOCX import đọc văn bản/bảng/ảnh/OMML công thức. Word export có bản đọc cơ bản và JSON kèm để giữ cấu trúc; bản in HTML/PDF là luồng rich media đầy đủ. Không tuyên bố fidelity Word bằng bản in.
- QTI hỗ trợ tương tác cơ bản Q01/Q02/Q10/Q12; dạng khác và quy tắc chấm riêng giữ trong `classroom/document.json` với cảnh báo. Chưa cấu hình/kiểm chứng nhà cung cấp LMS cụ thể. Nguồn chuẩn: [IMS QTI 2.1](https://www.imsglobal.org/question/qtiv2p1/imsqti_infov2p1.html).
- Bản ghi/minh chứng đã nộp không tự xóa theo tuổi file; xử lý yêu cầu dữ liệu cần rà soát theo DATA_POLICY. Queue code chưa được thử tải nhiều máy/tình huống JVM dừng quá thời hạn lease.
- Tailwind 3 toolchain còn advisory dev dependency, gồm [braces chưa có bản vá](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm) và [postcss-selector-parser](https://github.com/advisories/GHSA-rj75-hqrm-r3gf). Audit runtime 0 không thay thế audit build tooling; không ép nâng major hệ thiết kế trong lượt này.
- HTTPS/LAN/Internet và cold HTTPS load gate lịch sử chưa được nghiệm thu lại. PC phục vụ Classroom ở cổng 13000; cổng 3000 hiện thuộc dự án khác.

## Luồng sử dụng

Studio → Bài tập & đề → tạo đề/mẫu/import/ma trận → khối/môn/ngôn ngữ → thêm phần/nhóm/câu → đáp án/điểm/rubric/media → xem trước → kiểm tra đề → giao bài. Học viên vào Bài tập làm/lưu/nộp; giáo viên mở kết quả, nghe minh chứng/chấm và trả bài; học viên xem điểm/kỹ năng/transcript theo quyền công bố.

Cập nhật stack bằng `infra/scripts/deploy-current-stack.ps1` (RUNBOOK). Theo yêu cầu commit/push ngày 13/10, xuất bản source, đặc tả, thiết kế tham chiếu và tài liệu nghiệm thu lên GitHub; cấu hình chứa secret, backup, fixture và artifact kiểm thử giữ ngoài Git.
