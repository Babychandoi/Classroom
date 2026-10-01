package com.classroom.modules.exam.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.exam.dto.ExamAttemptDto;
import com.classroom.modules.exam.dto.ExamDto;
import com.classroom.modules.exam.dto.GradeAttemptRequest;
import com.classroom.modules.exam.dto.SubmitAttemptRequest;
import com.classroom.modules.exam.model.Exam;
import com.classroom.modules.exam.model.Question;
import com.classroom.modules.exam.model.AnswerOption;
import com.classroom.modules.exam.service.ExamService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ExamController {

    private final ExamService examService;

    public ExamController(ExamService examService) {
        this.examService = examService;
    }

    @GetMapping("/classes/{classId}/exams")
    public ResponseEntity<ApiResponse<List<ExamDto>>> getExams(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal) {
        String userId = (principal != null) ? principal.getId() : null;
        List<ExamDto> exams = examService.getExamsByClass(classId, userId);
        return ResponseEntity.ok(ApiResponse.ok(exams));
    }

    @PostMapping("/classes/{classId}/exams")
    public ResponseEntity<ApiResponse<Exam>> createExam(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal,
            @RequestBody Exam exam) {
        Exam created = examService.createExam(classId, exam, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(created));
    }

    @GetMapping("/exams/{examId}")
    public ResponseEntity<ApiResponse<ExamDto>> getExamDetails(
            @PathVariable String examId,
            @CurrentUser UserPrincipal principal) {
        String userId = (principal != null) ? principal.getId() : null;
        ExamDto exam = examService.getExamDetails(examId, userId);
        return ResponseEntity.ok(ApiResponse.ok(exam));
    }

    @PostMapping("/exams/{examId}/attempts")
    public ResponseEntity<ApiResponse<ExamAttemptDto>> startAttempt(
            @PathVariable String examId,
            @CurrentUser UserPrincipal principal,
            @RequestParam(defaultValue = "false") boolean preview,
            @RequestParam(defaultValue = "false") boolean resumeOnly) {
        ExamAttemptDto attempt = examService.startAttempt(examId, principal.getId(), preview, resumeOnly);
        return ResponseEntity.ok(ApiResponse.ok(attempt));
    }

    @PutMapping("/attempts/{attemptId}/answers")
    public ResponseEntity<ApiResponse<ExamAttemptDto>> saveAnswers(
            @PathVariable String attemptId,
            @CurrentUser UserPrincipal principal,
            @RequestBody SubmitAttemptRequest request) {
        ExamAttemptDto result = examService.saveAnswers(attemptId, principal.getId(), request.getAnswers());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/attempts/{attemptId}/submit")
    public ResponseEntity<ApiResponse<ExamAttemptDto>> submitAttempt(
            @PathVariable String attemptId,
            @CurrentUser UserPrincipal principal,
            @RequestBody SubmitAttemptRequest request) {
        ExamAttemptDto result = examService.submitAttempt(attemptId, principal.getId(), request);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/attempts/{attemptId}/result")
    public ResponseEntity<ApiResponse<ExamAttemptDto>> getAttemptResult(
            @PathVariable String attemptId,
            @CurrentUser UserPrincipal principal) {
        ExamAttemptDto result = examService.getAttemptResult(attemptId, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/exams/{examId}/my-attempts")
    public ResponseEntity<ApiResponse<List<ExamAttemptDto>>> getMyAttempts(
            @PathVariable String examId,
            @CurrentUser UserPrincipal principal) {
        List<ExamAttemptDto> attempts = examService.getMyAttempts(examId, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(attempts));
    }

    @PostMapping("/attempts/{attemptId}/grade")
    public ResponseEntity<ApiResponse<ExamAttemptDto>> gradeAttempt(
            @PathVariable String attemptId,
            @CurrentUser UserPrincipal principal,
            @RequestBody GradeAttemptRequest request) {
        ExamAttemptDto result = examService.gradeAttempt(attemptId, principal.getId(), request);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/classes/{classId}/grading-queue")
    public ResponseEntity<ApiResponse<List<ExamAttemptDto>>> getGradingQueue(
            @PathVariable String classId, @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(examService.getGradingQueue(classId, principal.getId())));
    }

    @GetMapping("/attempts/{attemptId}/grading")
    public ResponseEntity<ApiResponse<ExamAttemptDto>> getGradingAttempt(
            @PathVariable String attemptId, @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(examService.getGradingAttempt(attemptId, principal.getId())));
    }

    @GetMapping("/exams/{examId}/published-attempts")
    public ResponseEntity<ApiResponse<List<ExamAttemptDto>>> getPublishedAttempts(
            @PathVariable String examId,
            @CurrentUser UserPrincipal principal,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(ApiResponse.ok(examService.getPublishedAttempts(examId, principal.getId(), limit)));
    }

    public record AddQuestionRequest(Question question, List<AnswerOption> options) {}

    @PostMapping("/exams/{examId}/questions")
    public ResponseEntity<ApiResponse<Question>> addQuestion(
            @PathVariable String examId,
            @CurrentUser UserPrincipal principal,
            @RequestBody AddQuestionRequest request) {
        Question created = examService.addQuestion(examId, request.question(), request.options(), principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(created));
    }

    @PostMapping("/exams/{examId}/publish")
    public ResponseEntity<ApiResponse<Exam>> publishExam(
            @PathVariable String examId, @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(examService.publishExam(examId, principal.getId())));
    }

    @PutMapping("/exams/{examId}")
    public ResponseEntity<ApiResponse<Exam>> updateExam(
            @PathVariable String examId,
            @CurrentUser UserPrincipal principal,
            @RequestBody Exam patch) {
        return ResponseEntity.ok(ApiResponse.ok(examService.updateExam(examId, patch, principal.getId())));
    }

    @PostMapping("/exams/{examId}/close")
    public ResponseEntity<ApiResponse<Exam>> closeExam(
            @PathVariable String examId, @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(examService.closeExam(examId, principal.getId())));
    }

    @PostMapping("/exams/{examId}/archive")
    public ResponseEntity<ApiResponse<Exam>> archiveExam(
            @PathVariable String examId, @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(examService.archiveExam(examId, principal.getId())));
    }

    @PutMapping("/questions/{questionId}")
    public ResponseEntity<ApiResponse<Question>> updateQuestion(
            @PathVariable String questionId,
            @CurrentUser UserPrincipal principal,
            @RequestBody AddQuestionRequest request) {
        Question updated = examService.updateQuestion(questionId, request.question(), request.options(), principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(updated));
    }

    @DeleteMapping("/questions/{questionId}")
    public ResponseEntity<ApiResponse<Void>> deleteQuestion(
            @PathVariable String questionId, @CurrentUser UserPrincipal principal) {
        examService.deleteQuestion(questionId, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PutMapping("/exams/{examId}/questions/reorder")
    public ResponseEntity<ApiResponse<Void>> reorderQuestions(
            @PathVariable String examId,
            @CurrentUser UserPrincipal principal,
            @RequestBody List<String> orderedQuestionIds) {
        examService.reorderQuestions(examId, orderedQuestionIds, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    public record CancelAttemptRequest(String reason) {}

    @PostMapping("/attempts/{attemptId}/cancel")
    public ResponseEntity<ApiResponse<ExamAttemptDto>> cancelAttempt(
            @PathVariable String attemptId,
            @CurrentUser UserPrincipal principal,
            @RequestBody(required = false) CancelAttemptRequest request) {
        String reason = request != null ? request.reason() : null;
        return ResponseEntity.ok(ApiResponse.ok(examService.cancelAttempt(attemptId, principal.getId(), reason)));
    }
}
