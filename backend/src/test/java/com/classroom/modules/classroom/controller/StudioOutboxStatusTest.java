package com.classroom.modules.classroom.controller;

import com.classroom.common.AppException;
import com.classroom.common.ApiResponse;
import com.classroom.common.ErrorCode;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.exam.repository.ExamRepository;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.outbox.worker.OutboxMonitor;
import com.classroom.modules.outbox.worker.OutboxWorker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** R20-04: the owner-scoped outbox status view sits next to the replay it informs and is guarded by the same permission. */
class StudioOutboxStatusTest {

    private final AccessPolicy accessPolicy = mock(AccessPolicy.class);
    private final OutboxMonitor monitor = mock(OutboxMonitor.class);
    private final StudioController controller = new StudioController(
            mock(ClassroomRepository.class), mock(ClassMemberRepository.class), mock(CourseRepository.class), mock(ExamRepository.class),
            accessPolicy, mock(OutboxWorker.class), monitor, mock(AuditService.class));
    private final UserPrincipal owner = new UserPrincipal("owner-1", "owner@example.test", "x", "Owner", "USER", "ACTIVE");

    @Test
    @DisplayName("GET outbox/status returns the class-scoped counts and the store states to a user who may replay the class outbox")
    void returnsTheClassStatus() {
        Map<String, Object> status = Map.of("classId", "class-1", "pending", 3L, "deadLetter", 1L,
                "sinks", Map.of("mongo", "UP", "neo4j", "DOWN"));
        when(monitor.classStatus("class-1")).thenReturn(status);

        ResponseEntity<ApiResponse<Map<String, Object>>> response = controller.getOutboxStatus("class-1", owner);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(status, response.getBody().getData());
        verify(accessPolicy).enforceManage("owner-1", "class-1", "OUTBOX", "REPLAY", null);
    }

    @Test
    @DisplayName("without the OUTBOX:REPLAY permission the status is refused and nothing is read")
    void deniedWithoutPermission() {
        doThrow(new AppException(ErrorCode.STAFF_PERMISSION_DENIED, "denied"))
                .when(accessPolicy).enforceManage(eq("owner-1"), eq("class-1"), eq("OUTBOX"), eq("REPLAY"), any());

        assertThrows(AppException.class, () -> controller.getOutboxStatus("class-1", owner));

        verify(monitor, never()).classStatus(any());
    }
}
