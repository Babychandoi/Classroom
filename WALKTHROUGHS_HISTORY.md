# Lịch sử nghiệm thu triển khai

## Round 23 — 01/10/2026: hoàn thiện backlog web và server tại máy

Thực hiện theo yêu cầu hoàn thiện web, ngoại trừ cổng thanh toán thật. Thiết kế UI theo AboutTab/Studio và hệ màu hiện có. Không triển khai app native.

### Phần xây dựng

- Hạ tầng: `recreate-stacks.ps1`, xoay secret DB trên volume thật, Caddy HTTPS, overlay hai backend và nginx cân bằng tải; secure cookie, CORS chính xác, MinIO HTTPS. File cấu hình cũ đã được dọn. Mật khẩu DB/JWT/webhook ngẫu nhiên 64 ký tự; tài khoản mẫu của server chính được xoay và giữ dữ liệu.
- ClassAbout + media ABOUT: V38, DTO/service/controller, optimistic version, upload ảnh và tab có bàn phím tại AboutTab/StudioCommunity.
- Rate limit: V39, SharedRateLimitStore/MySqlAuthThrottle; bucket và lockout dùng giờ MySQL, các backend dùng chung.
- Outbox/Neo4j: V40, OutboxLeaseStore, claim token khi finalize; MembershipState dùng sequence chặn sự kiện cũ đảo trạng thái mới.
- Dữ liệu cá nhân: V41, PrivacyService/Controller/Request, DataRightsPanel, `/privacy`; export chính chủ có xác nhận mật khẩu, yêu cầu xóa và xử lý có audit, thu hồi phiên/ẩn danh hóa.
- Trợ năng video: V42, captionsVtt trong Lesson/DTO, StudioCourses tạo/sửa WebVTT, LessonView hiển thị cue và bản chép lời; cùng permission của bài học.
- Thư viện: Spring Boot 3.5.16, React Router 7.18.4, Vite 8.3.2; frontend Node 22 và npm ci. `npm audit` không còn vulnerability.
- Vận hành: backup retention 30 ngày, lịch 03:00, HTTPS health mỗi 5 phút và resume lúc đăng nhập Windows; hướng dẫn LAN, CA, Internet và chính sách dữ liệu.

### Bảng kiểm thử

| Nhóm | Kết quả mới | Bằng chứng trên máy |
|---|---|---|
| Backend JUnit/policy | **999**, 0 failure/error | backend-final-validation.log, Docker Java 21 |
| Integration bốn kho thật | **96**, 0 failure/error | .artifacts/integration-auth-read-validation.log; V1..V42 |
| Frontend Vitest | **369**, 57 file, 0 failure | frontend-captions-validation.log, Node 22 |
| TypeScript strict + Vite build | Thành công, 0 lỗi | frontend-build-final-validation.log và Docker build |
| Chrome E2E demo | **210/210**, 10 script pass | e2e-deployed-final-validation.log; đầy đủ private/paid/mời/refund |
| Chrome E2E HTTPS chính | **182/182**; 9 script chạy + e2e5 bỏ qua có chủ đích | e2e-https-final-validation.log; sandbox chính tắt, luồng đó đã chạy ở demo |
| Attack/regression Round23 | **16/16** | round23-deployed-validation.log: private concealment, invite race, purchase, forged webhook, refund, privacy, ảnh/About |
| Hai backend thật | Pass | multinode-deployed-validation.log; JWT, cookie và load balancer trong https-deployed-validation.log |
| Caption thật | Pass | captions-deployed-validation.log; Chrome đọc/sửa cue, transcript, không lỗi CSP |
| WCAG 2.2 quét tự động | **29 trang × 2 môi trường**, 0 violation | axe-report.json hai lượt; ACCESSIBILITY_REVIEW.md ghi giới hạn manual |
| Backup/restore | Hai backup trước xoay + backup sau; restore đủ bốn kho thành công vào project riêng | backup-final-validation.log, restore-validation.log; main giữ nguyên |

