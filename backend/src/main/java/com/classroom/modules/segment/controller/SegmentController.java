package com.classroom.modules.segment.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.segment.dto.SegmentDto;
import com.classroom.modules.segment.dto.SegmentRule;
import com.classroom.modules.segment.service.SegmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class SegmentController {

    private final SegmentService segmentService;

    public SegmentController(SegmentService segmentService) {
        this.segmentService = segmentService;
    }

    @GetMapping("/classes/{classId}/segments")
    public ResponseEntity<ApiResponse<List<SegmentDto>>> getSegments(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal) {
        List<SegmentDto> segments = segmentService.getSegments(classId, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(segments));
    }

    @PostMapping("/classes/{classId}/segments")
    public ResponseEntity<ApiResponse<SegmentDto>> createSegment(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal,
            @RequestBody SegmentDto body) {
        SegmentDto created = segmentService.createSegment(classId, body.getName(), body.getDescription(),
                body.getLogicOperator(), body.getRules(), principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(created));
    }

    @PostMapping("/segments/{id}/preview")
    public ResponseEntity<ApiResponse<Map<String, Object>>> previewSegment(
            @PathVariable String id,
            @RequestParam String classId,
            @CurrentUser UserPrincipal principal) {
        Map<String, Object> preview = segmentService.previewSegment(id, classId, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(preview));
    }
}
