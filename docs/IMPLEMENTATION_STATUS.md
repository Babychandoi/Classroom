# Trạng thái Triển khai Hệ thống Lớp học Trực tuyến (Implementation Status)

**Phiên bản:** 0.1.0  
**Cập nhật:** 24/09/2026  
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
| **Phase 12** | Bộ kiểm thử (Backend Unit/Policy/Integration, Frontend build/typecheck) | **DONE** | TC-01 đến TC-18, UT-01 đến UT-07 (55 tests passed in Docker) |
| **Phase 13** | Docker Verify (Build, Backend-test, Frontend-test) | **DONE** | 100% build và test thành công trong Docker container |
| **Phase 14** | Local Full Stack (Chạy up -d, healthcheck) | **DONE** | Tất cả 6 containers đều UP & HEALTHY |
| **Phase 15** | Reproducibility Test (Clean down -v && up) | **DONE** | Khởi động sạch từ zero-state và Flyway migration thành công. `DataSeedRunner` chỉ chạy khi bật lớp phủ demo tường minh (`infra/compose.demo.yaml`) — xem RUNBOOK §1.4 |
| **Phase 16** | Tài liệu hóa & Bàn giao (README, RUNBOOK, DOCKER) | **DONE** | README, RUNBOOK.md, DOCKER.md, API.md, DECISIONS.md hoàn thiện |

---

## 2. Chi tiết Module

### Backend
- **Identity & Auth:** DONE (JWT, BCrypt, Role-based)
- **Classroom & Staff Management:** DONE (Ownership, StaffAssignment, fine-grained Permissions)
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
- **Studio Management:** DONE (Overview, Courses, Exams, Grading, Staff, Segments, Store, Audit)

### Infrastructure
- `infra/compose.yaml`: DONE
- `backend/Dockerfile`: DONE (Multi-stage builder, tester, runner)
- `frontend/Dockerfile`: DONE (Multi-stage builder, tester, runner with Nginx)
- `.env.example` & `.env`: DONE
