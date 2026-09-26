package com.classroom.modules.community.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.community.dto.DocumentAssetDto;
import com.classroom.modules.community.model.DocumentAsset;
import com.classroom.modules.community.service.DocumentService;
import com.classroom.modules.media.dto.DownloadUrlResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping("/classes/{classId}/documents")
    public ResponseEntity<ApiResponse<List<DocumentAssetDto>>> getDocuments(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal) {
        List<DocumentAssetDto> docs = documentService.getDocuments(classId, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(docs));
    }

    @GetMapping("/documents/{documentId}/download-url")
    public ResponseEntity<ApiResponse<DownloadUrlResponse>> getDocumentDownloadUrl(
            @PathVariable String documentId,
            @CurrentUser UserPrincipal principal) {
        DownloadUrlResponse response = documentService.getDocumentDownloadUrl(documentId, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/classes/{classId}/documents")
    public ResponseEntity<ApiResponse<DocumentAsset>> createDocument(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal,
            @RequestBody Map<String, String> body) {
        DocumentAsset doc = documentService.createDocument(
                classId,
                body.get("title"),
                body.get("description"),
                body.get("mediaAssetId"),
                body.getOrDefault("visibility", "FREE"),
                body.get("targetProductId"),
                body.get("targetCourseId"),
                principal.getId()
        );
        return ResponseEntity.ok(ApiResponse.ok(doc));
    }
}