Lỗi test integration do worker lịch nền đọc fixture đã commit được sửa bằng transaction cô lập fixture; test pipeline xóa persistence cache sau bulk update. Không nới assertion nghiệp vụ. Frontend được chạy với hai worker để tránh timeout khi đồng thời build/test backend trên cùng máy.

### Luồng người dùng và vận hành

Chủ lớp vào Studio → Giới thiệu để thêm/sắp xếp mục, upload ảnh và mô tả; lưu bị xung đột phiên bản sẽ báo để tải lại. Người học dùng tab About bằng bàn phím. Studio khóa học cho phép nhập WebVTT và bản chép lời/mô tả video; học viên thấy captions theo quyền học hiện tại.

Hồ sơ của tôi → Dữ liệu cá nhân: nhập mật khẩu để tải bản sao hoặc gửi yêu cầu xóa. PLATFORM_ADMIN xem cùng panel, ghi lý do/căn cứ trước khi xử lý. Khi còn lớp ACTIVE đang sở hữu, phải lưu trữ/chuyển quyền trước khi đóng tài khoản. Chứng từ, bài thi/tệp cần giữ được rà soát riêng; không tuyên bố xóa sạch mọi dấu vết.

Server: https://192.168.1.7 / https://localhost; demo localhost:13000. Firewall LAN đã bật qua UAC; CA có trong LocalMachine Root, Chrome smoke xác minh TLS thật. Quy trình chính, chứng chỉ CA, mật khẩu được bảo vệ và nhiệm vụ tự chạy: `docs/SERVER_ON_THIS_PC.md`. Chưa xác nhận kết nối từ thiết bị LAN thứ hai, Internet, reboot thật hoặc hoàn tất pháp lý của đơn vị vận hành. Cổng thanh toán thật nằm ngoài yêu cầu.

### Bảng ánh xạ route toàn hệ thống

| Nhóm | Route |
|---|---|
| Đăng nhập/khám phá/mời | `/login`, `/classes`, `/join/:code` |
| Lớp | `/classes/:slug/{feed,learn,exams,leaderboard,documents,members,about,store}` |
| Bài học/thi | `/classes/:slug/learn/lessons/:lessonId`, `/classes/:slug/exams/:examId/{attempt,result}` |
| Hồ sơ | `/me/profile`, `/classes/:slug/members/:userId`, `/privacy` |
| Studio lớp | `/studio/classes/:classId/{overview,courses,exams,grading,leaderboard,members,staff,segments,store,audit,feed,documents,about,settings}` |
| API mới | `/api/v1/privacy/me/{export,deletion-requests,requests}`, `/api/v1/privacy/requests`, `/api/v1/privacy/requests/:userId` |

Các bảng trên phản ánh kiểm thử hiện tại, không dùng số test cũ của bản bàn giao làm kết quả mới. Tình trạng lịch sử Git và các bước còn cần quyền hệ thống ghi ở tài liệu vận hành/báo cáo Git.

### Bổ sung 2026-10-02 — batch metadata và xác thực

Gom số lượt làm/bài đang làm/số câu hỏi theo lớp; policy dùng cùng logic nhưng context chỉ sống trong request. MySQL test chứng minh bỏ cancelled/preview/người khác và tiếp tục bài sau khi đóng kỳ thi; unit đối chiếu policy đơn/batch ở các ranh giới member/archive/schedule/PRO/preview. Xác thực lấy năm trường user, vẫn đọc ACTIVE/role/blacklist mỗi request, bỏ hai transaction chỉ đọc riêng. Test đảm bảo không tin role JWT, không nhận DELETED/INACTIVE/missing và không fallback khi DB lỗi. Backend **999/999**, integration **96/96**, không failure/error/skip.

Test đo query ban đầu lệch 13/12 vì Statistics toàn ứng dụng tính cả scheduler. Chuyển sang StatementInspector với ThreadLocal đếm đúng thread request, giữ nguyên assertion số query bằng nhau và control N+1. Lượt full `backend-final-validation.log` qua đủ 999.

