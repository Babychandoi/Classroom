package com.classroom.modules.classroom.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.dto.AboutSectionDto;
import com.classroom.modules.classroom.dto.UpdateClassAboutRequest;
import com.classroom.modules.classroom.model.ClassAbout;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.ClassAboutRepository;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ClassAboutSectionsTest {
    final ClassAboutRepository repository = mock(ClassAboutRepository.class);
    final ClassroomRepository classrooms = mock(ClassroomRepository.class);
    final AccessPolicy policy = mock(AccessPolicy.class);
    final ClassAboutService service = new ClassAboutService(repository, policy, classrooms);
    final ClassAbout about = new ClassAbout("class", "Existing intro", "Existing rules");
    @BeforeEach void setup() {
        when(classrooms.findByIdForUpdate("class")).thenReturn(Optional.of(new Classroom()));
        when(repository.findByClassId("class")).thenReturn(Optional.of(about));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    }
    @Test void orderedSectionsRoundTripAndLegacyUpdatesPreserveThem() {
        var sections = List.of(new AboutSectionDto("Mục tiêu", "Nội dung", "https://example.com/photo.png", "Ảnh lớp"),
                new AboutSectionDto("Giảng viên", "Giới thiệu", "", ""));
        service.updateAbout("class", new UpdateClassAboutRequest(null, null, sections, 1), "owner");
        assertEquals(sections, service.toDto(about).sections());
        service.updateAbout("class", "New intro", "New rules", "owner");
        assertEquals(sections, service.toDto(about).sections());
        assertEquals(3, about.getPublishedVersion());
    }
    @Test void staleEditorCannotOverwriteNewerVersion() {
        about.setPublishedVersion(3);
        var error = assertThrows(AppException.class, () -> service.updateAbout("class", new UpdateClassAboutRequest("overwrite", null, List.of(), 2), "owner"));
        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
        assertEquals("Existing intro", about.getContentMarkdown());
        verify(repository, never()).save(any());
    }
    @Test void unsafeImageOrMissingDescriptionIsRejected() {
        for (var section : List.of(new AboutSectionDto("x", "x", "javascript:alert(1)", "x"), new AboutSectionDto("x", "x", "https://example.com/a", ""))) {
            assertThrows(AppException.class, () -> service.updateAbout("class", new UpdateClassAboutRequest(null, null, List.of(section), 1), "owner"));
        }
    }
    @Test void deniedAuthorCannotReachPersistence() {
        doThrow(new AppException(ErrorCode.FORBIDDEN, "Denied")).when(policy).enforceManage("outsider", "class", "ABOUT", "EDIT", null);
        assertThrows(AppException.class, () -> service.updateAbout("class", "x", "y", "outsider"));
        verify(classrooms, never()).findByIdForUpdate(any());
    }
}
