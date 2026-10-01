package com.classroom.modules.classroom.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.classroom.dto.ClassMemberDto;
import com.classroom.modules.classroom.dto.ClassMemberPageDto;
import com.classroom.modules.classroom.service.MemberService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * R13-02: Studio member management (FR-14 / sitemap /studio/classes/:id/members). Kept separate
 * from the public-facing ClassroomController (GET /classes/{id}/members) the same way
 * StaffController is kept separate from it — this is the MEMBER:EDIT write surface, gated per
 * action in MemberService via AccessPolicy.canManage.
 */
@RestController
@RequestMapping("/api/v1/classes/{classId}/studio/members")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    /**
     * R20-03: paged roster - `page` (0-based), `size` (default 50, max 200) and the server-side filters `q` (name / e-mail),
     * `state` and `role`. The response is an envelope (`members`, `total`, `page`, `size`, `hasNext`), no longer a bare array.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<ClassMemberPageDto>> getStudioMembers(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String role) {
        ClassMemberPageDto members = memberService.getStudioMembers(classId, principal.getId(), page, size, q, state, role);
        return ResponseEntity.ok(ApiResponse.ok(members));
    }

    @PostMapping("/{userId}/remove")
    public ResponseEntity<ApiResponse<ClassMemberDto>> removeMember(
            @PathVariable String classId,
            @PathVariable String userId,
            @CurrentUser UserPrincipal principal) {
        ClassMemberDto dto = memberService.removeMember(classId, userId, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(dto));
    }

    @PostMapping("/{userId}/block")
    public ResponseEntity<ApiResponse<ClassMemberDto>> blockMember(
            @PathVariable String classId,
            @PathVariable String userId,
            @CurrentUser UserPrincipal principal) {
        ClassMemberDto dto = memberService.blockMember(classId, userId, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(dto));
    }

    @PostMapping("/{userId}/unblock")
    public ResponseEntity<ApiResponse<ClassMemberDto>> unblockMember(
            @PathVariable String classId,
            @PathVariable String userId,
            @CurrentUser UserPrincipal principal) {
        ClassMemberDto dto = memberService.unblockMember(classId, userId, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(dto));
    }
}
