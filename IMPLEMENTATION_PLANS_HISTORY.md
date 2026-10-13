# Lịch sử kế hoạch triển khai

## 2026-10-01 — Hoàn thiện web, dùng máy hiện tại làm server (Round 23)

Yêu cầu: hoàn thiện backlog trong bản bàn giao, ngoại trừ tích hợp cổng thanh toán thật. Người dùng xác nhận sản phẩm là web; không triển khai ứng dụng Android/iOS.

- Vận hành: sao lưu hai stack hiện có, xoay secret bằng lệnh của từng CSDL trên volume đang dùng, tái tạo container và kiểm tra xác thực/readiness. Không xóa volume.
- Bảo mật: review và kiểm thử riêng lớp PRIVATE/PAID, mã mời, settlement/refund/expiry; làm sạch file cấu hình cũ; chuẩn bị loại `.env` khỏi lịch sử Git trong bản sao riêng trước khi thay lịch sử remote.
- Thư viện: cập nhật bản vá Spring Boot và React Router có tồn tại trên registry, chạy lại backend/integration/frontend và E2E.
- FR-13: mở rộng giới thiệu lớp thành nhiều mục có ảnh, giữ nội dung/nội quy và quyền ABOUT:EDIT hiện tại. Căn cứ giao diện: AboutTab, Studio About, tokens/CSS hiện có. Migration mới, không sửa migration đã áp dụng.
- Vận hành nhiều backend: chia sẻ trạng thái rate limit, giữ job nền bền vững và khóa/lease trên CSDL; kiểm chứng bằng hai instance cùng nguồn dữ liệu.
- Web server tại máy: HTTPS ingress, secure cookie, CORS chính xác, tắt demo/sandbox ở đường truy cập ngoài, restart/backup/restore/monitoring. Internet phụ thuộc IP/tên miền và router thực tế.
- Trợ năng: WCAG 2.2 AA bằng axe trên các route, kiểm tra bàn phím, focus, zoom/mobile; ghi rõ phần chưa được kiểm chứng bằng công nghệ hỗ trợ.
- Dữ liệu: chính sách lưu trữ/xóa/export, phân biệt chứng từ tài chính, audit và projection; không tự xóa dữ liệu người dùng hiện có.

Nghiệm thu dựa trên kết quả chạy mới, không dùng số test của bản bàn giao làm bằng chứng hiện tại. Commit chứa thay đổi đã kiểm thử; force push lịch sử remote cần được người dùng xác nhận trên kết quả cụ thể.

### Bổ sung trợ năng video trong vòng 23

V42 thêm `lessons.captions_vtt`; nội dung bài học hiện có làm bản chép lời/mô tả hình ảnh. Studio tạo/sửa WebVTT, LessonView hiển thị track tiếng Việt và bản chép lời. Caption tuân theo cùng quyền COURSE:EDIT và quyền học của nội dung bài; DTO của bài bị khóa không chứa phụ đề. Kiểm chứng bằng Chrome đọc cue thực từ video MinIO, sửa và tải lại.

### Tối ưu kiểm thử tải sau nghiệm thu chức năng

Đo riêng từng kịch bản; không chạy build/test khác trong lúc đo. Giảm ghi rate-limit của autosave bằng lượt đặt trước từ ngân sách MySQL chung; kiểm chứng giới hạn đồng thời, hết hạn, cache không cấp lượt ngoài reservation. Giữ các ngưỡng tải ban đầu. Đo lại cấu hình HTTPS hai backend, báo rõ kết quả cold/warm và giới hạn phần cứng nếu chưa đạt.
# Bổ sung ngày 2026-10-02: tự lưu tại ranh giới budget

