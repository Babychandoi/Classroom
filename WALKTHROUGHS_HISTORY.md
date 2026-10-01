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
