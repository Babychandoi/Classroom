package com.classroom.modules.admin.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.admin.dto.AdminDtos.AuditRow;
import com.classroom.modules.admin.dto.AdminDtos.ClassDetail;
import com.classroom.modules.admin.dto.AdminDtos.ClassRow;
import com.classroom.modules.admin.dto.AdminDtos.Overview;
import com.classroom.modules.admin.dto.AdminDtos.Page;
import com.classroom.modules.admin.dto.AdminDtos.ReasonRequest;
import com.classroom.modules.admin.dto.AdminDtos.RoleRequest;
import com.classroom.modules.admin.dto.AdminDtos.UserDetail;
import com.classroom.modules.admin.dto.AdminDtos.UserRow;
import com.classroom.modules.admin.service.AdminAuditService;
import com.classroom.modules.admin.service.AdminClassService;
import com.classroom.modules.admin.service.AdminOverviewService;
import com.classroom.modules.admin.service.AdminUserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * D-29: platform administration ({@code /api/v1/admin/**}). Every route requires the platform role PLATFORM_ADMIN, read from the database on
 * each request by the JWT filter (D-26): the URL rule in SecurityConfig and this class-level {@code @PreAuthorize} both enforce it (guest 401,
 * anyone else 403). A platform admin is not a class owner - nothing here grants Studio access or returns content bodies, answer keys,
 * messages, payment secrets or password hashes. Responses are {@code no-store}: they describe other people's accounts.
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class AdminController {

    private final AdminOverviewService overviewService;
    private final AdminUserService userService;
    private final AdminClassService classService;
    private final AdminAuditService auditService;

    public AdminController(AdminOverviewService overviewService, AdminUserService userService, AdminClassService classService,
                           AdminAuditService auditService) {
        this.overviewService = overviewService;
        this.userService = userService;
        this.classService = classService;
        this.auditService = auditService;
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiResponse.ok(body));
    }

    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<Overview>> overview() {
        return ok(overviewService.overview());
    }

    // ---------------------------------------------------------------------------------------------------------------------------- users

    @GetMapping("/users")
    public ResponseEntity<ApiResponse<Page<UserRow>>> users(@RequestParam(required = false) String q,
                                                            @RequestParam(required = false) String status,
                                                            @RequestParam(required = false) String role,
                                                            @RequestParam(required = false) String sort,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        return ok(userService.list(q, status, role, sort, page, size));
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<ApiResponse<UserDetail>> user(@PathVariable String id) {
        return ok(userService.detail(id));
    }

    @PostMapping("/users/{id}/ban")
    public ResponseEntity<ApiResponse<UserRow>> ban(@PathVariable String id, @CurrentUser UserPrincipal admin,
                                                    @Valid @RequestBody ReasonRequest request) {
        return ok(userService.ban(id, admin.getId(), request.reason()));
    }

    @PostMapping("/users/{id}/unban")
    public ResponseEntity<ApiResponse<UserRow>> unban(@PathVariable String id, @CurrentUser UserPrincipal admin,
                                                      @Valid @RequestBody ReasonRequest request) {
        return ok(userService.unban(id, admin.getId(), request.reason()));
    }

    @PutMapping("/users/{id}/role")
    public ResponseEntity<ApiResponse<UserRow>> role(@PathVariable String id, @CurrentUser UserPrincipal admin,
                                                     @Valid @RequestBody RoleRequest request) {
        return ok(userService.changeRole(id, admin.getId(), request.role(), request.reason()));
    }

    // -------------------------------------------------------------------------------------------------------------------------- classes

    @GetMapping("/classes")
    public ResponseEntity<ApiResponse<Page<ClassRow>>> classes(@RequestParam(required = false) String q,
                                                               @RequestParam(required = false) String status,
                                                               @RequestParam(required = false) String visibility,
                                                               @RequestParam(required = false) String accessType,
                                                               @RequestParam(required = false) String category,
                                                               @RequestParam(required = false) String sort,
                                                               @RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(defaultValue = "20") int size) {
        return ok(classService.list(q, status, visibility, accessType, category, sort, page, size));
    }

    @GetMapping("/classes/{id}")
    public ResponseEntity<ApiResponse<ClassDetail>> classDetail(@PathVariable String id) {
        return ok(classService.detail(id));
    }

    @PostMapping("/classes/{id}/suspend")
    public ResponseEntity<ApiResponse<ClassRow>> suspend(@PathVariable String id, @CurrentUser UserPrincipal admin,
                                                         @Valid @RequestBody ReasonRequest request) {
        return ok(classService.suspend(id, admin.getId(), request.reason()));
    }

    @PostMapping("/classes/{id}/restore")
    public ResponseEntity<ApiResponse<ClassRow>> restore(@PathVariable String id, @CurrentUser UserPrincipal admin,
                                                         @Valid @RequestBody ReasonRequest request) {
        return ok(classService.restore(id, admin.getId(), request.reason()));
    }

    // ---------------------------------------------------------------------------------------------------------------------------- audit

    @GetMapping("/audit")
    public ResponseEntity<ApiResponse<Page<AuditRow>>> audit(@RequestParam(required = false) String action,
                                                             @RequestParam(required = false) String actorId,
                                                             @RequestParam(required = false) String classId,
                                                             @RequestParam(required = false) String from,
                                                             @RequestParam(required = false) String to,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "50") int size) {
        return ok(auditService.search(action, actorId, classId, from, to, page, size));
    }
}
