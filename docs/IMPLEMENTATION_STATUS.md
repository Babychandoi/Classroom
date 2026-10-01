# Trạng thái Triển khai Hệ thống Lớp học Trực tuyến (Implementation Status)

**Phiên bản:** 0.1.0
**Cập nhật:** 01/10/2026 (Round 23 - HTTPS tại máy, bảo mật, dữ liệu cá nhân, trợ năng)
**Chế độ:** DOCKER-FIRST (Zero-host-dependency runtime)

---

## 1. Bảng tổng hợp trạng thái các Phase

| Phase | Mô tả | Trạng thái | Ghi chú |
|---|---|---|---|
| **Phase 0** | Audit & Kế hoạch | **DONE** | Đã phân tích toàn bộ tài liệu 23 file, xác định kiến trúc, lập DECISIONS.md |
| **Phase 1** | Hạ tầng & Nền tảng (infra, compose, Dockerfile, Flyway, DBs) | **DONE** | compose.yaml, Dockerfile backend & frontend, cấu hình 4 DB/storage hoàn tất |
| **Phase 2** | Identity, Classroom Core, RBAC & AccessPolicy | **DONE** | User, Class, Member, Staff, Permissions, RBAC Audit, AccessPolicy |
| **Phase 3** | Khóa học & Học tập (Course, Section, Lesson, MinIO Media, Progress, Q&A) | **DONE** | MinIO upload intent, presigned URL ngắn hạn 15p, progress tracking |
| **Phase 4** | Cộng đồng (Feed, Post, Comment, Profile, About) | **DONE** | Feed visibility (PUBLIC, FREE, PRO, PRODUCT_OWNER, SEGMENT), Profile privacy |
| **Phase 5** | Thi cử (Exam, Questions, Attempts, Autosave, Auto/Manual Grading) | **DONE** | Idempotent submit, audience checks, snapshot câu hỏi, bảo mật answer key |
| **Phase 6** | Xếp hạng & Điểm thưởng (Leaderboard, Rank tiers, Reward rules) | **DONE** | Best attempt calculation, score correction audit, idempotent leaderboard |
| **Phase 7** | Phân khúc học viên (Segment, Whitelisted rules parser) | **DONE** | Không dùng raw SQL, AST parsing với whitelist criteria/operators |
| **Phase 8** | Thương mại (Product, Order, MockPaymentProvider, Webhooks, Entitlement) | **DONE** | Idempotency, snapshot giá, mock provider (PAID/REFUND/FAILED) |
| **Phase 9** | Outbox Worker & Projection (MongoDB, Neo4j) | **DONE** | Outbox pattern MySQL commit chung TX, idempotent projection |
| **Phase 10** | Frontend Web (React, TS strict, 8 tabs Class + Studio, Responsive) | **DONE** | Multi-stage Nginx build, API client, States (loading, empty, error, forbidden) |
| **Phase 11** | Bảo mật & Kiểm soát truy cập (IDOR, Cross-class, Answer guard) | **DONE** | Backend-enforced authorization trên mọi endpoint |
| **Phase 12** | Bộ kiểm thử | **DONE chức năng; cold HTTPS còn mở** | Backend 1005, integration 99 (V1..V43), frontend 369; Chrome demo 194/194, HTTPS 166/166; quét axe đầy đủ riêng 30/30 mỗi môi trường. Xem WALKTHROUGHS_HISTORY.md và PERFORMANCE_REVIEW.md. |
| **Phase 13** | Docker Verify (Build, Backend-test, Frontend-test) | **DONE** | 100% build và test thành công trong Docker container |
| **Phase 14** | Local Full Stack (Chạy up -d, healthcheck) | **DONE** | Tất cả 6 containers đều UP & HEALTHY |
| **Phase 15** | Reproducibility Test (Clean down -v && up) | **DONE** | Khởi động sạch từ zero-state và Flyway migration thành công. `DataSeedRunner` chỉ chạy khi bật lớp phủ demo tường minh (`infra/compose.demo.yaml`) — xem RUNBOOK §1.4 |
| **Phase 16** | Tài liệu hóa & Bàn giao (README, RUNBOOK, DOCKER) | **DONE** | README, RUNBOOK.md, DOCKER.md, API.md, DECISIONS.md hoàn thiện |

