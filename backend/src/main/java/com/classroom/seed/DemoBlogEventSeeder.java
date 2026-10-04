package com.classroom.seed;

import com.classroom.modules.blog.model.BlogPost;
import com.classroom.modules.blog.repository.BlogPostRepository;
import com.classroom.modules.blog.service.BlogService;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.event.model.ClassEvent;
import com.classroom.modules.event.model.EventRegistration;
import com.classroom.modules.event.repository.ClassEventRepository;
import com.classroom.modules.event.repository.EventRegistrationRepository;
import com.classroom.modules.identity.model.User;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * D-27 demo content for the blog and events (only with the demo overlay, like {@link DataSeedRunner} which calls it). Idempotent: a class
 * that already has any blog post / any event is left alone. Event dates are relative to the moment the seed first runs.
 */
@Profile({"dev", "test", "docker", "integration"})
@ConditionalOnProperty(name = "classroom.seed.demo.enabled", havingValue = "true")
@Component
public class DemoBlogEventSeeder {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    private final BlogPostRepository blogPostRepository;
    private final ClassEventRepository eventRepository;
    private final EventRegistrationRepository registrationRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DemoBlogEventSeeder(BlogPostRepository blogPostRepository,
                               ClassEventRepository eventRepository,
                               EventRegistrationRepository registrationRepository) {
        this.blogPostRepository = blogPostRepository;
        this.eventRepository = eventRepository;
        this.registrationRepository = registrationRepository;
    }

