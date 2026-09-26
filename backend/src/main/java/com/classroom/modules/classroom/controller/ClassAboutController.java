package com.classroom.modules.classroom.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.classroom.model.ClassAbout;
import com.classroom.modules.classroom.service.ClassAboutService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/classes/{classId}/about")
public class ClassAboutController {

    private final ClassAboutService aboutService;

    public ClassAboutController(ClassAboutService aboutService) {
        this.aboutService = aboutService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<ClassAbout>> getAbout(@PathVariable String classId) {
        ClassAbout about = aboutService.getAbout(classId);
        return ResponseEntity.ok(ApiResponse.ok(about));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<ClassAbout>> updateAbout(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal,
            @RequestBody Map<String, String> body) {
        ClassAbout updated = aboutService.updateAbout(
                classId,
                body.get("contentMarkdown"),
                body.get("rulesMarkdown"),
                principal.getId()
        );
        return ResponseEntity.ok(ApiResponse.ok(updated));
    }
}
