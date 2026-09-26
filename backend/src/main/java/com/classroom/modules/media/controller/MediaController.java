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
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class MediaController {

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

    @GetMapping("/media/{id}/download")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable String id,
            @CurrentUser UserPrincipal principal) {
        mediaService.generateAuthorizedDownloadUrl(id, principal.getId());
        MediaAsset asset = mediaService.getAsset(id);
        StreamingResponseBody body = output -> {
            try (var input = mediaService.openAuthorizedDownload(id, principal.getId())) {
                input.transferTo(output);
            }
        };
        String filename = asset.getOriginalFilename() == null ? "download" : asset.getOriginalFilename().replaceAll("[\\r\\n\"]", "_");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.getMimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }
}