Kết quả tải trên server HTTPS hai node phát hiện 8 lần tự lưu trả 429 dù mỗi học viên chưa dùng hết 300 lượt/phút. Khi IO đã tiêu hết thời gian an toàn của một batch đặt trước, bỏ batch và kiểm tra lại đúng một lượt bằng thao tác MySQL nguyên tử; tuyệt đối không dùng lượt cache đã hết hạn. Kiểm thử hồi quy phải phân biệt budget hiện tại còn/hết, và chứng minh batch cũ không được dùng cho yêu cầu kế tiếp. Giữ nguyên ngưỡng NFR và kiểm tra lại stack thật. Loại fallback mật khẩu Neo4j khỏi cấu hình mặc định.
# Bổ sung 2026-10-02: đọc danh sách kỳ thi theo batch

Chẩn đoán tải cho thấy danh sách kỳ thi có truy vấn lặp theo số kỳ thi. Gom số lượt làm, bài IN_PROGRESS và số câu hỏi trong các query theo lớp/danh sách id; đánh giá canEnter bằng cùng nhánh policy cũ với context chỉ sống trong một request. Không cache quyền qua request hoặc thay điều kiện preview/cancel/deadline/closed/archived/audience. Kiểm thử đối chiếu policy batch với policy đơn, kiểm tra count thật và đo lại feed trên HTTPS.
# Bổ sung 2026-10-02: chi phí xác thực mỗi request

Performance Schema cho thấy nhiều COMMIT/SET trên đường đọc; xác thực user và blacklist đang mở read transaction độc lập trước transaction nghiệp vụ. Dùng projection năm trường cho user và một query tồn tại blacklist, không mở transaction riêng cho hai SELECT này. Vẫn kiểm tra DB mỗi request, không cache ACTIVE/role/revocation qua request. Việc ghi/cleanup blacklist giữ transaction. Test phải chứng minh role lấy từ DB, DELETED/INACTIVE/missing bị từ chối và DB lỗi không fallback sang JWT; chạy lại integration MySQL và phép đo tải riêng.
# Bổ sung 2026-10-02: snapshot công bố kỳ thi, V43

Gate 200 học viên còn chậm riêng pha bắt đầu. V43 thêm bảng exam_publication_snapshots tách khỏi metadata, lưu learner JSON không có đáp án và grading JSON nội bộ khi công bố, cùng transaction/khóa exam. Lượt bắt đầu sao chép payload đã chuẩn bị sau toàn bộ kiểm tra quyền/lịch/limit; preview vẫn dựng đề hiện tại. Startup backfill legacy theo keyset từng 100 id, khóa từng exam để chịu hai node; exam legacy không câu hỏi giữ lỗi từ chối cũ. Không sửa migration đã áp dụng. Test phải chứng minh snapshot cố định, grading không lộ, legacy/cancel/preview vẫn đúng; chạy toàn bộ backend/integration và đo 200 học viên lại.
D-25 bổ sung cấu hình JDBC MySQL: cache prepared statement giới hạn 250/connection, SQL dài tối đa 2048, server prepared statement và theo dõi trạng thái session/autocommit ở driver. Áp dụng cùng cấu hình trên stack chính/demo và integration; chạy lại rollback/race trên MySQL rồi đo tải. Không cache dữ liệu hay quyền, không đổi isolation/độ bền COMMIT. Căn cứ: HikariCP MySQL Configuration và Connector/J Performance Extensions.
D-25 bổ sung đường bắt đầu thi: đọc số lượt đúng một lần sau khi lấy khóa `(exam,user)` rồi truyền vào policy, vẫn kiểm tra đầy đủ member/lịch/audience/hạn lượt. `isActiveStaff` kiểm tra assignment trước, chỉ đọc membership/expiry nếu assignment ACTIVE; giữ nguyên phép AND và kiểm tra expiry trực tiếp. Test đối chiếu removed/blocked/expired và không có assignment; integration race/rollback giữ nguyên.
D-25 bổ sung persistence: ExamAttempt dùng `Persistable` với cờ transient và callback PostPersist/PostLoad, theo mẫu Spring Data cho ID gán sẵn. Bài mới persist trực tiếp; bài đã tải vẫn merge khi cập nhật. Test MySQL kiểm tra lifecycle, giữ cùng ID và cập nhật không tạo bản ghi mới; cờ nội bộ không xuất hiện trong JSON. Thử pool 25 không cải thiện, quay lại 20.