Server mặc định một JVM trên PC sau đối chiếu tải; hai node bật bằng `-TwoBackends`, không bỏ khả năng dùng budget/database/job chung. Script build một lần, backend ready trước frontend; chuyển routing trước khi dừng node phụ đúng project. `server-default-final-validation.log` xác nhận luồng mới hoạt động.

### Bổ sung 2026-10-02 — gate tải và quota ranh giới

Backend sau sửa quota ranh giới: **994/994 PASS**, không failure/error/skip (`backend-boundary-validation.log`). Batch hết lifetime bị bỏ và yêu cầu hiện tại kiểm tra lại bằng MySQL nguyên tử; test xác minh không tái dùng batch cũ và không cho qua khi budget mới hết. Cấu hình mặc định bỏ fallback mật khẩu Neo4j. Bản published exam không truy vấn quyền tác giả vốn chỉ dùng cho draft.

Đã đo lại tải HTTPS/hai node tuần tự. Một lượt 50 học viên pass nhưng lượt khởi động lại không đạt; 200/200 bài nộp đầy đủ và không mất/sai/trùng đáp án, song start/save/readiness còn vượt ngưỡng. Lượt sau có 8 save 429 trước bản sửa ranh giới. Feed/media chưa đạt; NAT/auth pass. **Không nghiệm thu gate tải**; bảng số/log và giới hạn chẩn đoán ghi trong `docs/PERFORMANCE_REVIEW.md`. Giữ nguyên ngưỡng, không giảm độ bền database hoặc bỏ kiểm tra quyền để đạt kết quả.

### Nghiệm thu mới nhất 2026-10-02 — V43 và server mặc định

Mục này cập nhật kết quả hiện tại; các số ở mục trước giữ làm lịch sử. V43 thêm ExamPublicationSnapshot/entity/repository, upgrade theo batch và giao dịch công bố trong ExamService. Bản người học không chứa đáp án; bản chấm điểm nội bộ được sao chép vào attempt. ExamAudiencePolicy dùng count đã đọc dưới khóa, AccessPolicy tránh đọc roster khi không có assignment; ExamAttempt dùng Persistable cho UUID gán sẵn. URL JDBC của ứng dụng/integration có prepared cache giới hạn. Không thêm route giao diện hoặc endpoint; bảng route toàn hệ thống ở trên vẫn áp dụng. Migration V43 đã chạy trên main/demo, không sửa migration đã áp dụng.

| Nhóm | Kết quả hiện tại | Artifact trên máy, ngoài Git |
|---|---|---|
| Backend đầy đủ | **1005/1005**, 0 failure/error/skip | `.artifacts/backend-persistable-validation.log` |
| Integration bốn kho thật | **99/99**, Flyway V1..V43 | `.artifacts/integration-persistable-validation.log`; snapshot rollback/legacy/lifecycle MySQL thật |
| Frontend | **369/369**, 57 file; TypeScript/Vite build pass, npm audit 0 | Frontend không đổi sau bộ kiểm tra `frontend-captions-validation.log`/`frontend-build-final-validation.log` |
| Chrome demo | **194/194**, 10 script | `.artifacts/e2e-v43-demo-release-validation.log` |
| Chrome HTTPS chính | **166/166**, 9 script chạy; 28 bước sandbox bỏ qua đúng cấu hình | `.artifacts/e2e-v43-https-release-validation.log` |
| Axe đầy đủ chạy riêng | **30/30 mỗi môi trường**, 29 trang × 2, **0 violation** | `.artifacts/a11y-v43-{https,demo}-full-validation.log`; ACCESSIBILITY_REVIEW.md ghi report |
| Private/paid/invite/refund/privacy | **16/16** attack/regression | `.artifacts/round23-v43-demo-validation.log` |
| WebVTT/transcript/CSP | PASS trong Chrome | `.artifacts/captions-v43-demo-validation.log` |
| Hai node, quota/JWT chung | PASS với image V43 mới ở cả hai | `.artifacts/multinode-v43-final-validation.log`, `https-v43-multinode-smoke.log` |
| Một node mặc định, TLS/cookie/Chrome | PASS | `.artifacts/server-v43-default-validation.log`, `https-v43-default-smoke.log` |
| Fixture server | **661** tài khoản giả INACTIVE, **25** lớp thử ARCHIVED; hồ sơ giữ nguyên/audit/refresh thu hồi | `.artifacts/fixture-maintenance-v43-validation.log`: JWT trước 200/200, sau 401/401; preview lần sau 0 |
| Backup bằng Scheduled Task đã bật lại | Đủ **4 kho**, task kết thúc **0**, VerifyOnly toàn bộ hash PASS; lần kế tiếp 03:00 03/10 | `infra/backups/20261002-040424/backup.json`, `.artifacts/backup-v43-verify-validation.log`: MySQL 108766981 B, Mongo 601273 B, Neo4j 104473 B, MinIO 152 tệp/2122767704 B |

