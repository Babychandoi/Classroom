package com.classroom.modules.event.service;

import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.event.model.ClassEvent;
import com.classroom.modules.event.repository.ClassEventRepository;
import com.classroom.modules.event.repository.EventRegistrationRepository;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.media.service.MediaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * D-27 review: every write that saves the whole event row (update, cancel, delete, unregister, register) must read it through the row lock
 * FIRST - an unlocked read would leave a stale registered_count in the persistence context and the save would overwrite a concurrent
 * registration. (The real contention is proven on MySQL by EventCapacityConcurrencyIntegrationTest.)
 */
@ExtendWith(MockitoExtension.class)
class EventWriteLockingTest {

    @Mock private ClassEventRepository eventRepository;
    @Mock private EventRegistrationRepository registrationRepository;
    @Mock private ClassroomRepository classroomRepository;
    @Mock private ClassMemberRepository memberRepository;
    @Mock private AccessPolicy accessPolicy;
    @Mock private UserRepository userRepository;
    @Mock private MediaService mediaService;
    @Mock private AuditService auditService;

    private EventService service;
    private ClassEvent event;

    @BeforeEach
    void setUp() {
        service = new EventService(eventRepository, registrationRepository, classroomRepository, memberRepository, accessPolicy,
                userRepository, mediaService, auditService, new ObjectMapper());
        event = new ClassEvent();
        event.setId("e1");
        event.setClassId("c1");
        event.setCreatedBy("owner");
        event.setHostUserId("owner");
        event.setTitle("Su kien");
        event.setStartsAt(Instant.now().plus(1, ChronoUnit.DAYS));
        event.setEndsAt(Instant.now().plus(1, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS));
        event.setRegisteredCount(3);
        lenient().when(eventRepository.findByIdForUpdate("e1")).thenReturn(Optional.of(event));
        lenient().when(eventRepository.save(any(ClassEvent.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("cancel() reads the event only through findByIdForUpdate and keeps the locked registered_count")
    void cancelLocksTheRow() {
        service.cancel("e1", "owner");

        verify(eventRepository).findByIdForUpdate("e1");
        verify(eventRepository, never()).findById(anyString());
        verify(eventRepository).save(argThat(e -> ClassEvent.STATUS_CANCELLED.equals(e.getStatus()) && e.getRegisteredCount() == 3));
    }

    @Test
    @DisplayName("update() and delete() read the event only through findByIdForUpdate")
    void updateAndDeleteLockTheRow() {
        service.update("e1", "owner", new com.classroom.modules.event.dto.UpdateEventRequest());
        service.delete("e1", "owner");

        verify(eventRepository, times(2)).findByIdForUpdate("e1");
        verify(eventRepository, never()).findById(anyString());
    }

    @Test
    @DisplayName("unregister() reads the event only through findByIdForUpdate")
    void unregisterLocksTheRow() {
        when(registrationRepository.findByEventIdAndUserId("e1", "u1")).thenReturn(Optional.empty());
        assertNotNull(service.unregister("e1", "u1")); // no registration -> the visibility check runs, then the event is returned
        verify(eventRepository).findByIdForUpdate("e1");
        verify(eventRepository, never()).findById(anyString());
        verify(accessPolicy).requireVisibleClass("c1", "u1", EventService.EVENT_NOT_FOUND);
    }
}
