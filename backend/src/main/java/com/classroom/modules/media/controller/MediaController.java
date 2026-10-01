package com.classroom.modules.media.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.media.dto.DownloadUrlResponse;
import com.classroom.modules.media.dto.UploadIntentRequest;
import com.classroom.modules.media.dto.UploadIntentResponse;
import com.classroom.modules.media.model.MediaAsset;
import com.classroom.modules.media.service.MediaService;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.*;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1")
public class MediaController {
    private static final Logger log = LoggerFactory.getLogger(MediaController.class);
    private static final Pattern RANGE_PATTERN = Pattern.compile("^bytes=(\\d*)-(\\d*)$");

    private final MediaService mediaService;
    private final AccessPolicy accessPolicy;
    private final CourseRepository courseRepository;

    public MediaController(MediaService mediaService, AccessPolicy accessPolicy, CourseRepository courseRepository) {
        this.mediaService = mediaService;
        this.accessPolicy = accessPolicy;
        this.courseRepository = courseRepository;
    }

    @PostMapping("/classes/{classId}/media/upload-intents")
    public ResponseEntity<ApiResponse<UploadIntentResponse>> createUploadIntent(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody UploadIntentRequest request) {
        authorizeUploadIntent(principal.getId(), classId, request);

        UploadIntentResponse response = mediaService.createUploadIntent(classId, principal.getId(), request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/media/{id}/complete")
    public ResponseEntity<ApiResponse<MediaAsset>> completeUpload(
            @PathVariable String id,
            @CurrentUser UserPrincipal principal) {
        MediaAsset asset = mediaService.completeUpload(id, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(asset));
    }

    /**
     * Authorizes an upload against the authoring action the file is for, instead of always
     * demanding MEDIA:CREATE.
     *
     * <p>A staff member granted DOCUMENT:CREATE (or a course author granted COURSE:EDIT) has to
     * upload the file before the resource that references it can be created, so requiring a
     * separate MEDIA:CREATE grant made the granted authoring permission unusable. Each purpose
     * therefore maps to the permission for the action it is part of, and unknown or absent
     * purposes still fall back to the generic MEDIA:CREATE check.</p>
     */
    private void authorizeUploadIntent(String userId, String classId, UploadIntentRequest request) {
        String purpose = request.getPurpose() == null ? "" : request.getPurpose().trim().toUpperCase();
        String scopeCourseId = resolveScopeCourseId(classId, request.getScopeCourseId());

        switch (purpose) {
            case "ABOUT" -> {
                accessPolicy.enforceManage(userId, classId, "ABOUT", "EDIT", null);
                if (!java.util.Set.of("image/jpeg", "image/png", "image/webp", "image/gif").contains(request.getMimeType())
                        || request.getSizeBytes() > 5L * 1024 * 1024) {
                    throw new AppException(ErrorCode.BAD_REQUEST, "Ảnh giới thiệu cần JPG/PNG/WebP/GIF, tối đa 5 MB");
                }
            }
            case "DOCUMENT" -> requireAny(userId, classId,
                    new String[][]{{"DOCUMENT", "CREATE", null}, {"MEDIA", "CREATE", null}}, null);
            case "COURSE", "LESSON" -> requireAny(userId, classId,
                    new String[][]{{"COURSE", "EDIT", "scoped"}, {"COURSE", "CREATE", "scoped"}, {"MEDIA", "CREATE", null}},
                    scopeCourseId);
            case "FEED", "POST" -> requireAny(userId, classId,
                    new String[][]{{"FEED", "CREATE", null}, {"MEDIA", "CREATE", null}}, null);
            case "STORE", "PRODUCT" -> requireAny(userId, classId,
                    new String[][]{{"STORE", "CREATE", null}, {"MEDIA", "CREATE", null}}, null);
            default -> accessPolicy.enforceManage(userId, classId, "MEDIA", "CREATE", null);
        }
    }

    /** Rejects a scope that is not a course of this class so a scoped grant cannot be misapplied. */
    private String resolveScopeCourseId(String classId, String scopeCourseId) {
        if (scopeCourseId == null || scopeCourseId.isBlank()) return null;
        Course course = courseRepository.findById(scopeCourseId.trim())
                .orElseThrow(() -> new AppException(ErrorCode.BAD_REQUEST, "Không tìm thấy khóa học của tệp tải lên"));
        if (!classId.equals(course.getClassId())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Khóa học của tệp tải lên không thuộc lớp này");
        }
        return course.getId();
    }

    /** Passes when any listed permission is granted; otherwise reports the primary one as denied. */
    private void requireAny(String userId, String classId, String[][] candidates, String scopeCourseId) {
        for (String[] candidate : candidates) {
            String scope = candidate[2] == null ? null : scopeCourseId;
            if (accessPolicy.canManage(userId, classId, candidate[0], candidate[1], scope)) return;
        }
        accessPolicy.enforceManage(userId, classId, candidates[0][0], candidates[0][1],
                candidates[0][2] == null ? null : scopeCourseId);
    }

    @GetMapping("/media/{id}/download-url")
    public ResponseEntity<ApiResponse<DownloadUrlResponse>> getDownloadUrl(
            @PathVariable String id,
            @CurrentUser UserPrincipal principal) {
        DownloadUrlResponse response = mediaService.generateAuthorizedDownloadUrl(id, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * R8-01: kept for any remaining consumer of the proxy route (the frontend now prefers the
     * presigned URL from /media/{id}/download-url). Two hardenings close the silent-truncation
     * finding: (1) this endpoint is exempted from Tomcat's default 30s async timeout via
     * spring.mvc.async.request-timeout, and (2) HTTP Range requests are honored end-to-end against
     * MinIO so large files can still be resumed/streamed/sought through this route.
     */
    @GetMapping("/media/{id}/download")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable String id,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
            @CurrentUser UserPrincipal principal) {
        mediaService.generateAuthorizedDownloadUrl(id, principal.getId());
        MediaAsset asset = mediaService.getAsset(id);
        // R9-08: build via ContentDisposition rather than hand-rolling the header, so a non-ASCII
        // filename (e.g. Vietnamese) is carried correctly as filename*=UTF-8''... (RFC 5987/6266)
        // alongside an ASCII-safe filename="..." fallback for clients that don't parse filename*.
        String contentDisposition = safeContentDisposition("attachment", asset.getOriginalFilename());
        long totalSize = asset.getSizeBytes();

        long[] range = parseRange(rangeHeader, totalSize);
        boolean isPartial = range != null;
        long start = isPartial ? range[0] : 0L;
        long end = isPartial ? range[1] : totalSize - 1;
        if (rangeHeader != null && range == null) {
            // Malformed or unsatisfiable range: RFC 7233 wants 416 with the resource's real extent.
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + totalSize)
                    .build();
        }
        long contentLength = end - start + 1;
        MediaType contentType = MediaType.parseMediaType(asset.getMimeType());

        // R20-12: the object is opened HERE, before the response is started, not inside the streaming body. A failure to reach the
        // object store therefore surfaces as a normal controller exception (503 + Retry-After in the standard envelope) instead of
        // an error raised on the async thread after the status line was chosen, which the client saw as a 500 with an empty body.
        io.minio.GetObjectResponse input = mediaService.openAuthorizedDownload(id, principal.getId(),
                isPartial ? start : null, isPartial ? contentLength : null);
        StreamingResponseBody body = output -> {
            try (input) {
                input.transferTo(output);
            } catch (Exception e) {
                log.warn("Media download stream for asset {} ended early: {}", id, e.getMessage());
                throw e;
            }
        };

        ResponseEntity.BodyBuilder responseBuilder = isPartial
                ? ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                        .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + totalSize)
                : ResponseEntity.ok();
        return responseBuilder
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_LENGTH, Long.toString(contentLength))
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .body(body);
    }

