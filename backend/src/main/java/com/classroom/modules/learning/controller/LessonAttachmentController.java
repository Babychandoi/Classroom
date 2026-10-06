package com.classroom.modules.learning.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.learning.dto.LessonAttachmentDto;
import com.classroom.modules.learning.service.LessonAttachmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** D-32: lesson documents. COURSE:EDIT (course-scoped) on every route; reads come with the lesson itself. */
@RestController
@RequestMapping("/api/v1/lessons/{lessonId}/attachments")
public class LessonAttachmentController {

    public record AddRequest(String mediaAssetId, String title) {}
    public record RenameRequest(String title) {}
    public record ReorderRequest(List<String> ids) {}

    private final LessonAttachmentService service;

    public LessonAttachmentController(LessonAttachmentService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<LessonAttachmentDto>> add(@PathVariable String lessonId, @CurrentUser UserPrincipal principal,
                                                                @RequestBody AddRequest body) {
        return ResponseEntity.ok(ApiResponse.ok(service.add(lessonId, body == null ? null : body.mediaAssetId(),
                body == null ? null : body.title(), principal.getId())));
    }

    @PutMapping("/reorder")
    public ResponseEntity<ApiResponse<List<LessonAttachmentDto>>> reorder(@PathVariable String lessonId, @CurrentUser UserPrincipal principal,
                                                                          @RequestBody ReorderRequest body) {
        return ResponseEntity.ok(ApiResponse.ok(service.reorder(lessonId, body == null ? null : body.ids(), principal.getId())));
    }

    @PatchMapping("/{attachmentId}")
    public ResponseEntity<ApiResponse<LessonAttachmentDto>> rename(@PathVariable String lessonId, @PathVariable String attachmentId,
                                                                   @CurrentUser UserPrincipal principal, @RequestBody RenameRequest body) {
        return ResponseEntity.ok(ApiResponse.ok(service.rename(lessonId, attachmentId, body == null ? null : body.title(), principal.getId())));
    }

    @DeleteMapping("/{attachmentId}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String lessonId, @PathVariable String attachmentId,
                                                    @CurrentUser UserPrincipal principal) {
        service.delete(lessonId, attachmentId, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
