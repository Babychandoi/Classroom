package com.classroom.modules.learning.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.identity.policy.ProfileVisibilityPolicy;
import com.classroom.modules.learning.dto.CourseDto;
import com.classroom.modules.learning.dto.LessonDto;
import com.classroom.modules.learning.dto.QuestionAnswerDto;
import com.classroom.modules.learning.dto.SectionDto;
import com.classroom.modules.learning.model.*;
import com.classroom.modules.learning.policy.LearningPolicy;
import com.classroom.modules.learning.repository.*;
import com.classroom.modules.commerce.model.Product;
import com.classroom.modules.commerce.repository.ProductRepository;
import com.classroom.modules.commerce.repository.OrderItemRepository;
import com.classroom.modules.media.dto.DownloadUrlResponse;
import com.classroom.modules.media.model.MediaAsset;
import com.classroom.modules.media.service.MediaService;
import com.classroom.modules.outbox.service.OutboxService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class LearningService {

    private final CourseRepository courseRepository;
    private final SectionRepository sectionRepository;
    private final LessonRepository lessonRepository;
    private final LessonProgressRepository progressRepository;
    private final LessonQuestionRepository questionRepository;
    private final LessonAnswerRepository answerRepository;
    private final UserRepository userRepository;
    private final LearningPolicy learningPolicy;
    private final AccessPolicy accessPolicy;
    private final MediaService mediaService;
    private final OutboxService outboxService;
    private final ProductRepository productRepository;
    private final ProfileVisibilityPolicy profileVisibilityPolicy;
    private final OrderItemRepository orderItemRepository;

    public LearningService(CourseRepository courseRepository,
                           SectionRepository sectionRepository,
                           LessonRepository lessonRepository,
                           LessonProgressRepository progressRepository,
                           LessonQuestionRepository questionRepository,
                           LessonAnswerRepository answerRepository,
                           UserRepository userRepository,
                           LearningPolicy learningPolicy,
                           AccessPolicy accessPolicy,
                           MediaService mediaService,
                           OutboxService outboxService,
                           ProductRepository productRepository,
                           ProfileVisibilityPolicy profileVisibilityPolicy,
                           OrderItemRepository orderItemRepository) {
        this.courseRepository = courseRepository;
        this.sectionRepository = sectionRepository;
        this.lessonRepository = lessonRepository;
        this.progressRepository = progressRepository;
        this.questionRepository = questionRepository;
        this.answerRepository = answerRepository;
        this.userRepository = userRepository;
        this.learningPolicy = learningPolicy;
        this.accessPolicy = accessPolicy;
        this.mediaService = mediaService;
        this.outboxService = outboxService;
        this.productRepository = productRepository;
        this.profileVisibilityPolicy = profileVisibilityPolicy;
        this.orderItemRepository = orderItemRepository;
    }

    @Transactional(readOnly = true)
    public List<CourseDto> getCoursesByClass(String classId, String userId) {
        accessPolicy.enforceMember(userId, classId);
        List<Course> courses = courseRepository.findByClassIdOrderByPositionAsc(classId);
        List<CourseDto> dtos = new ArrayList<>();

        for (Course course : courses) {
            // Unpublished (DRAFT) courses are not learner-visible: their metadata is only listed
            // for the OWNER and STAFF holding an explicit COURSE:PREVIEW / COURSE:EDIT grant.
            if (!learningPolicy.canViewCourse(userId, course)) {
                continue;
            }
            boolean canLearn = (userId != null) && learningPolicy.canLearn(userId, course);
            long totalLessons = lessonRepository.countByCourseId(course.getId());
            long completedLessons = (userId != null)
                    ? progressRepository.countByUserIdAndCourseIdAndCompletedTrue(userId, course.getId())
                    : 0;

            CourseDto dto = toCourseDto(course);
            dto.setCanLearn(canLearn);
            dto.setTotalLessons((int) totalLessons);
            dto.setCompletedLessons((int) completedLessons);
            dto.setProgressPercentage(totalLessons > 0 ? ((double) completedLessons / totalLessons) * 100 : 0);
            dtos.add(dto);
        }

        return dtos;
    }

    @Transactional(readOnly = true)
    public CourseDto getCourseDetails(String courseId, String userId) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));

        accessPolicy.enforceMember(userId, course.getClassId());

        // Do not disclose the existence or structure of a DRAFT course to ordinary members.
        if (!learningPolicy.canViewCourse(userId, course)) {
            throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học");
        }

        boolean canLearn = (userId != null) && learningPolicy.canLearn(userId, course);
        CourseDto dto = toCourseDto(course);
        dto.setCanLearn(canLearn);

        List<Section> sections = sectionRepository.findByCourseIdOrderByPositionAsc(courseId);
        List<SectionDto> sectionDtos = new ArrayList<>();

        for (Section section : sections) {
            List<Lesson> lessons = lessonRepository.findBySectionIdOrderByPositionAsc(section.getId());
            List<LessonDto> lessonDtos = new ArrayList<>();

            for (Lesson lesson : lessons) {
                LessonDto lDto = toLessonDto(lesson);
                if (userId != null) {
                    boolean completed = progressRepository.findByUserIdAndLessonId(userId, lesson.getId())
                            .map(LessonProgress::isCompleted)
                            .orElse(false);
                    lDto.setCompleted(completed);
                }
                // If student cannot learn, do not expose full content text or media
                if (!canLearn) {
                    lDto.setContentText(null);
                    lDto.setMediaAssetId(null);
                }
                lessonDtos.add(lDto);
            }

            sectionDtos.add(new SectionDto(section.getId(), section.getCourseId(), section.getTitle(), section.getPosition(), lessonDtos));
        }

        dto.setSections(sectionDtos);
        return dto;
    }

    @Transactional(readOnly = true)
    public LessonDto getLesson(String lessonId, String userId) {
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài học"));

        Course course = courseRepository.findById(lesson.getCourseId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));

        // Enforce student has access
        learningPolicy.enforceLearn(userId, course);

        LessonDto dto = toLessonDto(lesson);

        if (userId != null) {
            boolean completed = progressRepository.findByUserIdAndLessonId(userId, lesson.getId())
                    .map(LessonProgress::isCompleted)
                    .orElse(false);
            dto.setCompleted(completed);
        }

        // If lesson has media, generate short-lived signed URL
        if (lesson.getMediaAssetId() != null) {
            try {
                DownloadUrlResponse downloadResponse = mediaService.generateAuthorizedDownloadUrl(lesson.getMediaAssetId(), userId);
                dto.setMediaDownloadUrl(downloadResponse.getDownloadUrl());
            } catch (Exception e) {
                // If media url generation fails or access denied, continue with null
            }
        }

        return dto;
    }

    @Transactional
    public void markLessonProgress(String lessonId, String userId, boolean completed) {
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài học"));

        Course course = courseRepository.findById(lesson.getCourseId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));

        learningPolicy.enforceLearn(userId, course);

        Optional<LessonProgress> progressOpt = progressRepository.findByUserIdAndLessonId(userId, lessonId);
        if (progressOpt.isPresent()) {
            LessonProgress p = progressOpt.get();
            p.setCompleted(completed);
            progressRepository.save(p);
        } else {
            LessonProgress p = new LessonProgress(userId, lessonId, course.getId(), course.getClassId());
            p.setCompleted(completed);
            progressRepository.save(p);
        }

        // Finding 8: Emit outbox event for lesson completion
        if (completed) {
            outboxService.recordEvent("LEARNING", lessonId, "LESSON_COMPLETED", Map.of(
                    "userId", userId,
                    "classId", course.getClassId(),
                    "courseId", course.getId(),
                    "lessonId", lessonId,
                    "completedAt", Instant.now().toString()
            ));
        }
    }

    @Transactional
    public Course createCourse(String classId, Course course, String currentUserId) {
        accessPolicy.enforceManage(currentUserId, classId, "COURSE", "CREATE", null);
        if (course == null || course.getTitle() == null || course.getTitle().isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Tên khóa học không được để trống");
        }
        course.setId(java.util.UUID.randomUUID().toString()); // Ignore client ID; create always inserts.
        course.setClassId(classId);
        course.setStatus("DRAFT"); // Ignore client status; new courses start in DRAFT
        if ("PURCHASE_REQUIRED".equalsIgnoreCase(course.getAccessMode())) {
            if (course.getProductId() != null && !course.getProductId().isBlank()) {
                accessPolicy.enforceManage(currentUserId, classId, "STORE", "EDIT", null);
                Product product = productRepository.findByIdForUpdate(course.getProductId())
                    .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy sản phẩm khóa học"));
                if (!classId.equals(product.getClassId())) {
                    throw new AppException(ErrorCode.BAD_REQUEST, "Sản phẩm phải nhắm tới đúng khóa học trong cùng lớp");
                }
                // The course id is generated here, so a pre-existing product can never already
                // name it. An unclaimed product in the same class is adopted by this course;
                // one already bound to another course is still rejected.
                if (product.getTargetCourseId() == null || product.getTargetCourseId().isBlank()) {
                    rejectOrderedProductAssociation(product.getId());
                    product.setTargetCourseId(course.getId());
                    productRepository.save(product);
                } else if (!course.getId().equals(product.getTargetCourseId())) {
                    throw new AppException(ErrorCode.BAD_REQUEST, "Sản phẩm phải nhắm tới đúng khóa học trong cùng lớp");
                }
            } else {
                throw new AppException(ErrorCode.BAD_REQUEST, "Khóa học trả phí phải được liên kết với sản phẩm dành riêng cho khóa học");
            }
        } else {
            course.setProductId(null);
        }
        return courseRepository.save(course);
    }

    @Transactional
    public Course publishCourse(String courseId, String currentUserId) {
        Course course = courseRepository.findByIdForUpdate(courseId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));
        accessPolicy.enforceManage(currentUserId, course.getClassId(), "COURSE", "PUBLISH", courseId);
        course.setStatus("PUBLISHED");
        course.setUpdatedAt(Instant.now());
        return courseRepository.save(course);
    }

    @Transactional
    public Course linkProduct(String courseId, String productId, String currentUserId) {
        Course course = courseRepository.findByIdForUpdate(courseId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));
        accessPolicy.enforceManage(currentUserId, course.getClassId(), "COURSE", "EDIT", courseId);
        accessPolicy.enforceManage(currentUserId, course.getClassId(), "STORE", "EDIT", null);
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy sản phẩm khóa học"));
        if (!course.getClassId().equals(product.getClassId())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Sản phẩm phải nhắm tới đúng khóa học trong cùng lớp");
        }
        if (course.getProductId() != null && !course.getProductId().equals(productId)) {
            Product previousProduct = productRepository.findByIdForUpdate(course.getProductId())
                    .orElseThrow(() -> new AppException(ErrorCode.CONFLICT, "Sản phẩm hiện tại của khóa học không còn tồn tại"));
            if (orderItemRepository.countOrdersByProductId(previousProduct.getId()) > 0) {
                throw new AppException(ErrorCode.CONFLICT,
                        "Không thể đổi sản phẩm của khóa học đã có đơn hàng; quyền đã bán phải được bảo toàn");
            }
            previousProduct.setTargetCourseId(null);
            productRepository.save(previousProduct);
        }
        // Same rule as createCourse: adopt an unclaimed same-class product, never steal one
        // already bound to a different course.
        if (product.getTargetCourseId() == null || product.getTargetCourseId().isBlank()) {
            rejectOrderedProductAssociation(productId);
            product.setTargetCourseId(courseId);
            productRepository.save(product);
        } else if (!courseId.equals(product.getTargetCourseId())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Sản phẩm phải nhắm tới đúng khóa học trong cùng lớp");
        }
        course.setAccessMode("PURCHASE_REQUIRED");
        course.setProductId(productId);
        return courseRepository.save(course);
    }

    @Transactional
    public Section createSection(String courseId, String title, int position, String currentUserId) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));
        accessPolicy.enforceManage(currentUserId, course.getClassId(), "COURSE", "EDIT", courseId);
        if (title == null || title.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Tên chương không được để trống");
        }

        Section section = new Section(courseId, title.trim(), position);
        return sectionRepository.save(section);
    }

    @Transactional
    public Lesson createLesson(String sectionId, Lesson lesson, String currentUserId) {
        Section section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy chương"));
        Course course = courseRepository.findById(section.getCourseId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));

        accessPolicy.enforceManage(currentUserId, course.getClassId(), "COURSE", "EDIT", course.getId());

        if (lesson.getMediaAssetId() != null && !lesson.getMediaAssetId().isBlank()) {
            MediaAsset media = mediaService.getAssetForUpdate(lesson.getMediaAssetId());
            if (!media.getClassId().equals(course.getClassId())) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Tệp đính kèm không thuộc lớp học này");
            }
            if (!currentUserId.equals(media.getUploaderId()) && !accessPolicy.isOwner(currentUserId, course.getClassId())) {
                throw new AppException(ErrorCode.FORBIDDEN, "Chỉ người tải tệp lên hoặc OWNER mới được gắn tệp này vào bài học");
            }
            if (!"UPLOADED".equalsIgnoreCase(media.getStatus())) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Tệp chưa hoàn tất quá trình tải lên");
            }
            if (mediaService.isReferencedByDocument(lesson.getMediaAssetId())
                    || mediaService.isReferencedByLesson(lesson.getMediaAssetId())) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Tệp đã được gắn với nội dung khác và không thể tái sử dụng");
            }
        }

        lesson.setId(java.util.UUID.randomUUID().toString()); // Ignore client ID; create always inserts.
        lesson.setSectionId(sectionId);
        lesson.setCourseId(course.getId());
        return lessonRepository.save(lesson);
    }

    @Transactional(readOnly = true)
    public List<QuestionAnswerDto> getLessonQuestions(String lessonId, String userId) {
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài học"));
        Course course = courseRepository.findById(lesson.getCourseId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));

        learningPolicy.enforceLearn(userId, course);

        List<LessonQuestion> questions = questionRepository.findByLessonIdOrderByCreatedAtDesc(lessonId);
        List<QuestionAnswerDto> dtos = new ArrayList<>();

        for (LessonQuestion q : questions) {
            QuestionAnswerDto dto = new QuestionAnswerDto();
            dto.setId(q.getId());
            dto.setLessonId(q.getLessonId());
            User questionAuthor = userRepository.findById(q.getUserId()).orElse(null);
            boolean questionIdentityVisible = questionAuthor != null
                    && profileVisibilityPolicy.isIdentityVisible(questionAuthor, userId, course.getClassId());
            dto.setUserId(questionIdentityVisible ? q.getUserId() : null);
            dto.setAuthorName(questionAuthor == null ? ProfileVisibilityPolicy.ANONYMOUS_DISPLAY_NAME
                    : profileVisibilityPolicy.displayName(questionAuthor, userId, course.getClassId()));
            dto.setQuestionText(q.getQuestionText());
            dto.setCreatedAt(q.getCreatedAt());

            List<LessonAnswer> answers = answerRepository.findByQuestionIdOrderByCreatedAtAsc(q.getId());
            List<QuestionAnswerDto.AnswerDto> aDtos = answers.stream().map(a -> {
                User answerAuthor = userRepository.findById(a.getUserId()).orElse(null);
                boolean identityVisible = answerAuthor != null
                        && profileVisibilityPolicy.isIdentityVisible(answerAuthor, userId, course.getClassId());
                return new QuestionAnswerDto.AnswerDto(a.getId(), a.getQuestionId(), identityVisible ? a.getUserId() : null,
                        answerAuthor == null ? ProfileVisibilityPolicy.ANONYMOUS_DISPLAY_NAME
                                : profileVisibilityPolicy.displayName(answerAuthor, userId, course.getClassId()),
                        a.getAnswerText(), a.getCreatedAt());
            }).toList();
            dto.setAnswers(aDtos);

            dtos.add(dto);
        }

        return dtos;
    }

    @Transactional
    public QuestionAnswerDto askQuestion(String lessonId, String userId, String questionText) {
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài học"));
        Course course = courseRepository.findById(lesson.getCourseId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));

        learningPolicy.enforceLearn(userId, course);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy người dùng"));

        LessonQuestion q = new LessonQuestion(lessonId, userId, user.getFullName(), questionText.trim());
        LessonQuestion saved = questionRepository.save(q);

        QuestionAnswerDto dto = new QuestionAnswerDto();
        dto.setId(saved.getId());
        dto.setLessonId(saved.getLessonId());
        dto.setUserId(saved.getUserId());
        dto.setAuthorName(saved.getAuthorName());
        dto.setQuestionText(saved.getQuestionText());
        dto.setCreatedAt(saved.getCreatedAt());
        dto.setAnswers(List.of());
        return dto;
    }

    @Transactional
    public QuestionAnswerDto.AnswerDto answerQuestion(String questionId, String userId, String answerText) {
        LessonQuestion q = questionRepository.findById(questionId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy câu hỏi"));

        Lesson lesson = lessonRepository.findById(q.getLessonId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài học"));
        Course course = courseRepository.findById(lesson.getCourseId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));

        learningPolicy.enforceLearn(userId, course);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy người dùng"));

        LessonAnswer a = new LessonAnswer(questionId, userId, user.getFullName(), answerText.trim());
        LessonAnswer saved = answerRepository.save(a);

        return new QuestionAnswerDto.AnswerDto(saved.getId(), saved.getQuestionId(), saved.getUserId(), saved.getAuthorName(), saved.getAnswerText(), saved.getCreatedAt());
    }

    private CourseDto toCourseDto(Course course) {
        CourseDto dto = new CourseDto();
        dto.setId(course.getId());
        dto.setClassId(course.getClassId());
        dto.setProductId(course.getProductId());
        dto.setTitle(course.getTitle());
        dto.setDescription(course.getDescription());
        dto.setCoverImageUrl(course.getCoverImageUrl());
        dto.setAccessMode(course.getAccessMode());
        dto.setStatus(course.getStatus());
        dto.setPosition(course.getPosition());
        dto.setCreatedAt(course.getCreatedAt());
        return dto;
    }

    private LessonDto toLessonDto(Lesson lesson) {
        LessonDto dto = new LessonDto();
        dto.setId(lesson.getId());
        dto.setSectionId(lesson.getSectionId());
        dto.setCourseId(lesson.getCourseId());
        dto.setTitle(lesson.getTitle());
        dto.setType(lesson.getType());
        dto.setContentText(lesson.getContentText());
        dto.setMediaAssetId(lesson.getMediaAssetId());
        dto.setDurationMinutes(lesson.getDurationMinutes());
        dto.setPosition(lesson.getPosition());
        return dto;
    }

    private void rejectOrderedProductAssociation(String productId) {
        if (orderItemRepository.countOrdersByProductId(productId) > 0) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "Không thể gắn sản phẩm đã có đơn hàng vào khóa học; hãy tạo sản phẩm mới cho khóa học này");
        }
    }
}
