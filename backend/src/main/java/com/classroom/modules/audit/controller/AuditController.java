package com.classroom.modules.audit.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.audit.model.AuditEvent;
import com.classroom.modules.audit.service.AuditService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/classes/{classId}/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<AuditEvent>>> getAuditLogs(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal) {
        List<AuditEvent> logs = auditService.getAuditLogs(classId, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(logs));
    }
}