## Bổ sung 2026-10-02: chốt nghiệm thu và fixture server

Giữ nguyên kịch bản cold/ngưỡng; thêm kịch bản độc lập mở trang đề trước khi bắt đầu, ghi riêng preload. Media yêu cầu đủ N lần complete và xuất JSON, giữ gate readiness tương đối. Chạy lại Chrome trên hai stack V43, quét axe toàn bộ và Round23/captions; kiểm tra hai node rồi về một node mặc định. Sau kiểm tra, script operator chỉ nhận diện tài khoản giả bằng đồng thời tên/email reserved, kiểm tra project container, lưu trạng thái trước, audit và vô hiệu hóa/lưu trữ trong giao dịch, giữ hồ sơ. Xác minh JWT bị thu hồi ở cả hai node, bật lại lịch backup và kiểm tra manifest đủ bốn kho trước commit. Chuẩn bị bản Git sạch có tree giống bản kiểm thử; vẫn cần xác nhận riêng trước ghi đè GitHub.

## 2026-10-11 — Question Builder lớp 1–12 và đợt đổi giao diện (D-34, D-35)

Căn cứ: `Dac_ta_Question_Builder_Lop_1_12.txt` (P0 §XI) và bản mẫu `Classroom - Lop Hoc Truc Tuyen (offline).html` (shell Sidebar/Topbar, Studio "Bài tập & đề", Builder ba cột, tab "Bài tập", làm bài/kết quả/chấm).

- Backend: module mới `modules/assessment` song song với `exam` (không đụng ExamService). Migration từ V50: danh mục cấp học/lớp/môn/thành phần/ngôn ngữ/chủ đề nạp từ `assessment/catalog-v1.json` bằng seeder idempotent; `assessments` lưu cây Đề > Phần > Nhóm > Câu dạng JSON có `revision` (409 khi lệch); `assessment_versions` bất biến (learner_json/grading_json, content_hash) tạo khi giao; assignments, attempts, answers, rubrics; tham chiếu media không nhân bản file.
- 28 kiểu câu là enum có phase; P0 dùng được: Q01–Q12, Q13/Q21 container, Q22. Q04 là một câu có nhiều mệnh đề. Bộ chấm tách container / kiểu nhập / chiến lược chấm; AUTO/MANUAL/HYBRID/PASS_FAIL, điểm một phần, sai số số học, chuẩn hóa văn bản giữ dấu tiếng Việt, rubric, tổng nhóm = tổng câu con. Trộn nhóm/câu con/phương án theo seed lưu ở lượt làm, đánh số sau khi trộn.
- Bảo mật: DTO học viên dựng theo whitelist, không chứa answerKey/feedback/transcript/rubric; audio phát qua `/plays` kiểm tra lượt nghe phía server và cấp URL ngắn hạn; ghi âm Speaking qua recording-intent riêng, chỉ chủ bài và người chấm nghe được. Quyền staff mới `ASSESSMENT:{VIEW,CREATE,EDIT,DELETE,PUBLISH,GRADE}`, audit tạo/giao/chấm/hoàn tất/xóa.
- Frontend: `features/assessment/**` (API, kiểu dữ liệu, Builder, TypePicker 28 kiểu, ConfigPanel, Preview desktop/mobile, kiểm tra đề, AssignDialog, tab Bài tập, làm bài có ngữ liệu dính + thanh audio + ghi âm có hướng dẫn khi bị từ chối micro, kết quả, GradePanel); shell mới và reskin theo token Connecty sẵn có, giữ nhãn trợ năng hiện tại.
- Ngoài phạm vi đợt này: tương tác P1/P2 ngoài danh mục, TTS, STT/AI, QTI/Word/PDF, sandbox code, chuyển mã audio.
- Kiểm thử: unit cho từng bộ chấm P0, HTTP test chứng minh không lộ khóa/transcript và đề đã giao không đổi khi sửa ngân hàng; vitest cho reducer builder (sắp xếp, đánh số, tổng điểm, kiểm tra đề); `npm run typecheck`, `npm run build`, toàn bộ `mvn test`.

