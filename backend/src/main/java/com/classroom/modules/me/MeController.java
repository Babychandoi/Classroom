package com.classroom.modules.me;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.classroom.dto.ClassroomDto;
import com.classroom.modules.classroom.service.ClassroomService;
import com.classroom.modules.event.dto.ClassEventDto;
import com.classroom.modules.event.service.EventService;
import com.classroom.modules.learning.dto.MyCourseDto;
import com.classroom.modules.learning.service.MyCoursesService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * D-30: the "của tôi" read models - GET /me/classes, /me/courses, /me/events. All authenticated (SecurityConfig: {@code /api/v1/**}), read-only,
 * and each is a thin composition of the owning module's service, so they return only what the normal endpoints already show the caller.
 * Page size: default 20, at most 50; a negative page is 0.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 50;

    private final ClassroomService classroomService;
    private final MyCoursesService myCoursesService;
    private final EventService eventService;

    public MeController(ClassroomService classroomService, MyCoursesService myCoursesService, EventService eventService) {
        this.classroomService = classroomService;
        this.myCoursesService = myCoursesService;
        this.eventService = eventService;
    }

    private static int size(Integer size) {
        return Math.min(Math.max(size == null ? DEFAULT_SIZE : size, 1), MAX_SIZE);
    }

    private static int page(Integer page) {
        return Math.max(page == null ? 0 : page, 0);
    }

    @GetMapping("/classes")
    public ResponseEntity<ApiResponse<List<ClassroomDto>>> classes(
            @CurrentUser UserPrincipal principal,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(ApiResponse.ok(classroomService.listMine(principal.getId(), page(page), size(size))));
    }

    @GetMapping("/courses")
    public ResponseEntity<ApiResponse<List<MyCourseDto>>> courses(
            @CurrentUser UserPrincipal principal,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(ApiResponse.ok(myCoursesService.listMine(principal.getId(), page(page), size(size))));
    }

    @GetMapping("/events")
    public ResponseEntity<ApiResponse<List<ClassEventDto>>> events(
            @CurrentUser UserPrincipal principal,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(ApiResponse.ok(eventService.listMine(principal.getId(), scope, page(page), size(size))));
    }
}
