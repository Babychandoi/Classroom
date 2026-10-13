package com.classroom.modules.assessment.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.assessment.dto.AssessmentRequests.*;
import com.classroom.modules.assessment.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class AssessmentController {
    private final AssessmentAuthoringService author;
    private final AssessmentAttemptService attempts;
    private final AssessmentGradingService grading;
    private final AssessmentCodeService code;
    public AssessmentController(AssessmentAuthoringService author,AssessmentAttemptService attempts,AssessmentGradingService grading,AssessmentCodeService code){this.author=author;this.attempts=attempts;this.grading=grading;this.code=code;}
    @PostMapping("/assessment-attempts/{id}/items/{itemId}/run-code")
    public ApiResponse<?> runCode(@PathVariable String id,@PathVariable String itemId,@CurrentUser UserPrincipal u,@RequestBody JsonNode body){return ApiResponse.ok(code.run(id,itemId,u.getId(),body));}
    @GetMapping("/classes/{classId}/assessments")
    public ApiResponse<?> list(@PathVariable String classId,@CurrentUser UserPrincipal u,@RequestParam(required=false) String status,@RequestParam(required=false) String q){return ApiResponse.ok(author.list(classId,u.getId(),status,q));}
    @PostMapping("/classes/{classId}/assessments")
    public ApiResponse<?> create(@PathVariable String classId,@CurrentUser UserPrincipal u,@RequestBody Create body){return ApiResponse.ok(author.create(classId,u.getId(),body));}
    @GetMapping("/assessments/{id}")
    public ApiResponse<?> get(@PathVariable String id,@CurrentUser UserPrincipal u){return ApiResponse.ok(author.get(id,u.getId()));}
    @PutMapping("/assessments/{id}")
    public ApiResponse<?> save(@PathVariable String id,@CurrentUser UserPrincipal u,@RequestBody Save body){return ApiResponse.ok(author.save(id,u.getId(),body));}
    @PostMapping("/assessments/{id}/validate")
    public ApiResponse<?> validate(@PathVariable String id,@CurrentUser UserPrincipal u,@RequestBody(required=false) Validate body){return ApiResponse.ok(author.validate(id,u.getId(),body));}
    @GetMapping("/assessments/{id}/preview")
    public ApiResponse<?> preview(@PathVariable String id,@CurrentUser UserPrincipal u){return ApiResponse.ok(author.preview(id,u.getId()));}
    @GetMapping("/assessments/{id}/media/{assetId}/url")
    public ApiResponse<?> media(@PathVariable String id,@PathVariable String assetId,@CurrentUser UserPrincipal u){return ApiResponse.ok(author.mediaUrl(id,assetId,u.getId()));}
    @PostMapping("/assessments/{id}/duplicate")
    public ApiResponse<?> duplicate(@PathVariable String id,@CurrentUser UserPrincipal u){return ApiResponse.ok(author.duplicate(id,u.getId()));}
    @DeleteMapping("/assessments/{id}")
    public ApiResponse<?> archive(@PathVariable String id,@CurrentUser UserPrincipal u){author.archive(id,u.getId());return ApiResponse.ok(Map.of("archived",true));}
    @GetMapping("/classes/{classId}/question-bank")
    public ApiResponse<?> bank(@PathVariable String classId,@CurrentUser UserPrincipal u,@RequestParam(required=false) String q,@RequestParam(required=false) String type,
        @RequestParam(required=false) String mode,@RequestParam(required=false) Integer grade,@RequestParam(required=false) String subjectCode,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="30") int size){return ApiResponse.ok(author.bank(classId,u.getId(),q,type,mode,grade,subjectCode,page,size));}
    @GetMapping("/question-bank-items/{id}")
    public ApiResponse<?> bankItem(@PathVariable String id,@CurrentUser UserPrincipal u){return ApiResponse.ok(author.bankItem(id,u.getId()));}
    @PostMapping("/assessments/{id}/assign")
    public ApiResponse<?> assign(@PathVariable String id,@CurrentUser UserPrincipal u,@RequestBody Assign body){return ApiResponse.ok(author.assign(id,u.getId(),body));}
    @GetMapping("/classes/{classId}/assessment-assignments")
    public ApiResponse<?> assignments(@PathVariable String classId,@CurrentUser UserPrincipal u){return ApiResponse.ok(author.assignments(classId,u.getId()));}
    @PatchMapping("/assessment-assignments/{id}")
    public ApiResponse<?> patch(@PathVariable String id,@CurrentUser UserPrincipal u,@RequestBody JsonNode body){return ApiResponse.ok(author.patchAssignment(id,u.getId(),body));}
    @GetMapping("/classes/{classId}/homework")
    public ApiResponse<?> homework(@PathVariable String classId,@CurrentUser UserPrincipal u,@RequestParam(defaultValue="all") String filter){return ApiResponse.ok(attempts.homework(classId,u.getId(),filter));}
    @PostMapping("/assessment-assignments/{id}/attempts")
    public ApiResponse<?> start(@PathVariable String id,@CurrentUser UserPrincipal u){return ApiResponse.ok(attempts.start(id,u.getId()));}
    @GetMapping("/assessment-attempts/{id}")
    public ApiResponse<?> attempt(@PathVariable String id,@CurrentUser UserPrincipal u){return ApiResponse.ok(attempts.get(id,u.getId()));}
    @PutMapping("/assessment-attempts/{id}/answers")
    public ApiResponse<?> answers(@PathVariable String id,@CurrentUser UserPrincipal u,@RequestBody Answers body){return ApiResponse.ok(attempts.autosave(id,u.getId(),body));}
    @PostMapping("/assessment-attempts/{id}/plays")
    public ApiResponse<?> play(@PathVariable String id,@CurrentUser UserPrincipal u,@RequestBody Play body){return ApiResponse.ok(attempts.play(id,u.getId(),body.assetKey()));}
    @PostMapping("/assessment-attempts/{id}/items/{itemId}/recording-intent")
    public ApiResponse<?> recordIntent(@PathVariable String id,@PathVariable String itemId,@CurrentUser UserPrincipal u,@RequestBody RecordingIntent body){return ApiResponse.ok(attempts.uploadIntent(id,itemId,u.getId(),body,true));}
    @PostMapping("/assessment-attempts/{id}/items/{itemId}/recording-complete")
    public ApiResponse<?> recordComplete(@PathVariable String id,@PathVariable String itemId,@CurrentUser UserPrincipal u,@RequestBody RecordingComplete body){return ApiResponse.ok(attempts.uploadComplete(id,itemId,u.getId(),body,true));}
    @DeleteMapping("/assessment-attempts/{id}/items/{itemId}/recording")
    public ApiResponse<?> deleteRecord(@PathVariable String id,@PathVariable String itemId,@CurrentUser UserPrincipal u){attempts.deleteRecording(id,itemId,u.getId());return ApiResponse.ok(Map.of("deleted",true));}
    @GetMapping("/assessment-attempts/{id}/items/{itemId}/recording-url")
    public ApiResponse<?> recordUrl(@PathVariable String id,@PathVariable String itemId,@CurrentUser UserPrincipal u){return ApiResponse.ok(attempts.recordingUrl(id,itemId,u.getId()));}
    @PostMapping("/assessment-attempts/{id}/items/{itemId}/evidence-intent")
    public ApiResponse<?> evidenceIntent(@PathVariable String id,@PathVariable String itemId,@CurrentUser UserPrincipal u,@RequestBody RecordingIntent body){return ApiResponse.ok(attempts.uploadIntent(id,itemId,u.getId(),body,false));}
    @PostMapping("/assessment-attempts/{id}/items/{itemId}/evidence-complete")
    public ApiResponse<?> evidenceComplete(@PathVariable String id,@PathVariable String itemId,@CurrentUser UserPrincipal u,@RequestBody RecordingComplete body){return ApiResponse.ok(attempts.uploadComplete(id,itemId,u.getId(),body,false));}
    @GetMapping("/assessment-attempts/{id}/items/{itemId}/evidence/{assetId}/url")
    public ApiResponse<?> evidenceUrl(@PathVariable String id,@PathVariable String itemId,@PathVariable String assetId,@CurrentUser UserPrincipal u){return ApiResponse.ok(attempts.evidenceUrl(id,itemId,assetId,u.getId()));}
    @PostMapping("/assessment-attempts/{id}/submit")
    public ApiResponse<?> submit(@PathVariable String id,@CurrentUser UserPrincipal u){return ApiResponse.ok(attempts.submit(id,u.getId()));}
    @GetMapping("/assessment-attempts/{id}/result")
    public ApiResponse<?> result(@PathVariable String id,@CurrentUser UserPrincipal u){return ApiResponse.ok(attempts.result(id,u.getId()));}
    @GetMapping("/assessment-attempts/{id}/review-audio/{assetKey}")
    public ApiResponse<?> reviewAudio(@PathVariable String id,@PathVariable String assetKey,@CurrentUser UserPrincipal u){return ApiResponse.ok(attempts.reviewAudio(id,assetKey,u.getId()));}
    @GetMapping("/assessment-assignments/{id}/my-attempts")
    public ApiResponse<?> mine(@PathVariable String id,@CurrentUser UserPrincipal u){return ApiResponse.ok(attempts.mine(id,u.getId()));}
    @GetMapping("/assessment-assignments/{id}/submissions")
    public ApiResponse<?> submissions(@PathVariable String id,@CurrentUser UserPrincipal u){return ApiResponse.ok(grading.submissions(id,u.getId()));}
    @GetMapping("/assessment-attempts/{id}/grading")
    public ApiResponse<?> grading(@PathVariable String id,@CurrentUser UserPrincipal u){return ApiResponse.ok(grading.grading(id,u.getId()));}
    @PutMapping("/assessment-attempts/{id}/answers/{itemId}/grade")
    public ApiResponse<?> grade(@PathVariable String id,@PathVariable String itemId,@CurrentUser UserPrincipal u,@RequestBody Grade body){return ApiResponse.ok(grading.grade(id,itemId,u.getId(),body));}
    @PostMapping("/assessment-attempts/{id}/finalize")
    public ApiResponse<?> finalizeAttempt(@PathVariable String id,@CurrentUser UserPrincipal u,@RequestBody Finalize body){return ApiResponse.ok(grading.finalizeAttempt(id,u.getId(),body));}
    @PostMapping("/assessment-assignments/{id}/release")
    public ApiResponse<?> release(@PathVariable String id,@CurrentUser UserPrincipal u){return ApiResponse.ok(grading.release(id,u.getId()));}
    @GetMapping("/assessment-assignments/{id}/stats")
    public ApiResponse<?> stats(@PathVariable String id,@CurrentUser UserPrincipal u){return ApiResponse.ok(grading.stats(id,u.getId()));}
}