---

## 2. Chi tiết Module

### Backend
- **Identity & Auth:** DONE (JWT, BCrypt, Role-based)
- **Classroom & Staff Management:** DONE (Ownership, StaffAssignment, fine-grained Permissions)
- **Lớp riêng tư / lớp trả phí / mã mời (D-19):** DONE ở backend - `visibility` `PUBLIC|PRIVATE` (lớp riêng tư là 404 với người không có quan hệ, một quy tắc duy nhất trong `AccessPolicy` + bản SQL được test song song), `accessType` `FREE|PAID` (sản phẩm `CLASS_ACCESS`, mua qua đơn/webhook/entitlement hiện có, cộng dồn gia hạn, hoàn tiền thu hồi ngay, trọn đời hoặc theo ngày), mã mời (192 bit, chỉ lưu SHA-256, xem trước công khai + giới hạn tần suất, `maxUses` đúng tuyệt đối), trạng thái thành viên `EXPIRED` + bộ quét luồng riêng + sự kiện `MEMBER_EXPIRED`, grandfather khi `FREE -> PAID`. Giao diện cũng DONE (Studio cài đặt/mã mời, trang `/join/:code`, tường phí/gia hạn, thanh toán dùng chung, xử lý `PAYMENT_REQUIRED`/`INVITE_REQUIRED`/`MEMBERSHIP_EXPIRED`; E2E `e2e5.js` 28/28 trên stack demo, toàn bộ `npm run all` pass trên stack thường)
- **Learning & Course Catalog:** DONE (Courses, Sections, Lessons, Progress tracking, Q&A)
- **Exam & Assessment:** DONE (Exam, Questions, Attempts, Auto/Manual Grading, Answer privacy)
- **Leaderboard & Ranking:** DONE (RankTiers, RewardRules, Leaderboard recalculation)
- **Segment Engine:** DONE (Whitelisted AST evaluation, Preview matching)
- **Commerce & Entitlements:** DONE (Products, Pricing, Orders, Idempotency, MockPaymentProvider)
- **Media Asset Service:** DONE (MinIO S3 metadata, Presigned upload & download URLs)
- **Outbox & Projection Worker:** DONE (MySQL Outbox table, Scheduled worker to Mongo & Neo4j)

### Frontend
- **Auth & Layouts:** DONE (Login, Register, Quick Demo Account Switcher — chỉ hiển thị khi bật lớp phủ demo)
- **Classroom 8 Tabs:** DONE (Feed, Learn, Exams, Leaderboard, Documents, Members, About, Store)
- **Lesson Viewer:** DONE (Video player, Markdown text, Progress toggle, Lesson Q&A)
- **Exam Interface:** DONE (Question list, Countdown timer, Submit & Result view)
- **Studio Management:** DONE (14 trang: Overview, Courses, Exams, Grading, Leaderboard, Store, Segments, Staff, Members, Settings, Feed, Documents, About, Audit)

### Infrastructure
- `infra/compose.yaml`: DONE
- `backend/Dockerfile`: DONE (Multi-stage builder, tester, runner)
- `frontend/Dockerfile`: DONE (Multi-stage builder, tester, runner with Nginx)
- `.env.example` & `.env`: DONE

---

## 3. Trạng thái hiện tại

