package com.classroom.modules.media.controller;

import com.classroom.common.AppException;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.media.dto.UploadIntentRequest;
import com.classroom.modules.media.dto.UploadIntentResponse;
import com.classroom.modules.media.service.MediaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Uploading the file is a required step of the authoring action it belongs to, so a staff member
 * granted that authoring permission must be able to perform the upload. These tests pin the
 * mapping from upload purpose to the permission actually enforced.
 */
@ExtendWith(MockitoExtension.class)
public class MediaUploadIntentAuthorizationTest {

    @Mock
    private MediaService mediaService;
    @Mock
    private AccessPolicy accessPolicy;
    @Mock
    private CourseRepository courseRepository;

    @InjectMocks
    private MediaController mediaController;

    private UserPrincipal staff;

    @BeforeEach
    void setUp() {
        staff = new UserPrincipal("staff-1", "staff@example.com", "hashed", "Staff One", "STAFF", "ACTIVE");
        lenient().when(mediaService.createUploadIntent(anyString(), anyString(), any()))
                .thenReturn(new UploadIntentResponse("asset-1", "http://minio/upload", "objects/asset-1", "PUT"));
    }

    private UploadIntentRequest request(String purpose, String scopeCourseId) {
        UploadIntentRequest request = new UploadIntentRequest();
        request.setFilename("tai-lieu.pdf");
        request.setMimeType("application/pdf");
        request.setSizeBytes(1024);
        request.setPurpose(purpose);
        request.setScopeCourseId(scopeCourseId);
        return request;
    }

    @Test
    @DisplayName("DOCUMENT:CREATE alone authorizes the document's own upload, without a MEDIA:CREATE grant")
    void testDocumentCreateAuthorizesDocumentUpload() {
        when(accessPolicy.canManage("staff-1", "class-1", "DOCUMENT", "CREATE", null)).thenReturn(true);

        mediaController.createUploadIntent("class-1", staff, request("DOCUMENT", null));

        verify(mediaService).createUploadIntent(eq("class-1"), eq("staff-1"), any());
        verify(accessPolicy, never()).enforceManage(anyString(), anyString(), eq("MEDIA"), anyString(), any());
    }

    @Test
    @DisplayName("A course-scoped COURSE:EDIT grant authorizes the lesson upload for that course")
    void testCourseEditAuthorizesLessonUpload() {
        Course course = new Course("class-1", "Khóa học", "FREE");
        course.setId("course-1");
        when(courseRepository.findById("course-1")).thenReturn(Optional.of(course));
        when(accessPolicy.canManage("staff-1", "class-1", "COURSE", "EDIT", "course-1")).thenReturn(true);

        mediaController.createUploadIntent("class-1", staff, request("LESSON", "course-1"));

        verify(mediaService).createUploadIntent(eq("class-1"), eq("staff-1"), any());
    }

    @Test
    @DisplayName("An upload whose purpose grants nothing is still rejected")
    void testUnauthorizedDocumentUploadRejected() {
        when(accessPolicy.canManage("staff-1", "class-1", "DOCUMENT", "CREATE", null)).thenReturn(false);
        when(accessPolicy.canManage("staff-1", "class-1", "MEDIA", "CREATE", null)).thenReturn(false);
        doThrow(new AppException(com.classroom.common.ErrorCode.STAFF_PERMISSION_DENIED, "denied"))
                .when(accessPolicy).enforceManage("staff-1", "class-1", "DOCUMENT", "CREATE", null);

        assertThrows(AppException.class,
                () -> mediaController.createUploadIntent("class-1", staff, request("DOCUMENT", null)));
        verify(mediaService, never()).createUploadIntent(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("An upload with no purpose still requires the generic MEDIA:CREATE permission")
    void testUnknownPurposeFallsBackToMediaCreate() {
        mediaController.createUploadIntent("class-1", staff, request(null, null));

        verify(accessPolicy).enforceManage("staff-1", "class-1", "MEDIA", "CREATE", null);
    }

    @Test
    @DisplayName("A course scope from another class is rejected so a scoped grant cannot be misapplied")
    void testForeignCourseScopeRejected() {
        Course foreign = new Course("class-2", "Khóa học lớp khác", "FREE");
        foreign.setId("course-2");
        when(courseRepository.findById("course-2")).thenReturn(Optional.of(foreign));

        assertThrows(AppException.class,
                () -> mediaController.createUploadIntent("class-1", staff, request("LESSON", "course-2")));
        verify(mediaService, never()).createUploadIntent(anyString(), anyString(), any());
    }
}