    /** {@code students}: demo students who are ACTIVE members of the math class (they get registrations there). */
    public void seed(Classroom mathClass, Classroom paidClass, User owner, User staff, List<User> students) {
        if (mathClass != null && !blogPostRepository.existsByClassId(mathClass.getId())) {
            seedMathBlog(mathClass, owner, staff);
        }
        if (mathClass != null && !eventRepository.existsByClassId(mathClass.getId())) {
            seedMathEvents(mathClass, owner, staff, students);
        }
        if (paidClass != null && !blogPostRepository.existsByClassId(paidClass.getId())) {
            seedPaidBlog(paidClass, owner);
        }
        if (paidClass != null && !eventRepository.existsByClassId(paidClass.getId())) {
            seedPaidEvents(paidClass, owner);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------- blog

    private void seedMathBlog(Classroom c, User owner, User staff) {
        post(c, owner, "5 thói quen giúp học Toán hiệu quả hơn mỗi ngày", "Phương pháp học",
                "Học Toán không cần thức khuya, chỉ cần đều đặn. Đây là 5 thói quen thầy thấy ở mọi học sinh tiến bộ nhanh.",
                """
                ## 1. Học ít nhưng đều

                Mỗi ngày 45 phút tập trung hiệu quả hơn nhiều so với 5 tiếng dồn vào cuối tuần. Bộ não cần thời gian để "ngấm" kiến thức, \
                và việc ôn lại sau một đêm ngủ giúp ghi nhớ lâu hơn hẳn.

                ## 2. Tự giải trước khi xem lời giải

                Hãy cho mình ít nhất 15 phút vật lộn với một bài khó. Kể cả khi không giải ra, quá trình thử - sai giúp em hiểu lời giải \
                sâu hơn rất nhiều so với đọc ngay đáp án.

                ## 3. Ghi lại lỗi sai vào một cuốn sổ riêng

                Mỗi lỗi sai là một bài học. Cuối tuần, em mở sổ ra và làm lại đúng những bài đó - đây là cách ôn tập "đúng chỗ đau" nhất.

                ## 4. Giải thích lại cho người khác

                Nếu em giảng lại được cho bạn cùng lớp, nghĩa là em đã thật sự hiểu. Hãy tận dụng bảng tin của lớp để hỏi và trả lời nhau.

                ## 5. Ngủ đủ giấc

                Nghe có vẻ hiển nhiên, nhưng thiếu ngủ làm giảm khả năng tư duy logic rõ rệt. Đừng đánh đổi giấc ngủ lấy thêm một đề luyện.
                """, BlogPost.AUDIENCE_PUBLIC, 12);
        post(c, owner, "Lộ trình ôn thi vào lớp 10 chuyên Toán trong 3 tháng", "Kinh nghiệm thi",
                "Chia 12 tuần thành 3 giai đoạn: củng cố nền tảng, luyện chuyên đề và luyện đề tổng hợp.",
                """
                Ba tháng là đủ để tạo khác biệt nếu em có kế hoạch rõ ràng. Thầy chia lộ trình thành ba giai đoạn, mỗi giai đoạn 4 tuần.

                ### Giai đoạn 1 (tuần 1-4): Củng cố nền tảng

                - Ôn lại toàn bộ đại số lớp 8-9: biến đổi biểu thức, phương trình, hệ phương trình.
                - Mỗi ngày làm 10 bài cơ bản để tăng tốc độ tính toán.

                ### Giai đoạn 2 (tuần 5-8): Luyện chuyên đề

                Tập trung vào bất đẳng thức, số học và hình học phẳng - ba chuyên đề chiếm phần lớn điểm của đề chuyên. Mỗi tuần một \
                chuyên đề, cuối tuần làm một bài kiểm tra nhỏ trong tab **Kỳ thi** của lớp.

                ### Giai đoạn 3 (tuần 9-12): Luyện đề tổng hợp

                Mỗi tuần hai đề, làm đúng giờ như thi thật. Sau mỗi đề, dành thời gian chữa kỹ hơn thời gian làm bài.

                Chúc các em kiên trì - kết quả sẽ đến!
                """, BlogPost.AUDIENCE_PUBLIC, 7);
        post(c, staff, "Giải chi tiết đề thi thử tháng 9", "Kinh nghiệm thi",
                "Lời giải từng câu và những lỗi sai phổ biến nhất trong đề thi thử vừa rồi - chỉ dành cho thành viên lớp.",
                """
                Cô đã chấm xong đề thi thử tháng 9. Điểm trung bình của lớp là 6,8 - khá tốt, nhưng vẫn còn nhiều lỗi đáng tiếc.

                ## Câu 3: Phương trình chứa căn

                Rất nhiều bạn quên đặt điều kiện trước khi bình phương hai vế, dẫn đến nhận nghiệm ngoại lai. Hãy luôn viết điều kiện \
                xác định ở dòng đầu tiên của lời giải.

                ## Câu 5: Bất đẳng thức

                Cách nhanh nhất là áp dụng AM-GM cho hai số dương: a + b ≥ 2√(ab). Dấu bằng xảy ra khi a = b - đừng quên kiểm tra điều kiện này.

                ## Câu 7: Hình học

                Chìa khóa là kẻ thêm đường phụ song song. Cô sẽ chữa kỹ câu này trong buổi livestream sắp tới, các em nhớ đăng ký nhé.
                """, BlogPost.AUDIENCE_MEMBERS, 3);
        post(c, owner, "Lịch học và quy định lớp trong học kỳ mới", "Thông báo",
                "Lịch livestream hằng tuần, cách nộp bài tập và kênh hỏi đáp chính thức của lớp.",
                """
                Chào các em, học kỳ mới bắt đầu từ tuần này. Thầy tóm tắt lại lịch và một số quy định quan trọng.

                **Lịch livestream:** tối thứ Tư và thứ Bảy hằng tuần, 20:00 - 21:30. Link tham gia có trong mục **Sự kiện** của lớp.

                **Nộp bài tập:** nộp qua tab Khóa học trước 23:59 Chủ nhật. Bài nộp muộn vẫn được chấm nhưng không tính điểm thưởng.

                **Hỏi đáp:** đăng câu hỏi lên bảng tin của lớp để cả lớp cùng thảo luận; trợ giảng sẽ trả lời trong vòng 24 giờ.
                """, BlogPost.AUDIENCE_PUBLIC, 1);
        draft(c, owner, "Bất đẳng thức AM-GM: từ cơ bản đến nâng cao", "Phương pháp học",
                "Bản nháp - tổng hợp các kỹ thuật dùng AM-GM thường gặp trong đề thi.",
                """
                Bất đẳng thức AM-GM là công cụ mạnh nhất trong chương trình THCS. Bài viết này sẽ đi từ dạng cơ bản đến các kỹ thuật \
                chọn điểm rơi, thêm bớt hằng số.

                (Đang soạn tiếp...)
                """);
    }

    private void seedPaidBlog(Classroom c, User owner) {
        post(c, owner, "Bộ tài liệu tổng ôn mà lớp trả phí được nhận", "Tài liệu",
                "Danh sách đầy đủ các tài liệu, đề luyện và video chữa bài đi kèm quyền truy cập lớp.",
                """
                Khi tham gia lớp trả phí, em nhận được trọn bộ tài liệu tổng ôn được cập nhật hằng tuần.

                - 30 đề luyện có lời giải chi tiết.
                - 12 video chữa chuyên đề, mỗi video 40-60 phút.
                - Sổ tay công thức bản PDF để in.

                Tất cả nằm trong tab **Tài liệu** và **Khóa học** ngay sau khi thanh toán.
                """, BlogPost.AUDIENCE_PUBLIC, 9);
        post(c, owner, "Kinh nghiệm phân bổ thời gian trong phòng thi", "Kinh nghiệm thi",
                "Làm câu dễ trước, đánh dấu câu khó, và luôn dành 10 phút cuối để soát lại.",
                """
                Rất nhiều bạn mất điểm không phải vì không biết làm, mà vì hết giờ.

                Quy tắc của thầy: đọc lướt toàn bộ đề trong 5 phút đầu, làm các câu chắc chắn trước, đánh dấu câu khó để quay lại sau.

                Luôn chừa 10 phút cuối để soát lại đơn vị, dấu và điều kiện của nghiệm.
                """, BlogPost.AUDIENCE_PUBLIC, 5);
        post(c, owner, "Đề luyện tuần này và đáp án", "Tài liệu",
                "Đề luyện số 8 kèm đáp án - chỉ dành cho học viên đã đăng ký lớp.",
                """
                Đề luyện số 8 tập trung vào hàm số bậc nhất và bậc hai.

                Đáp án nhanh: 1B, 2C, 3A, 4D, 5B. Lời giải chi tiết có trong video chữa bài tối thứ Sáu.

                Các em làm xong nhớ ghi lại điểm vào bảng theo dõi cá nhân để thầy nắm được tiến độ.
                """, BlogPost.AUDIENCE_MEMBERS, 2);
        post(c, owner, "Chào mừng các bạn đến với lớp trả phí", "Thông báo",
                "Những điều cần biết trong tuần đầu tiên: cách vào lớp, lịch học và kênh hỗ trợ.",
                """
                Cảm ơn các bạn đã tin tưởng đăng ký lớp!

                Tuần đầu tiên, hãy xem bài giới thiệu trong tab Giới thiệu, làm bài kiểm tra đầu vào và đăng ký buổi livestream định hướng.

                Mọi thắc mắc về thanh toán hoặc gia hạn, các bạn nhắn trực tiếp cho thầy qua bảng tin.
                """, BlogPost.AUDIENCE_PUBLIC, 14);
        draft(c, owner, "Kế hoạch học tháng tới (nháp)", "Thông báo",
                "Bản nháp kế hoạch học tháng tới.",
                """
                Tháng tới lớp sẽ chuyển sang chuyên đề hình học.

                (Đang hoàn thiện...)
                """);
    }

    private void post(Classroom c, User author, String title, String category, String excerpt, String content, String audience,
                      int daysAgo) {
        BlogPost p = base(c, author, title, category, excerpt, content, audience);
        Instant publishedAt = Instant.now().minus(daysAgo, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES);
        p.setStatus(BlogPost.STATUS_PUBLISHED);
        p.setPublishedAt(publishedAt);
        p.setCreatedAt(publishedAt.minus(2, ChronoUnit.HOURS));
        p.setUpdatedAt(publishedAt);
        blogPostRepository.save(p);
    }

    private void draft(Classroom c, User author, String title, String category, String excerpt, String content) {
        BlogPost p = base(c, author, title, category, excerpt, content, BlogPost.AUDIENCE_PUBLIC);
        p.setStatus(BlogPost.STATUS_DRAFT);
        blogPostRepository.save(p);
    }

    private BlogPost base(Classroom c, User author, String title, String category, String excerpt, String content, String audience) {
        BlogPost p = new BlogPost();
        p.setClassId(c.getId());
        p.setAuthorId(author.getId());
        p.setTitle(title);
        p.setCategory(category);
        p.setExcerpt(excerpt);
        p.setContentMarkdown(content.strip());
        p.setAudience(audience);
        p.setReadingMinutes(BlogService.readingMinutes(p.getContentMarkdown()));
        return p;
    }

    // ----------------------------------------------------------------------------------------------------------------------------- events

    private void seedMathEvents(Classroom c, User owner, User staff, List<User> students) {
        User first = students.isEmpty() ? null : students.get(0);
        User second = students.size() > 1 ? students.get(1) : null;

        ClassEvent live = event(c, owner, owner, "Livestream chữa đề: Hàm số và cực trị", ClassEvent.FORMAT_ONLINE,
                "Zoom", "https://zoom.us/j/9876543210", 3, 20, 120, 100, ClassEvent.AUDIENCE_PUBLIC,
                "Buổi livestream chữa chi tiết 10 câu hàm số khó nhất trong các đề thi thử gần đây.\n\n"
                        + "Các em chuẩn bị giấy nháp và làm trước đề trong tab **Kỳ thi** để theo dõi hiệu quả nhất.",
                "Học sinh lớp 9 ôn thi vào 10 và học sinh lớp 10 muốn củng cố phần hàm số.",
                List.of("Nắm chắc cách khảo sát và vẽ đồ thị hàm số", "Kỹ thuật tìm cực trị nhanh",
                        "Tránh 5 lỗi sai phổ biến khi trình bày lời giải"));
        register(live, first, second);

        ClassEvent workshop = event(c, staff, staff, "Workshop: Tư duy hình học không gian", ClassEvent.FORMAT_ONLINE,
                "Google Meet", "https://meet.google.com/abc-defg-hij", 6, 19, 90, 3, ClassEvent.AUDIENCE_PUBLIC,
                "Workshop nhóm nhỏ, tương tác trực tiếp: dựng hình, tưởng tượng mặt cắt và tính thể tích.",
                "Học viên đã nắm vững hình học phẳng, muốn làm quen hình không gian.",
                List.of("Cách dựng hình chuẩn trên giấy", "Phương pháp tọa độ hóa bài toán", "Bộ bài tập tự luyện sau buổi học"));
        register(workshop, first, second); // 2 / 3 seats: one place left

        ClassEvent meetup = event(c, owner, owner, "Gặp mặt & giải đề trực tiếp tại Hà Nội", ClassEvent.FORMAT_OFFLINE,
                "Phòng 302, Thư viện Quốc gia Việt Nam, 31 Tràng Thi, Hoàn Kiếm, Hà Nội", null, 10, 9, 180, null,
                ClassEvent.AUDIENCE_MEMBERS,
                "Buổi gặp mặt trực tiếp dành cho thành viên lớp: giải đề theo nhóm, giao lưu với thầy và trợ giảng.\n\n"
                        + "Lớp chuẩn bị sẵn đề, giấy nháp và nước uống. Các em mang theo máy tính cầm tay.",
                "Thành viên lớp ở Hà Nội và các tỉnh lân cận.",
                List.of("Luyện đề theo nhóm có người hướng dẫn", "Gặp gỡ bạn cùng lớp", "Quà tặng sổ tay công thức"));
        register(meetup, second);

        ClassEvent past = event(c, owner, owner, "Hội thảo định hướng học kỳ mới", ClassEvent.FORMAT_OFFLINE,
                "Hội trường A, 144 Xuân Thủy, Cầu Giấy, Hà Nội", null, -14, 14, 120, 200, ClassEvent.AUDIENCE_PUBLIC,
                "Giới thiệu lộ trình học, giáo viên phụ trách và cách sử dụng nền tảng lớp học.",
                "Học viên mới và phụ huynh.",
                List.of("Hiểu lộ trình cả học kỳ", "Biết cách theo dõi tiến độ học tập"));
        register(past, first, second);
    }

    private void seedPaidEvents(Classroom c, User owner) {
        event(c, owner, owner, "Livestream định hướng cho học viên mới", ClassEvent.FORMAT_ONLINE,
                "Zoom", "https://zoom.us/j/1234509876", 2, 20, 60, null, ClassEvent.AUDIENCE_MEMBERS,
                "Hướng dẫn sử dụng tài liệu, lịch học và cách đặt câu hỏi hiệu quả trong lớp.",
                "Học viên vừa đăng ký lớp trả phí.",
                List.of("Biết cách khai thác trọn bộ tài liệu", "Lên kế hoạch học cá nhân"));
        event(c, owner, owner, "Chữa đề luyện số 8", ClassEvent.FORMAT_ONLINE,
                "Google Meet", "https://meet.google.com/xyz-abcd-efg", 5, 20, 90, 50, ClassEvent.AUDIENCE_MEMBERS,
                "Chữa chi tiết đề luyện số 8: hàm số bậc nhất và bậc hai.",
                "Học viên đã làm đề luyện số 8.",
                List.of("Lời giải chi tiết từng câu", "Mẹo trình bày ăn trọn điểm"));
        event(c, owner, owner, "Ngày hội ôn thi cuối kỳ", ClassEvent.FORMAT_OFFLINE,
                "Trung tâm Hội nghị, 35 Lê Văn Thiêm, Thanh Xuân, Hà Nội", null, 12, 8, 240, 80, ClassEvent.AUDIENCE_PUBLIC,
                "Một buổi sáng luyện đề tập trung và giải đáp thắc mắc trực tiếp với thầy.",
                "Mọi học sinh quan tâm đến lớp, kể cả chưa đăng ký.",
                List.of("Làm đề thi thử có chấm điểm tại chỗ", "Tư vấn lộ trình cá nhân"));
        event(c, owner, owner, "Buổi học thử miễn phí", ClassEvent.FORMAT_ONLINE,
                "Zoom", "https://zoom.us/j/5550001111", -7, 20, 60, null, ClassEvent.AUDIENCE_PUBLIC,
                "Buổi học thử để làm quen phương pháp dạy của lớp.",
                "Học sinh muốn tìm hiểu lớp trước khi đăng ký.",
                List.of("Trải nghiệm một buổi học thật"));
    }

    /** {@code dayOffset} days from today (Vietnam time) at {@code hour}:00 local, lasting {@code minutes}. */
    private ClassEvent event(Classroom c, User creator, User host, String title, String format, String location, String meetingUrl,
                             int dayOffset, int hour, int minutes, Integer capacity, String audience, String description,
                             String forWhom, List<String> takeaways) {
        Instant startsAt = LocalDate.now(VIETNAM).plusDays(dayOffset).atTime(hour, 0).atZone(VIETNAM).toInstant();
        ClassEvent e = new ClassEvent();
        e.setClassId(c.getId());
        e.setCreatedBy(creator.getId());
        e.setHostUserId(host.getId());
        e.setTitle(title);
        e.setDescription(description);
        e.setForWhom(forWhom);
        try {
            e.setTakeawaysJson(objectMapper.writeValueAsString(takeaways));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
        e.setFormat(format);
        e.setLocation(location);
        e.setMeetingUrl(meetingUrl);
        e.setStartsAt(startsAt);
        e.setEndsAt(startsAt.plus(minutes, ChronoUnit.MINUTES));
        e.setCapacity(capacity);
        e.setAudience(audience);
        e.setStatus(ClassEvent.STATUS_SCHEDULED);
        e.setRegisteredCount(0);
        return eventRepository.save(e);
    }

    private void register(ClassEvent event, User... users) {
        int count = event.getRegisteredCount();
        for (User u : users) {
            if (u == null || registrationRepository.existsByEventIdAndUserId(event.getId(), u.getId())) continue;
            registrationRepository.save(new EventRegistration(event.getId(), u.getId()));
            count++;
        }
        event.setRegisteredCount(count);
        eventRepository.save(event);
    }
}
