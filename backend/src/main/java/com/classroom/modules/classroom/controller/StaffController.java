package com.classroom.modules.classroom.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.classroom.dto.StaffAssignmentDto;
import com.classroom.modules.classroom.dto.StaffPermissionDto;
import com.classroom.modules.classroom.service.StaffService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/classes/{classId}/staff")
public class StaffController {

    private final StaffService staffService;

    public StaffController(StaffService staffService) {
        this.staffService = staffService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<StaffAssignmentDto>>> getStaff(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal) {
        List<StaffAssignmentDto> staff = staffService.getClassStaff(classId, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(staff));
    }

    @PutMapping("/{userId}/permissions")
    public ResponseEntity<ApiResponse<StaffAssignmentDto>> assignPermissions(
            @PathVariable String classId,
            @PathVariable String userId,
            @CurrentUser UserPrincipal principal,
            @RequestBody List<StaffPermissionDto> permissions) {
        StaffAssignmentDto dto = staffService.assignStaff(classId, userId, permissions, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(dto));
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<ApiResponse<Map<String, String>>> removeStaff(
            @PathVariable String classId,
            @PathVariable String userId,
            @CurrentUser UserPrincipal principal) {
        staffService.removeStaff(classId, userId, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(Map.of("message", "Đã xóa nhân sự thành công")));
    }
}