Lỗi chuẩn bị test V43 ban đầu do collation mặc định của disposable MySQL không khớp FK; sửa trước khi deploy và chạy lại đầy đủ. Test rollback dùng repository spy ném lỗi trong giao dịch thật, không cấp SUPER cho tài khoản database. Bộ Chrome demo từng dùng nhầm cổng MinIO 9000, đã chạy lại đúng 19000 và toàn bộ pass. Kiểm tra thu hồi fixture dùng token đăng nhập mới để không nhầm token hết hạn với thu hồi.

Tải chạy tuần tự trên HTTPS thật, một backend/pool 20: 200 người đã mở trang đề **PASS** (start p95 1262 ms, save 57, submit 1367, readiness p99 244, đủ 200 bài và 0 mất/sai/trùng); feed **PASS**; 50×20 MB media **PASS theo gate readiness tương đối**, không nhận là ngưỡng tuyệt đối 1000; NAT/auth 200 **PASS**. **200 kết nối HTTPS mới vẫn FAIL start p95 1925 > 1500**, còn lại request và bài/đáp án đầy đủ. Không đổi mặc định/ngưỡng hoặc thay báo cáo cold bằng preload. Chi tiết và phép đo TLS đọc riêng nằm trong PERFORMANCE_REVIEW.md.

Luồng sử dụng: chủ lớp tạo đề nháp → công bố (snapshot được lưu cùng giao dịch) → học viên mở đề/bắt đầu/lưu/nộp → công bố kết quả. Quyền hiện tại và thời hạn vẫn kiểm tra; xem thử vẫn đọc đề hiện hành. Server dùng `start-server.ps1` một node, `-TwoBackends` cho kiểm tra phân tán. Fixture maintenance chỉ chạy sau đợt test được phép; seed mới cho lần sau. Backup/credential/ảnh/log và trạng thái phục hồi fixture không đưa lên Git.

Chưa tuyên bố hoàn tất Internet, thiết bị LAN thứ hai/reboot thật, WCAG thủ công hoặc dữ liệu pháp lý của đơn vị vận hành. Thanh toán thật ngoài phạm vi. Lịch sử GitHub cần xác nhận riêng trước force-with-lease; báo cáo bản sạch chỉ có giá trị sau khi chạy và đối chiếu tree/object. Không nghiệm thu toàn bộ NFR khi cold HTTPS còn mở.

## Nghiệm thu 2026-10-13 — Question Builder P0–P3, release 20261013-063537

Mục này thay số nghiệm thu hiện tại; các mục 02/10 phía trên giữ làm lịch sử. Căn cứ là `Dac_ta_Question_Builder_Lop_1_12.txt`, `Classroom - Lop Hoc Truc Tuyen (offline).html` và source chưa commit của người dùng. Phạm vi mở đến P3 theo xác nhận: đủ 28 kiểu câu, nghe/nói, import, ma trận và báo cáo. Giữ shell/token/model/các thay đổi riêng trên đĩa; không stage/commit/push, không viết lại GitHub và không khôi phục file `recreate-stacks.ps1` đã bị xóa.

