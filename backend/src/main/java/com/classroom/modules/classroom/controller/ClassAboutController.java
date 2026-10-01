package com.classroom.modules.classroom.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.classroom.model.ClassAbout;
import com.classroom.modules.classroom.dto.ClassAboutDto;
import com.classroom.modules.classroom.dto.UpdateClassAboutRequest;
import jakarta.validation.Valid;
import com.classroom.modules.classroom.service.ClassAboutService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/classes/{classId}/about")
public class ClassAboutController {

    private final ClassAboutService aboutService;
    @org.springframework.beans.factory.annotation.Autowired
    private com.classroom.modules.media.service.MediaService media;

    private ClassAboutDto withImages(ClassAbout about, String viewerId) {
        ClassAboutDto dto = aboutService.toDto(about);
        var sections = dto.sections().stream().map(s -> s.mediaAssetId() == null || s.mediaAssetId().isBlank() ? s
                : new com.classroom.modules.classroom.dto.AboutSectionDto(s.title(), s.contentMarkdown(),
                    media.generateAboutImageUrl(dto.classId(), s.mediaAssetId(), viewerId).getDownloadUrl(), s.imageAlt(), s.mediaAssetId())).toList();
        return new ClassAboutDto(dto.id(), dto.classId(), dto.contentMarkdown(), dto.rulesMarkdown(), dto.publishedVersion(), dto.updatedAt(), sections);
    }

    public ClassAboutController(ClassAboutService aboutService) {
        this.aboutService = aboutService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<ClassAboutDto>> getAbout(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal) {
        String userId = (principal != null) ? principal.getId() : null;
        ClassAbout about = aboutService.getAbout(classId, userId);
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(ApiResponse.ok(withImages(about, userId)));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<ClassAboutDto>> updateAbout(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody UpdateClassAboutRequest body) {
        ClassAbout updated = aboutService.updateAbout(
                classId,
                body,
                principal.getId()
        );
        return ResponseEntity.ok(ApiResponse.ok(withImages(updated, principal.getId())));
    }
}
