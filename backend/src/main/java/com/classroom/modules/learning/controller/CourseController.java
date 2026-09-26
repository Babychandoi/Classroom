package com.classroom.modules.learning.controller;

import com.classroom.common.ApiResponse;
import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.learning.dto.CourseDto;
import com.classroom.modules.learning.dto.SectionDto;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.model.Section;
import com.classroom.modules.learning.service.LearningService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class CourseController {

    private final LearningService learningService;

    public CourseController(LearningService learningService) {
        this.learningService = learningService;
    }

    @GetMapping("/classes/{classId}/courses")
    public ResponseEntity<ApiResponse<List<CourseDto>>> getCourses(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal) {
        String userId = (principal != null) ? principal.getId() : null;
        List<CourseDto> courses = learningService.getCoursesByClass(classId, userId);
        return ResponseEntity.ok(ApiResponse.ok(courses));
    }

    @PostMapping("/classes/{classId}/courses")
    public ResponseEntity<ApiResponse<Course>> createCourse(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal,
            @RequestBody Course course) {
        Course created = learningService.createCourse(classId, course, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(created));
    }

    @PostMapping("/courses/{courseId}/publish")
    public ResponseEntity<ApiResponse<Course>> publishCourse(
            @PathVariable String courseId,
            @CurrentUser UserPrincipal principal) {
        Course published = learningService.publishCourse(courseId, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(published));
    }

    @PutMapping("/courses/{courseId}/product/{productId}")
    public ResponseEntity<ApiResponse<Course>> linkProduct(
            @PathVariable String courseId, @PathVariable String productId,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(learningService.linkProduct(courseId, productId, principal.getId())));
    }

    @GetMapping("/courses/{courseId}")
    public ResponseEntity<ApiResponse<CourseDto>> getCourseDetails(
            @PathVariable String courseId,
            @CurrentUser UserPrincipal principal) {
        String userId = (principal != null) ? principal.getId() : null;
        CourseDto course = learningService.getCourseDetails(courseId, userId);
        return ResponseEntity.ok(ApiResponse.ok(course));
    }

    @PostMapping("/courses/{courseId}/sections")
    public ResponseEntity<ApiResponse<Section>> createSection(
            @PathVariable String courseId,
            @CurrentUser UserPrincipal principal,
            @RequestBody Map<String, Object> body) {
        Object rawTitle = body.get("title");
        if (rawTitle != null && !(rawTitle instanceof String)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Tên chương không hợp lệ");
        }
        String title = (String) rawTitle;
        Object rawPosition = body.get("position");
        int position = 0;
        if (rawPosition != null) {
            if (!(rawPosition instanceof Number number)) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Vị trí chương không hợp lệ");
            }
            position = number.intValue();
        }
        Section section = learningService.createSection(courseId, title, position, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(section));
    }
}