### Module và thay đổi đã hoàn thiện

- Backend `modules/assessment`: catalog + policy, JDBC store, authoring/validation, bản đề cố định khi giao, assignment/attempt/autosave/submission, graders/rubric, code queue/worker, deadline worker, matrix, Word/QTI interchange, báo cáo và privacy export. Catalog JPA được đăng ký trong application scan. V50–V54 đã áp dụng; không sửa migration đã chạy.
- Frontend `features/assessment`: sáu trang thật, builder cây ba cột/chuyển vùng mobile, editor/preview/input Q01–Q28, nghe kế thừa theo cấp đề/phần/nhóm/câu/phương án, recorder, nộp minh chứng, rubric, biến số, ngân hàng/gợi ý, import preview/mẫu, ma trận, báo cáo/CSV và in đề giữ media/ngữ liệu nhóm.
- Media controller/service/repository: kiểm tra uploader/quyền trước kết quả idempotent, flush asset trước binding JDBC, thời lượng đo từ tệp thật, quota ghi còn sau xóa/reload; URL nội bộ chỉ chủ bài/người chấm được quyền truy cập. Kết quả review/transcript/điểm/kỹ năng tuân thủ chính sách công bố. PrivacyService xuất own attempts/answers/upload metadata, không grading snapshot/object key và không điểm/nhận xét trước công bố.
- `infra/assessment-sandbox`, compose và script setup/deploy: Python/JavaScript chạy container không mạng, filesystem chỉ đọc, giới hạn tài nguyên/thời gian/output; queue ngoài giao dịch HTTP. Scheduler tối thiểu 12 để timeout/background job không bị code worker chiếm hết. Deploy chỉ thay backend/frontend/broker, giữ database credentials/volume và lưu image rollback.
- E2E nền tảng phát hiện lỗi wizard thật: sau nhập bài học chữ và bấm “Tiếp tục”, flush hoàn thành nhưng validation/save dùng course cũ. `StudioCourseWizard.tsx` dùng courseRef cập nhật đồng bộ; test tái hiện fail trước sửa, pass sau sửa và Chrome G5 đạt trên image cuối. Mobile test chọn aria-current trong đúng nav; axe đợi font/animation hữu hạn hoàn tất. Không đổi màu sản phẩm để sửa số đo giữa animation.

### Bảng kiểm thử hiện tại