    /**
     * R9-08: ContentDisposition.builder(...).filename(name, UTF_8) itself emits a correct
     * filename*=UTF-8''... parameter (RFC 5987) for non-ASCII names and a sanitized ASCII fallback
     * in filename="..." for older clients that only understand that form. CR/LF/quote stripping is
     * kept as defense in depth against header injection even though filename() already encodes.
     */
    private static String safeContentDisposition(String type, String originalFilename) {
        String filename = originalFilename == null || originalFilename.isBlank()
                ? "download"
                : originalFilename.replaceAll("[\\r\\n\"]", "_");
        return ContentDisposition.builder(type)
                .filename(filename, java.nio.charset.StandardCharsets.UTF_8)
                .build()
                .toString();
    }

    /** Returns {start, end} inclusive, or null when there is no (or no satisfiable) Range header. */
    private long[] parseRange(String rangeHeader, long totalSize) {
        if (rangeHeader == null || rangeHeader.isBlank() || totalSize <= 0) return null;
        Matcher matcher = RANGE_PATTERN.matcher(rangeHeader.trim());
        if (!matcher.matches()) return null;
        String startStr = matcher.group(1);
        String endStr = matcher.group(2);
        if (startStr.isEmpty() && endStr.isEmpty()) return null;

        long start;
        long end;
        if (startStr.isEmpty()) {
            // Suffix range: "bytes=-500" means the last 500 bytes.
            long suffixLength = Long.parseLong(endStr);
            if (suffixLength <= 0) return null;
            start = Math.max(0, totalSize - suffixLength);
            end = totalSize - 1;
        } else {
            start = Long.parseLong(startStr);
            end = endStr.isEmpty() ? totalSize - 1 : Long.parseLong(endStr);
        }
        if (start < 0 || start >= totalSize || end < start) return null;
        end = Math.min(end, totalSize - 1);
        return new long[]{start, end};
    }
}
