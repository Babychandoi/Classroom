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
import io.minio.MinioClient;
import io.minio.StatObjectResponse;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * R3-05: after the staging object passes the pre-copy size check, completeUpload copies it to its
 * final key and must re-validate the FINAL object's size before trusting it - closing the race
 * where the staging bytes are swapped for an oversized object between the pre-copy stat and the
 * copy itself.
 */
@ExtendWith(MockitoExtension.class)
public class MediaServiceTest {

    @Mock private MinioClient minioClient;
    @Mock private MinioClient presigningClient;
    @Mock private MediaAssetRepository mediaAssetRepository;
    @Mock private AccessPolicy accessPolicy;
    @Mock private LessonRepository lessonRepository;
    @Mock private CourseRepository courseRepository;
    @Mock private LearningPolicy learningPolicy;
    @Mock private DocumentAssetRepository documentAssetRepository;
    @Mock private ProPolicy proPolicy;
    @Mock private EntitlementRepository entitlementRepository;

    private MinioProperties minioProperties;
    private MediaService mediaService;

    @BeforeEach
    void setUp() {
        minioProperties = new MinioProperties();
        minioProperties.setBucket("classroom-media");
        mediaService = new MediaService(minioClient, presigningClient, minioProperties, mediaAssetRepository,
                accessPolicy, lessonRepository, courseRepository, learningPolicy, documentAssetRepository,
                proPolicy, entitlementRepository);
    }

    private StatObjectResponse statOf(long size, String contentType) throws Exception {
        Headers headers = new Headers.Builder()
                .add("Content-Length", String.valueOf(size))
                .add("Content-Type", contentType)
                .add("Last-Modified", "Mon, 01 Jan 2024 00:00:00 GMT")
                .add("ETag", "\"etag\"")
                .build();
        return new StatObjectResponse(headers, "classroom-media", null, "object-key");
    }

    @Test
    @DisplayName("R3-05: completeUpload re-checks size on the FINAL object after copy and rejects an oversized result")
    void completeUploadRejectsWhenFinalObjectExceedsSizeAfterCopy() throws Exception {
        MediaAsset asset = new MediaAsset("class-1", "uploader-1", "classes/class-1/media/final.bin",
                "final.bin", "text/plain", 100L);
        asset.setId("asset-1");
        asset.setStatus("PENDING");

        when(mediaAssetRepository.findByIdForUpdate("asset-1")).thenReturn(Optional.of(asset));
        when(accessPolicy.canManage(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(true);

        // Pre-copy stat on the staging object: within limits, matches registered metadata.
        StatObjectResponse stagingStat = statOf(100L, "text/plain");
        when(minioClient.statObject(argThat(a -> a != null && a.object().equals(asset.getUploadObjectKey()))))
                .thenReturn(stagingStat);
        // Pre-copy probe of the (not yet existing) final object key fails -> triggers copy path.
        when(minioClient.statObject(argThat(a -> a != null && a.object().equals(asset.getObjectKey()))))
                .thenThrow(new RuntimeException("not found"))
                // After copyObject() runs, the FINAL object is re-stat'd here and found oversized.
                .thenReturn(statOf(600L * 1024 * 1024, "text/plain"));

        AppException ex = assertThrows(AppException.class,
                () -> mediaService.completeUpload("asset-1", "uploader-1"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());

        // The oversized FINAL object (created by this call) must be removed; nothing says
        // staging must also be removed here, so only assert the final key was targeted.
        verify(minioClient).removeObject(argThat(a -> a != null && a.object().equals(asset.getObjectKey())));
    }

    @Test
    @DisplayName("R7-06: uploader of an unattached scoped LESSON draft can download it via COURSE:EDIT scoped to that course")
    void uploaderOfScopedLessonDraftCanDownloadViaScopedCourseEdit() {
        MediaAsset asset = new MediaAsset("class-1", "uploader-1", "classes/class-1/media/draft.mp4",
                "draft.mp4", "video/mp4", 100L);
        asset.setId("asset-1");
        asset.setStatus("UPLOADED");
        asset.setUploadPurpose("LESSON");
        asset.setScopeCourseId("course-A");

        when(mediaAssetRepository.findById("asset-1")).thenReturn(Optional.of(asset));
        when(documentAssetRepository.findByMediaAssetId("asset-1")).thenReturn(java.util.List.of());
        when(lessonRepository.findByMediaAssetId("asset-1")).thenReturn(java.util.List.of());
        when(accessPolicy.isOwner("uploader-1", "class-1")).thenReturn(false);
        // No class-wide authoring rights at all — only a grant scoped to the asset's own course.
        when(accessPolicy.canManage("uploader-1", "class-1", "MEDIA", "CREATE", null)).thenReturn(false);
        when(accessPolicy.canManage("uploader-1", "class-1", "DOCUMENT", "CREATE", null)).thenReturn(false);
        when(accessPolicy.canManage("uploader-1", "class-1", "COURSE", "CREATE", null)).thenReturn(false);
        when(accessPolicy.canManage("uploader-1", "class-1", "COURSE", "EDIT", "course-A")).thenReturn(true);

        var response = mediaService.generateAuthorizedDownloadUrl("asset-1", "uploader-1");

        assertNotNull(response);
        verify(accessPolicy).enforceMember("uploader-1", "class-1");
    }

    @Test
    @DisplayName("R7-06: a different member cannot download another uploader's scoped LESSON draft via their own course grant")
    void otherMemberCannotDownloadSomeoneElsesScopedLessonDraft() {
        MediaAsset asset = new MediaAsset("class-1", "uploader-1", "classes/class-1/media/draft.mp4",
                "draft.mp4", "video/mp4", 100L);
        asset.setId("asset-1");
        asset.setStatus("UPLOADED");
        asset.setUploadPurpose("LESSON");
        asset.setScopeCourseId("course-A");

        when(mediaAssetRepository.findById("asset-1")).thenReturn(Optional.of(asset));
        when(documentAssetRepository.findByMediaAssetId("asset-1")).thenReturn(java.util.List.of());
        when(lessonRepository.findByMediaAssetId("asset-1")).thenReturn(java.util.List.of());
        when(accessPolicy.isOwner("other-staff", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("other-staff", "class-1", "MEDIA", "CREATE", null)).thenReturn(false);
        when(accessPolicy.canManage("other-staff", "class-1", "DOCUMENT", "CREATE", null)).thenReturn(false);
        when(accessPolicy.canManage("other-staff", "class-1", "COURSE", "CREATE", null)).thenReturn(false);
        when(accessPolicy.canManage("other-staff", "class-1", "COURSE", "EDIT", "course-A")).thenReturn(true);

        // Even though other-staff holds the scoped grant, they are not the uploader, so this must
        // still be denied — the R7-06 fix only widens what the UPLOADER can use, not who counts.
        AppException ex = assertThrows(AppException.class,
                () -> mediaService.generateAuthorizedDownloadUrl("asset-1", "other-staff"));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }
}