| Nhóm | Kết quả | Bằng chứng trên máy, ngoài Git |
|---|---|---|
| Backend toàn bộ | **1.217**, 0 failure/error/skip | `.artifacts/assessment-backend-complete-tests.log` |
| Integration database thật | **101**, 0 failure/error/skip | `.artifacts/assessment-integration-tests.log`; project integration dùng một lần đã dọn |
| Frontend toàn bộ | **720**, 98 file | `.artifacts/assessment-frontend-release-720-tests.log` |
| TypeScript + Vite + image | PASS | `.artifacts/assessment-wizard-final-deploy.log`; còn cảnh báo bundle lớn |
| API/MySQL assessment | PASS | `.artifacts/assessment-api-acceptance.log`: catalog 28, quyền, start đồng thời, snapshot, autosave/reload/409, sandbox, ma trận/import/báo cáo, privacy own-only |
| MinIO/audio/recording | PASS | `.artifacts/assessment-media-complete.log`: quota nghe đồng thời, URL riêng, binding/uploader, metadata giả, recording/delete/reload, công bố review/transcript/kỹ năng, privacy |
| Chrome assessment desktop/mobile | PASS | `.artifacts/assessment-browser-final.log`; Q01–Q28, import preview, ma trận, báo cáo, audio thật, microphone giả Chrome ghi/upload/nghe lại/reload và fallback khi mô phỏng từ chối |
| Axe assessment | **8 trạng thái**, 0 violation | `.artifacts/assessment-browser/axe-report.json`; screenshot desktop/mobile được xem và đối chiếu prototype |
| Chrome nền tảng tại server 13000 | **9 script / 199 bước đạt** | `.artifacts/assessment-existing-e2e-acceptance.log` cho e2e/race/e2e2/netwatch/e2e4/origin; `.artifacts/assessment-live-e2e-rechecks.log` cho mobnav **8/8**, e2e3 **32/32**, axe **35/35** sau sửa |
| Chrome private/paid ở QA riêng | **30/30** | `.artifacts/assessment-demo-qa/private-paid-e2e.log`; cùng image release, fresh database, seed/sandbox bật chỉ ở QA 23000 |
| Attack/regression private/paid | **16/16** | `.artifacts/assessment-demo-qa/security-round23.log`: private concealment, invite race, duplicate purchase, webhook giả, refund, quyền privacy/closure, ảnh/About |
| Axe toàn nền tảng hiện tại | **35 trạng thái**, 0 violation | `e2e/shots/a11y-mv5wa71k/axe-report.json`; không cộng với số cũ 29 của 02/10 |
| Sandbox tấn công thử | PASS | `.artifacts/assessment-sandbox-verification.log`: Python/JS, network/filesystem, timeout/memory/token/output/language |
| Audit dependency frontend runtime | **0 advisory** | `.artifacts/assessment-production-audit.json`; source-map-js 1.2.2; toolchain dev còn 7 advisory (5 high/2 moderate) |
| Script và whitespace | PASS | `.artifacts/assessment-release-script-check.log`, `.artifacts/assessment-release-diff-check.log`; thêm node --check ba script assessment/Round23 |
| Triển khai hiện tại | backend/frontend/broker **healthy** | `.artifacts/assessment-release-acceptance.json`, `.artifacts/deploy-20261013-063537/manifest.json` |
| Dọn fixture server | **22 tài khoản INACTIVE / 10 lớp ARCHIVED** | `.artifacts/assessment-fixture-cleanup.log`: JWT fresh 200/200 → 401/401, seed giữ nguyên, preview sau 0; giữ hồ sơ và audit/trạng thái trước |
| Backup online + VerifyOnly | **3 kho**, hash PASS | `infra/backups/20261013-064310/backup.json`, `.artifacts/assessment-release-backup-verify.log`: MySQL 3.367.367 B, MongoDB 47.677 B, MinIO 137 tệp/5.063.856 B; Neo4j offline dump bỏ qua để không dừng server |

Lượt all E2E ban đầu có lỗi thực ở wizard, selector mobile/đo tương phản giữa animation, và e2e5 thiếu seed/sandbox. Không đánh dấu lượt đó pass: ba script lỗi được sửa và chạy lại trên image cuối; e2e5 và Round23 chuyển sang QA riêng có đúng điều kiện. Tổng 10 script đạt qua hai môi trường, không coi đây là một lượt chạy tất cả trên cấu hình server tắt sandbox. QA riêng đã gỡ đúng container/volume/network của nó; server giữ dữ liệu và dịch vụ BĐS không bị dừng.

### Luồng sử dụng và vận hành

Server hiện tại: `http://localhost:13000`, backend loopback 8080, media theo origin server đang dùng. Project tên `classroom-demo` nhưng demo seed và giả lập thanh toán đều tắt; đây là server giữ dữ liệu. 3000 đang thuộc BĐS. Backend/frontend chạy image `classroom/assessment-{backend,frontend}:20261013-063537`; broker healthy. Cập nhật bằng `infra/scripts/deploy-current-stack.ps1` theo RUNBOOK, không recreate database bằng cấu hình/mật khẩu cũ. Root `.env` cũ chuyển vào artifact ignored; `infra/backup-20260930-235558.env` không còn. Bí mật live được giữ, các credential database thực tế có độ dài 16+; không xuất secret trong bằng chứng.

