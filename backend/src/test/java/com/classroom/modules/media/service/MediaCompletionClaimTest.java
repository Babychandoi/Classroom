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
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R20-06: {@code MediaService#completeUpload} holds neither a database connection nor a row lock across MinIO I/O.
 *
 * <p>These tests run the service without Spring, so the two short transactions are just method calls; what they pin is the
 * ORDER (claim -> object-store work -> publish), the state the asset is in while the I/O runs, the recovery on failure, and how a
 * duplicate request behaves. (Under Spring, {@code completeUpload} itself is not transactional, so the claim's connection is back in
 * the pool before the first MinIO call.)</p>
 */
@ExtendWith(MockitoExtension.class)
class MediaCompletionClaimTest {

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
        lenient().when(accessPolicy.canManage("uploader-1", "class-1", "MEDIA", "CREATE", null)).thenReturn(true);
        lenient().when(mediaAssetRepository.saveAndFlush(any(MediaAsset.class))).thenAnswer(i -> i.getArgument(0));
    }

    private void healthyPdfInMinio() throws Exception {
        StatObjectResponse stat = mock(StatObjectResponse.class);
        lenient().when(stat.size()).thenReturn(1024L);
        lenient().when(stat.contentType()).thenReturn("application/pdf");
        lenient().when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(stat);
        lenient().when(minioClient.getObject(any(io.minio.GetObjectArgs.class))).thenAnswer(inv ->
                new GetObjectResponse(new okhttp3.Headers.Builder().build(), "", "", "", new ByteArrayInputStream("%PDF-1.4".getBytes())));
    }

    @Test
    @DisplayName("the asset is UPLOADING (claimed) while MinIO is being read, and the row lock is taken twice: to claim, then to publish - never across the I/O")
    void claimsThenDoesIoThenPublishes() throws Exception {
        healthyPdfInMinio();
        List<String> statusDuringIo = new ArrayList<>();
        when(minioClient.statObject(any(StatObjectArgs.class))).thenAnswer(inv -> {
            statusDuringIo.add(asset.getStatus());
            StatObjectResponse stat = mock(StatObjectResponse.class);
            when(stat.size()).thenReturn(1024L);
            when(stat.contentType()).thenReturn("application/pdf");
            return stat;
        });

        MediaAsset result = mediaService.completeUpload("asset-1", "uploader-1");

        assertEquals("UPLOADED", result.getStatus());
        assertNull(result.getCompletionClaimedAt(), "the lease is cleared once published");
        assertTrue(statusDuringIo.stream().allMatch("UPLOADING"::equals), "MinIO was read while the asset was " + statusDuringIo);
        InOrder order = inOrder(mediaAssetRepository, minioClient);
        order.verify(mediaAssetRepository).findByIdForUpdate("asset-1");          // claim (short transaction)
        order.verify(minioClient, org.mockito.Mockito.atLeastOnce()).statObject(any(StatObjectArgs.class)); // object-store work, no lock held
        order.verify(mediaAssetRepository).findByIdForUpdate("asset-1");          // publish (short transaction)
        order.verify(mediaAssetRepository).saveAndFlush(asset);
    }

    @Test
    @DisplayName("a failed validation gives the claim back: PENDING again, so the client can retry")
    void failureReleasesTheClaim() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(new RuntimeException("Object not found"));

        AppException ex = assertThrows(AppException.class, () -> mediaService.completeUpload("asset-1", "uploader-1"));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertEquals("PENDING", asset.getStatus());
        assertNull(asset.getCompletionClaimedAt());
        verify(mediaAssetRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a duplicate request waits for the request that owns a live claim, then returns the finished upload without touching MinIO")
    void duplicateRequestWaitsForTheOwnerOfTheClaim() throws Exception {
        asset.setStatus("UPLOADING");
        asset.setCompletionClaimedAt(Instant.now());
        // the owner finishes while we are waiting: the next lookup sees UPLOADED
        when(mediaAssetRepository.findByIdForUpdate("asset-1")).thenAnswer(new org.mockito.stubbing.Answer<Optional<MediaAsset>>() {
            private int calls;
            @Override
            public Optional<MediaAsset> answer(org.mockito.invocation.InvocationOnMock inv) {
                if (++calls >= 2) {
                    asset.setStatus("UPLOADED");
                    asset.setCompletionClaimedAt(null);
                }
                return Optional.of(asset);
            }
        });

        MediaAsset result = mediaService.completeUpload("asset-1", "uploader-1");

        assertEquals("UPLOADED", result.getStatus());
        verify(minioClient, never()).statObject(any(StatObjectArgs.class));
        verify(mediaAssetRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a claim that never finishes answers 409 after the wait instead of repeating the I/O")
    void stuckClaimEndsInConflict() throws Exception {
        asset.setStatus("UPLOADING");
        asset.setCompletionClaimedAt(Instant.now());
        mediaService.setCompletionWaitMillis(400);

        AppException ex = assertThrows(AppException.class, () -> mediaService.completeUpload("asset-1", "uploader-1"));

        assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
        verify(minioClient, never()).statObject(any(StatObjectArgs.class));
    }

    @Test
    @DisplayName("an expired claim (the request that owned it died) is taken over and completed")
    void expiredClaimIsTakenOver() throws Exception {
        healthyPdfInMinio();
        asset.setStatus("UPLOADING");
        asset.setCompletionClaimedAt(Instant.now().minusSeconds(MediaService.COMPLETION_LEASE_SECONDS + 30));

        MediaAsset result = mediaService.completeUpload("asset-1", "uploader-1");

        assertEquals("UPLOADED", result.getStatus());
        verify(mediaAssetRepository).saveAndFlush(asset);
    }

    @Test
    @DisplayName("an unauthorised caller neither claims the asset nor reaches MinIO")
    void unauthorisedCallerCannotClaim() throws Exception {
        assertThrows(AppException.class, () -> mediaService.completeUpload("asset-1", "someone-else"));

        assertEquals("PENDING", asset.getStatus());
        assertNull(asset.getCompletionClaimedAt());
        verify(mediaAssetRepository, never()).save(any());
        verify(minioClient, never()).statObject(any(StatObjectArgs.class));
    }
}
