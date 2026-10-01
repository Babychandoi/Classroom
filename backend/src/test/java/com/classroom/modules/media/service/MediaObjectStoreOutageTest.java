package com.classroom.modules.media.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.config.MinioProperties;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.commerce.policy.ProPolicy;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import com.classroom.modules.community.repository.DocumentAssetRepository;
import com.classroom.modules.learning.policy.LearningPolicy;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.learning.repository.LessonRepository;
import com.classroom.modules.media.model.MediaAsset;
import com.classroom.modules.media.repository.MediaAssetRepository;
import io.minio.CopyObjectArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.InternalException;
import io.minio.errors.ServerException;
import io.minio.messages.ErrorResponse;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R20-12: with MinIO unreachable, upload completion / download used to answer 400 "file not uploaded" (after 5 s), or a bare 500.
 * An unreachable or failing object store is a transient condition: 503 SERVICE_UNAVAILABLE with a Retry-After hint. A definite
 * "object missing" answer keeps its 400.
 */
@ExtendWith(MockitoExtension.class)
class MediaObjectStoreOutageTest {

    @Mock private MinioClient minioClient;
    @Mock private MinioClient presigningClient;
    @Mock private MinioProperties minioProperties;
    @Mock private MediaAssetRepository mediaAssetRepository;
    @Mock private AccessPolicy accessPolicy;
    @Mock private LessonRepository lessonRepository;
    @Mock private CourseRepository courseRepository;
    @Mock private LearningPolicy learningPolicy;
    @Mock private DocumentAssetRepository documentAssetRepository;
    @Mock private ProPolicy proPolicy;
    @Mock private EntitlementRepository entitlementRepository;

    private MediaService mediaService;
    private MediaAsset asset;

    @BeforeEach
    void setUp() {
        mediaService = new MediaService(minioClient, presigningClient, minioProperties, mediaAssetRepository,
                accessPolicy, lessonRepository, courseRepository, learningPolicy, documentAssetRepository,
                proPolicy, entitlementRepository);
        asset = new MediaAsset("class-1", "uploader-1", "classes/class-1/media/test.pdf", "test.pdf", "application/pdf", 1024L);
        asset.setId("asset-1");
        asset.setStatus("PENDING");
        lenient().when(minioProperties.getBucket()).thenReturn("classroom-media");
        lenient().when(mediaAssetRepository.findByIdForUpdate("asset-1")).thenReturn(Optional.of(asset));
        lenient().when(mediaAssetRepository.findById("asset-1")).thenReturn(Optional.of(asset));
        lenient().when(accessPolicy.canManage("uploader-1", "class-1", "MEDIA", "CREATE", null)).thenReturn(true);
    }

    private static Response httpResponse(int code) {
        return new Response.Builder()
                .request(new Request.Builder().url("http://minio:9000/classroom-media/x").build())
                .protocol(Protocol.HTTP_1_1).code(code).message("status").build();
    }

