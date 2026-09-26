package com.classroom.modules.classroom.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.classroom.dto.ClassMemberDto;
import com.classroom.modules.classroom.dto.ClassroomDto;
import com.classroom.modules.classroom.dto.CreateClassroomRequest;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.service.ClassroomService;
import com.classroom.modules.identity.dto.UserProfileDto;
import com.classroom.modules.identity.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/classes")
public class ClassroomController {

    private final ClassroomService classroomService;
    private final UserService userService;

    public ClassroomController(ClassroomService classroomService, UserService userService) {
        this.classroomService = classroomService;
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ClassroomDto>>> getAllClasses(@CurrentUser UserPrincipal principal) {
        String currentUserId = (principal != null) ? principal.getId() : null;
        List<ClassroomDto> classes = classroomService.getAllClassrooms(currentUserId);
        return ResponseEntity.ok(ApiResponse.ok(classes));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ClassroomDto>> createClass(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody CreateClassroomRequest request) {
        ClassroomDto created = classroomService.createClassroom(principal.getId(), request);
        return ResponseEntity.ok(ApiResponse.ok(created));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ClassroomDto>> getClassById(
            @PathVariable String id,
            @CurrentUser UserPrincipal principal) {
        String currentUserId = (principal != null) ? principal.getId() : null;
        ClassroomDto dto = classroomService.getById(id, currentUserId);
        return ResponseEntity.ok(ApiResponse.ok(dto));
    }

    @GetMapping("/slug/{slug}")
    public ResponseEntity<ApiResponse<ClassroomDto>> getClassBySlug(
            @PathVariable String slug,
            @CurrentUser UserPrincipal principal) {
        String currentUserId = (principal != null) ? principal.getId() : null;
        ClassroomDto dto = classroomService.getBySlug(slug, currentUserId);
        return ResponseEntity.ok(ApiResponse.ok(dto));
    }

    @PostMapping("/{id}/join")
    public ResponseEntity<ApiResponse<ClassroomDto>> joinClass(
            @PathVariable String id,
            @CurrentUser UserPrincipal principal) {
        ClassroomDto dto = classroomService.joinClassroom(id, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(dto));
    }

    @GetMapping("/{id}/members")
    public ResponseEntity<ApiResponse<List<ClassMemberDto>>> getClassMembers(
            @PathVariable String id,
            @CurrentUser UserPrincipal principal) {
        String currentUserId = (principal != null) ? principal.getId() : null;
        List<ClassMemberDto> members = classroomService.getClassMembers(id, currentUserId);
        return ResponseEntity.ok(ApiResponse.ok(members));
    }

    @GetMapping("/{id}/members/{userId}/profile")
    public ResponseEntity<ApiResponse<UserProfileDto>> getMemberProfile(
            @PathVariable String id,
            @PathVariable String userId,
            @CurrentUser UserPrincipal principal) {
        String currentUserId = (principal != null) ? principal.getId() : null;
        UserProfileDto profile = userService.getProfileForViewer(userId, currentUserId, id);
        return ResponseEntity.ok(ApiResponse.ok(profile));
    }
}
