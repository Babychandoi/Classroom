package com.classroom.modules.learning.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.learning.model.AssignmentSubmission;
import com.classroom.modules.learning.service.AssignmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class AssignmentController {
    private final AssignmentService service;
    public AssignmentController(AssignmentService service) { this.service = service; }

    @PostMapping("/lessons/{lessonId}/submissions")
    public ResponseEntity<ApiResponse<AssignmentSubmission>> submit(@PathVariable String lessonId,
            @CurrentUser UserPrincipal principal, @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(ApiResponse.ok(service.submit(lessonId, principal.getId(), body.get("submissionText"))));
    }
    @GetMapping("/lessons/{lessonId}/submissions/mine")
    public ResponseEntity<ApiResponse<List<AssignmentSubmission>>> mine(@PathVariable String lessonId, @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(service.mySubmissions(lessonId, principal.getId())));
    }
    @GetMapping("/lessons/{lessonId}/submissions")
    public ResponseEntity<ApiResponse<List<AssignmentSubmission>>> queue(@PathVariable String lessonId, @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(service.queue(lessonId, principal.getId())));
    }
    @GetMapping("/classes/{classId}/assignment-queue")
    public ResponseEntity<ApiResponse<List<AssignmentSubmission>>> classQueue(@PathVariable String classId, @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(service.classQueue(classId, principal.getId())));
    }
    @PutMapping("/assignment-submissions/{submissionId}/grade")
    public ResponseEntity<ApiResponse<AssignmentSubmission>> grade(@PathVariable String submissionId,
            @CurrentUser UserPrincipal principal, @RequestBody Map<String, Object> body) {
        BigDecimal score = body.get("score") == null ? null : new BigDecimal(body.get("score").toString());
        return ResponseEntity.ok(ApiResponse.ok(service.grade(submissionId, principal.getId(), score,
                body.get("feedback") == null ? null : body.get("feedback").toString())));
    }
}
