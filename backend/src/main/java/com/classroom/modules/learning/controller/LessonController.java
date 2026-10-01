package com.classroom.modules.learning.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.learning.dto.AnswerQuestionRequest;
import com.classroom.modules.learning.dto.AskQuestionRequest;
import com.classroom.modules.learning.dto.LessonDto;
import com.classroom.modules.learning.dto.LessonProgressRequest;
import com.classroom.modules.learning.dto.QuestionAnswerDto;
import com.classroom.modules.learning.model.Lesson;
import com.classroom.modules.learning.service.LearningService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class LessonController {

    private final LearningService learningService;

    public LessonController(LearningService learningService) {
        this.learningService = learningService;
    }

    @GetMapping("/lessons/{lessonId}")
    public ResponseEntity<ApiResponse<LessonDto>> getLesson(
            @PathVariable String lessonId,
            @CurrentUser UserPrincipal principal) {
        LessonDto lesson = learningService.getLesson(lessonId, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(lesson));
    }

    @PostMapping("/sections/{sectionId}/lessons")
    public ResponseEntity<ApiResponse<Lesson>> createLesson(
            @PathVariable String sectionId,
            @CurrentUser UserPrincipal principal,
            @RequestBody Lesson lesson) {
        Lesson created = learningService.createLesson(sectionId, lesson, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(created));
    }

    @PutMapping("/lessons/{lessonId}")
    public ResponseEntity<ApiResponse<Lesson>> updateLesson(
            @PathVariable String lessonId,
            @CurrentUser UserPrincipal principal,
            @RequestBody Lesson patch) {
        Lesson updated = learningService.updateLesson(lessonId, patch, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(updated));
    }

    @DeleteMapping("/lessons/{lessonId}")
    public ResponseEntity<ApiResponse<Void>> deleteLesson(
            @PathVariable String lessonId,
            @CurrentUser UserPrincipal principal) {
        learningService.deleteLesson(lessonId, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/lessons/{lessonId}/archive")
    public ResponseEntity<ApiResponse<Lesson>> archiveLesson(
            @PathVariable String lessonId,
            @CurrentUser UserPrincipal principal,
            @RequestParam(defaultValue = "true") boolean archived) {
        Lesson result = learningService.archiveLesson(lessonId, archived, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PutMapping("/sections/{sectionId}/lessons/reorder")
    public ResponseEntity<ApiResponse<Void>> reorderLessons(
            @PathVariable String sectionId,
            @CurrentUser UserPrincipal principal,
            @RequestBody List<String> orderedLessonIds) {
        learningService.reorderLessons(sectionId, orderedLessonIds, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PutMapping("/lessons/{lessonId}/progress")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateProgress(
            @PathVariable String lessonId,
            @CurrentUser UserPrincipal principal,
            @RequestBody(required = false) LessonProgressRequest body) {
        boolean completed = body == null || body.isCompletedOrDefault();
        learningService.markLessonProgress(lessonId, principal.getId(), completed);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("lessonId", lessonId, "completed", completed)));
    }

    @GetMapping("/lessons/{lessonId}/questions")
    public ResponseEntity<ApiResponse<List<QuestionAnswerDto>>> getQuestions(
            @PathVariable String lessonId,
            @CurrentUser UserPrincipal principal) {
        List<QuestionAnswerDto> questions = learningService.getLessonQuestions(lessonId, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(questions));
    }

    @PostMapping("/lessons/{lessonId}/questions")
    public ResponseEntity<ApiResponse<QuestionAnswerDto>> askQuestion(
            @PathVariable String lessonId,
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody AskQuestionRequest body) {
        QuestionAnswerDto dto = learningService.askQuestion(lessonId, principal.getId(), body.getQuestionText());
        return ResponseEntity.ok(ApiResponse.ok(dto));
    }

    @PostMapping("/questions/{questionId}/answers")
    public ResponseEntity<ApiResponse<QuestionAnswerDto.AnswerDto>> answerQuestion(
            @PathVariable String questionId,
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody AnswerQuestionRequest body) {
        QuestionAnswerDto.AnswerDto dto = learningService.answerQuestion(questionId, principal.getId(), body.getAnswerText());
        return ResponseEntity.ok(ApiResponse.ok(dto));
    }
}
