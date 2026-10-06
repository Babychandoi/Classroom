package com.classroom.modules.learning.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.common.ReorderRequests;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.learning.dto.LessonAttachmentDto;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.model.Lesson;
import com.classroom.modules.learning.model.LessonAttachment;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.learning.repository.LessonAttachmentRepository;
import com.classroom.modules.learning.repository.LessonRepository;
import com.classroom.modules.media.model.MediaAsset;
import com.classroom.modules.media.service.MediaService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * D-32: the documents of a lesson (0..20, each an UPLOADED, non-video media asset of the lesson's class, attached nowhere else). Writes need
 * COURSE:EDIT on the course (so a suspended class answers 409) and the lesson row lock; the lesson's derived {@code type} is recomputed on
 * every change. Reads are batched: the DTOs of a whole course come from two queries.
 */
@Service
public class LessonAttachmentService {

    public static final int MAX_PER_LESSON = 20;
    public static final int MAX_TITLE = 200;

    private final LessonRepository lessonRepository;
    private final LessonAttachmentRepository attachmentRepository;
    private final CourseRepository courseRepository;
    private final AccessPolicy accessPolicy;
    private final MediaService mediaService;
    private final AuditService auditService;

    public LessonAttachmentService(LessonRepository lessonRepository, LessonAttachmentRepository attachmentRepository,
                                   CourseRepository courseRepository, AccessPolicy accessPolicy, MediaService mediaService,
                                   AuditService auditService) {
        this.lessonRepository = lessonRepository;
        this.attachmentRepository = attachmentRepository;
        this.courseRepository = courseRepository;
        this.accessPolicy = accessPolicy;
        this.mediaService = mediaService;
        this.auditService = auditService;
    }

    /** Documents per lesson id (every id present, possibly empty), with the file metadata; two statements however many lessons. */
    @Transactional(readOnly = true)
    public Map<String, List<LessonAttachmentDto>> viewsFor(Collection<String> lessonIds) {
        Map<String, List<LessonAttachmentDto>> result = new HashMap<>();
        for (String id : lessonIds) result.put(id, new ArrayList<>());
        if (lessonIds.isEmpty()) return result;
        List<LessonAttachment> rows = attachmentRepository.findByLessonIds(lessonIds);
        Map<String, MediaAsset> assets = mediaService.getAssetsById(rows.stream().map(LessonAttachment::getMediaAssetId).toList());
        for (LessonAttachment a : rows) {
            result.computeIfAbsent(a.getLessonId(), k -> new ArrayList<>()).add(toDto(a, assets.get(a.getMediaAssetId())));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public int count(String lessonId) {
        return (int) attachmentRepository.countByLessonId(lessonId);
    }

    private static LessonAttachmentDto toDto(LessonAttachment a, MediaAsset m) {
        return new LessonAttachmentDto(a.getId(), a.getMediaAssetId(), a.getTitle(), m == null ? null : m.getOriginalFilename(),
                m == null ? null : m.getMimeType(), m == null ? 0 : m.getSizeBytes(), a.getPosition());
    }

    private Lesson lockedLesson(String lessonId, String userId) {
        Lesson lesson = lessonRepository.findByIdForUpdate(lessonId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy bài học"));
        Course course = courseRepository.findById(lesson.getCourseId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));
        accessPolicy.enforceManage(userId, course.getClassId(), "COURSE", "EDIT", course.getId());
        return lesson;
    }

    private Course courseOf(Lesson lesson) {
        return courseRepository.findById(lesson.getCourseId()).orElseThrow();
    }

    private static String cleanTitle(String title, String fallback) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) t = fallback == null ? "" : fallback.trim();
        if (t.isEmpty()) throw new AppException(ErrorCode.BAD_REQUEST, "Tên tài liệu không được để trống");
        if (t.length() > MAX_TITLE) throw new AppException(ErrorCode.BAD_REQUEST, "Tên tài liệu tối đa " + MAX_TITLE + " ký tự");
        return t;
    }

    // READ_COMMITTED for the same reason as createLesson: the "attached elsewhere" check must see a concurrent commit.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public LessonAttachmentDto add(String lessonId, String mediaAssetId, String title, String userId) {
        Lesson lesson = lockedLesson(lessonId, userId);
        Course course = courseOf(lesson);
        if (mediaAssetId == null || mediaAssetId.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Thiếu tệp tài liệu");
        }
        MediaAsset media = mediaService.getAssetForUpdate(mediaAssetId);
        if (!media.getClassId().equals(course.getClassId())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Tệp đính kèm không thuộc lớp học này");
        }
        if (!userId.equals(media.getUploaderId()) && !accessPolicy.isOwner(userId, course.getClassId())) {
            throw new AppException(ErrorCode.FORBIDDEN, "Chỉ người tải tệp lên hoặc OWNER mới được gắn tệp này vào bài học");
        }
        if (!"UPLOADED".equalsIgnoreCase(media.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Tệp chưa hoàn tất quá trình tải lên");
        }
        String mime = media.getMimeType() == null ? "" : media.getMimeType().toLowerCase(Locale.ROOT);
        if (mime.startsWith("video/") || mime.startsWith("audio/")) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Video và âm thanh được gắn ở mục Video, không phải Tài liệu");
        }
        if (mediaService.isReferencedByDocument(mediaAssetId) || mediaService.isReferencedByLesson(mediaAssetId)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Tệp đã được gắn với nội dung khác và không thể tái sử dụng");
        }
        List<LessonAttachment> existing = attachmentRepository.findByLesson(lessonId);
        if (existing.size() >= MAX_PER_LESSON) {
            throw new AppException(ErrorCode.CONFLICT, "Mỗi bài học tối đa " + MAX_PER_LESSON + " tài liệu");
        }
        int position = existing.isEmpty() ? 0 : existing.get(existing.size() - 1).getPosition() + 1;
        LessonAttachment saved = attachmentRepository.save(
                new LessonAttachment(lessonId, mediaAssetId, cleanTitle(title, media.getOriginalFilename()), position));
        lesson.recomputeType(existing.size() + 1);
        lessonRepository.save(lesson);
        auditService.record(course.getClassId(), userId, "LESSON_ATTACHMENT_ADD", "LESSON", lessonId, "{\"attachmentId\":\"" + saved.getId() + "\"}");
        return toDto(saved, media);
    }

    @Transactional
    public LessonAttachmentDto rename(String lessonId, String attachmentId, String title, String userId) {
        Lesson lesson = lockedLesson(lessonId, userId);
        LessonAttachment a = attachmentRepository.findByIdAndLessonId(attachmentId, lessonId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy tài liệu"));
        a.setTitle(cleanTitle(title, null));
        attachmentRepository.save(a);
        auditService.record(courseOf(lesson).getClassId(), userId, "LESSON_ATTACHMENT_UPDATE", "LESSON", lessonId,
                "{\"attachmentId\":\"" + a.getId() + "\"}");
        return viewsFor(List.of(lessonId)).get(lessonId).stream().filter(d -> d.id().equals(attachmentId)).findFirst().orElseThrow();
    }

    /** Detaches the document; the MinIO object and media row stay (unattached, like any abandoned upload). */
    @Transactional
    public void delete(String lessonId, String attachmentId, String userId) {
        Lesson lesson = lockedLesson(lessonId, userId);
        LessonAttachment a = attachmentRepository.findByIdAndLessonId(attachmentId, lessonId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy tài liệu"));
        attachmentRepository.delete(a);
        attachmentRepository.flush();
        lesson.recomputeType((int) attachmentRepository.countByLessonId(lessonId));
        lessonRepository.save(lesson);
        auditService.record(courseOf(lesson).getClassId(), userId, "LESSON_ATTACHMENT_REMOVE", "LESSON", lessonId,
                "{\"attachmentId\":\"" + attachmentId + "\"}");
    }

    @Transactional
    public List<LessonAttachmentDto> reorder(String lessonId, List<String> orderedIds, String userId) {
        lockedLesson(lessonId, userId);
        List<LessonAttachment> rows = attachmentRepository.findByLesson(lessonId);
        Map<String, LessonAttachment> byId = new HashMap<>();
        rows.forEach(r -> byId.put(r.getId(), r));
        if (!ReorderRequests.isPermutationOf(orderedIds, byId.keySet())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Danh sách thứ tự tài liệu không hợp lệ");
        }
        for (int i = 0; i < orderedIds.size(); i++) {
            byId.get(orderedIds.get(i)).setPosition(i);
        }
        attachmentRepository.saveAll(rows);
        return viewsFor(List.of(lessonId)).get(lessonId);
    }
}
