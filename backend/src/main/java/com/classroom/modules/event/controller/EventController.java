package com.classroom.modules.event.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.event.dto.ClassEventDto;
import com.classroom.modules.event.dto.CreateEventRequest;
import com.classroom.modules.event.dto.EventRegistrantDto;
import com.classroom.modules.event.dto.UpdateEventRequest;
import com.classroom.modules.event.service.EventService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * D-27: class events. GET /classes/{classId}/events, GET /events/{id} and GET /events/upcoming are open to guests (SecurityConfig).
 * {@code /events/upcoming} is a literal mapping, which Spring MVC always prefers over the {@code /events/{id}} pattern.
 */
@RestController
@RequestMapping("/api/v1")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    private static String idOf(UserPrincipal principal) {
        return principal != null ? principal.getId() : null;
    }

    @GetMapping("/events/upcoming")
    public ResponseEntity<ApiResponse<List<ClassEventDto>>> upcoming(
            @CurrentUser UserPrincipal principal,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(ApiResponse.ok(eventService.upcoming(idOf(principal), size)));
    }

    @GetMapping("/classes/{classId}/events")
    public ResponseEntity<ApiResponse<List<ClassEventDto>>> list(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal,
            @RequestParam(required = false) String scope) {
        return ResponseEntity.ok(ApiResponse.ok(eventService.listForClass(classId, idOf(principal), scope)));
    }

    @GetMapping("/events/{id}")
    public ResponseEntity<ApiResponse<ClassEventDto>> get(
            @PathVariable String id,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(eventService.get(id, idOf(principal))));
    }

    @PostMapping("/classes/{classId}/events")
    public ResponseEntity<ApiResponse<ClassEventDto>> create(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody CreateEventRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(eventService.create(classId, principal.getId(), request)));
    }

    @PutMapping("/events/{id}")
    public ResponseEntity<ApiResponse<ClassEventDto>> update(
            @PathVariable String id,
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody UpdateEventRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(eventService.update(id, principal.getId(), request)));
    }

    @PostMapping("/events/{id}/cancel")
    public ResponseEntity<ApiResponse<ClassEventDto>> cancel(
            @PathVariable String id,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(eventService.cancel(id, principal.getId())));
    }

    @DeleteMapping("/events/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable String id,
            @CurrentUser UserPrincipal principal) {
        eventService.delete(id, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @PostMapping("/events/{id}/registrations")
    public ResponseEntity<ApiResponse<ClassEventDto>> register(
            @PathVariable String id,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(eventService.register(id, principal.getId())));
    }

    /** Answers a {@link ClassEventDto}, or {@code {id, isRegistered:false}} when the class is no longer visible to the caller. */
    @DeleteMapping("/events/{id}/registrations/me")
    public ResponseEntity<ApiResponse<Object>> unregister(
            @PathVariable String id,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(eventService.unregister(id, principal.getId())));
    }

    @GetMapping("/events/{id}/registrations")
    public ResponseEntity<ApiResponse<List<EventRegistrantDto>>> registrants(
            @PathVariable String id,
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(eventService.registrants(id, principal.getId())));
    }
}
