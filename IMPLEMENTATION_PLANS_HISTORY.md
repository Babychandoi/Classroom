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