## 2026-10-13 — hoàn thiện Question Builder P0–P3 trên source hiện tại

Người dùng xác nhận mở phạm vi sang toàn bộ P0–P3, gồm đủ 28 kiểu, import, ma trận và báo cáo. Đối chiếu source phát hiện backend thiếu policy/service/controller/persistence và sáu trang frontend còn placeholder; giữ nguyên shell/model/reducer đã có. Kế hoạch và bảng yêu cầu nằm trong `docs/ASSESSMENT_IMPLEMENTATION.md`. Hoàn thiện luồng dữ liệu/phân quyền/snapshot trước, sau đó tương tác P1/P2, import/export, ma trận/biến số/gợi ý/ngân hàng/báo cáo. Giữ nguyên migration đã áp dụng, không đụng ExamService. Kiểm chứng frontend/Java/integration/Chrome và cập nhật nghiệm thu bằng kết quả thật. Không commit/push thay đổi riêng của người dùng trong đợt này khi chưa được yêu cầu.


### Bổ sung nghiệm thu Question Builder — 13/10/2026

Căn cứ vẫn là đặc tả P0–P3 và HTML offline của người dùng. Rà soát kết nối JPA/JDBC khi bind media, idempotency/quyền uploader, thời lượng recording thực, quota ghi sau xóa/reload, review audio theo quyền công bố, QTI SCORE/manifest và accessibility tên/role/tương phản. Bổ sung AssessmentPrivacyExport vào privacy data copy: own attempts/answers/upload metadata, ẩn điểm/nhận xét trước công bố; không xuất grading snapshot/object key. Test H2 SQL riêng và API MySQL/MinIO thực tế; đối chiếu cả nền tảng cũ bằng toàn bộ E2E. Chỉ cập nhật app/broker trong project classroom-demo, giữ database credentials/volume và rollback image. Không thay project BĐS/cổng 3000.

E2E phát hiện regression trình tạo khóa học: lần bấm “Tiếp tục” đầu tiên sau nhập bài học chữ flush thành công nhưng validation/save đọc course cũ. Cập nhật courseRef đồng bộ trước setState và dùng course mới sau flush; test tái hiện phải fail trước sửa, pass sau sửa, rồi kiểm chứng lại Chrome trên image cuối. Các script mobile/axe cần chọn tab trong đúng nav và đợi animation/font hoàn tất; không sửa product chỉ để xử lý selector hoặc ảnh chụp giữa animation. Private/paid chạy trên project QA dùng volume riêng, cùng image nhưng seed/sandbox bật; không bật giả lập thanh toán trên server giữ dữ liệu. Sau nghiệm thu dọn đúng fixture mới theo giới hạn UTC, xác minh JWT thu hồi/tài khoản seed giữ nguyên, backup ba kho online và xác minh hash.

### Bổ sung trước commit/push — 13/10/2026

Quét index cho repository công khai phát hiện credential live MySQL/MongoDB/Neo4j/MinIO trùng mẫu đã có trong `.env.example`; độ dài 16+ không đủ khi giá trị đã công khai. Xoay năm secret datastore bằng giá trị ngẫu nhiên 64 ký tự; giữ JWT/webhook, image, port, network, volume và dữ liệu. Script mới `rotate-current-stack-secrets.py` chỉ chấp nhận project được nêu rõ, preview mặc định, yêu cầu backup bốn kho cho server giữ dữ liệu, lưu recovery có ACL riêng và rollback khi thất bại. Thử trên QA dùng một lần trước prod; xác minh auth mới/credential cũ bị từ chối, count dữ liệu/volume không đổi, MinIO và health public; quét lại index trước commit. Không force-push lịch sử GitHub.
