package com.classroom.modules.classroom.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.classroom.dto.ClassMemberDto;
import com.classroom.modules.classroom.dto.ClassroomDto;
import com.classroom.modules.classroom.dto.CreateClassroomRequest;
import com.classroom.modules.classroom.dto.UpdateClassAccessRequest;
import com.classroom.modules.classroom.dto.UpdateClassroomRequest;
import com.classroom.modules.classroom.dto.UpdateClassroomStatusRequest;
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
    public ResponseEntity<ApiResponse<List<ClassroomDto>>> getAllClasses(
            @CurrentUser UserPrincipal principal,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String category,
            @RequestParam(required = false, defaultValue = "false") boolean discover) {
        String currentUserId = (principal != null) ? principal.getId() : null;
        // D-27: optional q (title / description search, <= 100 chars) and sort=newest|popular (default newest, as before).
        // R16-08: the listing is always paged. Without page/size the first ClassroomService.DEFAULT_PAGE_SIZE
        // (50) classes are returned; size is clamped to ClassroomService.MAX_PAGE_SIZE (100); a client
        // fetches further pages with page=1,2,... (a page shorter than the requested size is the last).
        List<ClassroomDto> classes = classroomService.getAllClassrooms(currentUserId,
                page != null ? page : 0, size != null ? size : ClassroomService.DEFAULT_PAGE_SIZE, q, sort, category, discover);
        return ResponseEntity.ok(ApiResponse.ok(classes));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ClassroomDto>> createClass(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody CreateClassroomRequest request) {
        ClassroomDto created = classroomService.createClassroom(principal.getId(), request);
        return ResponseEntity.ok(ApiResponse.ok(created));
    }

    /** D-28: the fixed category list, in display order (public). */
    @GetMapping("/categories")
    public ResponseEntity<ApiResponse<List<String>>> categories() {
        return ResponseEntity.ok(ApiResponse.ok(com.classroom.modules.classroom.service.ClassCategories.ALL));
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

    /** R13-02: Studio "Cài đặt lớp" (FR-14) — see ClassroomService#updateClassroom for gating. */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ClassroomDto>> updateClass(
            @PathVariable String id,
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody UpdateClassroomRequest request) {
        ClassroomDto dto = classroomService.updateClassroom(id, request, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(dto));
    }

    /**
     * D-19: FREE / PAID and the price of the class-access product. Money, so: OWNER, or staff holding both STORE:EDIT and CLASS:EDIT - see
     * ClassAccessService#changeAccess for the conversion rules.
     */
    @PutMapping("/{id}/access")
    public ResponseEntity<ApiResponse<ClassroomDto>> updateClassAccess(
            @PathVariable String id,
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody UpdateClassAccessRequest request) {
        ClassroomDto dto = classroomService.updateClassAccess(id, request, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(dto));
    }

    /** R13-02: archive/unarchive — OWNER-only, see ClassroomService#updateClassroomStatus. */
    @PutMapping("/{id}/status")
    public ResponseEntity<ApiResponse<ClassroomDto>> updateClassStatus(
            @PathVariable String id,
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody UpdateClassroomStatusRequest request) {
        ClassroomDto dto = classroomService.updateClassroomStatus(id, request, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(dto));
    }

    @PostMapping("/{id}/join")
    public ResponseEntity<ApiResponse<ClassroomDto>> joinClass(
            @PathVariable String id,
            @CurrentUser UserPrincipal principal) {
        ClassroomDto dto = classroomService.joinClassroom(id, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(dto));
    }

    /**
     * D-28: withdraw the caller's PENDING join request (idempotent). Answers the class, or {@code data: null} when the caller held a
     * request but can no longer see the class.
     */
    @DeleteMapping("/{id}/join-request")
    public ResponseEntity<ApiResponse<ClassroomDto>> withdrawJoinRequest(
            @PathVariable String id,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(classroomService.withdrawJoinRequest(id, principal.getId())));
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
