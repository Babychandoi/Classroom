package com.classroom.modules.learning.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.common.ReorderRequests;
import com.classroom.modules.classroom.model.StaffAssignment;
import com.classroom.modules.classroom.model.StaffPermission;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.StaffAssignmentRepository;
import com.classroom.modules.classroom.repository.StaffPermissionRepository;
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
import com.classroom.modules.audit.service.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    private final AuditService auditService;
    private final AssignmentSubmissionRepository assignmentSubmissionRepository;
    private final StaffAssignmentRepository staffAssignmentRepository;
    private final StaffPermissionRepository staffPermissionRepository;

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
                           OrderItemRepository orderItemRepository,
                           AuditService auditService,
                           AssignmentSubmissionRepository assignmentSubmissionRepository,
                           StaffAssignmentRepository staffAssignmentRepository,
                           StaffPermissionRepository staffPermissionRepository) {
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
        this.auditService = auditService;
        this.assignmentSubmissionRepository = assignmentSubmissionRepository;
        this.staffAssignmentRepository = staffAssignmentRepository;
        this.staffPermissionRepository = staffPermissionRepository;
    }

    @Transactional(readOnly = true)
    public List<CourseDto> getCoursesByClass(String classId, String userId) {
        accessPolicy.enforceMember(userId, classId);
        List<Course> courses = courseRepository.findByClassIdOrderByPositionAsc(classId);
        List<CourseDto> dtos = new ArrayList<>();

        // Unpublished (DRAFT) courses are not learner-visible: their metadata is only listed
        // for the OWNER and STAFF holding an explicit COURSE:PREVIEW / COURSE:EDIT grant.
        List<Course> viewable = courses.stream()
                .filter(course -> learningPolicy.canViewCourse(userId, course))
                .toList();

        // R14-03: progress counts only learner-visible lessons (lesson not archived AND its section
        // not archived) on BOTH sides of the fraction, otherwise an archived lesson inflates the
        // denominator (100% becomes unreachable) or its old progress inflates the numerator. Two
        // grouped queries for the whole class replace the previous 2 count queries per course.
        Map<String, Long> totalByCourse = new java.util.HashMap<>();
        Map<String, Long> completedByCourse = new java.util.HashMap<>();
        if (!viewable.isEmpty()) {
            List<String> courseIds = viewable.stream().map(Course::getId).toList();
            lessonRepository.countVisibleByCourseIdIn(courseIds)
                    .forEach(row -> totalByCourse.put(row.getCourseId(), row.getTotal()));
            if (userId != null) {
                progressRepository.countCompletedVisibleByUserAndCourseIdIn(userId, courseIds)
                        .forEach(row -> completedByCourse.put(row.getCourseId(), row.getTotal()));
            }
        }

        // R19-12: one lookup for every linked product, to tell which paid courses can still be bought.
        Map<String, Product> productsById = new java.util.HashMap<>();
        java.util.Set<String> linkedProductIds = new java.util.HashSet<>();
        for (Course course : viewable) {
            if (course.getProductId() != null && !course.getProductId().isBlank()) {
                linkedProductIds.add(course.getProductId());
            }
        }
        if (!linkedProductIds.isEmpty()) {
            for (Product linked : productRepository.findAllById(linkedProductIds)) {
                productsById.put(linked.getId(), linked);
            }
        }

        for (Course course : viewable) {
            boolean canLearn = (userId != null) && learningPolicy.canLearn(userId, course);
            long totalLessons = totalByCourse.getOrDefault(course.getId(), 0L);
            long completedLessons = Math.min(completedByCourse.getOrDefault(course.getId(), 0L), totalLessons);

            CourseDto dto = toCourseDto(course);
            dto.setCanLearn(canLearn);
            dto.setCanEdit(userId != null && accessPolicy.canManage(userId, classId, "COURSE", "EDIT", course.getId()));
            // R13-09: access reason/expiry only meaningful for a purchase-gated course; a FREE
            // course's dto already carries accessReason=FREE from resolveAccessReason.
            applyAccessReason(dto, learningPolicy.resolveAccessReason(userId, course));
            applyPurchasability(dto, course, course.getProductId() == null ? null : productsById.get(course.getProductId()));
            dto.setTotalLessons((int) totalLessons);
            dto.setCompletedLessons((int) completedLessons);
            dto.setProgressPercentage(totalLessons > 0 ? ((double) completedLessons / totalLessons) * 100 : 0);
            dtos.add(dto);
        }

        return dtos;
    }

    private void applyAccessReason(CourseDto dto, LearningPolicy.AccessReason accessReason) {
        dto.setAccessReason(accessReason.reason());
        dto.setExpiresAt(accessReason.expiresAt());
        dto.setAccessStartsAt(accessReason.startsAt());
    }

    /**
     * R19-12: a paid course is purchasable only while its product is PUBLISHED. After the product is archived
     * ("gỡ bán") it is no longer listed or sellable, so a learner without access must not be pointed at the
     * store (and an expired buyer cannot renew); existing buyers keep their entitlement and their expiry.
     */
    private void applyPurchasability(CourseDto dto, Course course, Product product) {
        dto.setProductStatus(product == null ? null : product.getStatus());
        dto.setCanPurchase("PURCHASE_REQUIRED".equalsIgnoreCase(course.getAccessMode())
                && product != null
                && "PUBLISHED".equalsIgnoreCase(product.getStatus()));
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
        boolean canEdit = userId != null && accessPolicy.canManage(userId, course.getClassId(), "COURSE", "EDIT", course.getId());
        CourseDto dto = toCourseDto(course);
        dto.setCanLearn(canLearn);
        dto.setCanEdit(canEdit);
        applyAccessReason(dto, learningPolicy.resolveAccessReason(userId, course));
        Product linkedProduct = (course.getProductId() == null || course.getProductId().isBlank())
                ? null : productRepository.findById(course.getProductId()).orElse(null);
        applyPurchasability(dto, course, linkedProduct);

        List<Section> sections = sectionRepository.findByCourseIdOrderByPositionAsc(courseId);
        List<SectionDto> sectionDtos = new ArrayList<>();

        for (Section section : sections) {
            // Archived sections/lessons are hidden from learners but remain visible to editors
            // so Studio can offer "restore".
            if (section.isArchived() && !canEdit) {
                continue;
            }
            List<Lesson> lessons = lessonRepository.findBySectionIdOrderByPositionAsc(section.getId());
            List<LessonDto> lessonDtos = new ArrayList<>();

            for (Lesson lesson : lessons) {
                if (lesson.isArchived() && !canEdit) {
                    continue;
                }
                LessonDto lDto = toLessonDto(lesson);
                if (canLearn || canEdit) exposeVideoLinks(lDto, lesson);
                if (userId != null) {
                    boolean completed = progressRepository.findByUserIdAndLessonId(userId, lesson.getId())
                            .map(LessonProgress::isCompleted)
                            .orElse(false);
                    lDto.setCompleted(completed);
                }
                // If student cannot learn, do not expose full content text or media
                if (!canLearn) {
                    lDto.setContentText(null);
                    lDto.setCaptionsVtt(null);
                    lDto.setMediaAssetId(null);
                }
                lessonDtos.add(lDto);
            }

            SectionDto sDto = new SectionDto(section.getId(), section.getCourseId(), section.getTitle(), section.getPosition(), lessonDtos);
            sDto.setArchived(section.isArchived());
            sectionDtos.add(sDto);
        }

        dto.setSections(sectionDtos);
        return dto;
    }

    /** R13-09: lightweight access-reason lookup for GET /courses/{id}/access. */
    @Transactional(readOnly = true)
    public com.classroom.modules.learning.dto.CourseAccessDto getCourseAccess(String courseId, String userId) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));
        accessPolicy.enforceMember(userId, course.getClassId());
        if (!learningPolicy.canViewCourse(userId, course)) {
            throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học");
        }
        boolean canLearn = learningPolicy.canLearn(userId, course);
        LearningPolicy.AccessReason accessReason = learningPolicy.resolveAccessReason(userId, course);
        return new com.classroom.modules.learning.dto.CourseAccessDto(canLearn, accessReason.reason(),
                accessReason.expiresAt(), accessReason.startsAt());
    }

    @Transactional(readOnly = true)
    public LessonDto getLesson(String lessonId, String userId) {
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài học"));

        Course course = courseRepository.findById(lesson.getCourseId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));

        // Enforce student has access
        learningPolicy.enforceLearn(userId, course);
        requireVisibleToLearner(lesson, course, userId);

        LessonDto dto = toLessonDto(lesson);
        exposeVideoLinks(dto, lesson); // enforceLearn passed above

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
        accessPolicy.enforceNotSuspended(course.getClassId()); // D-29: a suspended class is read-only, also for its owner
        // R14-02: an archived lesson (or one inside an archived section) is invisible to learners -
        // report it as missing rather than confirming it exists. A course editor who can still open
        // it in Studio gets the explicit reason instead, since progress is never recorded on it.
        requireVisibleToLearner(lesson, course, userId);
        if (lesson.isArchived() || isSectionArchived(lesson.getSectionId())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Bài học đã được lưu trữ và không thể đánh dấu tiến độ");
        }

        Optional<LessonProgress> progressOpt = progressRepository.findByUserIdAndLessonId(userId, lessonId);
        boolean wasCompleted = progressOpt.map(LessonProgress::isCompleted).orElse(false);
        if (progressOpt.isPresent()) {
            LessonProgress p = progressOpt.get();
            p.setCompleted(completed);
            progressRepository.save(p);
        } else {
            LessonProgress p = new LessonProgress(userId, lessonId, course.getId(), course.getClassId());
            p.setCompleted(completed);
            progressRepository.save(p);
        }

        // Finding 8: Emit outbox event for lesson completion.
        // R3-11: only fire on the false->true transition; re-marking an already-completed
        // lesson must not emit a duplicate LESSON_COMPLETED event.
        if (completed && !wasCompleted) {
            outboxService.recordEvent("LEARNING", lessonId, "LESSON_COMPLETED", Map.of(
                    "userId", userId,
                    "classId", course.getClassId(),
                    "courseId", course.getId(),
                    "lessonId", lessonId,
                    "completedAt", Instant.now().toString()
            ));
        }
    }

    // R19-01(c): READ_COMMITTED - accessPolicy reads precede the product lock, and the "product already has orders" check
    // after it must see the orders a concurrent purchase just committed (not the pre-lock snapshot).
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Course createCourse(String classId, Course course, String currentUserId) {
        accessPolicy.enforceManage(currentUserId, classId, "COURSE", "CREATE", null);
        if (course == null || course.getTitle() == null || course.getTitle().isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Tên khóa học không được để trống");
        }
        course.setId(java.util.UUID.randomUUID().toString()); // Ignore client ID; create always inserts.
        course.setClassId(classId);
        course.setStatus("DRAFT"); // Ignore client status; new courses start in DRAFT
        // R18-04: append to the end of the class order (the client sends no position, so every course used to
        // get 0 and the list order was arbitrary); reorderCourses renumbers 0..n-1 whenever the owner reorders.
        course.setPosition(courseRepository.findMaxPositionByClassId(classId) + 1);
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
        // R16-02: publish is a DRAFT -> PUBLISHED transition only. Before, any status (notably
        // ARCHIVED) could be flipped to PUBLISHED by a COURSE:PUBLISH holder, bypassing restoreCourse
        // (which needs COURSE:EDIT) and leaving no audit trail. Re-publishing a PUBLISHED course is
        // an idempotent no-op (same as archiveCourse on an already ARCHIVED course).
        if ("PUBLISHED".equalsIgnoreCase(course.getStatus())) {
            return course;
        }
        if (!"DRAFT".equalsIgnoreCase(course.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "Chỉ có thể công bố khóa học ở trạng thái DRAFT; khóa học đã lưu trữ cần được khôi phục trước");
        }
        course.setStatus("PUBLISHED");
        course.setUpdatedAt(Instant.now());
        Course saved = courseRepository.save(course);
        auditService.record(course.getClassId(), currentUserId, "COURSE_PUBLISH", "COURSE", courseId, "{}");
        return saved;
    }

    // R19-01(c): READ_COMMITTED, same reason as createCourse (order-count check after the product lock).
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Course linkProduct(String courseId, String productId, String currentUserId) {
        Course course = courseRepository.findByIdForUpdate(courseId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));
        accessPolicy.enforceManage(currentUserId, course.getClassId(), "COURSE", "EDIT", courseId);
        accessPolicy.enforceManage(currentUserId, course.getClassId(), "STORE", "EDIT", null);
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy sản phẩm khóa học"));
        if (product.isClassAccess()) {
            // D-19: the class-access product sells membership of the class; it can never gate a single course.
            throw new AppException(ErrorCode.BAD_REQUEST, "Sản phẩm truy cập lớp học không thể gắn với khóa học");
        }
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

    /**
     * R13-03 (FR-04/LLD §5): edit course metadata. Title/description/cover/position may be edited
     * regardless of course status — the spec's only explicit lifecycle restriction is on
     * access-mode/product changes (handled separately by linkProduct) and on archive/delete.
     * Editing a PUBLISHED course's metadata must not disturb learner progress or entitlements,
     * which this method never touches.
     */
    @Transactional
    public Course updateCourse(String courseId, CourseDto patch, String currentUserId) {
        Course course = courseRepository.findByIdForUpdate(courseId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));
        accessPolicy.enforceManage(currentUserId, course.getClassId(), "COURSE", "EDIT", courseId);
        if ("ARCHIVED".equalsIgnoreCase(course.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Không thể sửa khóa học đã lưu trữ; hãy khôi phục trước");
        }
        if (patch.getTitle() != null) {
            if (patch.getTitle().isBlank()) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Tên khóa học không được để trống");
            }
            course.setTitle(patch.getTitle().trim());
        }
        if (patch.getDescription() != null) {
            course.setDescription(patch.getDescription());
        }
        if (patch.getCoverImageUrl() != null) {
            course.setCoverImageUrl(patch.getCoverImageUrl());
        }
        course.setUpdatedAt(Instant.now());
        Course saved = courseRepository.save(course);
        auditService.record(course.getClassId(), currentUserId, "COURSE_UPDATE", "COURSE", courseId,
                String.format("{\"title\":\"%s\"}", saved.getTitle()));
        return saved;
    }

    /**
     * R13-03 (SRS §5 + LLD §5 "khóa học bị gỡ bán nhưng người mua còn hạn"): archiving a course
     * never revokes entitlements already sold — LearningPolicy.canLearn/canViewCourse already keep
     * ARCHIVED+PURCHASE_REQUIRED courses reachable to existing entitlement holders and OWNER/STAFF,
     * this only flips the flag so new members stop seeing it in FREE listings / new purchases stop.
     */
    @Transactional
    public Course archiveCourse(String courseId, String currentUserId) {
        Course course = courseRepository.findByIdForUpdate(courseId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));
        accessPolicy.enforceManage(currentUserId, course.getClassId(), "COURSE", "EDIT", courseId);
        if ("ARCHIVED".equalsIgnoreCase(course.getStatus())) {
            return course;
        }
        course.setStatus("ARCHIVED");
        course.setUpdatedAt(Instant.now());
        Course saved = courseRepository.save(course);
        auditService.record(course.getClassId(), currentUserId, "COURSE_ARCHIVE", "COURSE", courseId, "{}");
        return saved;
    }

    @Transactional
    public Course restoreCourse(String courseId, String currentUserId) {
        Course course = courseRepository.findByIdForUpdate(courseId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));
        accessPolicy.enforceManage(currentUserId, course.getClassId(), "COURSE", "EDIT", courseId);
        if (!"ARCHIVED".equalsIgnoreCase(course.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Chỉ có thể khôi phục khóa học đã lưu trữ");
        }
        course.setStatus("DRAFT");
        course.setUpdatedAt(Instant.now());
        Course saved = courseRepository.save(course);
        auditService.record(course.getClassId(), currentUserId, "COURSE_RESTORE", "COURSE", courseId, "{}");
        return saved;
    }

    /**
     * R13-03: hard delete is only safe for a DRAFT course with no learner progress recorded
     * anywhere under it (no one could have progress on an unpublished course, but guard anyway
     * for defense in depth) and never sold (no order_items reference its product). Any other
     * course must be archived instead — mirrors the "gỡ bán nhưng người mua còn hạn" principle:
     * we never destroy a record that access/audit history depends on.
     */
    @Transactional
    public void deleteCourse(String courseId, String currentUserId) {
        Course course = courseRepository.findByIdForUpdate(courseId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));
        accessPolicy.enforceManage(currentUserId, course.getClassId(), "COURSE", "EDIT", courseId);
        if (!"DRAFT".equalsIgnoreCase(course.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Chỉ có thể xóa khóa học ở trạng thái DRAFT; hãy lưu trữ khóa học đã công bố");
        }
        if (course.getProductId() != null && orderItemRepository.countOrdersByProductId(course.getProductId()) > 0) {
            throw new AppException(ErrorCode.CONFLICT, "Không thể xóa khóa học đã có đơn hàng; hãy lưu trữ thay vì xóa");
        }
        // R16-03: never delete a course a product still points at. products.target_course_id used to
        // dangle (no FK): the product stayed PUBLISHED and a later payment failed its entitlement
        // insert (fk_ent_course) after the buyer had already paid. V29 adds the FK as a backstop;
        // this gives the Studio user an actionable message instead of a constraint violation.
        boolean linkedToProduct = productRepository.existsByTargetCourseId(courseId)
                || (course.getProductId() != null && !course.getProductId().isBlank()
                && productRepository.existsById(course.getProductId()));
        if (linkedToProduct) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "Không thể xóa khóa học đang được liên kết với một sản phẩm; hãy lưu trữ khóa học thay vì xóa");
        }
        // R18-07: staff_permissions.scope_course_id has a RESTRICT foreign key to courses (V19), so a course that
        // some staff grant is scoped to cannot be deleted. Say so up front (409, naming who holds the grant and
        // how to clear it) instead of letting the delete hit the constraint and surface as a misleading 400.
        List<StaffPermission> scopedGrants = staffPermissionRepository.findByScopeCourseId(courseId);
        if (!scopedGrants.isEmpty()) {
            throw new AppException(ErrorCode.CONFLICT, describeScopedStaffGrants(scopedGrants));
        }
        // Broader check: any learner progress, Q&A or assignment submission under this course's
        // lessons blocks delete (all are ON DELETE CASCADE from lessons/courses - R14-04).
        for (Lesson lesson : lessonRepository.findByCourseIdOrderByPositionAsc(courseId)) {
            if (!isLessonSafeToDelete(lesson.getId())) {
                throw new AppException(ErrorCode.CONFLICT,
                        "Không thể xóa khóa học đã có tiến độ/bài nộp/hỏi đáp của học viên; hãy lưu trữ thay vì xóa");
            }
        }
        sectionRepository.deleteByCourseId(courseId);
        courseRepository.delete(course);
        auditService.record(course.getClassId(), currentUserId, "COURSE_DELETE", "COURSE", courseId, "{}");
    }

    /** R18-07: "A (COURSE:EDIT, EXAM:CREATE); B (COURSE:PREVIEW)" + what to do about it (max 3 people named). */
    private String describeScopedStaffGrants(List<StaffPermission> scopedGrants) {
        Map<String, List<String>> grantsByAssignment = new java.util.LinkedHashMap<>();
        for (StaffPermission grant : scopedGrants) {
            grantsByAssignment.computeIfAbsent(grant.getAssignmentId(), key -> new ArrayList<>())
                    .add(grant.getModule() + ":" + grant.getAction());
        }
        Map<String, String> userIdByAssignment = new java.util.HashMap<>();
        for (StaffAssignment assignment : staffAssignmentRepository.findAllById(grantsByAssignment.keySet())) {
            userIdByAssignment.put(assignment.getId(), assignment.getUserId());
        }
        Map<String, String> nameByUser = new java.util.HashMap<>();
        for (User user : userRepository.findAllById(userIdByAssignment.values())) {
            nameByUser.put(user.getId(), user.getFullName());
        }
        List<String> people = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : grantsByAssignment.entrySet()) {
            String name = nameByUser.get(userIdByAssignment.get(entry.getKey()));
            people.add((name == null || name.isBlank() ? "Trợ giảng" : name) + " (" + String.join(", ", entry.getValue()) + ")");
        }
        String named = people.size() <= 3
                ? String.join("; ", people)
                : String.join("; ", people.subList(0, 3)) + " và " + (people.size() - 3) + " trợ giảng khác";
        return "Không thể xóa khóa học vì đang có quyền trợ giảng gắn riêng với khóa này: " + named
                + ". Hãy vào Studio > Trợ giảng, gỡ các quyền theo khóa đó rồi xóa lại, hoặc lưu trữ khóa học thay vì xóa.";
    }

    @Transactional
    public void reorderCourses(String classId, List<String> orderedCourseIds, String currentUserId) {
        accessPolicy.enforceManage(currentUserId, classId, "COURSE", "EDIT", null);
        List<Course> courses = courseRepository.findByClassIdOrderByPositionAsc(classId);
        Map<String, Course> byId = new java.util.HashMap<>();
        courses.forEach(c -> byId.put(c.getId(), c));
        if (!ReorderRequests.isPermutationOf(orderedCourseIds, byId.keySet())) { // R16-10: no duplicates
            throw new AppException(ErrorCode.BAD_REQUEST, "Danh sách thứ tự khóa học không hợp lệ");
        }
        for (int i = 0; i < orderedCourseIds.size(); i++) {
            Course c = byId.get(orderedCourseIds.get(i));
            c.setPosition(i);
            c.setUpdatedAt(Instant.now());
            courseRepository.save(c);
        }
    }

    @Transactional
    public void reorderSections(String courseId, List<String> orderedSectionIds, String currentUserId) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));
        accessPolicy.enforceManage(currentUserId, course.getClassId(), "COURSE", "EDIT", courseId);
        List<Section> sections = sectionRepository.findByCourseIdForUpdate(courseId);
        Map<String, Section> byId = new java.util.HashMap<>();
        sections.forEach(s -> byId.put(s.getId(), s));
        if (!ReorderRequests.isPermutationOf(orderedSectionIds, byId.keySet())) { // R16-10: no duplicates
            throw new AppException(ErrorCode.BAD_REQUEST, "Danh sách thứ tự chương không hợp lệ");
        }
        for (int i = 0; i < orderedSectionIds.size(); i++) {
            Section s = byId.get(orderedSectionIds.get(i));
            s.setPosition(i);
            sectionRepository.save(s);
        }
    }

    @Transactional
    public Section updateSection(String sectionId, String title, String currentUserId) {
        Section section = sectionRepository.findByIdForUpdate(sectionId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy chương"));
        Course course = courseRepository.findById(section.getCourseId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));
        accessPolicy.enforceManage(currentUserId, course.getClassId(), "COURSE", "EDIT", course.getId());
        if (title != null) {
            if (title.isBlank()) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Tên chương không được để trống");
            }
            section.setTitle(title.trim());
        }
        return sectionRepository.save(section);
    }

    /**
     * R13-03: a section may be hard-deleted only when none of its lessons have learner progress,
     * Q&A, or assignment submissions attached (deleting it would silently destroy that history via
     * FK cascade). Otherwise it is archived (hidden from learners, kept for OWNER/STAFF) instead.
     */
    @Transactional
    public void deleteSection(String sectionId, String currentUserId) {
        Section section = sectionRepository.findByIdForUpdate(sectionId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy chương"));
        Course course = courseRepository.findById(section.getCourseId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));
        accessPolicy.enforceManage(currentUserId, course.getClassId(), "COURSE", "EDIT", course.getId());
        List<Lesson> lessons = lessonRepository.findBySectionIdForUpdate(sectionId);
        if (!isSectionSafeToDelete(lessons)) {
            throw new AppException(ErrorCode.CONFLICT,
                    "Chương có bài học đã ghi nhận tiến độ/bài nộp/hỏi đáp; hãy lưu trữ thay vì xóa");
        }
        for (Lesson lesson : lessons) {
            lessonRepository.delete(lesson);
        }
        sectionRepository.delete(section);
        auditService.record(course.getClassId(), currentUserId, "SECTION_DELETE", "SECTION", sectionId, "{}");
    }

    @Transactional
    public Section archiveSection(String sectionId, boolean archived, String currentUserId) {
        Section section = sectionRepository.findByIdForUpdate(sectionId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy chương"));
        Course course = courseRepository.findById(section.getCourseId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));
        accessPolicy.enforceManage(currentUserId, course.getClassId(), "COURSE", "EDIT", course.getId());
        section.setArchived(archived);
        Section saved = sectionRepository.save(section);
        auditService.record(course.getClassId(), currentUserId, archived ? "SECTION_ARCHIVE" : "SECTION_RESTORE",
                "SECTION", sectionId, "{}");
        return saved;
    }

    private boolean isSectionSafeToDelete(List<Lesson> lessons) {
        for (Lesson lesson : lessons) {
            if (!isLessonSafeToDelete(lesson.getId())) return false;
        }
        return true;
    }

    /**
     * R14-04: a lesson may be hard-deleted only when nothing learner-generated hangs off it. The
     * progress, Q&amp;A <em>and assignment submission</em> tables all reference lessons ON DELETE
     * CASCADE, so deleting a lesson (directly, via its section, or via its course) silently destroys
     * that history - including graded assignment work. Any such record makes the lesson archive-only.
     */
    private boolean isLessonSafeToDelete(String lessonId) {
        return !progressRepository.existsByLessonId(lessonId)
                && !questionRepository.existsByLessonId(lessonId)
                && !assignmentSubmissionRepository.existsByLessonId(lessonId);
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

    private static final Set<String> ALLOWED_LESSON_TYPES = Set.of("VIDEO", "TEXT", "DOCUMENT", "ASSIGNMENT");
    private void validateCaptions(String value) {
        if (value != null && !value.isBlank() && (value.length() > 1000000 || !value.stripLeading().startsWith("WEBVTT"))) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Phụ đề cần định dạng WebVTT, tối đa 1 triệu ký tự");
        }
    }

    /**
     * R8-09: createLesson (and any future update path) must reject an out-of-range or unsupported
     * lesson payload with 400 rather than silently persisting it — an unrecognised type would never
     * render correctly client-side, and a negative duration/position is nonsensical and would sort
     * or display incorrectly.
     */
    private void validateLesson(Lesson lesson) {
        if (lesson == null || lesson.getTitle() == null || lesson.getTitle().isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Tên bài học không được để trống");
        }
        String type = lesson.getType() == null ? "" : lesson.getType().trim().toUpperCase(java.util.Locale.ROOT);
        if (!ALLOWED_LESSON_TYPES.contains(type)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Loại bài học không hợp lệ: " + lesson.getType());
        }
        lesson.setType(type);
        if (lesson.getDurationMinutes() < 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Thời lượng bài học không được âm");
        }
        if (lesson.getPosition() < 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Vị trí bài học không được âm");
        }
    }

    /**
     * D-31: applies what the author pasted. {@code input} null = keep; blank = clear the external video; otherwise it is parsed (400 when it is
     * not a supported https YouTube / Google Drive file link) and only the provider and id are stored. Call after the media attachment was
     * settled; {@link #requireSingleVideoSource} then enforces "one source, VIDEO lessons only".
     */
    private void applyVideoUrl(Lesson lesson, String input) {
        if (input == null) return;
        if (input.isBlank()) {
            lesson.setStoredVideoProvider(null);
            lesson.setStoredVideoRef(null);
            return;
        }
        VideoLinkParser.Parsed parsed = VideoLinkParser.parse(input);
        lesson.setStoredVideoProvider(parsed.provider());
        lesson.setStoredVideoRef(parsed.ref());
    }

    private void requireSingleVideoSource(Lesson lesson) {
        if (lesson.getStoredVideoRef() == null) return;
        if (!"VIDEO".equalsIgnoreCase(lesson.getType())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Chỉ bài học loại VIDEO mới dùng liên kết video");
        }
        if (lesson.getMediaAssetId() != null && !lesson.getMediaAssetId().isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Mỗi bài chỉ dùng một nguồn video: tải tệp lên hoặc dán liên kết");
        }
    }

    // R19-01(c): READ_COMMITTED. The section/course/permission reads before mediaService.getAssetForUpdate freeze the
    // MySQL REPEATABLE READ snapshot, so the "media already attached to a document/lesson" check made AFTER that lock
    // could not see an attachment a concurrent transaction had just committed - the same file was attached twice.
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Lesson createLesson(String sectionId, Lesson lesson, String currentUserId) {
        Section section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy chương"));
        Course course = courseRepository.findById(section.getCourseId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));

        accessPolicy.enforceManage(currentUserId, course.getClassId(), "COURSE", "EDIT", course.getId());

        validateLesson(lesson);

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

        validateCaptions(lesson.getCaptionsVtt());
        lesson.setStoredVideoProvider(null); // D-31: never trust anything but the parsed videoUrl
        lesson.setStoredVideoRef(null);
        applyVideoUrl(lesson, lesson.getVideoUrlInput());
        requireSingleVideoSource(lesson);
        lesson.setId(java.util.UUID.randomUUID().toString()); // Ignore client ID; create always inserts.
        lesson.setSectionId(sectionId);
        lesson.setCourseId(course.getId());
        Lesson created = lessonRepository.save(lesson);
        auditService.record(course.getClassId(), currentUserId, "LESSON_CREATE", "LESSON", created.getId(),
                String.format("{\"title\":\"%s\",\"videoProvider\":%s}", created.getTitle(), providerJson(created)));
        return created;
    }

    private static String providerJson(Lesson lesson) {
        String provider = lesson.getVideoProvider();
        return provider == null ? "null" : "\"" + provider + "\"";
    }

    /**
     * R13-03: update a lesson's title/content/media/type/duration/position. COURSE:EDIT scoped to
     * the owning course, same as createLesson. Media re-validation mirrors createLesson's checks
     * so an update cannot attach an asset from another class or one still uploading.
     */
    // R19-01(c): READ_COMMITTED, same reason as createLesson.
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Lesson updateLesson(String lessonId, Lesson patch, String currentUserId) {
        Lesson lesson = lessonRepository.findByIdForUpdate(lessonId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài học"));
        Course course = courseRepository.findById(lesson.getCourseId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));
        accessPolicy.enforceManage(currentUserId, course.getClassId(), "COURSE", "EDIT", course.getId());

        if (patch.getTitle() != null) lesson.setTitle(patch.getTitle());
        if (patch.getType() != null) lesson.setType(patch.getType());
        if (patch.getContentText() != null) lesson.setContentText(patch.getContentText());
        if (patch.getCaptionsVtt() != null) { validateCaptions(patch.getCaptionsVtt()); lesson.setCaptionsVtt(patch.getCaptionsVtt()); }
        if (patch.getDurationMinutes() >= 0) lesson.setDurationMinutes(patch.getDurationMinutes());

        String newMediaId = patch.getMediaAssetId();
        if (newMediaId != null && !newMediaId.equals(lesson.getMediaAssetId())) {
            if (!newMediaId.isBlank()) {
                MediaAsset media = mediaService.getAssetForUpdate(newMediaId);
                if (!media.getClassId().equals(course.getClassId())) {
                    throw new AppException(ErrorCode.BAD_REQUEST, "Tệp đính kèm không thuộc lớp học này");
                }
                if (!currentUserId.equals(media.getUploaderId()) && !accessPolicy.isOwner(currentUserId, course.getClassId())) {
                    throw new AppException(ErrorCode.FORBIDDEN, "Chỉ người tải tệp lên hoặc OWNER mới được gắn tệp này vào bài học");
                }
                if (!"UPLOADED".equalsIgnoreCase(media.getStatus())) {
                    throw new AppException(ErrorCode.BAD_REQUEST, "Tệp chưa hoàn tất quá trình tải lên");
                }
                if (mediaService.isReferencedByDocument(newMediaId) || mediaService.isReferencedByLesson(newMediaId)) {
                    throw new AppException(ErrorCode.BAD_REQUEST, "Tệp đã được gắn với nội dung khác và không thể tái sử dụng");
                }
                lesson.setMediaAssetId(newMediaId);
            } else {
                lesson.setMediaAssetId(null);
            }
        }

        validateLesson(lesson);
        applyVideoUrl(lesson, patch.getVideoUrlInput()); // D-31
        requireSingleVideoSource(lesson);
        Lesson saved = lessonRepository.save(lesson);
        auditService.record(course.getClassId(), currentUserId, "LESSON_UPDATE", "LESSON", lessonId,
                String.format("{\"title\":\"%s\",\"videoProvider\":%s}", saved.getTitle(), providerJson(saved)));
        return saved;
    }

    @Transactional
    public void reorderLessons(String sectionId, List<String> orderedLessonIds, String currentUserId) {
        Section section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy chương"));
        Course course = courseRepository.findById(section.getCourseId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));
        accessPolicy.enforceManage(currentUserId, course.getClassId(), "COURSE", "EDIT", course.getId());
        List<Lesson> lessons = lessonRepository.findBySectionIdForUpdate(sectionId);
        Map<String, Lesson> byId = new java.util.HashMap<>();
        lessons.forEach(l -> byId.put(l.getId(), l));
        if (!ReorderRequests.isPermutationOf(orderedLessonIds, byId.keySet())) { // R16-10: no duplicates
            throw new AppException(ErrorCode.BAD_REQUEST, "Danh sách thứ tự bài học không hợp lệ");
        }
        for (int i = 0; i < orderedLessonIds.size(); i++) {
            Lesson l = byId.get(orderedLessonIds.get(i));
            l.setPosition(i);
            lessonRepository.save(l);
        }
    }

    /**
     * R13-03/R14-04: hard delete only when the lesson has no learner progress, no Q&amp;A and no
     * assignment submissions recorded (submissions are keyed by lessonId with ON DELETE CASCADE, so
     * they are checked by the same safe-to-delete helper shared with deleteSection/deleteCourse).
     * Otherwise archive (hide from learners) instead of destroying history that progress/grading/
     * audit tracking depends on.
     */
    @Transactional
    public void deleteLesson(String lessonId, String currentUserId) {
        Lesson lesson = lessonRepository.findByIdForUpdate(lessonId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài học"));
        Course course = courseRepository.findById(lesson.getCourseId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));
        accessPolicy.enforceManage(currentUserId, course.getClassId(), "COURSE", "EDIT", course.getId());
        if (!isLessonSafeToDelete(lessonId)) {
            throw new AppException(ErrorCode.CONFLICT,
                    "Bài học đã có tiến độ/bài nộp/hỏi đáp của học viên; hãy lưu trữ thay vì xóa");
        }
        lessonRepository.delete(lesson);
        auditService.record(course.getClassId(), currentUserId, "LESSON_DELETE", "LESSON", lessonId, "{}");
    }

    @Transactional
    public Lesson archiveLesson(String lessonId, boolean archived, String currentUserId) {
        Lesson lesson = lessonRepository.findByIdForUpdate(lessonId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài học"));
        Course course = courseRepository.findById(lesson.getCourseId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));
        accessPolicy.enforceManage(currentUserId, course.getClassId(), "COURSE", "EDIT", course.getId());
        lesson.setArchived(archived);
        Lesson saved = lessonRepository.save(lesson);
        auditService.record(course.getClassId(), currentUserId, archived ? "LESSON_ARCHIVE" : "LESSON_RESTORE",
                "LESSON", lessonId, "{}");
        return saved;
    }

    @Transactional(readOnly = true)
    public List<QuestionAnswerDto> getLessonQuestions(String lessonId, String userId) {
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài học"));
        Course course = courseRepository.findById(lesson.getCourseId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));

        learningPolicy.enforceLearn(userId, course);
        requireVisibleToLearner(lesson, course, userId);

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
        accessPolicy.enforceNotSuspended(course.getClassId()); // D-29: a suspended class is read-only, also for its owner
        requireVisibleToLearner(lesson, course, userId);

        if (questionText == null || questionText.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Nội dung câu hỏi không được để trống");
        }

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
        accessPolicy.enforceNotSuspended(course.getClassId()); // D-29: a suspended class is read-only, also for its owner
        requireVisibleToLearner(lesson, course, userId);

        if (answerText == null || answerText.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Nội dung câu trả lời không được để trống");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy người dùng"));

        LessonAnswer a = new LessonAnswer(questionId, userId, user.getFullName(), answerText.trim());
        LessonAnswer saved = answerRepository.save(a);

        return new QuestionAnswerDto.AnswerDto(saved.getId(), saved.getQuestionId(), saved.getUserId(), saved.getAuthorName(), saved.getAnswerText(), saved.getCreatedAt());
    }

    /**
     * R14-02: every learner-facing lesson path (detail, progress, Q&amp;A read/ask/answer, assignment
     * submit, media download) funnels through {@link LearningPolicy#isLessonHiddenFromLearner} so the
     * "archived lesson OR archived section" rule is defined once; course editors are exempt.
     */
    private void requireVisibleToLearner(Lesson lesson, Course course, String userId) {
        if (learningPolicy.isLessonHiddenFromLearner(lesson, course, userId)) {
            throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài học");
        }
    }

    private boolean isSectionArchived(String sectionId) {
        if (sectionId == null) return false;
        return sectionRepository.findById(sectionId).map(Section::isArchived).orElse(false);
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

    /** D-31: the external video's URLs, for callers already cleared to learn the course (or to edit it). Never for anyone else. */
    private static void exposeVideoLinks(LessonDto dto, Lesson lesson) {
        dto.setVideoUrl(lesson.getVideoUrl());
        dto.setEmbedUrl(lesson.getEmbedUrl());
    }

    private LessonDto toLessonDto(Lesson lesson) {
        LessonDto dto = new LessonDto();
        dto.setId(lesson.getId());
        dto.setSectionId(lesson.getSectionId());
        dto.setCourseId(lesson.getCourseId());
        dto.setTitle(lesson.getTitle());
        dto.setType(lesson.getType());
        dto.setContentText(lesson.getContentText());
        dto.setCaptionsVtt(lesson.getCaptionsVtt());
        dto.setMediaAssetId(lesson.getMediaAssetId());
        dto.setVideoProvider(lesson.getVideoProvider()); // D-31: the provider is public metadata; the URLs only via exposeVideoLinks
        dto.setDurationMinutes(lesson.getDurationMinutes());
        dto.setPosition(lesson.getPosition());
        dto.setArchived(lesson.isArchived());
        return dto;
    }

    private void rejectOrderedProductAssociation(String productId) {
        if (orderItemRepository.countOrdersByProductId(productId) > 0) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "Không thể gắn sản phẩm đã có đơn hàng vào khóa học; hãy tạo sản phẩm mới cho khóa học này");
        }
    }
}
