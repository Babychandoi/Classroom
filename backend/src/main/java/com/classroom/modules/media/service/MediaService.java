package com.classroom.modules.media.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.config.MinioProperties;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.commerce.model.Entitlement;
import com.classroom.modules.commerce.policy.ProPolicy;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import com.classroom.modules.community.model.DocumentAsset;
import com.classroom.modules.community.repository.DocumentAssetRepository;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.model.Lesson;
import com.classroom.modules.learning.policy.LearningPolicy;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.learning.repository.LessonRepository;
import com.classroom.modules.media.dto.DownloadUrlResponse;
import com.classroom.modules.media.dto.UploadIntentRequest;
import com.classroom.modules.media.dto.UploadIntentResponse;
import com.classroom.modules.media.model.MediaAsset;
import com.classroom.modules.media.repository.MediaAssetRepository;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.GetObjectArgs;
import io.minio.CopyObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.CopySource;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class MediaService {
    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    private final MinioClient minioClient;
    private final MinioClient presigningClient;
    private final MinioProperties minioProperties;
    private final MediaAssetRepository mediaAssetRepository;
    private final AccessPolicy accessPolicy;
    private final LessonRepository lessonRepository;
    private final CourseRepository courseRepository;
    private final LearningPolicy learningPolicy;
    private final DocumentAssetRepository documentAssetRepository;
    private final ProPolicy proPolicy;
    private final EntitlementRepository entitlementRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @Lazy
    private MediaService selfProxy;

    public static final long MAX_ALLOWED_FILE_SIZE_BYTES = 500L * 1024 * 1024; // 500 MB

    public static final java.util.Set<String> ALLOWED_MIME_TYPES = java.util.Set.of(
            "application/pdf",
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif",
            "video/mp4",
            "video/webm",
            "video/quicktime",
            "audio/mpeg",
            "audio/wav",
            "text/plain",
            "application/zip",
            "application/x-zip-compressed",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.ms-powerpoint"
    );

    public MediaService(MinioClient minioClient,
                        @Qualifier("minioPresigningClient") MinioClient presigningClient,
                        MinioProperties minioProperties,
                        MediaAssetRepository mediaAssetRepository,
                        @Lazy AccessPolicy accessPolicy,
                        @Lazy LessonRepository lessonRepository,
                        @Lazy CourseRepository courseRepository,
                        @Lazy LearningPolicy learningPolicy,
                        @Lazy DocumentAssetRepository documentAssetRepository,
                        @Lazy ProPolicy proPolicy,
                        @Lazy EntitlementRepository entitlementRepository) {
        this.minioClient = minioClient;
        this.presigningClient = presigningClient;
        this.minioProperties = minioProperties;
        this.mediaAssetRepository = mediaAssetRepository;
        this.accessPolicy = accessPolicy;
        this.lessonRepository = lessonRepository;
        this.courseRepository = courseRepository;
        this.learningPolicy = learningPolicy;
        this.documentAssetRepository = documentAssetRepository;
        this.proPolicy = proPolicy;
        this.entitlementRepository = entitlementRepository;
    }

    @Transactional
    public UploadIntentResponse createUploadIntent(String classId, String uploaderId, UploadIntentRequest request) {
        if (request.getSizeBytes() <= 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Kích thước tệp phải lớn hơn 0");
        }
        if (request.getSizeBytes() > MAX_ALLOWED_FILE_SIZE_BYTES) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Kích thước tệp vượt quá giới hạn tối đa cho phép (500MB)");
        }
        String mime = request.getMimeType().trim().toLowerCase();
        if (!ALLOWED_MIME_TYPES.contains(mime)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Loại tệp không được hỗ trợ: " + request.getMimeType());
        }

        String ext = "";
        int dotIdx = request.getFilename().lastIndexOf('.');
        if (dotIdx >= 0) {
            ext = request.getFilename().substring(dotIdx);
        }
        String objectKey = String.format("classes/%s/media/%s%s", classId, UUID.randomUUID(), ext);

        MediaAsset asset = new MediaAsset(
                classId,
                uploaderId,
                objectKey,
                request.getFilename(),
                mime,
                request.getSizeBytes()
        );
        asset.setUploadPurpose(request.getPurpose() == null ? "MEDIA" : request.getPurpose().trim().toUpperCase(java.util.Locale.ROOT));
        asset.setScopeCourseId(request.getScopeCourseId() == null || request.getScopeCourseId().isBlank() ? null : request.getScopeCourseId().trim());
        MediaAsset saved = mediaAssetRepository.save(asset);

        try {
            String presignedUrl = presigningClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT)
                            .bucket(minioProperties.getBucket())
                            .object(saved.getUploadObjectKey())
                            .expiry(30, TimeUnit.MINUTES)
                            .build()
            );

            return new UploadIntentResponse(saved.getId(), presignedUrl, objectKey, "PUT");
        } catch (Exception e) {
            log.error("Failed to generate upload presigned URL", e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Không thể khởi tạo phiên tải tệp");
        }
    }

    @Transactional
    public MediaAsset completeUpload(String assetId, String userId) {
        MediaAsset asset = mediaAssetRepository.findByIdForUpdate(assetId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy tệp đính kèm"));

        // Recheck the original author's current grant, not just their identity at intent creation.
        String purpose = asset.getUploadPurpose() == null ? "MEDIA" : asset.getUploadPurpose();
        String scope = asset.getScopeCourseId();
        boolean authorized = switch (purpose) {
            case "DOCUMENT" -> accessPolicy.canManage(userId, asset.getClassId(), "DOCUMENT", "CREATE", null)
                    || accessPolicy.canManage(userId, asset.getClassId(), "MEDIA", "CREATE", null);
            case "LESSON", "COURSE" -> accessPolicy.canManage(userId, asset.getClassId(), "COURSE", "EDIT", scope)
                    || accessPolicy.canManage(userId, asset.getClassId(), "COURSE", "CREATE", scope)
                    || accessPolicy.canManage(userId, asset.getClassId(), "MEDIA", "CREATE", null);
            case "FEED", "POST" -> accessPolicy.canManage(userId, asset.getClassId(), "FEED", "CREATE", null)
                    || accessPolicy.canManage(userId, asset.getClassId(), "MEDIA", "CREATE", null);
            case "STORE", "PRODUCT" -> accessPolicy.canManage(userId, asset.getClassId(), "STORE", "CREATE", null)
                    || accessPolicy.canManage(userId, asset.getClassId(), "MEDIA", "CREATE", null);
            default -> accessPolicy.canManage(userId, asset.getClassId(), "MEDIA", "CREATE", null);
        };
        if (!asset.getUploaderId().equals(userId) || !authorized) {
            throw new AppException(ErrorCode.FORBIDDEN, "Bạn không có quyền xác nhận tải lên tệp này");
        }

        // Validate that the bytes actually exist in MinIO before marking complete. A previous
        // attempt may have promoted the object and removed staging before its SQL transaction
        // rolled back, so fall back to validating the already-promoted final object rather than
        // rejecting a recoverable retry.
        StatObjectResponse stat;
        boolean stagingPresent = true;
        try {
            stat = minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(minioProperties.getBucket())
                             .object(asset.getUploadObjectKey())
                            .build()
            );
        } catch (Exception stagingMissing) {
            stagingPresent = false;
            try {
                stat = minioClient.statObject(
                        StatObjectArgs.builder()
                                .bucket(minioProperties.getBucket())
                                .object(asset.getObjectKey())
                                .build()
                );
                log.info("Staging object missing for asset {}; validating already-promoted final object", asset.getId());
            } catch (Exception finalMissing) {
                log.warn("MinIO object validation failed for key {}: {}", asset.getObjectKey(), stagingMissing.getMessage());
                throw new AppException(ErrorCode.BAD_REQUEST, "Tệp chưa được tải lên máy chủ lưu trữ (MinIO)");
            }
        }

        // Validate actual object metadata from MinIO against allowed constraints (Finding 7)
        long actualSize = stat.size();
        if (actualSize <= 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Tệp đã tải lên rỗng (0 bytes)");
        }
        if (actualSize > MAX_ALLOWED_FILE_SIZE_BYTES) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Kích thước tệp thực tế vượt quá giới hạn tối đa cho phép (500MB)");
        }
        if (actualSize != asset.getSizeBytes()) {
            log.warn("Upload size mismatch for asset {}: registered {} bytes, actual {} bytes", asset.getId(), asset.getSizeBytes(), actualSize);
            throw new AppException(ErrorCode.BAD_REQUEST, "Kích thước tệp thực tế không khớp với thông tin đã đăng ký");
        }

        String actualContentType = stat.contentType();
        if (actualContentType != null && !actualContentType.isBlank()) {
            String cleanType = actualContentType.split(";")[0].trim().toLowerCase();
            if (!cleanType.equalsIgnoreCase(asset.getMimeType().toLowerCase())) {
                log.warn("Upload MIME type mismatch for asset {}: registered {}, actual {}", asset.getId(), asset.getMimeType(), cleanType);
                throw new AppException(ErrorCode.BAD_REQUEST, "Định dạng tệp thực tế không khớp với thông tin đã đăng ký");
            }
        }
        if (!ALLOWED_MIME_TYPES.contains(asset.getMimeType().toLowerCase())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Loại tệp không được hỗ trợ: " + asset.getMimeType());
        }

        // Validate content structure and magic bytes to prevent attacker-controlled MIME spoofing (Finding 6)
        validateMagicBytes(stagingPresent ? asset.getUploadObjectKey() : asset.getObjectKey(), asset.getMimeType());

        try {
            // The object store and SQL transaction cannot commit atomically. Make promotion
            // idempotent: if a previous request promoted the object but its SQL transaction
            // rolled back, validate the already-promoted object and finish the metadata update.
            boolean promotedAlready = false;
            try {
                if (!stagingPresent) {
                    // Already validated above against the final object; nothing left to copy.
                    throw new AlreadyPromoted();
                }
                StatObjectResponse finalObject = minioClient.statObject(StatObjectArgs.builder()
                        .bucket(minioProperties.getBucket()).object(asset.getObjectKey()).build());
                promotedAlready = finalObject.size() == actualSize
                        && (finalObject.contentType() == null || finalObject.contentType().isBlank()
                        || finalObject.contentType().split(";")[0].trim().equalsIgnoreCase(asset.getMimeType()));
                if (!promotedAlready) {
                    throw new AppException(ErrorCode.BAD_REQUEST, "Tệp đích hiện có không khớp với nội dung đã đăng ký");
                }
            } catch (AlreadyPromoted alreadyPromoted) {
                promotedAlready = true;
            } catch (AppException e) {
                throw e;
            } catch (Exception missingFinalObject) {
                minioClient.copyObject(CopyObjectArgs.builder()
                        .bucket(minioProperties.getBucket())
                        .object(asset.getObjectKey())
                        .source(CopySource.builder().bucket(minioProperties.getBucket()).object(asset.getUploadObjectKey()).build())
                        .build());
            }
            asset.setStatus("UPLOADED");
            MediaAsset saved = mediaAssetRepository.saveAndFlush(asset);
            // Remove staging only after the SQL transaction actually commits. A flush is not a
            // commit: deleting staging inline would destroy the only recoverable copy if the
            // transaction later rolled back, leaving the row PENDING and completion impossible.
            // If cleanup fails, a retry observes UPLOADED and never exposes staging bytes.
            if (stagingPresent) {
                final String stagingKey = asset.getUploadObjectKey();
                final String cleanupAssetId = asset.getId();
                Runnable cleanup = () -> {
                    try {
                        minioClient.removeObject(RemoveObjectArgs.builder().bucket(minioProperties.getBucket())
                                .object(stagingKey).build());
                    } catch (Exception cleanupFailure) {
                        log.warn("Could not remove staging object for completed asset {}: {}", cleanupAssetId, cleanupFailure.getMessage());
                    }
                };
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            cleanup.run();
                        }
                    });
                } else {
                    cleanup.run();
                }
            }
            return saved;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.error("Could not promote validated upload for asset {}", asset.getId(), e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Không thể hoàn tất lưu trữ tệp đã xác thực");
        }
    }

    @Transactional(readOnly = true)
    public DownloadUrlResponse generateAuthorizedDownloadUrl(String assetId, String userId) {
        MediaAsset asset = mediaAssetRepository.findById(assetId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy tệp đính kèm"));

        if (!"UPLOADED".equalsIgnoreCase(asset.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Tệp chưa hoàn tất quá trình tải lên");
        }

        accessPolicy.enforceMember(userId, asset.getClassId());

        boolean isOwner = accessPolicy.isOwner(userId, asset.getClassId());

        // Check if referenced by DocumentAsset
        List<DocumentAsset> docs = documentAssetRepository.findByMediaAssetId(assetId);
        // Check if referenced by Lesson
        List<Lesson> lessons = lessonRepository.findByMediaAssetId(assetId);

        // Fail closed for pre-existing or legacy shared references: authorization by
        // any one reference must not grant bytes protected by another reference.
        if (!docs.isEmpty() && !lessons.isEmpty() || docs.size() > 1 || lessons.size() > 1) {
            throw new AppException(ErrorCode.FORBIDDEN, "Tệp được gắn với nhiều nội dung; không thể cấp quyền tải không mơ hồ");
        }

        // If not referenced by any document or lesson (draft asset), only class owner or exact uploader with active authoring rights can download (Finding 2)
        if (docs.isEmpty() && lessons.isEmpty()) {
            boolean isUploader = asset.getUploaderId().equals(userId);
            boolean hasAuthoringRights = isOwner
                    || accessPolicy.canManage(userId, asset.getClassId(), "MEDIA", "CREATE", null)
                    || accessPolicy.canManage(userId, asset.getClassId(), "DOCUMENT", "CREATE", null)
                    || accessPolicy.canManage(userId, asset.getClassId(), "COURSE", "CREATE", null);

            if (isOwner || (isUploader && hasAuthoringRights)) {
                return signPresignedDownloadUrl(asset);
            }
            throw new AppException(ErrorCode.FORBIDDEN, "Bạn không có quyền tải xuống tệp nháp của người dùng khác");
        }

        // If referenced by documents or lessons:
        if (isOwner) {
            return signPresignedDownloadUrl(asset);
        }

        for (DocumentAsset doc : docs) {
            if (!doc.getClassId().equals(asset.getClassId())) continue;
            if (accessPolicy.canManage(userId, doc.getClassId(), "DOCUMENT", "VIEW", null)) {
                return signPresignedDownloadUrl(asset);
            }
            if ("FREE".equalsIgnoreCase(doc.getVisibility())) {
                return signPresignedDownloadUrl(asset);
            }
            if ("PRO".equalsIgnoreCase(doc.getVisibility()) && proPolicy.isPro(userId, doc.getClassId())) {
                return signPresignedDownloadUrl(asset);
            }
            if ("PRODUCT_OWNER".equalsIgnoreCase(doc.getVisibility())) {
                Instant now = Instant.now();
                if (doc.getTargetProductId() != null && !doc.getTargetProductId().isBlank()) {
                    if (entitlementRepository.hasProductAccess(userId, doc.getClassId(), doc.getTargetProductId(), now)) {
                        return signPresignedDownloadUrl(asset);
                    }
                } else if (doc.getTargetCourseId() != null && !doc.getTargetCourseId().isBlank()) {
                    Optional<Course> targetCourse = courseRepository.findById(doc.getTargetCourseId());
                    if (targetCourse.filter(course -> asset.getClassId().equals(course.getClassId())
                                    && course.getProductId() != null && !course.getProductId().isBlank())
                            .map(course -> entitlementRepository.hasCourseAccess(userId, doc.getClassId(), course.getId(), course.getProductId(), now))
                            .orElse(false)) {
                        return signPresignedDownloadUrl(asset);
                    }
                }
            }
        }

        for (Lesson lesson : lessons) {
            Optional<Course> courseOpt = courseRepository.findById(lesson.getCourseId());
            if (courseOpt.isPresent()) {
                Course course = courseOpt.get();
                if (!course.getClassId().equals(asset.getClassId())) {
                    continue;
                }
                if (accessPolicy.canManage(userId, course.getClassId(), "COURSE", "PREVIEW", course.getId())) {
                    return signPresignedDownloadUrl(asset);
                }
                if (learningPolicy.canLearn(userId, course)) {
                    return signPresignedDownloadUrl(asset);
                }
            }
        }

        throw new AppException(ErrorCode.FORBIDDEN, "Bạn không có quyền tải xuống tệp media này");
    }

    @Transactional
    public MediaAsset getAssetForUpdate(String assetId) {
        return mediaAssetRepository.findByIdForUpdate(assetId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy tệp đính kèm"));
    }

    @Deprecated
    public DownloadUrlResponse generateDownloadUrl(String assetId) {
        throw new UnsupportedOperationException("Unauthenticated media download is prohibited. Use generateAuthorizedDownloadUrl instead.");
    }

    private DownloadUrlResponse signPresignedDownloadUrl(MediaAsset asset) {
        if (!"UPLOADED".equalsIgnoreCase(asset.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Tệp chưa hoàn tất quá trình tải lên");
        }

        // Never expose a reusable object-store capability. The first-party download route
        // re-runs this resource's access policy immediately before streaming the bytes.
        return new DownloadUrlResponse(asset.getId(), "/api/v1/media/" + asset.getId() + "/download", null);
    }

    @Transactional(readOnly = true)
    public io.minio.GetObjectResponse openAuthorizedDownload(String assetId, String userId) {
        generateAuthorizedDownloadUrl(assetId, userId); // authorize against current membership and entitlement
        MediaAsset asset = mediaAssetRepository.findById(assetId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy tệp đính kèm"));
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(minioProperties.getBucket()).object(asset.getObjectKey()).build());
        } catch (Exception e) {
            log.error("Failed to open authorized media object {}", assetId, e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Không thể tải tệp");
        }
    }

    @Transactional(readOnly = true)
    public MediaAsset getAsset(String assetId) {
        return mediaAssetRepository.findById(assetId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy tệp đính kèm"));
    }

    @Transactional(readOnly = true)
    public boolean isReferencedByLesson(String assetId) {
        return !lessonRepository.findByMediaAssetId(assetId).isEmpty();
    }

    @Transactional(readOnly = true)
    public boolean isReferencedByDocument(String assetId) {
        return !documentAssetRepository.findByMediaAssetId(assetId).isEmpty();
    }

    /** Removes expired pending metadata and staging objects; successful uploads are never touched. */
    @Scheduled(fixedDelayString = "${classroom.media.orphan-cleanup-delay-ms:3600000}")
    public void cleanupAbandonedUploads() {
        Instant cutoff = Instant.now().minus(2, ChronoUnit.HOURS);
        for (MediaAsset stale : mediaAssetRepository.findByStatusAndCreatedAtBefore("PENDING", cutoff)) {
            try {
                (selfProxy != null ? selfProxy : this).cleanupOneAbandonedUpload(stale.getId(), cutoff);
            } catch (Exception e) {
                log.warn("Could not clean abandoned media upload {}: {}", stale.getId(), e.getMessage());
            }
        }
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void cleanupOneAbandonedUpload(String assetId, Instant cutoff) {
        // Must be invoked through a transactional proxy; see cleanupAbandonedUploads.
        MediaAsset stale = mediaAssetRepository.findByIdForUpdate(assetId).orElse(null);
        if (stale == null || !"PENDING".equals(stale.getStatus())
                || !stale.getCreatedAt().isBefore(cutoff)) return;
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(minioProperties.getBucket()).object(stale.getUploadObjectKey()).build());
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(minioProperties.getBucket()).object(stale.getObjectKey()).build());
            mediaAssetRepository.delete(stale);
        } catch (Exception e) {
            throw new IllegalStateException("Could not remove abandoned media asset " + assetId, e);
        }
    }

    private void validateMagicBytes(String objectKey, String mimeType) {
        try (InputStream is = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(minioProperties.getBucket())
                        .object(objectKey)
                        .offset(0L)
                        .length(512L)
                        .build())) {
            if (is == null) throw new AppException(ErrorCode.BAD_REQUEST, "Không thể kiểm tra nội dung tệp");
            byte[] header = is.readNBytes(512);
            if (header.length == 0 || !isMagicBytesMatching(header, mimeType)) {
                log.warn("Magic bytes inspection failed for object {} with declared MIME {}", objectKey, mimeType);
                throw new AppException(ErrorCode.BAD_REQUEST, "Nội dung tệp thực tế không khớp với định dạng " + mimeType);
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Magic bytes inspection could not inspect object {}: {}", objectKey, e.getMessage());
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Không thể kiểm tra nội dung tệp");
        }
    }

    private boolean isMagicBytesMatching(byte[] header, String mimeType) {
        if (header == null || header.length < 4) return true;
        String mime = mimeType.toLowerCase(Locale.ROOT);

        // Disallow dangerous active script/executable headers regardless of declared mime
        if (header.length >= 2 && header[0] == 0x4D && header[1] == 0x5A) { // MZ executable
            return false;
        }
        if (header.length >= 4 && header[0] == 0x7F && header[1] == 0x45 && header[2] == 0x4C && header[3] == 0x46) { // ELF
            return false;
        }
        String textPrefix = new String(header, 0, Math.min(header.length, 64), StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT).trim();
        if (textPrefix.startsWith("<!doctype html") || textPrefix.startsWith("<html") || textPrefix.startsWith("<script") || textPrefix.startsWith("<?php")) {
            return false;
        }

        switch (mime) {
            case "application/pdf":
                return header.length >= 5 && header[0] == 0x25 && header[1] == 0x50 && header[2] == 0x44 && header[3] == 0x46 && header[4] == 0x2D;
            case "image/jpeg":
                return header.length >= 3 && (header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8 && (header[2] & 0xFF) == 0xFF;
            case "image/png":
                return header.length >= 8 && (header[0] & 0xFF) == 0x89 && header[1] == 0x50 && header[2] == 0x4E && header[3] == 0x47
                        && header[4] == 0x0D && header[5] == 0x0A && header[6] == 0x1A && header[7] == 0x0A;
            case "image/gif":
                return header.length >= 6 && header[0] == 0x47 && header[1] == 0x49 && header[2] == 0x46 && header[3] == 0x38;
            case "image/webp":
                if (header.length >= 12 && header[0] == 0x52 && header[1] == 0x49 && header[2] == 0x46 && header[3] == 0x46) {
                    return header[8] == 0x57 && header[9] == 0x45 && header[10] == 0x42 && header[11] == 0x50;
                }
                return false;
            case "application/zip":
            case "application/x-zip-compressed":
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document":
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation":
                return header.length >= 4 && header[0] == 0x50 && header[1] == 0x4B
                        && (header[2] == 0x03 || header[2] == 0x05 || header[2] == 0x07);
            case "video/mp4":
            case "video/quicktime":
                if (header.length >= 8) {
                    return header[4] == 0x66 && header[5] == 0x74 && header[6] == 0x79 && header[7] == 0x70;
                }
                return true;
            case "video/webm":
                return header.length >= 4 && (header[0] & 0xFF) == 0x1A && (header[1] & 0xFF) == 0x45 && (header[2] & 0xFF) == 0xDF && (header[3] & 0xFF) == 0xA3;
            case "audio/mpeg":
                if (header.length >= 3 && header[0] == 0x49 && header[1] == 0x44 && header[2] == 0x33) return true;
                return header.length >= 2 && (header[0] & 0xFF) == 0xFF && ((header[1] & 0xE0) == 0xE0);
            case "audio/wav":
                if (header.length >= 12 && header[0] == 0x52 && header[1] == 0x49 && header[2] == 0x46 && header[3] == 0x46) {
                    return header[8] == 0x57 && header[9] == 0x41 && header[10] == 0x56 && header[11] == 0x45;
                }
                return false;
            case "text/plain":
                return true;
            default:
                return true;
        }
    }

    private static String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) return "downloaded_file";
        return filename.replaceAll("[\\r\\n\"\\\\]", "_");
    }

    /** Internal control-flow marker: the final object was already validated, skip the copy step. */
    private static final class AlreadyPromoted extends RuntimeException {
        private AlreadyPromoted() {
            super(null, null, false, false);
        }
    }
}