**Tính năng đã hoàn tất (chạy được đầu-cuối trong Docker):**
- Định danh: đăng ký/đăng nhập JWT + refresh token cookie HttpOnly có xoay vòng và cửa sổ ân hạn; thu hồi token; giới hạn tần suất.
- Lớp học: tạo/sửa/lưu trữ lớp, tham gia, vòng đời thành viên `ACTIVE/REMOVED/BLOCKED` (gỡ, chặn, mở khóa, tự tham gia lại cho `REMOVED` - D-12), danh sách lớp phân trang, trang giới thiệu.
- Phân quyền: OWNER/STAFF với quyền chi tiết theo module (có phạm vi theo khóa học), `AccessPolicy` là nguồn sự thật duy nhất, nhật ký kiểm toán (Audit) cho mọi chuyển trạng thái quan trọng.
- Học tập: khóa học/chương/bài học (video, văn bản, tài liệu, bài tập có nộp bài), tiến độ, hỏi đáp, lưu trữ/khôi phục/xóa an toàn.
- Cộng đồng: bảng tin có phân quyền hiển thị, bình luận, tài liệu, hồ sơ có quyền riêng tư.
- Thi cử: kỳ thi trắc nghiệm/tự luận, chấm tự động/thủ công, chấm lại, công bố, đóng/lưu trữ, xem thử; bảng xếp hạng toàn lớp và theo kỳ thi, hạng/điểm thưởng cấu hình được.
- Phân khúc học viên (Segment Engine), thương mại (sản phẩm, đơn hàng, entitlement, hoàn tiền, webhook có idempotency), media MinIO, Outbox -> MongoDB/Neo4j.
- Vận hành: sao lưu/khôi phục + diễn tập (RUNBOOK §5), checklist trước khi deploy production (RUNBOOK §6).

**Round 23 đã triển khai thêm:**
- About tối đa 12 mục có ảnh, upload theo quyền ABOUT:EDIT, optimistic version, tab bằng bàn phím; D-10 đã được thay bằng D-22.
- MySQL chia sẻ rate limit, autosave tiêu thụ lượt đặt trước; outbox có claim token, Neo4j chống áp dụng ngược sequence.
- Export/xóa dữ liệu cá nhân có xác nhận mật khẩu và xử lý có audit; video hỗ trợ WebVTT và bản chép lời.
- Secret ngẫu nhiên 64 ký tự đã được áp dụng lên volume thật; cấu hình cũ đã dọn, dependency đã cập nhật. Hai stack được tái tạo và giữ dữ liệu.
- HTTPS trên máy hiện tại, mặc định một backend và hỗ trợ hai backend, cookie Secure/HttpOnly, CORS chính xác, firewall LocalSubnet, CA được tin cậy trên máy chủ; ba Scheduled Tasks đã đăng ký. Backup/restore bốn kho đã chạy thật.
- V43 chuẩn bị bản đề/đáp án chấm điểm bất biến trong giao dịch công bố; DTO người học không chứa đáp án. Nâng cấp đề cũ theo batch, rollback khi ghi thất bại; quyền và thời hạn vẫn kiểm tra hiện tại.

**Giới hạn còn mở:**
1. 200 người đã mở trang đề đạt start p95 1262 ms, save 57, submit 1367; feed/media/NAT qua gate script. Mặc định 200 kết nối HTTPS mới vẫn vượt ngưỡng start (1925 > 1500 ms), đủ bài/đáp án và không lỗi request. Media readiness dùng ngưỡng tương đối của script. Các lỗi 503/429 thuộc lượt cũ trước sửa; giữ riêng bằng chứng lịch sử. Xem `PERFORMANCE_REVIEW.md`; chưa nghiệm thu toàn bộ NFR tải.
2. Lịch sử GitHub cần xác nhận riêng trước khi thay bằng bản đã loại secret; báo cáo chuẩn bị trong `.artifacts/git-cleanup/result.json`.
3. Internet cần DNS/router hoặc phương án kết nối do người vận hành cung cấp. Chưa kiểm chứng từ thiết bị LAN thứ hai hoặc reboot thật. Server hiện tại không có cam kết SLA.
4. Quét axe và kiểm tra bàn phím đã qua; NVDA/VoiceOver, mọi nội dung giáo viên và toàn bộ WCAG cần nghiệm thu thủ công. Danh tính/căn cứ giữ dữ liệu của đơn vị vận hành cần được điền trong DATA_POLICY.md.
5. Cổng thanh toán thật nằm ngoài yêu cầu; sandbox chỉ bật ở demo. Web responsive là sản phẩm được yêu cầu, không làm app native.

**Migration:** Flyway V1..V43. V37 lớp riêng tư/trả phí; V38 mục giới thiệu; V39 budget chung; V40 claim token; V41 quyền dữ liệu; V42 WebVTT; V43 bản đề khi công bố. Giữ nguyên các migration đã áp dụng.
