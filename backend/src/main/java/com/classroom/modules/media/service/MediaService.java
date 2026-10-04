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
import org.springframework.http.ContentDisposition;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    @org.springframework.beans.factory.annotation.Autowired
    private com.classroom.modules.classroom.service.ClassAboutService aboutService;

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
            throw ObjectStoreFailure.classify(e, new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Không thể khởi tạo phiên tải tệp"));
        }
    }

    /** How long a completion claim (status UPLOADING) is honoured before another request may take the asset over. */
    static final long COMPLETION_LEASE_SECONDS = 120;
    /** How long a second, concurrent completion request waits for the first one to finish before answering 409. */
    private static final long COMPLETION_WAIT_MILLIS = 15_000;
    private long completionWaitMillis = COMPLETION_WAIT_MILLIS;

    /** Test seam: shortens the wait a duplicate completion request does for the request that owns the claim. */
    void setCompletionWaitMillis(long millis) {
        this.completionWaitMillis = millis;
    }

    /** What one completion attempt needs from the asset row, copied out so MinIO I/O runs without a database connection. */
    record UploadSpec(String id, String objectKey, String uploadObjectKey, String mimeType, long sizeBytes) {}

    /** Outcome of the claim step: already finished, claimed for this request, or held by another live request. */
    record Claim(MediaAsset asset, UploadSpec spec, boolean alreadyUploaded, boolean busy) {}

    /** Outcome of the object-store phase. */
    record Promotion(boolean stagingPresent) {}

    /**
     * Confirms an upload: validates the bytes in MinIO, promotes the staging object to its final key and marks the asset
     * UPLOADED.
     *
     * <p><b>R20-06 - no database connection is held across object-store I/O.</b> This used to be one transaction: the asset row
     * was locked {@code FOR UPDATE} and a pooled connection kept while MinIO was stat'ed, copied (server-side) and re-read.
     * Fifty parallel 20 MB completions therefore pinned fifty connections and made the readiness probe (p99 6.7 s)
     * queue for one. It is now a small state machine with three short steps around the I/O:</p>
     * <ol>
     *   <li>{@link #claimCompletion}: short transaction - lock the row, re-check authorisation, and move it
     *   PENDING -&gt; UPLOADING with a lease timestamp. A concurrent second call for the same asset does not run the I/O
     *   again: it waits (no connection) for the first to finish;</li>
     *   <li>the MinIO stat / copy / re-stat / magic-byte inspection - exactly the same rules as before, no connection;</li>
     *   <li>{@link #finalizeCompletion}: short transaction - UPLOADING -&gt; UPLOADED. The staging object is removed only after
     *   that commit, as before. On any failure {@link #releaseCompletionClaim} puts the row back to PENDING so the client can
     *   retry; a crash mid-way is repaired by the lease (the next call re-claims and finds the promoted object).</li>
     * </ol>
     * Idempotency is unchanged: completing an already UPLOADED asset returns it without touching MinIO.
     */
    public MediaAsset completeUpload(String assetId, String userId) {
        MediaService steps = selfProxy != null ? selfProxy : this;

        Claim claim = steps.claimCompletion(assetId, userId);
        long waitUntil = System.currentTimeMillis() + completionWaitMillis;
        while (claim.busy()) {
            if (System.currentTimeMillis() >= waitUntil) {
                throw new AppException(ErrorCode.CONFLICT, "Tệp đang được xử lý bởi một yêu cầu khác; vui lòng thử lại sau giây lát");
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Không thể hoàn tất lưu trữ tệp đã xác thực");
            }
            claim = steps.claimCompletion(assetId, userId); // finished by the other request -> alreadyUploaded; failed -> claim it
        }
        if (claim.alreadyUploaded()) {
            return claim.asset();
        }

        Promotion promotion;
        try {
            promotion = promoteAndValidate(claim.spec());
        } catch (AppException e) {
            releaseQuietly(steps, assetId);
            throw e;
        } catch (Exception e) {
            // R20-12: an unreachable / failing object store is retryable (503 + Retry-After), not an internal error.
            if (ObjectStoreFailure.isUnavailable(e)) {
                log.warn("Object store unavailable while completing asset {}: {}", assetId, e.toString());
            } else {
                log.error("Could not promote validated upload for asset {}", assetId, e);
            }
            releaseQuietly(steps, assetId);
            throw ObjectStoreFailure.classify(e,
                    new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Không thể hoàn tất lưu trữ tệp đã xác thực"));
        }

        MediaAsset saved;
        try {
            saved = steps.finalizeCompletion(assetId);
        } catch (RuntimeException e) {
            log.error("Could not mark asset {} UPLOADED after promotion; the claim lease will let a retry finish it", assetId, e);
            throw e;
        }

        // Remove staging only after the SQL transaction actually committed (finalizeCompletion returned). A flush is not a
        // commit: deleting staging earlier would destroy the only recoverable copy if the transaction rolled back. If
        // cleanup fails, a retry observes UPLOADED and never exposes staging bytes.
        if (promotion.stagingPresent()) {
            try {
                minioClient.removeObject(RemoveObjectArgs.builder().bucket(minioProperties.getBucket())
                        .object(claim.spec().uploadObjectKey()).build());
            } catch (Exception cleanupFailure) {
                log.warn("Could not remove staging object for completed asset {}: {}", assetId, cleanupFailure.getMessage());
            }
        }
        return saved;
    }

    private void releaseQuietly(MediaService steps, String assetId) {
        try {
            steps.releaseCompletionClaim(assetId);
        } catch (RuntimeException e) {
            log.warn("Could not release the completion claim of asset {} (the lease will expire): {}", assetId, e.getMessage());
        }
    }

    /** Step 1 (short transaction): authorise, then move the asset to UPLOADING - or report it finished / busy. */
    @Transactional
    public Claim claimCompletion(String assetId, String userId) {
        MediaAsset asset = mediaAssetRepository.findByIdForUpdate(assetId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy tệp đính kèm"));

        // R2-03: completeUpload is called by clients as a plain retry-safe confirmation. Once the
        // asset is already UPLOADED there is nothing left to validate or promote, so return the
        // current row as-is (idempotent success) instead of re-running validation against live
        // MinIO reads. Re-validating here previously meant a transient MinIO read error on an
        // already-completed asset could delete the live final object out from under users who can
        // already download it.
        if ("UPLOADED".equalsIgnoreCase(asset.getStatus())) {
            return new Claim(asset, null, true, false);
        }

        // Recheck the original author's current grant, not just their identity at intent creation.
        String purpose = asset.getUploadPurpose() == null ? "MEDIA" : asset.getUploadPurpose();
        String scope = asset.getScopeCourseId();
        boolean authorized = switch (purpose) {
            case "ABOUT" -> accessPolicy.canManage(userId, asset.getClassId(), "ABOUT", "EDIT", null);
            case "CLASS_COVER", "CLASS_AVATAR" -> accessPolicy.canManage(userId, asset.getClassId(), "CLASS", "EDIT", null);
            case "BLOG" -> accessPolicy.canManage(userId, asset.getClassId(), "BLOG", "CREATE", null)
                    || accessPolicy.canManage(userId, asset.getClassId(), "BLOG", "EDIT", null)
                    || accessPolicy.canManage(userId, asset.getClassId(), "MEDIA", "CREATE", null);
            case "EVENT" -> accessPolicy.canManage(userId, asset.getClassId(), "EVENT", "CREATE", null)
                    || accessPolicy.canManage(userId, asset.getClassId(), "EVENT", "EDIT", null)
                    || accessPolicy.canManage(userId, asset.getClassId(), "MEDIA", "CREATE", null);
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

        Instant now = Instant.now();
        if ("UPLOADING".equalsIgnoreCase(asset.getStatus()) && asset.getCompletionClaimedAt() != null
                && asset.getCompletionClaimedAt().isAfter(now.minusSeconds(COMPLETION_LEASE_SECONDS))) {
            return new Claim(asset, null, false, true); // another live request is doing the I/O
        }

        asset.setStatus("UPLOADING");
        asset.setCompletionClaimedAt(now);
        mediaAssetRepository.save(asset);
        return new Claim(asset, new UploadSpec(asset.getId(), asset.getObjectKey(), asset.getUploadObjectKey(),
                asset.getMimeType(), asset.getSizeBytes()), false, false);
    }

    /** Step 3 (short transaction): the bytes are validated and promoted - publish the row. */
    @Transactional
    public MediaAsset finalizeCompletion(String assetId) {
        MediaAsset asset = mediaAssetRepository.findByIdForUpdate(assetId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy tệp đính kèm"));
        if ("UPLOADED".equalsIgnoreCase(asset.getStatus())) {
            return asset; // finished meanwhile by a request that took the asset over after our lease expired
        }
        asset.setStatus("UPLOADED");
        asset.setCompletionClaimedAt(null);
        return mediaAssetRepository.saveAndFlush(asset);
    }

    /** Failure path: give the claim back so the client can retry (the row returns to PENDING, exactly as a rollback used to leave it). */
    @Transactional
    public void releaseCompletionClaim(String assetId) {
        mediaAssetRepository.findByIdForUpdate(assetId).ifPresent(asset -> {
            if ("UPLOADING".equalsIgnoreCase(asset.getStatus())) {
                asset.setStatus("PENDING");
                asset.setCompletionClaimedAt(null);
                mediaAssetRepository.save(asset);
            }
        });
    }

    /**
     * Step 2: everything that talks to the object store. Runs with NO database connection and no lock. The rules are those of
     * the former single-transaction implementation, unchanged.
     */
    private Promotion promoteAndValidate(UploadSpec spec) throws Exception {
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
                            .object(spec.uploadObjectKey())
                            .build()
            );
        } catch (Exception stagingMissing) {
            // R20-12: only a definite "no such object" may fall through to the final key and, failing that, to "not uploaded"
            // (400). An unreachable / failing object store says nothing about the object and is a retryable 503.
            if (ObjectStoreFailure.isUnavailable(stagingMissing)) {
                throw ObjectStoreFailure.unavailable();
            }
            stagingPresent = false;
            try {
                stat = minioClient.statObject(
                        StatObjectArgs.builder()
                                .bucket(minioProperties.getBucket())
                                .object(spec.objectKey())
                                .build()
                );
                log.info("Staging object missing for asset {}; validating already-promoted final object", spec.id());
            } catch (Exception finalMissing) {
                if (ObjectStoreFailure.isUnavailable(finalMissing)) {
                    throw ObjectStoreFailure.unavailable();
                }
                log.warn("MinIO object validation failed for key {}: {}", spec.objectKey(), stagingMissing.getMessage());
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
        if (actualSize != spec.sizeBytes()) {
            log.warn("Upload size mismatch for asset {}: registered {} bytes, actual {} bytes", spec.id(), spec.sizeBytes(), actualSize);
            throw new AppException(ErrorCode.BAD_REQUEST, "Kích thước tệp thực tế không khớp với thông tin đã đăng ký");
        }

        String actualContentType = stat.contentType();
        if (actualContentType != null && !actualContentType.isBlank()) {
            String cleanType = actualContentType.split(";")[0].trim().toLowerCase();
            if (!cleanType.equalsIgnoreCase(spec.mimeType().toLowerCase())) {
                log.warn("Upload MIME type mismatch for asset {}: registered {}, actual {}", spec.id(), spec.mimeType(), cleanType);
                throw new AppException(ErrorCode.BAD_REQUEST, "Định dạng tệp thực tế không khớp với thông tin đã đăng ký");
            }
        }
        if (!ALLOWED_MIME_TYPES.contains(spec.mimeType().toLowerCase())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Loại tệp không được hỗ trợ: " + spec.mimeType());
        }

        // The object store and SQL transaction cannot commit atomically. Make promotion
        // idempotent: if a previous request promoted the object but its SQL transaction
        // rolled back, validate the already-promoted object and finish the metadata update.
        boolean promotedAlready = false;
        // R2-03: only this call's own copy created the final object. If the final object was
        // already there (a previous call promoted it, or the staging object itself was already
        // gone), a transient failure below must never delete bytes this call did not create.
        boolean createdFinalObjectThisCall = false;
        try {
            if (!stagingPresent) {
                // Already validated below against the final object; nothing left to copy.
                throw new AlreadyPromoted();
            }
            StatObjectResponse finalObject = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(minioProperties.getBucket()).object(spec.objectKey()).build());
            promotedAlready = finalObject.size() == actualSize
                    && (finalObject.contentType() == null || finalObject.contentType().isBlank()
                    || finalObject.contentType().split(";")[0].trim().equalsIgnoreCase(spec.mimeType()));
            if (!promotedAlready) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Tệp đích hiện có không khớp với nội dung đã đăng ký");
            }
        } catch (AlreadyPromoted alreadyPromoted) {
            promotedAlready = true;
        } catch (AppException e) {
            throw e;
        } catch (Exception missingFinalObject) {
            if (ObjectStoreFailure.isUnavailable(missingFinalObject)) {
                throw ObjectStoreFailure.unavailable(); // the copy below would fail the same way
            }
            minioClient.copyObject(CopyObjectArgs.builder()
                    .bucket(minioProperties.getBucket())
                    .object(spec.objectKey())
                    .source(CopySource.builder().bucket(minioProperties.getBucket()).object(spec.uploadObjectKey()).build())
                    .build());
            createdFinalObjectThisCall = true;
        }

        // R3-05: the pre-copy size check ran against the staging object; re-validate the FINAL
        // object's size right after promotion so a race that swaps staging bytes between the
        // pre-copy check and the copy itself cannot smuggle an oversized object past the limit.
        // Only this call's own freshly-created final object is removed on failure. A transient
        // failure to even read back the stat here is not itself a size violation - the very next
        // step (magic-byte validation) reads this same key and will surface any real problem -
        // so this check fails open rather than turning an unrelated transient error into a 500.
        if (createdFinalObjectThisCall) {
            try {
                StatObjectResponse copiedStat = minioClient.statObject(StatObjectArgs.builder()
                        .bucket(minioProperties.getBucket()).object(spec.objectKey()).build());
                // R4-10: the post-copy re-stat must also compare against the registered size
                // (spec.sizeBytes()), not only the absolute MAX_ALLOWED_FILE_SIZE_BYTES cap.
                // The pre-copy check above validated the staging object's size against the
                // registration; without the same comparison here, bytes swapped in at the
                // staging key between that check and the copy (still under the absolute cap,
                // but different from what was registered) would be promoted unnoticed.
                boolean exceedsAbsoluteCap = copiedStat.size() > MAX_ALLOWED_FILE_SIZE_BYTES;
                boolean mismatchesRegisteredSize = copiedStat.size() != spec.sizeBytes();
                if (exceedsAbsoluteCap || mismatchesRegisteredSize) {
                    try {
                        minioClient.removeObject(RemoveObjectArgs.builder()
                                .bucket(minioProperties.getBucket()).object(spec.objectKey()).build());
                    } catch (Exception cleanupFailure) {
                        log.warn("Could not remove size-mismatched final object for asset {} after post-copy size check: {}",
                                spec.id(), cleanupFailure.getMessage());
                    }
                    if (exceedsAbsoluteCap) {
                        throw new AppException(ErrorCode.BAD_REQUEST, "Kích thước tệp thực tế vượt quá giới hạn tối đa cho phép (500MB)");
                    }
                    log.warn("Post-copy size mismatch for asset {}: registered {} bytes, copied {} bytes",
                            spec.id(), spec.sizeBytes(), copiedStat.size());
                    throw new AppException(ErrorCode.BAD_REQUEST, "Kích thước tệp thực tế không khớp với thông tin đã đăng ký");
                }
            } catch (AppException sizeViolation) {
                throw sizeViolation;
            } catch (Exception statFailure) {
                log.warn("Could not re-stat final object for asset {} after copy to verify size: {}",
                        spec.id(), statFailure.getMessage());
            }
        }

        // Validate content structure and magic bytes against the FINAL object, not staging
        // (Finding 6, hardened against TOCTOU): the presigned PUT URL for the staging key
        // remains valid until staging is deleted, so bytes validated at the staging key could
        // otherwise be swapped out by a fresh PUT between validation and promotion. Validating
        // only after the object is copied to its immutable final key closes that window; any
        // final object that FAILS INSPECTION (not a transient read/IO error) is removed, and
        // only when this same call is the one that created it by copy - a read/IO error, or a
        // failure against an object promoted by an earlier call, must never delete a live
        // final object other requests may already be downloading (R2-03).
        try {
            validateMagicBytes(spec.objectKey(), spec.mimeType());
        } catch (AppException validationFailure) {
            boolean genuineContentMismatch = validationFailure.getErrorCode() == ErrorCode.BAD_REQUEST;
            if (createdFinalObjectThisCall && genuineContentMismatch) {
                try {
                    minioClient.removeObject(RemoveObjectArgs.builder()
                            .bucket(minioProperties.getBucket()).object(spec.objectKey()).build());
                } catch (Exception cleanupFailure) {
                    log.warn("Could not remove invalid final object for asset {} after failed validation: {}",
                            spec.id(), cleanupFailure.getMessage());
                }
            }
            throw validationFailure;
        }
        return new Promotion(stagingPresent);
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
                    || accessPolicy.canManage(userId, asset.getClassId(), "COURSE", "CREATE", null)
                    // R7-06: a course-scoped LESSON upload (asset.scopeCourseId set at intent time,
                    // see createUploadIntent) is authored under COURSE:EDIT/CREATE scoped to that
                    // course, not just the class-wide CREATE checked above — a scoped-only staff
                    // member uploading their own lesson media must still be able to download their
                    // own not-yet-attached draft.
                    // D-27: the author of a not-yet-attached cover image may preview it under the grant it was uploaded with.
                    || (("CLASS_COVER".equals(asset.getUploadPurpose()) || "CLASS_AVATAR".equals(asset.getUploadPurpose()))
                        && accessPolicy.canManage(userId, asset.getClassId(), "CLASS", "EDIT", null))
                    || ("BLOG".equals(asset.getUploadPurpose())
                        && (accessPolicy.canManage(userId, asset.getClassId(), "BLOG", "CREATE", null)
                            || accessPolicy.canManage(userId, asset.getClassId(), "BLOG", "EDIT", null)))
                    || ("EVENT".equals(asset.getUploadPurpose())
                        && (accessPolicy.canManage(userId, asset.getClassId(), "EVENT", "CREATE", null)
                            || accessPolicy.canManage(userId, asset.getClassId(), "EVENT", "EDIT", null)))
                    || ("LESSON".equalsIgnoreCase(asset.getUploadPurpose()) && asset.getScopeCourseId() != null
                        && (accessPolicy.canManage(userId, asset.getClassId(), "COURSE", "EDIT", asset.getScopeCourseId())
                            || accessPolicy.canManage(userId, asset.getClassId(), "COURSE", "CREATE", asset.getScopeCourseId())));

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
                // R14-02: media of an archived lesson (or a lesson in an archived section) is not
                // learner-visible either; only course editors keep it (isLessonHiddenFromLearner
                // exempts COURSE:EDIT), so a stale link cannot pull the bytes after archiving.
                if (learningPolicy.isLessonHiddenFromLearner(lesson, course, userId)) {
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

    /** R8-01: presigned GET URLs are short-lived, so a capability leaked in logs/history is only ever usable briefly. */
    public static final long PRESIGNED_DOWNLOAD_TTL_MINUTES = 10;

    private DownloadUrlResponse signPresignedDownloadUrl(MediaAsset asset) {
        if (!"UPLOADED".equalsIgnoreCase(asset.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Tệp chưa hoàn tất quá trình tải lên");
        }

        // R8-01: the caller's access has just been re-checked above (this method is only reached
        // from the authorization branches in generateAuthorizedDownloadUrl). Issuing a short-lived
        // presigned MinIO GET URL lets the browser stream/seek the object directly from the object
        // store (Range support included) instead of relaying every byte through this backend's
        // 30s-capped async request, which silently truncated large files (R8-01). The TTL bounds
        // how long a leaked URL remains usable; the legacy /media/{id}/download route is kept for
        // any remaining consumer and now supports Range itself (see MediaController#download).
        try {
            // R9-08: built via ContentDisposition so a non-ASCII (e.g. Vietnamese) original filename
            // is carried correctly as filename*=UTF-8''... (RFC 5987/6266) alongside an ASCII-safe
            // filename="..." fallback, the same as MediaController#download's proxy route.
            String contentDisposition = ContentDisposition.attachment()
                    .filename(sanitizeFilename(asset.getOriginalFilename()), StandardCharsets.UTF_8)
                    .build()
                    .toString();
            Instant expiresAt = Instant.now().plus(PRESIGNED_DOWNLOAD_TTL_MINUTES, ChronoUnit.MINUTES);
            String presignedUrl = presigningClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(minioProperties.getBucket())
                            .object(asset.getObjectKey())
                            .expiry((int) PRESIGNED_DOWNLOAD_TTL_MINUTES, TimeUnit.MINUTES)
                            .extraQueryParams(Map.of(
                                    "response-content-disposition", contentDisposition,
                                    "response-content-type", asset.getMimeType()
                            ))
                            .build()
            );
            return new DownloadUrlResponse(asset.getId(), presignedUrl, expiresAt);
        } catch (Exception e) {
            log.error("Failed to generate download presigned URL for asset {}", asset.getId(), e);
            throw ObjectStoreFailure.classify(e, new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Không thể khởi tạo liên kết tải xuống"));
        }
    }

    /** D-27: image uploads (About sections, class cover, blog / event covers): these types only, at most 5 MB. */
    public static final java.util.Set<String> IMAGE_MIME_TYPES = java.util.Set.of("image/jpeg", "image/png", "image/webp", "image/gif");
    public static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    /**
     * D-27: the media id an entity (class cover, blog post, event) may point at - an UPLOADED image of the SAME class uploaded for
     * {@code purpose}. Anything else is a 400, so a cover can never borrow a file of another class or a protected document.
     */
    @Transactional(readOnly = true)
    public String requireAttachableImage(String assetId, String classId, String purpose) {
        MediaAsset asset = mediaAssetRepository.findById(assetId.trim())
                .orElseThrow(() -> new AppException(ErrorCode.BAD_REQUEST, "Không tìm thấy ảnh bìa"));
        if (!isUsableImage(asset, purpose) || !asset.getClassId().equals(classId)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Ảnh bìa phải là ảnh đã tải xong của lớp học này");
        }
        return asset.getId();
    }

    private static boolean isUsableImage(MediaAsset asset, String purpose) {
        return asset != null && "UPLOADED".equalsIgnoreCase(asset.getStatus())
                && purpose.equals(asset.getUploadPurpose())
                && asset.getMimeType() != null && IMAGE_MIME_TYPES.contains(asset.getMimeType().toLowerCase(Locale.ROOT));
    }

    /**
     * D-27: short-lived presigned GET URLs (same TTL as every other download, R8-01) for a batch of cover images, keyed by asset id - ONE
     * query for a whole listing; signing is local (the presigning client has a fixed region), so no network call per row. Only call this
     * AFTER the caller has passed the class-visibility check of the entity that references the images. Assets that are missing, not
     * uploaded, not images or of another purpose are simply absent from the map (the UI then falls back to its tile).
     */
    @Transactional(readOnly = true)
    public Map<String, String> presignedImageUrls(java.util.Collection<String> assetIds, String purpose) {
        Map<String, String> urls = new HashMap<>();
        if (assetIds == null) return urls;
        List<String> ids = assetIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return urls;
        for (MediaAsset asset : mediaAssetRepository.findAllById(ids)) {
            if (!isUsableImage(asset, purpose)) continue;
            String url = signInlineImage(asset);
            if (url != null) urls.put(asset.getId(), url);
        }
        return urls;
    }

    /** D-27: single-entity form of {@link #presignedImageUrls}. */
    @Transactional(readOnly = true)
    public String presignedImageUrl(String assetId, String purpose) {
        if (assetId == null || assetId.isBlank()) return null;
        return presignedImageUrls(List.of(assetId), purpose).get(assetId);
    }

    private String signInlineImage(MediaAsset asset) {
        try {
            return presigningClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(minioProperties.getBucket())
                            .object(asset.getObjectKey())
                            .expiry((int) PRESIGNED_DOWNLOAD_TTL_MINUTES, TimeUnit.MINUTES)
                            .extraQueryParams(Map.of("response-content-type", asset.getMimeType()))
                            .build());
        } catch (Exception e) {
            // A cover is decoration: a signing failure must not fail the page that shows it.
            log.warn("Could not sign the image URL of asset {}: {}", asset.getId(), e.toString());
            return null;
        }
    }

    @Transactional(readOnly = true)
    public DownloadUrlResponse generateAboutImageUrl(String classId, String assetId, String viewerId) {
        var about = aboutService.getAbout(classId, viewerId);
        boolean referenced = aboutService.toDto(about).sections().stream().anyMatch(s -> assetId.equals(s.mediaAssetId()));
        if (!referenced) throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy ảnh giới thiệu");
        var asset = mediaAssetRepository.findById(assetId).filter(a -> classId.equals(a.getClassId())
                && "ABOUT".equals(a.getUploadPurpose()) && a.getMimeType().startsWith("image/"))
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy ảnh giới thiệu"));
        return signPresignedDownloadUrl(asset);
    }

    @Transactional(readOnly = true)
    public io.minio.GetObjectResponse openAuthorizedDownload(String assetId, String userId) {
        return openAuthorizedDownload(assetId, userId, null, null);
    }

    /**
     * R8-01: the legacy proxy download route stays available for any remaining consumer, but must
     * no longer silently truncate large files. Accepting an optional byte range lets the controller
     * answer HTTP Range requests (206 Partial Content) the same way a presigned MinIO URL would,
     * instead of always streaming the whole object.
     */
    @Transactional(readOnly = true)
    public io.minio.GetObjectResponse openAuthorizedDownload(String assetId, String userId, Long offset, Long length) {
        generateAuthorizedDownloadUrl(assetId, userId); // authorize against current membership and entitlement
        MediaAsset asset = mediaAssetRepository.findById(assetId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy tệp đính kèm"));
        try {
            GetObjectArgs.Builder builder = GetObjectArgs.builder()
                    .bucket(minioProperties.getBucket()).object(asset.getObjectKey());
            if (offset != null) builder.offset(offset);
            if (length != null) builder.length(length);
            return minioClient.getObject(builder.build());
        } catch (Exception e) {
            if (ObjectStoreFailure.isUnavailable(e)) {
                log.warn("Object store unavailable while opening media object {}: {}", assetId, e.toString());
            } else {
                log.error("Failed to open authorized media object {}", assetId, e);
            }
            throw ObjectStoreFailure.classify(e, new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Không thể tải tệp"));
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
        // R20-06: an upload whose completion request died mid-way stays UPLOADING; once it is older than the cutoff it is as abandoned
        // as a PENDING one (the lease is minutes, the cutoff hours).
        java.util.List<MediaAsset> candidates = new java.util.ArrayList<>(mediaAssetRepository.findByStatusAndCreatedAtBefore("PENDING", cutoff));
        candidates.addAll(mediaAssetRepository.findByStatusAndCreatedAtBefore("UPLOADING", cutoff));
        for (MediaAsset stale : candidates) {
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
        if (stale == null || !stale.getCreatedAt().isBefore(cutoff)) return;
        boolean abandonedPending = "PENDING".equals(stale.getStatus());
        boolean abandonedClaim = "UPLOADING".equals(stale.getStatus())
                && (stale.getCompletionClaimedAt() == null || stale.getCompletionClaimedAt().isBefore(cutoff));
        if (!abandonedPending && !abandonedClaim) return;
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
            throw ObjectStoreFailure.classify(e, new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Không thể kiểm tra nội dung tệp"));
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
