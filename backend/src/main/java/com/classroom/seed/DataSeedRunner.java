package com.classroom.seed;

import com.classroom.modules.classroom.model.*;
import com.classroom.modules.classroom.repository.*;
import com.classroom.modules.classroom.service.InviteCodes;
import com.classroom.modules.commerce.model.*;
import com.classroom.modules.commerce.repository.*;
import com.classroom.modules.community.model.*;
import com.classroom.modules.community.repository.*;
import com.classroom.modules.exam.model.*;
import com.classroom.modules.exam.repository.*;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.learning.model.*;
import com.classroom.modules.learning.repository.*;
import com.classroom.modules.ranking.model.*;
import com.classroom.modules.ranking.repository.*;
import com.classroom.modules.segment.model.Segment;
import com.classroom.modules.segment.repository.SegmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Seeds the demo dataset — including fixed-password accounts such as {@code owner@classroom.local}
 * — used by the local demo stack and by the test suites.
 *
 * <p>Gated on an explicit opt-in as well as the profile (Finding 1): the property has no default,
 * so a stack started without {@code DEMO_SEED_ENABLED=true} never creates these accounts. The test
 * and integration profiles set it themselves; the Docker profile only receives it when the
 * operator opts in through {@code infra/.env}.</p>
 */
@Profile({"dev", "test", "docker", "integration"})
@ConditionalOnProperty(name = "classroom.seed.demo.enabled", havingValue = "true")
@Component
public class DataSeedRunner implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DataSeedRunner.class);

    // R19-05: titles of the seeded exams the repair step below recognises (it never touches other exams).
    static final String PRO_EXAM_TITLE = "Kỳ Thi Đấu Trường PRO";
    static final String COURSE_EXAM_TITLE = "Kiểm Tra Cuối Khóa Toán Chuyên Sâu";
    // D-19: the demo classes for the private / paid class features. Everything below is created ONLY by this runner, which exists only when
    // the demo opt-in is on (DEMO_SEED_ENABLED=true + a dev/test/docker/integration profile): a production deployment never has these classes
    // and, above all, never has the fixed invite code - a real invite code is 192 random bits (InviteCodes.generate).
    public static final String DEMO_PRIVATE_CLASS_SLUG = "lop-rieng-tu-ma-moi";
    public static final String DEMO_PRIVATE_CLASS_TITLE = "Lớp Riêng Tư (mã mời)";
    /** DEMO ONLY. Fixed so the README and the e2e suite can use it; 29 URL-safe characters, accepted by the same validation as a real code. */
    public static final String DEMO_PRIVATE_INVITE_CODE = "demo-invite-lop-rieng-tu-2026";
    public static final String DEMO_PAID_CLASS_SLUG = "lop-tra-phi";
    // D-28: the one demo class that approves every join request (a FREE PUBLIC class with one pending request from student.pro).
    public static final String DEMO_APPROVAL_CLASS_SLUG = "lop-duyet-thanh-vien";
    public static final String DEMO_APPROVAL_CLASS_TITLE = "Lớp Duyệt Thành Viên";
    public static final String DEMO_PAID_CLASS_TITLE = "Lớp Trả Phí";
    public static final BigDecimal DEMO_PAID_PRICE = new BigDecimal("199000");
    public static final int DEMO_PAID_DURATION_DAYS = 30;

    private static final String PRO_EXAM_QUESTION_TEXT = "Cho hàm số f(x) = x³ - 3x. Tìm giá trị cực đại của f(x).";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ClassroomRepository classroomRepository;
    private final ClassMemberRepository memberRepository;
    private final StaffAssignmentRepository staffAssignmentRepository;
    private final StaffPermissionRepository staffPermissionRepository;
    private final ClassAboutRepository aboutRepository;
    private final CourseRepository courseRepository;
    private final SectionRepository sectionRepository;
    private final LessonRepository lessonRepository;
    private final ProductRepository productRepository;
    private final ProductPriceRepository priceRepository;
    private final EntitlementRepository entitlementRepository;
    private final ExamRepository examRepository;
    private final QuestionRepository questionRepository;
    private final AnswerOptionRepository optionRepository;
    private final RankTierRepository rankTierRepository;
    private final ExamRewardRuleRepository rewardRuleRepository;
    private final LeaderboardEntryRepository leaderboardRepository;
    private final SegmentRepository segmentRepository;
    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final ClassInviteRepository inviteRepository;
    /** D-27: blog and event demo content (a separate component so the constructor above - used as is by tests - stays unchanged). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DemoBlogEventSeeder blogEventSeeder;

    public DataSeedRunner(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          ClassroomRepository classroomRepository,
                          ClassMemberRepository memberRepository,
                          StaffAssignmentRepository staffAssignmentRepository,
                          StaffPermissionRepository staffPermissionRepository,
                          ClassAboutRepository aboutRepository,
                          CourseRepository courseRepository,
                          SectionRepository sectionRepository,
                          LessonRepository lessonRepository,
                          ProductRepository productRepository,
                          ProductPriceRepository priceRepository,
                          EntitlementRepository entitlementRepository,
                          ExamRepository examRepository,
                          QuestionRepository questionRepository,
                          AnswerOptionRepository optionRepository,
                          RankTierRepository rankTierRepository,
                          ExamRewardRuleRepository rewardRuleRepository,
                          LeaderboardEntryRepository leaderboardRepository,
                          SegmentRepository segmentRepository,
                          PostRepository postRepository,
                          CommentRepository commentRepository,
                          ClassInviteRepository inviteRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.classroomRepository = classroomRepository;
        this.memberRepository = memberRepository;
        this.staffAssignmentRepository = staffAssignmentRepository;
        this.staffPermissionRepository = staffPermissionRepository;
        this.aboutRepository = aboutRepository;
        this.courseRepository = courseRepository;
        this.sectionRepository = sectionRepository;
        this.lessonRepository = lessonRepository;
        this.productRepository = productRepository;
        this.priceRepository = priceRepository;
        this.entitlementRepository = entitlementRepository;
        this.examRepository = examRepository;
        this.questionRepository = questionRepository;
        this.optionRepository = optionRepository;
        this.rankTierRepository = rankTierRepository;
        this.rewardRuleRepository = rewardRuleRepository;
        this.leaderboardRepository = leaderboardRepository;
        this.segmentRepository = segmentRepository;
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
        this.inviteRepository = inviteRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        log.info("Checking and executing idempotent development seed...");

        // 1. Users
        String encodedPass = passwordEncoder.encode("Password123!");

        User owner = getOrCreateUser("owner@classroom.local", "Thầy Nguyễn Chủ Nhiệm", encodedPass, "USER");
        User staff = getOrCreateUser("staff@classroom.local", "Trợ Giảng Trần Thị Mai", encodedPass, "USER");
        User freeStudent = getOrCreateUser("student.free@classroom.local", "Học Viên Lê Tự Do", encodedPass, "USER");
        User proStudent = getOrCreateUser("student.pro@classroom.local", "Học Viên Phạm VIP Pro", encodedPass, "USER");
        User expiredStudent = getOrCreateUser("student.expired@classroom.local", "Học Viên Vũ Hết Hạn", encodedPass, "USER");
        User admin = getOrCreateUser("admin@classroom.local", "Quản Trị Viên Hệ Thống", encodedPass, "PLATFORM_ADMIN");

        // 2. Classroom
        String slug = "lop-toan-nang-cao";
        Classroom classroom = classroomRepository.findBySlug(slug).orElseGet(() -> {
            Classroom c = new Classroom();
            c.setOwnerId(owner.getId());
            c.setSlug(slug);
            c.setTitle("Lớp Học Toán Nâng Cao & Tư Duy");
            c.setDescription("Khóa học và nền tảng ôn luyện thi Toán toàn diện từ cơ bản đến nâng cao.");
            c.setCoverImageUrl("https://images.unsplash.com/photo-1635070041078-e363dbe005cb?w=1200&auto=format&fit=crop");
            c.setStatus("ACTIVE");
            return classroomRepository.save(c);
        });

        ensureCategory(classroom, "Ôn thi");

        // 3. Class Memberships
        ensureMember(classroom.getId(), owner.getId(), "OWNER");
        ensureMember(classroom.getId(), staff.getId(), "STAFF");
        ensureMember(classroom.getId(), freeStudent.getId(), "STUDENT");
        ensureMember(classroom.getId(), proStudent.getId(), "STUDENT");
        ensureMember(classroom.getId(), expiredStudent.getId(), "STUDENT");

        // 4. Staff Assignment & Permissions
        StaffAssignment staffAssignment = staffAssignmentRepository.findByClassIdAndUserId(classroom.getId(), staff.getId())
                .orElseGet(() -> staffAssignmentRepository.save(new StaffAssignment(classroom.getId(), staff.getId())));

        if (staffPermissionRepository.findByAssignmentId(staffAssignment.getId()).isEmpty()) {
            staffPermissionRepository.save(new StaffPermission(staffAssignment.getId(), "EXAM", "GRADE", null));
            staffPermissionRepository.save(new StaffPermission(staffAssignment.getId(), "EXAM", "VIEW", null));
            staffPermissionRepository.save(new StaffPermission(staffAssignment.getId(), "COURSE", "PREVIEW", null));
            staffPermissionRepository.save(new StaffPermission(staffAssignment.getId(), "FEED", "VIEW", null));
        }

        // 5. Class About Page
        if (aboutRepository.findByClassId(classroom.getId()).isEmpty()) {
            ClassAbout about = new ClassAbout(
                    classroom.getId(),
                    "## Giới thiệu Lớp Học Toán Nâng Cao\n\nNơi bồi dưỡng tư duy giải toán, chuẩn bị cho các kỳ thi tuyển sinh và học sinh giỏi.\n\n### Đội ngũ giáo viên\n- **Thầy Nguyễn:** Hơn 15 năm kinh nghiệm giảng dạy.\n- **Cô Mai:** Trợ giảng phụ trách chấm bài và giải đáp thắc mắc 24/7.",
                    "1. Tham gia học và làm bài tập đầy đủ.\n2. Trao đổi lịch sự, tôn trọng mọi người trong diễn đàn.\n3. Không chia sẻ tài liệu bài giảng hoặc tài khoản cho người khác."
            );
            aboutRepository.save(about);
        }

        // 6. Courses, Sections & Lessons
        List<Course> existingCourses = courseRepository.findByClassIdOrderByPositionAsc(classroom.getId());
        Course freeCourse;
        Course paidCourse;

        if (existingCourses.isEmpty()) {
            freeCourse = new Course(classroom.getId(), "Toán Nền Tảng Miễn Phí", "FREE");
            freeCourse.setStatus("PUBLISHED");
            freeCourse.setDescription("Khóa học trang bị các công thức và phương pháp nền tảng hoàn toàn miễn phí.");
            freeCourse.setCoverImageUrl("https://images.unsplash.com/photo-1596495578065-6e0763fa1178?w=800&auto=format&fit=crop");
            freeCourse.setPosition(1);
            freeCourse = courseRepository.save(freeCourse);

            Section sec1 = sectionRepository.save(new Section(freeCourse.getId(), "Chương 1: Khởi động & Nhập môn", 1));
            lessonRepository.save(new Lesson(sec1.getId(), freeCourse.getId(), "Bài 1: Giới thiệu khóa học và lộ trình", "VIDEO", 1));
            Lesson l2 = new Lesson(sec1.getId(), freeCourse.getId(), "Bài 2: Các tiên đề và công thức cốt lõi", "TEXT", 2);
            l2.setContentText("### Nội dung cốt lõi\n\nTrong bài học này chúng ta nắm vững 5 định lý cơ bản và cách chứng minh ngắn gọn nhất.");
            lessonRepository.save(l2);

            paidCourse = new Course(classroom.getId(), "Toán Tư Duy Chuyên Sâu Pro", "PURCHASE_REQUIRED");
            paidCourse.setStatus("PUBLISHED");
            paidCourse.setDescription("Khóa học nâng cao đặc biệt dành cho học viên ôn luyện thi chuyên và Olympic.");
            paidCourse.setCoverImageUrl("https://images.unsplash.com/photo-1509228468518-180dd4864904?w=800&auto=format&fit=crop");
            paidCourse.setPosition(2);
            paidCourse = courseRepository.save(paidCourse);

            Section sec2 = sectionRepository.save(new Section(paidCourse.getId(), "Chương 1: Kỹ thuật biến đổi đại số nâng cao", 1));
            Lesson l3 = new Lesson(sec2.getId(), paidCourse.getId(), "Bài 1: Phương pháp giải nhanh hệ phương trình", "VIDEO", 1);
            l3.setDurationMinutes(45);
            lessonRepository.save(l3);
            Lesson l4 = new Lesson(sec2.getId(), paidCourse.getId(), "Bài 2: Chuyên đề Bất đẳng thức Cauchy-Schwarz", "ASSIGNMENT", 2);
            l4.setContentText("Hãy hoàn thành 10 câu hỏi bất đẳng thức trong tài liệu đính kèm.");
            lessonRepository.save(l4);
        } else {
            freeCourse = existingCourses.get(0);
            paidCourse = existingCourses.size() > 1 ? existingCourses.get(1) : existingCourses.get(0);
        }

        // 7. Products & Prices
        if (productRepository.findByClassIdOrderByCreatedAtDesc(classroom.getId()).isEmpty()) {
            Product proMembership = new Product(classroom.getId(), null, "Gói Hội Viên PRO 30 Ngày", "Mở khóa toàn bộ đặc quyền PRO, phòng thi PRO và diễn đàn trao đổi chuyên sâu.");
            proMembership.setStatus("PUBLISHED");
            proMembership = productRepository.save(proMembership);
            priceRepository.save(new ProductPrice(proMembership.getId(), new BigDecimal("299000"), "VND", 30));

            Product courseProd = new Product(classroom.getId(), paidCourse.getId(), "Khóa Học Toán Tư Duy Chuyên Sâu Pro (365 Ngày)", "Quyền học trọn vẹn khóa học Toán Tư Duy Chuyên Sâu trong 1 năm.");
            courseProd.setStatus("PUBLISHED");
            courseProd = productRepository.save(courseProd);
            priceRepository.save(new ProductPrice(courseProd.getId(), new BigDecimal("599000"), "VND", 365));

            paidCourse.setProductId(courseProd.getId());
            courseRepository.save(paidCourse);

            // 8. Entitlements
            Instant now = Instant.now();
            // student.pro -> ACTIVE
            Entitlement proEnt = new Entitlement(
                    proStudent.getId(),
                    classroom.getId(),
                    proMembership.getId(),
                    null,
                    now.minus(5, ChronoUnit.DAYS),
                    now.plus(25, ChronoUnit.DAYS)
            );
            entitlementRepository.save(proEnt);

            // student.pro course access
            Entitlement courseEnt = new Entitlement(
                    proStudent.getId(),
                    classroom.getId(),
                    courseProd.getId(),
                    paidCourse.getId(),
                    now.minus(5, ChronoUnit.DAYS),
                    now.plus(360, ChronoUnit.DAYS)
            );
            entitlementRepository.save(courseEnt);

            // student.expired -> EXPIRED
            Entitlement expEnt = new Entitlement(
                    expiredStudent.getId(),
                    classroom.getId(),
                    proMembership.getId(),
                    null,
                    now.minus(40, ChronoUnit.DAYS),
                    now.minus(10, ChronoUnit.DAYS)
            );
            expEnt.setState("EXPIRED");
            entitlementRepository.save(expEnt);
        }

        // 9. Exams & Questions
        if (examRepository.findByClassIdOrderByCreatedAtDesc(classroom.getId()).isEmpty()) {
            Exam midTerm = new Exam(classroom.getId(), "Kỳ Thi Kiểm Tra Giữa Kỳ (Mở cho tất cả)", "ALL", 45);
            midTerm.setDescription("Bài thi khảo sát kiến thức giữa kỳ cho toàn bộ học viên lớp học.");
            midTerm = examRepository.save(midTerm);

            Question q1 = questionRepository.save(new Question(midTerm.getId(), "Nghiệm của phương trình 2x + 6 = 12 là gì?", "MULTIPLE_CHOICE", 10, 1, "C"));
            optionRepository.save(new AnswerOption(q1.getId(), "A", "x = 1", 1));
            optionRepository.save(new AnswerOption(q1.getId(), "B", "x = 2", 2));
            optionRepository.save(new AnswerOption(q1.getId(), "C", "x = 3", 3));
            optionRepository.save(new AnswerOption(q1.getId(), "D", "x = 4", 4));

            Question q2 = questionRepository.save(new Question(midTerm.getId(), "Hình vuông có cạnh 5cm thì diện tích bằng bao nhiêu cm²?", "MULTIPLE_CHOICE", 10, 2, "B"));
            optionRepository.save(new AnswerOption(q2.getId(), "A", "20 cm²", 1));
            optionRepository.save(new AnswerOption(q2.getId(), "B", "25 cm²", 2));
            optionRepository.save(new AnswerOption(q2.getId(), "C", "30 cm²", 3));
            optionRepository.save(new AnswerOption(q2.getId(), "D", "35 cm²", 4));

            Question q3 = questionRepository.save(new Question(midTerm.getId(), "Số nguyên tố chẵn duy nhất là số 2. Đúng hay Sai?", "TRUE_FALSE", 10, 3, "TRUE"));
            optionRepository.save(new AnswerOption(q3.getId(), "TRUE", "Đúng", 1));
            optionRepository.save(new AnswerOption(q3.getId(), "FALSE", "Sai", 2));

            // Exam for PRO
            Exam proExam = new Exam(classroom.getId(), PRO_EXAM_TITLE, "PRO", 60);
            proExam.setDescription("Kỳ thi tuyển chọn đội tuyển dành riêng cho học viên PRO.");
            proExam = examRepository.save(proExam);
            seedProExamQuestion(proExam);

            // Exam for COURSE OWNERS
            Exam courseExam = new Exam(classroom.getId(), COURSE_EXAM_TITLE, "COURSE", 60);
            courseExam.setTargetCourseId(paidCourse.getId());
            courseExam = examRepository.save(courseExam);
            seedCourseExamQuestion(courseExam);
        }
        // R19-05: databases seeded by an earlier version hold a PUBLISHED course exam with NO questions (and a PRO exam
        // question with no options). The block above only runs on an empty class, so repair those rows here.
        repairSeededExams(classroom.getId());

        // 10. Rank Tiers & Rewards
        if (rankTierRepository.findByClassIdOrderByMinPointsAsc(classroom.getId()).isEmpty()) {
            rankTierRepository.save(new RankTier(classroom.getId(), "Tân thủ", 0, null, "Cấp độ khởi đầu"));
            rankTierRepository.save(new RankTier(classroom.getId(), "Tập sự", 20, null, "Đã vượt qua kỳ thi đầu tiên"));
            rankTierRepository.save(new RankTier(classroom.getId(), "Chiến binh", 50, null, "Thành tích xuất sắc"));
            rankTierRepository.save(new RankTier(classroom.getId(), "Bậc thầy", 100, null, "Đạt đỉnh cao lớp học"));
        }

        List<Exam> exams = examRepository.findByClassIdOrderByCreatedAtDesc(classroom.getId());
        if (!exams.isEmpty() && rewardRuleRepository.findByClassId(classroom.getId()).isEmpty()) {
            rewardRuleRepository.save(new ExamRewardRule(classroom.getId(), exams.get(0).getId(), new BigDecimal("80.00"), 25));
            rewardRuleRepository.save(new ExamRewardRule(classroom.getId(), exams.get(0).getId(), new BigDecimal("50.00"), 10));
        }

        // Leaderboard points are derived only from published exam attempts; seed no fabricated total.

        // 11. Segment
        if (segmentRepository.findByClassId(classroom.getId()).isEmpty()) {
            String ruleJson = "[{\"criterion\":\"IS_PRO\",\"operator\":\"EQUALS\",\"value\":\"true\"}]";
            segmentRepository.save(new Segment(classroom.getId(), "Hội Viên PRO Lớp Học", "Toàn bộ học viên đang có quyền PRO hiệu lực", "AND", ruleJson));
        }

        // 12. Feed Posts
        if (!postRepository.existsByClassIdAndStatus(classroom.getId(), "PUBLISHED")) {
            Post pinned = new Post(
                    classroom.getId(),
                    owner.getId(),
                    "📢 Chào mừng tất cả các em đến với Lớp Học Toán Nâng Cao!",
                    "Thầy rất vui được đồng hành cùng các em trong năm học này. Hãy xem kỹ phần **Nội quy lớp học** trong tab Giới Thiệu và bắt đầu với khóa **Toán Nền Tảng Miễn Phí** ngay hôm nay nhé!\n\nChúc các em học tốt!",
                    "PUBLIC"
            );
            pinned.setStatus("PUBLISHED");
            pinned.setPinned(true);
            postRepository.save(pinned);

            Post proPost = new Post(
                    classroom.getId(),
                    owner.getId(),
                    "⭐ [Dành cho PRO] Tài liệu ôn thi chuyên đề nâng cao tuần này",
                    "Chào các bạn hội viên PRO, thầy vừa upload bộ tài liệu giải đề chuyên sâu trong tab **Tài liệu**. Các bạn tải về làm trước thứ 7 nhé.",
                    "PRO"
            );
            proPost.setStatus("PUBLISHED");
            postRepository.save(proPost);
        }

        // 13. D-19: a PRIVATE free class (join by the fixed demo invite code) and a PUBLIC paid class (199.000 VND / 30 days).
        seedPrivateAndPaidClasses(owner);
        seedApprovalClass(owner, proStudent);

        // 14. D-27: blog posts and events for the math class and the paid class.
        if (blogEventSeeder != null) {
            blogEventSeeder.seed(classroom, classroomRepository.findBySlug(DEMO_PAID_CLASS_SLUG).orElse(null),
                    owner, staff, List.of(freeStudent, proStudent));
        }

        log.info("Idempotent development seed completed successfully.");
    }

    /**
     * D-19 demo data, idempotent: owner@classroom.local owns both classes; student.free / student.pro / expired have no access to either (they
     * are not members), so the "join by invite" and "buy access" flows can be walked through from a clean slate.
     */
    private void seedPrivateAndPaidClasses(User owner) {
        Classroom privateClass = classroomRepository.findBySlug(DEMO_PRIVATE_CLASS_SLUG).orElseGet(() -> {
            Classroom c = new Classroom();
            c.setOwnerId(owner.getId());
            c.setSlug(DEMO_PRIVATE_CLASS_SLUG);
            c.setTitle(DEMO_PRIVATE_CLASS_TITLE);
            c.setDescription("Lớp học riêng tư: không hiện trong danh sách, chỉ vào được bằng mã mời.");
            c.setStatus("ACTIVE");
            c.setVisibility(Classroom.VISIBILITY_PRIVATE);
            c.setAccessType(Classroom.ACCESS_FREE);
            return classroomRepository.save(c);
        });
        ensureCategory(privateClass, "Phát triển bản thân");
        ensureMember(privateClass.getId(), owner.getId(), "OWNER");
        ensureAbout(privateClass, "## Lớp riêng tư\n\nBạn đang xem lớp học chỉ dành cho người có mã mời.");
        String demoHash = InviteCodes.hash(DEMO_PRIVATE_INVITE_CODE);
        if (inviteRepository.findByCodeHash(demoHash).isEmpty()) {
            inviteRepository.save(new ClassInvite(privateClass.getId(), demoHash, InviteCodes.hint(DEMO_PRIVATE_INVITE_CODE),
                    owner.getId(), null, null));
        }

        Classroom paidClass = classroomRepository.findBySlug(DEMO_PAID_CLASS_SLUG).orElseGet(() -> {
            Classroom c = new Classroom();
            c.setOwnerId(owner.getId());
            c.setSlug(DEMO_PAID_CLASS_SLUG);
            c.setTitle(DEMO_PAID_CLASS_TITLE);
            c.setDescription("Lớp học trả phí: xem giới thiệu miễn phí, mua quyền truy cập để tham gia.");
            c.setStatus("ACTIVE");
            c.setVisibility(Classroom.VISIBILITY_PUBLIC);
            c.setAccessType(Classroom.ACCESS_FREE);
            return classroomRepository.save(c);
        });
        ensureCategory(paidClass, "Ôn thi");
        ensureMember(paidClass.getId(), owner.getId(), "OWNER");
        ensureAbout(paidClass, "## Lớp trả phí\n\nMua quyền truy cập 30 ngày để học toàn bộ nội dung lớp.");
        if (paidClass.getAccessProductId() == null) {
            Product access = new Product(paidClass.getId(), null, "Quyền truy cập lớp học: " + paidClass.getTitle(),
                    "Mua để trở thành thành viên của lớp học");
            access.setKind(Product.KIND_CLASS_ACCESS);
            access.setStatus("PUBLISHED");
            access = productRepository.save(access);
            priceRepository.save(new ProductPrice(access.getId(), DEMO_PAID_PRICE, "VND", DEMO_PAID_DURATION_DAYS, Instant.EPOCH));
            paidClass.setAccessProductId(access.getId());
            paidClass.setAccessType(Classroom.ACCESS_PAID);
            classroomRepository.save(paidClass);
        }
    }

    /** D-28: a FREE PUBLIC class with requireApproval and one PENDING request (student.pro), so the Studio queue has data. Idempotent. */
    private void seedApprovalClass(User owner, User requester) {
        Classroom c = classroomRepository.findBySlug(DEMO_APPROVAL_CLASS_SLUG).orElseGet(() -> {
            Classroom created = new Classroom();
            created.setOwnerId(owner.getId());
            created.setSlug(DEMO_APPROVAL_CLASS_SLUG);
            created.setTitle(DEMO_APPROVAL_CLASS_TITLE);
            created.setDescription("Lớp học miễn phí, công khai; chủ lớp duyệt từng người trước khi vào.");
            created.setStatus("ACTIVE");
            created.setVisibility(Classroom.VISIBILITY_PUBLIC);
            created.setAccessType(Classroom.ACCESS_FREE);
            created.setCategory("Phát triển bản thân");
            created.setRequireApproval(true);
            return classroomRepository.save(created);
        });
        ensureMember(c.getId(), owner.getId(), "OWNER");
        ensureAbout(c, "## Lớp duyệt thành viên\n\nGửi yêu cầu tham gia, chủ lớp sẽ duyệt trong thời gian sớm nhất.");
        if (!memberRepository.existsByClassIdAndUserId(c.getId(), requester.getId())) {
            ClassMember pending = new ClassMember(c.getId(), requester.getId(), "STUDENT");
            pending.setState("PENDING");
            memberRepository.save(pending);
        }
    }

    private void ensureCategory(Classroom classroom, String category) {
        if (classroom.getCategory() == null) {
            classroom.setCategory(category);
            classroomRepository.save(classroom);
        }
    }

    private void ensureAbout(Classroom classroom, String contentMarkdown) {
        if (aboutRepository.findByClassId(classroom.getId()).isEmpty()) {
            aboutRepository.save(new ClassAbout(classroom.getId(), contentMarkdown, "1. Tôn trọng giảng viên và bạn học."));
        }
    }

    /** The PRO exam's question (f(x) = x^3 - 3x has its local maximum f(-1) = 2, option A) with its four options. */
    private void seedProExamQuestion(Exam proExam) {
        Question q = questionRepository.save(new Question(proExam.getId(), PRO_EXAM_QUESTION_TEXT, "MULTIPLE_CHOICE", 20, 1, "A"));
        seedProExamOptions(q);
    }

    private void seedProExamOptions(Question q) {
        optionRepository.save(new AnswerOption(q.getId(), "A", "2", 1));
        optionRepository.save(new AnswerOption(q.getId(), "B", "-2", 2));
        optionRepository.save(new AnswerOption(q.getId(), "C", "0", 3));
        optionRepository.save(new AnswerOption(q.getId(), "D", "4", 4));
    }

    /** The course exam's question: a + b = 2, a, b > 0 gives ab <= 1 (AM-GM), so the maximum of ab is 1 (option B). */
    private void seedCourseExamQuestion(Exam courseExam) {
        Question q = questionRepository.save(new Question(courseExam.getId(),
                "Cho a, b > 0 thỏa mãn a + b = 2. Giá trị lớn nhất của tích ab là bao nhiêu?", "MULTIPLE_CHOICE", 10, 1, "B"));
        optionRepository.save(new AnswerOption(q.getId(), "A", "1/2", 1));
        optionRepository.save(new AnswerOption(q.getId(), "B", "1", 2));
        optionRepository.save(new AnswerOption(q.getId(), "C", "2", 3));
        optionRepository.save(new AnswerOption(q.getId(), "D", "4", 4));
    }

    /**
     * R19-05: idempotent repair of the seeded exams. The seed used to save a PUBLISHED course exam without any
     * question (bypassing the publishExam rule), so starting it created an attempt that could not be answered or
     * submitted. Only exams recognised by their seeded title are touched, and only when they are actually broken:
     * a seeded exam that already has questions (and options) is left exactly as it is.
     */
    private void repairSeededExams(String classId) {
        for (Exam exam : examRepository.findByClassIdOrderByCreatedAtDesc(classId)) {
            if (COURSE_EXAM_TITLE.equals(exam.getTitle()) && questionRepository.countByExamId(exam.getId()) == 0) {
                log.warn("Seeded exam '{}' has no questions; adding its question", exam.getTitle());
                seedCourseExamQuestion(exam);
            }
            if (PRO_EXAM_TITLE.equals(exam.getTitle())) {
                for (Question q : questionRepository.findByExamIdOrderByPositionAsc(exam.getId())) {
                    if (PRO_EXAM_QUESTION_TEXT.equals(q.getQuestionText())
                            && optionRepository.findByQuestionIdOrderByPositionAsc(q.getId()).isEmpty()) {
                        log.warn("Seeded exam '{}' question has no answer options; adding them", exam.getTitle());
                        seedProExamOptions(q);
                    }
                }
            }
        }
    }

    private User getOrCreateUser(String email, String fullName, String encodedPassword, String role) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User u = new User();
            u.setEmail(email);
            u.setFullName(fullName);
            u.setPasswordHash(encodedPassword);
            u.setRole(role);
            u.setStatus("ACTIVE");
            return userRepository.save(u);
        });
    }

    private void ensureMember(String classId, String userId, String role) {
        if (!memberRepository.existsByClassIdAndUserId(classId, userId)) {
            memberRepository.save(new ClassMember(classId, userId, role));
        }
    }
}
