package com.classroom.modules.classroom.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.classroom.dto.ClassInviteDto;
import com.classroom.modules.classroom.dto.ClassroomDto;
import com.classroom.modules.classroom.dto.CreateInviteRequest;
import com.classroom.modules.classroom.dto.InvitePreviewDto;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.service.ClassInviteService;
import com.classroom.modules.classroom.service.ClassroomService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * D-19: invite links.
 *
 * <ul>
 *   <li>{@code POST|GET /classes/{id}/invites}, {@code DELETE /classes/{id}/invites/{inviteId}} - OWNER or staff with {@code MEMBER:EDIT}.</li>
 *   <li>{@code GET /classes/invites/{code}} - PUBLIC (no login): the class card for a valid code, one 404 for every invalid code.</li>
 *   <li>{@code POST /classes/invites/{code}/join} - login required.</li>
 * </ul>
 * The two code endpoints are throttled per client address by {@code AuthRateLimitFilter} (invite bucket) so codes cannot be enumerated.
 */
@RestController
@RequestMapping("/api/v1/classes")
public class ClassInviteController {

    private final ClassInviteService inviteService;
    private final ClassroomService classroomService;

    public ClassInviteController(ClassInviteService inviteService, ClassroomService classroomService) {
        this.inviteService = inviteService;
        this.classroomService = classroomService;
    }

    @PostMapping("/{classId}/invites")
    public ResponseEntity<ApiResponse<ClassInviteDto>> createInvite(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal,
            @RequestBody(required = false) CreateInviteRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(inviteService.create(classId, request, principal.getId())));
    }

    @GetMapping("/{classId}/invites")
    public ResponseEntity<ApiResponse<List<ClassInviteDto>>> listInvites(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(inviteService.list(classId, principal.getId())));
    }

    @DeleteMapping("/{classId}/invites/{inviteId}")
    public ResponseEntity<ApiResponse<ClassInviteDto>> revokeInvite(
            @PathVariable String classId,
            @PathVariable String inviteId,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(inviteService.revoke(classId, inviteId, principal.getId())));
    }

    @GetMapping("/invites/{code}")
    public ResponseEntity<ApiResponse<InvitePreviewDto>> previewInvite(@PathVariable String code) {
        return ResponseEntity.ok(ApiResponse.ok(inviteService.preview(code)));
    }

    @PostMapping("/invites/{code}/join")
    public ResponseEntity<ApiResponse<ClassroomDto>> joinByInvite(
            @PathVariable String code,
            @CurrentUser UserPrincipal principal) {
        Classroom classroom = inviteService.join(code, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(classroomService.toDto(classroom, principal.getId())));
    }
}
