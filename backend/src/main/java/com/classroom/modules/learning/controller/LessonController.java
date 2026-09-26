package com.classroom.modules.learning.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.learning.dto.LessonDto;
import com.classroom.modules.learning.dto.QuestionAnswerDto;
import com.classroom.modules.learning.model.Lesson;
import com.classroom.modules.learning.service.LearningService;
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

    @PutMapping("/lessons/{lessonId}/progress")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateProgress(
            @PathVariable String lessonId,
            @CurrentUser UserPrincipal principal,
            @RequestBody Map<String, Boolean> body) {
        boolean completed = body.getOrDefault("completed", true);
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
            @RequestBody Map<String, String> body) {
        String questionText = body.get("questionText");
        QuestionAnswerDto dto = learningService.askQuestion(lessonId, principal.getId(), questionText);
        return ResponseEntity.ok(ApiResponse.ok(dto));
    }

    @PostMapping("/questions/{questionId}/answers")
    public ResponseEntity<ApiResponse<QuestionAnswerDto.AnswerDto>> answerQuestion(
            @PathVariable String questionId,
            @CurrentUser UserPrincipal principal,
            @RequestBody Map<String, String> body) {
        String answerText = body.get("answerText");
        QuestionAnswerDto.AnswerDto dto = learningService.answerQuestion(questionId, principal.getId(), answerText);
        return ResponseEntity.ok(ApiResponse.ok(dto));
    }
}