    private static void assertRetryable503(AppException ex) {
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, ex.getErrorCode());
        assertEquals(503, ex.getErrorCode().getHttpStatus().value());
        assertEquals(ObjectStoreFailure.RETRY_AFTER_SECONDS, ex.getRetryAfterSeconds());
        assertTrue(ex.getMessage().contains("thử lại"), ex.getMessage());
    }

    @Test
    @DisplayName("classification: connectivity, timeout, I/O and 5xx failures are 'unavailable'; NoSuchKey / plain runtime errors are not")
    void classification() {
        assertTrue(ObjectStoreFailure.isUnavailable(new ConnectException("Connection refused")));
        assertTrue(ObjectStoreFailure.isUnavailable(new UnknownHostException("minio")));
        assertTrue(ObjectStoreFailure.isUnavailable(new SocketTimeoutException("timeout")));
        assertTrue(ObjectStoreFailure.isUnavailable(new IOException("unexpected end of stream")));
        assertTrue(ObjectStoreFailure.isUnavailable(new ServerException("server failed", 503, "")));
        assertTrue(ObjectStoreFailure.isUnavailable(new InternalException("boom", "")));
        assertTrue(ObjectStoreFailure.isUnavailable(new RuntimeException("wrapped", new UnknownHostException("minio"))),
                "found through the cause chain");
        assertTrue(ObjectStoreFailure.isUnavailable(new ErrorResponseException(new ErrorResponse(), httpResponse(503), "")));

        assertFalse(ObjectStoreFailure.isUnavailable(new ErrorResponseException(new ErrorResponse(), httpResponse(404), "")),
                "a definite 404 is not an outage");
        assertFalse(ObjectStoreFailure.isUnavailable(new RuntimeException("Object not found")));
        assertFalse(ObjectStoreFailure.isUnavailable(new AppException(ErrorCode.BAD_REQUEST, "x")));
    }

    @Test
    @DisplayName("complete with MinIO unreachable: 503 + Retry-After (not 400 'file not uploaded'), and the claim is released for the retry")
    void completeWhileMinioIsDownIs503() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(new UnknownHostException("minio"));

        AppException ex = assertThrows(AppException.class, () -> mediaService.completeUpload("asset-1", "uploader-1"));

        assertRetryable503(ex);
        assertEquals("PENDING", asset.getStatus(), "the client can simply retry complete");
        verify(mediaAssetRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("complete: a connection timeout on the fallback (final-key) stat is also a 503")
    void completeFinalKeyStatOutage() throws Exception {
        ErrorResponseException noSuchKey = new ErrorResponseException(new ErrorResponse(), httpResponse(404), "");
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenThrow(noSuchKey)                                  // staging object is really missing
                .thenThrow(new SocketTimeoutException("read timed out")); // ... and MinIO dies before the final-key lookup

        AppException ex = assertThrows(AppException.class, () -> mediaService.completeUpload("asset-1", "uploader-1"));

        assertRetryable503(ex);
    }

    @Test
    @DisplayName("complete: a genuinely missing object (definite 404 from MinIO) keeps answering 400 'not uploaded'")
    void completeWithMissingObjectStaysBadRequest() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenThrow(new ErrorResponseException(new ErrorResponse(), httpResponse(404), ""));

        AppException ex = assertThrows(AppException.class, () -> mediaService.completeUpload("asset-1", "uploader-1"));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertEquals("PENDING", asset.getStatus());
    }

    @Test
    @DisplayName("complete: MinIO dying between the stat and the server-side copy is a 503, not a 500")
    void completeCopyOutage() throws Exception {
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(1024L);
        when(stat.contentType()).thenReturn("application/pdf");
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenReturn(stat)                                                             // staging object is there
                .thenThrow(new ErrorResponseException(new ErrorResponse(), httpResponse(404), "")); // no final object yet
        when(minioClient.copyObject(any(CopyObjectArgs.class))).thenThrow(new ConnectException("Connection refused"));

        AppException ex = assertThrows(AppException.class, () -> mediaService.completeUpload("asset-1", "uploader-1"));

        assertRetryable503(ex);
        assertEquals("PENDING", asset.getStatus());
    }

    @Test
    @DisplayName("complete: an I/O error while reading the magic bytes is a 503 and never deletes the promoted object")
    void completeMagicByteOutage() throws Exception {
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(1024L);
        when(stat.contentType()).thenReturn("application/pdf");
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(stat);
        when(minioClient.getObject(any(GetObjectArgs.class))).thenThrow(new ConnectException("Connection refused"));

        AppException ex = assertThrows(AppException.class, () -> mediaService.completeUpload("asset-1", "uploader-1"));

        assertRetryable503(ex);
        verify(minioClient, never()).removeObject(any());
    }

    @Test
    @DisplayName("proxy download: failing to open the object is a 503 (used to be a bare 500)")
    void openDownloadOutage() throws Exception {
        asset.setStatus("UPLOADED");
        MediaService spy = org.mockito.Mockito.spy(mediaService);
        org.mockito.Mockito.doReturn(new com.classroom.modules.media.dto.DownloadUrlResponse("asset-1", "http://x", null))
                .when(spy).generateAuthorizedDownloadUrl("asset-1", "user-1");
        when(minioClient.getObject(any(GetObjectArgs.class))).thenThrow(new UnknownHostException("minio"));

        AppException ex = assertThrows(AppException.class, () -> spy.openAuthorizedDownload("asset-1", "user-1", null, null));

        assertRetryable503(ex);
    }

    @Test
    @DisplayName("proxy download: a non-connectivity failure stays a 500")
    void openDownloadOtherFailureStays500() throws Exception {
        asset.setStatus("UPLOADED");
        MediaService spy = org.mockito.Mockito.spy(mediaService);
        org.mockito.Mockito.doReturn(new com.classroom.modules.media.dto.DownloadUrlResponse("asset-1", "http://x", null))
                .when(spy).generateAuthorizedDownloadUrl("asset-1", "user-1");
        when(minioClient.getObject(any(GetObjectArgs.class))).thenThrow(new IllegalStateException("bug"));

        AppException ex = assertThrows(AppException.class, () -> spy.openAuthorizedDownload("asset-1", "user-1", null, null));

        assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, ex.getErrorCode());
    }

    @Test
    @DisplayName("a healthy object store is unaffected (control)")
    void healthyCompletionStillWorks() throws Exception {
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(1024L);
        when(stat.contentType()).thenReturn("application/pdf");
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(stat);
        when(minioClient.getObject(any(GetObjectArgs.class))).thenAnswer(inv ->
                new GetObjectResponse(new okhttp3.Headers.Builder().build(), "", "", "", new ByteArrayInputStream("%PDF-1.4".getBytes())));
        when(mediaAssetRepository.saveAndFlush(any(MediaAsset.class))).thenAnswer(i -> i.getArgument(0));

        assertEquals("UPLOADED", mediaService.completeUpload("asset-1", "uploader-1").getStatus());
    }
}
