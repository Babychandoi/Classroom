package com.classroom.modules.media.controller;

import com.classroom.config.UserPrincipal;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.media.dto.DownloadUrlResponse;
import com.classroom.modules.media.model.MediaAsset;
import com.classroom.modules.media.service.MediaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * R8-01: the legacy proxy download route (/media/{id}/download) must no longer silently truncate
 * large files and must support HTTP Range requests the same way a presigned MinIO URL would.
 * These tests exercise MediaController#download directly (no MockMvc/HTTP layer), the same way
 * MediaUploadIntentAuthorizationTest exercises createUploadIntent.
 */
@ExtendWith(MockitoExtension.class)
public class MediaDownloadRangeTest {

    @Mock
    private MediaService mediaService;
    @Mock
    private AccessPolicy accessPolicy;
    @Mock
    private CourseRepository courseRepository;

    @InjectMocks
    private MediaController mediaController;

    private UserPrincipal user;
    private MediaAsset asset;

    @BeforeEach
    void setUp() {
        user = new UserPrincipal("student-1", "student@example.com", "hashed", "Student One", "USER", "ACTIVE");
        asset = new MediaAsset("class-1", "uploader-1", "classes/class-1/media/video.mp4", "video.mp4", "video/mp4", 1000L);
        asset.setId("asset-1");
        asset.setStatus("UPLOADED");
        lenient().when(mediaService.generateAuthorizedDownloadUrl("asset-1", "student-1"))
                .thenReturn(new DownloadUrlResponse("asset-1", "http://localhost:9000/signed", null));
        lenient().when(mediaService.getAsset("asset-1")).thenReturn(asset);
    }

    @Test
    @DisplayName("No Range header: streams the full object with 200 and Accept-Ranges: bytes")
    void fullDownloadReturns200WithAcceptRanges() throws Exception {
        byte[] payload = "0123456789".getBytes();
        when(mediaService.openAuthorizedDownload(eq("asset-1"), eq("student-1"), isNull(), isNull()))
                .thenReturn(fakeGetObjectResponse(payload));

        ResponseEntity<StreamingResponseBody> response = mediaController.download("asset-1", null, user);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("bytes", response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES));
        assertEquals("1000", response.getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH));
        assertEquals(streamed(response), new String(payload));
    }

    @Test
    @DisplayName("R8-01: a mid-range Range request returns 206 with the correct Content-Range and only the requested bytes")
    void partialRangeReturns206WithCorrectSlice() throws Exception {
        byte[] slice = "234".getBytes(); // bytes 2-4 of a 1000-byte object
        when(mediaService.openAuthorizedDownload(eq("asset-1"), eq("student-1"), eq(2L), eq(3L)))
                .thenReturn(fakeGetObjectResponse(slice));

        ResponseEntity<StreamingResponseBody> response = mediaController.download("asset-1", "bytes=2-4", user);

        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        assertEquals("bytes 2-4/1000", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        assertEquals("3", response.getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH));
        assertEquals("bytes", response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES));
        assertEquals(streamed(response), new String(slice));
    }

    @Test
    @DisplayName("R8-01: an open-ended Range request (bytes=500-) returns 206 through the end of the object")
    void openEndedRangeReturnsRemainderOfObject() throws Exception {
        when(mediaService.openAuthorizedDownload(eq("asset-1"), eq("student-1"), eq(500L), eq(500L)))
                .thenReturn(fakeGetObjectResponse(new byte[500]));

        ResponseEntity<StreamingResponseBody> response = mediaController.download("asset-1", "bytes=500-", user);

        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        assertEquals("bytes 500-999/1000", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        assertEquals("500", response.getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH));
        assertEquals(500, streamed(response).length());
    }

    @Test
    @DisplayName("R8-01: a suffix Range request (bytes=-100) returns the last 100 bytes")
    void suffixRangeReturnsLastBytes() throws Exception {
        when(mediaService.openAuthorizedDownload(eq("asset-1"), eq("student-1"), eq(900L), eq(100L)))
                .thenReturn(fakeGetObjectResponse(new byte[100]));

        ResponseEntity<StreamingResponseBody> response = mediaController.download("asset-1", "bytes=-100", user);

        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        assertEquals("bytes 900-999/1000", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        assertEquals(100, streamed(response).length());
    }

    @Test
    @DisplayName("R8-01: an unsatisfiable Range request returns 416 with Content-Range reporting the real size")
    void unsatisfiableRangeReturns416() {
        ResponseEntity<StreamingResponseBody> response = mediaController.download("asset-1", "bytes=5000-6000", user);

        assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, response.getStatusCode());
        assertEquals("bytes */1000", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        verify(mediaService, never()).openAuthorizedDownload(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("R8-01: a malformed Range header is ignored as a full-object 416, never a 500 or silent truncation")
    void malformedRangeReturns416() {
        ResponseEntity<StreamingResponseBody> response = mediaController.download("asset-1", "not-a-range", user);

        assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, response.getStatusCode());
    }

    private static io.minio.GetObjectResponse fakeGetObjectResponse(byte[] payload) {
        return new io.minio.GetObjectResponse(new okhttp3.Headers.Builder().build(), "classroom-media",
                "us-east-1", "classes/class-1/media/video.mp4", new java.io.ByteArrayInputStream(payload));
    }

    private static String streamed(ResponseEntity<StreamingResponseBody> response) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);
        return out.toString();
    }

    @Test
    @DisplayName("R20-12: the object is opened before the response starts, so an unreachable object store is a 503 the exception handler can map (was an async 500 with an empty body)")
    void objectStoreOutageIsRaisedBeforeStreaming() throws Exception {
        when(mediaService.openAuthorizedDownload(eq("asset-1"), eq("student-1"), isNull(), isNull()))
                .thenThrow(com.classroom.modules.media.service.ObjectStoreFailure.unavailable());

        com.classroom.common.AppException ex = assertThrows(com.classroom.common.AppException.class,
                () -> mediaController.download("asset-1", null, user));

        assertEquals(com.classroom.common.ErrorCode.SERVICE_UNAVAILABLE, ex.getErrorCode());
        assertEquals(5, ex.getRetryAfterSeconds());
    }

    @Test
    @DisplayName("R20-12: the MinIO stream is closed once the body has been written")
    void streamIsClosedAfterTheBodyIsWritten() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        io.minio.GetObjectResponse tracked = new io.minio.GetObjectResponse(new okhttp3.Headers.Builder().build(), "classroom-media",
                "us-east-1", "k", new java.io.ByteArrayInputStream("abc".getBytes()) {
            @Override
            public void close() {
                closed.set(true);
            }
        });
        when(mediaService.openAuthorizedDownload(eq("asset-1"), eq("student-1"), isNull(), isNull())).thenReturn(tracked);

        ResponseEntity<StreamingResponseBody> response = mediaController.download("asset-1", null, user);
        assertFalse(closed.get(), "opened eagerly but not consumed yet");
        streamed(response);

        assertTrue(closed.get());
    }
}
