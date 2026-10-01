package com.classroom.modules.classroom.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.exam.repository.ExamRepository;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.outbox.worker.OutboxMonitor;
import com.classroom.modules.outbox.worker.OutboxWorker;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/studio")
public class StudioController {

    private final ClassroomRepository classroomRepository;
    private final ClassMemberRepository memberRepository;
    private final CourseRepository courseRepository;
    private final ExamRepository examRepository;
    private final AccessPolicy accessPolicy;
    private final OutboxWorker outboxWorker;
    private final OutboxMonitor outboxMonitor;
    private final AuditService auditService;

    public StudioController(ClassroomRepository classroomRepository,
                            ClassMemberRepository memberRepository,
                            CourseRepository courseRepository,
                            ExamRepository examRepository,
                            AccessPolicy accessPolicy,
                            OutboxWorker outboxWorker,
                            OutboxMonitor outboxMonitor,
                            AuditService auditService) {
        this.classroomRepository = classroomRepository;
        this.memberRepository = memberRepository;
        this.courseRepository = courseRepository;
        this.examRepository = examRepository;
        this.accessPolicy = accessPolicy;
        this.outboxWorker = outboxWorker;
        this.outboxMonitor = outboxMonitor;
        this.auditService = auditService;
    }

    @GetMapping("/classes/{classId}/overview")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStudioOverview(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal) {
        
        // Enforce Studio access (Owner or authorized Staff)
        accessPolicy.enforceManage(principal.getId(), classId, "STUDIO", "VIEW", null);

        Classroom classroom = classroomRepository.findById(classId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy lớp học"));

        Map<String, Object> overview = new HashMap<>();
        overview.put("classId", classId);
        overview.put("slug", classroom.getSlug());
        overview.put("title", classroom.getTitle());
        overview.put("memberCount", memberRepository.countByClassIdAndState(classId, "ACTIVE"));
        overview.put("courseCount", courseRepository.findByClassIdOrderByPositionAsc(classId).size());
        overview.put("examCount", examRepository.findByClassIdOrderByCreatedAtDesc(classId).size());

        return ResponseEntity.ok(ApiResponse.ok(overview));
    }

    /**
     * R20-04: what is waiting in the projection outbox for this class - pending / processing / failed / dead-lettered events and whether
     * MongoDB and Neo4j are currently reachable. Same permission as the replay it informs; platform-wide figures are in the OPS health
     * details, not here.
     */
    @GetMapping("/classes/{classId}/outbox/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getOutboxStatus(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal) {
        accessPolicy.enforceManage(principal.getId(), classId, "OUTBOX", "REPLAY", null);
        return ResponseEntity.ok(ApiResponse.ok(outboxMonitor.classStatus(classId)));
    }

    @PostMapping("/classes/{classId}/outbox/replay")
    public ResponseEntity<ApiResponse<Map<String, Object>>> replayOutboxEvents(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal) {
        accessPolicy.enforceManage(principal.getId(), classId, "OUTBOX", "REPLAY", null);
        int replayedCount = outboxWorker.replayFailedEvents(classId);
        auditService.record(classId, principal.getId(), "OUTBOX_REPLAY", "OUTBOX", classId,
                "{\"replayedCount\":" + replayedCount + "}");
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "replayedCount", replayedCount,
                "message", "Đã đưa " + replayedCount + " sự kiện thất bại vào hàng đợi xử lý lại"
        )));
    }
}