Giáo viên: Studio → Bài tập & đề → tạo/import/ma trận → chọn khối/môn → thêm phần/nhóm/câu, đáp án/điểm/rubric/media → preview/kiểm tra → giao lớp/phân khúc. Học viên: Bài tập → mở lượt → lưu/nộp. Giáo viên mở kết quả → nghe/mở minh chứng → chấm/trả bài. Học viên xem điểm/kỹ năng/transcript theo thời điểm công bố. Sửa ngân hàng/nháp sau giao không thay đề đã giao.

### Bảng bổ sung route toàn hệ thống

Các route nền tảng ở bảng 02/10 phía trên vẫn giữ; bổ sung sáu route sau (placeholder đã thay bằng trang thực tế):

| Giao diện | Route |
|---|---|
| Danh sách bài tập học viên | `/classes/:slug/homework` |
| Làm bài | `/classes/:slug/homework/:assignmentId/attempt` |
| Kết quả học viên | `/classes/:slug/homework/:assignmentId/result` (`?attempt=` cho lượt cũ) |
| Danh sách đề/ngân hàng/import/ma trận Studio | `/studio/classes/:id/assessments` |
| Builder + preview/giao bài | `/studio/classes/:id/assessments/:assessmentId/edit` |
| Báo cáo/chấm/trả bài | `/studio/classes/:id/assessments/results/:assignmentId` |
| API assessment | `/api/v1/assessment-catalog`, `/api/v1/classes/:classId/{assessments,homework,question-bank,rubrics,assessment-import,assessment-matrix,question-suggestions}`, `/api/v1/assessments/*`, `/api/v1/assessment-assignments/*`, `/api/v1/assessment-attempts/*`; chi tiết contract trong `docs/API.md` |

### Giới hạn còn được ghi nhận

Word import đọc văn bản/bảng/ảnh/OMML; Word export là bản đọc cơ bản kèm native JSON, không nhận fidelity bằng bản in rich media. QTI 2.1 chuẩn cơ bản Q01/Q02/Q10/Q12; phần nâng cao/quy tắc chấm giữ native JSON kèm cảnh báo. Chưa cấu hình hoặc kiểm chứng LMS của một nhà cung cấp cụ thể. Chủ đề/yêu cầu cần đạt nhập theo nội dung dạy; không tuyên bố đã quản trị mọi phiên bản chương trình quốc gia. TTS/STT/AI bên ngoài chưa tích hợp.

Microphone giả của Chrome chứng minh luồng ghi/upload, không chứng minh thiết bị di động vật lý. Chưa có phiên NVDA/VoiceOver hay nghiệm thu WCAG thủ công đầy đủ. Queue code chưa thử tải nhiều máy/JVM dừng lâu hơn lease; bản ghi đã nộp giữ theo hồ sơ chấm và DATA_POLICY. Toolchain dev còn advisory; audit runtime 0 không xóa giới hạn đó. Chưa chạy lại HTTPS/LAN/Internet/cold HTTPS gate lịch sử trong đợt assessment; kết quả cold fail trong PERFORMANCE_REVIEW vẫn chưa được nghiệm thu lại. Thanh toán thật ngoài phạm vi. Không coi đợt này là đạt toàn bộ NFR hoặc phát hành Internet.

### Xác minh domain production public — 13/10/2026

Sau câu hỏi “lên prod chưa”, đã kiểm tra live routing: Cloudflare Tunnel `poiplegal.online` → `frontend:80` trên mạng `classroom-demo_classroom-net`, cùng frontend/backend đã triển khai release `20261013-063537`. HTTPS public trả 200 cho trang và `/api/v1/health`; asset `/assets/index-CGR_4Rwv.js` và nội dung bundle trùng server 13000. Backend/frontend/broker đều healthy. Catalog anonymous trả 401 theo xác thực. Artifact `.artifacts/assessment-public-release-verification.json` lưu thời điểm, status, asset, header Cloudflare và image hiện chạy, không chứa credential. Bản mới đã được phục vụ trên domain production; kiểm tra này bổ sung bằng chứng public build/health, không biến các luồng người dùng/tải/WCAG chưa chạy trên domain thành đã đạt. Chưa commit/push.

### Xuất bản source GitHub theo yêu cầu — 13/10/2026

Người dùng yêu cầu commit + push. Remote `Babychandoi/Classroom` công khai, nhánh `main` trùng HEAD `d6237d7` trước đợt này. Phạm vi commit gồm source Question Builder/shell hiện tại, V50–V54, tests, hạ tầng, tài liệu và hai file tham chiếu thiết kế/đặc tả. Giữ xóa `recreate-stacks.ps1` của người dùng. Quét index với giá trị secret live/đã thu hồi và mẫu token/private key, gồm giải mã tài nguyên trong HTML offline; artifact `.artifacts/assessment-publish-secret-audit.json` chỉ ghi số lượng/path/rule, không secret. Backup/runtime env/credential/fixture/ảnh kiểm thử được Git ignore. Kiểm chứng tính năng dùng đúng các kết quả 1.217 backend/101 integration/720 frontend/E2E và public build/health phía trên; không chạy lại chỉ để tạo commit. Push thường vào `main`, không ghi đè lịch sử GitHub.

#### Secret live và kết quả vận hành trước publish

Quét đầu tiên phát hiện các credential datastore đang chạy trùng literal mẫu công khai trong `.env.example`, gồm Neo4j xuất hiện trong test từ chối placeholder. Đây là credential live, không bỏ qua như false positive. Đã thêm `infra/scripts/rotate-current-stack-secrets.py`: preview/guard project, backup bốn kho, ACL recovery, đổi năm secret persisted, giữ image/port/volume/JWT/webhook, auth mới và từ chối auth cũ, rollback khi lỗi. Legacy `rotate-secrets.ps1` preflight cả hai stack trước mọi thay đổi và được kiểm tra từ chối topology hiện tại.

Drill trên `classroom-assessment-qa` đã kiểm chứng rotation và rollback thật. Mongosh khi chạy stdin tương tác có thể trả exit 0 dù auth lỗi; script dùng `--file /dev/stdin` để lỗi auth có exit code đúng. Lượt cuối `.artifacts/assessment-demo-qa/rotation-drill-final.log` PASS: năm secret 64 ký tự, volume/count giữ nguyên, credential cũ MySQL/Mongo/Neo4j/MinIO bị từ chối. QA container/network/cả sáu volume đã được dọn.

Prod `.artifacts/assessment-production-secret-rotation.log` PASS với cùng kiểm tra; `D:\classroom-server\infra\.env` và `infra/.env` được đồng bộ/protect ACL. Credential cũ đã vô hiệu; JWT/webhook không đổi. Backup bốn kho `20261013-115607` trước rotation và online ba kho `20261013-121231` sau rotation đều VerifyOnly PASS; Mongo và MinIO hash không đổi, MinIO vẫn 137 tệp/5.063.856 B. Script deploy `-SkipBuild -SkipBackup` tạo lại manifest/rollback với credential mới: `20261013-121220`, nội dung image giữ nguyên bản đã kiểm thử. HTTPS `poiplegal.online` trang/health 200, bundle public trùng local sau rotation. Các dịch vụ BĐS không bị dừng.

Quét lại toàn index và 44 tài nguyên giải mã trong HTML offline không phát hiện secret/token/private key. Hai nơi chứa mẫu development có sẵn được ghi nhận là ví dụ/test sau khi xác minh chúng không còn là credential runtime. Secret, dữ liệu backup, recovery và artifact không nằm trong commit; source mới không chứa credential được tạo trong rotation. Không xóa lịch sử GitHub bằng force push trong đợt này.
