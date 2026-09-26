package com.classroom.modules.media.service;

import com.classroom.common.AppException;
import com.classroom.config.MinioProperties;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.commerce.model.Entitlement;
import com.classroom.modules.commerce.policy.ProPolicy;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import com.classroom.modules.community.model.DocumentAsset;
import com.classroom.modules.community.repository.DocumentAssetRepository;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.model.Lesson;
import com.classroom.modules.learning.policy.LearningPolicy;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.learning.repository.LessonRepository;
import com.classroom.modules.media.dto.DownloadUrlResponse;
import com.classroom.modules.media.model.MediaAsset;
import com.classroom.modules.media.repository.MediaAssetRepository;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class MediaSecurityTest {

    @Mock
    private MinioClient minioClient;
    @Mock
    private MinioClient minioPresigningClient;
    @Mock
    private MinioProperties minioProperties;
    @Mock
    private MediaAssetRepository mediaAssetRepository;
    @Mock
    private AccessPolicy accessPolicy;
    @Mock
    private LessonRepository lessonRepository;
    @Mock
    private CourseRepository courseRepository;
    @Mock
    private LearningPolicy learningPolicy;
    @Mock
    private DocumentAssetRepository documentAssetRepository;
    @Mock
    private ProPolicy proPolicy;
    @Mock
    private EntitlementRepository entitlementRepository;

    private MediaService mediaService;

    private MediaAsset asset;

    @BeforeEach
    void setUp() {
        mediaService = new MediaService(minioClient, minioPresigningClient, minioProperties, mediaAssetRepository,
                accessPolicy, lessonRepository, courseRepository, learningPolicy, documentAssetRepository,
                proPolicy, entitlementRepository);
        asset = new MediaAsset("class-1", "uploader-1", "classes/class-1/media/test.pdf", "test.pdf", "application/pdf", 1024L);
        asset.setId("asset-1");
        asset.setStatus("PENDING");
        lenient().when(mediaAssetRepository.findByIdForUpdate("asset-1")).thenReturn(Optional.of(asset));
        lenient().when(accessPolicy.canManage("uploader-1", "class-1", "MEDIA", "CREATE", null)).thenReturn(true);
    }

    @Test
    @DisplayName("Finding 2: completeUpload rejects non-uploader, non-owner, non-staff caller (IDOR guard)")
    void testCompleteUploadRejectsUnauthorizedCaller() {

        assertThrows(AppException.class, () -> mediaService.completeUpload("asset-1", "attacker-id"));
        assertEquals("PENDING", asset.getStatus());
        verify(mediaAssetRepository, never()).save(any());
    }

    @Test
    void revokedUploaderCannotCompletePendingUpload() throws Exception {
        asset.setUploadPurpose("DOCUMENT");
        lenient().when(accessPolicy.canManage("uploader-1", "class-1", "MEDIA", "CREATE", null)).thenReturn(false);
        assertEquals(com.classroom.common.ErrorCode.FORBIDDEN,
                assertThrows(AppException.class, () -> mediaService.completeUpload("asset-1", "uploader-1")).getErrorCode());
        verify(minioClient, never()).statObject(any());
    }

    @Test
    @DisplayName("Finding 2: completeUpload validates object in MinIO storage before marking complete")
    void testCompleteUploadRequiresMinioObject() throws Exception {
        when(minioProperties.getBucket()).thenReturn("classroom-media");
        // MinIO throws when object not uploaded
        when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(new RuntimeException("Object not found"));

        assertThrows(AppException.class, () -> mediaService.completeUpload("asset-1", "uploader-1"));
        assertEquals("PENDING", asset.getStatus());
    }

    @Test
    @DisplayName("Finding 2: completeUpload succeeds when caller is uploader and object exists in MinIO")
    void testCompleteUploadSuccess() throws Exception {
        when(minioProperties.getBucket()).thenReturn("classroom-media");
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(1024L);
        when(stat.contentType()).thenReturn("application/pdf");
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(stat);
        stubPdfBytes();
        when(mediaAssetRepository.saveAndFlush(any(MediaAsset.class))).thenAnswer(i -> i.getArgument(0));

        MediaAsset result = mediaService.completeUpload("asset-1", "uploader-1");
        assertEquals("UPLOADED", result.getStatus());
        verify(mediaAssetRepository).saveAndFlush(asset);
        verify(minioClient).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    @DisplayName("Retry completes metadata after promotion survived a failed database transaction")
    void retryCompletesAfterObjectPromotion() throws Exception {
        when(minioProperties.getBucket()).thenReturn("classroom-media");
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(1024L);
        when(stat.contentType()).thenReturn("application/pdf");
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(stat);
        stubPdfBytes();
        // Final object already exists from the previous attempt. Do not copy staging again.
        when(mediaAssetRepository.saveAndFlush(asset)).thenAnswer(invocation -> invocation.getArgument(0));

        MediaAsset result = mediaService.completeUpload("asset-1", "uploader-1");

        assertEquals("UPLOADED", result.getStatus());
        verify(minioClient, never()).copyObject(any());
        verify(minioClient).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    @DisplayName("Rollback recovery: retry completes when staging was deleted but the promoted object survives")
    void retryCompletesWhenStagingGoneAndFinalObjectPresent() throws Exception {
        // Reproduces a completion whose SQL transaction rolled back after the staging object had
        // already been removed: the row is still PENDING and staging is gone. The retry must
        // recover from the promoted final object instead of rejecting the upload forever.
        when(minioProperties.getBucket()).thenReturn("classroom-media");
        StatObjectResponse finalStat = mock(StatObjectResponse.class);
        when(finalStat.size()).thenReturn(1024L);
        when(finalStat.contentType()).thenReturn("application/pdf");
        when(minioClient.statObject(any(StatObjectArgs.class))).thenAnswer(invocation -> {
            StatObjectArgs args = invocation.getArgument(0);
            if (asset.getUploadObjectKey().equals(args.object())) {
                throw new RuntimeException("NoSuchKey: staging object already removed");
            }
            return finalStat;
        });
        stubPdfBytes();
        when(mediaAssetRepository.saveAndFlush(asset)).thenAnswer(invocation -> invocation.getArgument(0));

        MediaAsset result = mediaService.completeUpload("asset-1", "uploader-1");

        assertEquals("UPLOADED", result.getStatus());
        verify(minioClient, never()).copyObject(any());
        // Staging is already gone; nothing left to clean up.
        verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    @DisplayName("Rollback recovery: completion is still rejected when neither staging nor final object exists")
    void completeUploadRejectsWhenNoObjectExistsAtAll() throws Exception {
        when(minioProperties.getBucket()).thenReturn("classroom-media");
        when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(new RuntimeException("NoSuchKey"));

        assertThrows(AppException.class, () -> mediaService.completeUpload("asset-1", "uploader-1"));
        assertEquals("PENDING", asset.getStatus());
        verify(mediaAssetRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Finding 2: generateAuthorizedDownloadUrl denies download for non-purchased course media")
    void testDownloadDeniedForUnpurchasedCourse() {
        asset.setStatus("UPLOADED");
        when(mediaAssetRepository.findById("asset-1")).thenReturn(Optional.of(asset));
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage(eq("student-1"), eq("class-1"), anyString(), anyString(), nullable(String.class))).thenReturn(false);

        Lesson lesson = new Lesson("sec-1", "course-1", "Lesson 1", "VIDEO", 1);
        lesson.setMediaAssetId("asset-1");
        Course course = new Course("class-1", "Paid Course", "PURCHASE_REQUIRED");

        when(documentAssetRepository.findByMediaAssetId("asset-1")).thenReturn(List.of());
        when(lessonRepository.findByMediaAssetId("asset-1")).thenReturn(List.of(lesson));
        when(courseRepository.findById("course-1")).thenReturn(Optional.of(course));
        when(learningPolicy.canLearn("student-1", course)).thenReturn(false);

        assertThrows(AppException.class, () -> mediaService.generateAuthorizedDownloadUrl("asset-1", "student-1"));
    }

    @Test
    @DisplayName("Shared legacy document and lesson references fail closed")
    void testDownloadDeniedWhenAssetHasMultipleAudienceReferences() throws Exception {
        asset.setStatus("UPLOADED");
        when(mediaAssetRepository.findById("asset-1")).thenReturn(Optional.of(asset));
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);
        Lesson lesson = new Lesson("sec-1", "course-1", "Lesson 1", "VIDEO", 1);
        lesson.setMediaAssetId("asset-1");
        DocumentAsset document = new DocumentAsset("class-1", "Free copy", "", "asset-1", "FREE");
        when(documentAssetRepository.findByMediaAssetId("asset-1")).thenReturn(List.of(document));
        when(lessonRepository.findByMediaAssetId("asset-1")).thenReturn(List.of(lesson));

        assertThrows(AppException.class, () -> mediaService.generateAuthorizedDownloadUrl("asset-1", "student-1"));
        verify(minioPresigningClient, never()).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
    }

    @Test
    @DisplayName("Finding 2: generateAuthorizedDownloadUrl grants download when user has course access")
    void testDownloadGrantedForPurchasedCourse() throws Exception {
        asset.setStatus("UPLOADED");
        when(mediaAssetRepository.findById("asset-1")).thenReturn(Optional.of(asset));
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage(eq("student-1"), eq("class-1"), anyString(), anyString(), nullable(String.class))).thenReturn(false);

        Lesson lesson = new Lesson("sec-1", "course-1", "Lesson 1", "VIDEO", 1);
        lesson.setMediaAssetId("asset-1");
        Course course = new Course("class-1", "Paid Course", "PURCHASE_REQUIRED");

        when(documentAssetRepository.findByMediaAssetId("asset-1")).thenReturn(List.of());
        when(lessonRepository.findByMediaAssetId("asset-1")).thenReturn(List.of(lesson));
        when(courseRepository.findById("course-1")).thenReturn(Optional.of(course));
        when(learningPolicy.canLearn("student-1", course)).thenReturn(true);

        DownloadUrlResponse res = mediaService.generateAuthorizedDownloadUrl("asset-1", "student-1");
        assertNotNull(res);
        assertEquals("/api/v1/media/asset-1/download", res.getDownloadUrl());
        assertNull(res.getExpiresAt());
    }

    @Test
    @DisplayName("Finding 5: generateAuthorizedDownloadUrl denies download when user has wrong product for PRODUCT_OWNER doc")
    void testDownloadDeniedForWrongProductOwner() {
        asset.setStatus("UPLOADED");
        when(mediaAssetRepository.findById("asset-1")).thenReturn(Optional.of(asset));
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage(eq("student-1"), eq("class-1"), anyString(), anyString(), nullable(String.class))).thenReturn(false);

        DocumentAsset doc = new DocumentAsset("class-1", "Doc A", "Desc", "asset-1", "PRODUCT_OWNER", "prod-A", null);
        when(documentAssetRepository.findByMediaAssetId("asset-1")).thenReturn(List.of(doc));
        when(lessonRepository.findByMediaAssetId("asset-1")).thenReturn(List.of());

        // User has active entitlement for prod-B, NOT prod-A
        when(entitlementRepository.hasProductAccess(eq("student-1"), eq("class-1"), eq("prod-A"), any())).thenReturn(false);

        assertThrows(AppException.class, () -> mediaService.generateAuthorizedDownloadUrl("asset-1", "student-1"));
    }

    @Test
    @DisplayName("Finding 5: generateAuthorizedDownloadUrl grants download when user has matching product for PRODUCT_OWNER doc")
    void testDownloadGrantedForMatchingProductOwner() throws Exception {
        asset.setStatus("UPLOADED");
        when(mediaAssetRepository.findById("asset-1")).thenReturn(Optional.of(asset));
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage(eq("student-1"), eq("class-1"), anyString(), anyString(), nullable(String.class))).thenReturn(false);

        DocumentAsset doc = new DocumentAsset("class-1", "Doc A", "Desc", "asset-1", "PRODUCT_OWNER", "prod-A", null);
        when(documentAssetRepository.findByMediaAssetId("asset-1")).thenReturn(List.of(doc));
        when(entitlementRepository.hasProductAccess(eq("student-1"), eq("class-1"), eq("prod-A"), any())).thenReturn(true);

        DownloadUrlResponse res = mediaService.generateAuthorizedDownloadUrl("asset-1", "student-1");
        assertNotNull(res);
        assertEquals("/api/v1/media/asset-1/download", res.getDownloadUrl());
        assertNull(res.getExpiresAt());
    }

    @Test
    @DisplayName("Course-targeted document download checks its linked course product")
    void courseDocumentRequiresLinkedProductEntitlement() throws Exception {
        asset.setStatus("UPLOADED");
        when(mediaAssetRepository.findById("asset-1")).thenReturn(Optional.of(asset));
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage(eq("student-1"), eq("class-1"), anyString(), anyString(), nullable(String.class))).thenReturn(false);
        DocumentAsset doc = new DocumentAsset("class-1", "Course notes", "", "asset-1", "PRODUCT_OWNER", null, "course-A");
        when(documentAssetRepository.findByMediaAssetId("asset-1")).thenReturn(List.of(doc));
        when(lessonRepository.findByMediaAssetId("asset-1")).thenReturn(List.of());
        Course course = new Course("class-1", "Course A", "PURCHASE_REQUIRED");
        course.setId("course-A");
        course.setProductId("product-A");
        when(courseRepository.findById("course-A")).thenReturn(Optional.of(course));
        when(entitlementRepository.hasCourseAccess(eq("student-1"), eq("class-1"), eq("course-A"), eq("product-A"), any())).thenReturn(false);

        assertThrows(AppException.class, () -> mediaService.generateAuthorizedDownloadUrl("asset-1", "student-1"));
        verify(entitlementRepository).hasCourseAccess(eq("student-1"), eq("class-1"), eq("course-A"), eq("product-A"), any());
        verify(minioPresigningClient, never()).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
    }

    @Test
    @DisplayName("Finding 3: Original uploader cannot bypass access policy when asset is attached to restricted course")
    void testOriginalUploaderDeniedWhenResourceRestricted() {
        asset.setStatus("UPLOADED");
        // Uploader ID is "uploader-1"
        when(mediaAssetRepository.findById("asset-1")).thenReturn(Optional.of(asset));
        when(accessPolicy.isOwner("uploader-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage(eq("uploader-1"), eq("class-1"), anyString(), anyString(), nullable(String.class))).thenReturn(false);

        Lesson lesson = new Lesson("sec-1", "course-1", "Lesson 1", "VIDEO", 1);
        lesson.setMediaAssetId("asset-1");
        Course course = new Course("class-1", "Paid Course", "PURCHASE_REQUIRED");

        when(documentAssetRepository.findByMediaAssetId("asset-1")).thenReturn(List.of());
        when(lessonRepository.findByMediaAssetId("asset-1")).thenReturn(List.of(lesson));
        when(courseRepository.findById("course-1")).thenReturn(Optional.of(course));
        // Uploader does not have entitlement to the course
        when(learningPolicy.canLearn("uploader-1", course)).thenReturn(false);

        assertThrows(AppException.class, () -> mediaService.generateAuthorizedDownloadUrl("asset-1", "uploader-1"));
    }

    @Test
    @DisplayName("Finding 3: generateAuthorizedDownloadUrl rejects uncompleted (PENDING) uploads with BAD_REQUEST")
    void testDownloadRejectsPendingUpload() {
        asset.setStatus("PENDING");
        when(mediaAssetRepository.findById("asset-1")).thenReturn(Optional.of(asset));

        AppException ex = assertThrows(AppException.class, () ->
                mediaService.generateAuthorizedDownloadUrl("asset-1", "student-1")
        );
        assertEquals(com.classroom.common.ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("chưa hoàn tất"));
    }

    @Test
    @DisplayName("Finding 3: generateAuthorizedDownloadUrl rejects cross-class course references")
    void testDownloadRejectsCrossClassCourse() {
        asset.setStatus("UPLOADED");
        // Asset belongs to class-1
        when(mediaAssetRepository.findById("asset-1")).thenReturn(Optional.of(asset));
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);

        Lesson lesson = new Lesson("sec-1", "course-foreign", "Lesson 1", "VIDEO", 1);
        lesson.setMediaAssetId("asset-1");
        // Foreign course belongs to class-2
        Course foreignCourse = new Course("class-2", "Foreign Course", "FREE");

        when(documentAssetRepository.findByMediaAssetId("asset-1")).thenReturn(List.of());
        when(lessonRepository.findByMediaAssetId("asset-1")).thenReturn(List.of(lesson));
        when(courseRepository.findById("course-foreign")).thenReturn(Optional.of(foreignCourse));

        assertThrows(AppException.class, () ->
                mediaService.generateAuthorizedDownloadUrl("asset-1", "student-1")
        );
    }

    @Test
    @DisplayName("Finding 3: generateAuthorizedDownloadUrl rejects cross-class document references")
    void testDownloadRejectsCrossClassDocument() {
        asset.setStatus("UPLOADED");
        // Asset belongs to class-1
        when(mediaAssetRepository.findById("asset-1")).thenReturn(Optional.of(asset));
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);

        // Document belongs to class-2
        DocumentAsset foreignDoc = new DocumentAsset("class-2", "Doc Foreign", "Desc", "asset-1", "FREE", null, null);
        when(documentAssetRepository.findByMediaAssetId("asset-1")).thenReturn(List.of(foreignDoc));
        when(lessonRepository.findByMediaAssetId("asset-1")).thenReturn(List.of());

        assertThrows(AppException.class, () ->
                mediaService.generateAuthorizedDownloadUrl("asset-1", "student-1")
        );
    }

    @Test
    @DisplayName("Finding 2: generateAuthorizedDownloadUrl denies cross-user access to draft/unreferenced media")
    void testDownloadDeniedForOtherUserDraft() {
        asset.setStatus("UPLOADED");
        // Uploader is "uploader-1"
        when(mediaAssetRepository.findById("asset-1")).thenReturn(Optional.of(asset));
        when(accessPolicy.isOwner("other-staff", "class-1")).thenReturn(false);

        when(documentAssetRepository.findByMediaAssetId("asset-1")).thenReturn(List.of());
        when(lessonRepository.findByMediaAssetId("asset-1")).thenReturn(List.of());

        // other-staff may even have authoring rights in the class, but is NOT the uploader of this draft
        AppException ex = assertThrows(AppException.class, () ->
                mediaService.generateAuthorizedDownloadUrl("asset-1", "other-staff")
        );
        assertEquals(com.classroom.common.ErrorCode.FORBIDDEN, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("người dùng khác"));
    }

    @Test
    @DisplayName("Finding 2: generateAuthorizedDownloadUrl grants access to draft media for original uploader with authoring rights")
    void testDownloadGrantedForUploaderWithAuthoringRights() throws Exception {
        asset.setStatus("UPLOADED");
        when(mediaAssetRepository.findById("asset-1")).thenReturn(Optional.of(asset));
        when(accessPolicy.isOwner("uploader-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("uploader-1", "class-1", "MEDIA", "CREATE", null)).thenReturn(true);

        when(documentAssetRepository.findByMediaAssetId("asset-1")).thenReturn(List.of());
        when(lessonRepository.findByMediaAssetId("asset-1")).thenReturn(List.of());

        DownloadUrlResponse res = mediaService.generateAuthorizedDownloadUrl("asset-1", "uploader-1");
        assertNotNull(res);
        assertEquals("/api/v1/media/asset-1/download", res.getDownloadUrl());
        assertNull(res.getExpiresAt());
    }

    @Test
    @DisplayName("Finding 7: completeUpload rejects size mismatch between registered and actual MinIO object")
    void testCompleteUploadRejectsSizeMismatch() throws Exception {
        when(minioProperties.getBucket()).thenReturn("classroom-media");

        StatObjectResponse stat = mock(StatObjectResponse.class);
        // asset.getSizeBytes() is 1024L, but actual is 2048L
        when(stat.size()).thenReturn(2048L);
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(stat);

        AppException ex = assertThrows(AppException.class, () -> mediaService.completeUpload("asset-1", "uploader-1"));
        assertEquals(com.classroom.common.ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("Kích thước tệp"));
    }

    @Test
    @DisplayName("Finding 7: completeUpload rejects oversized object exceeding maximum allowed limit")
    void testCompleteUploadRejectsOversizedObject() throws Exception {
        when(minioProperties.getBucket()).thenReturn("classroom-media");

        StatObjectResponse stat = mock(StatObjectResponse.class);
        // Exceeds 500MB
        when(stat.size()).thenReturn(501L * 1024 * 1024);
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(stat);

        AppException ex = assertThrows(AppException.class, () -> mediaService.completeUpload("asset-1", "uploader-1"));
        assertEquals(com.classroom.common.ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("vượt quá giới hạn tối đa"));
    }

    @Test
    @DisplayName("Finding 7: completeUpload rejects MIME type mismatch between registered and actual MinIO object")
    void testCompleteUploadRejectsMimeMismatch() throws Exception {
        when(minioProperties.getBucket()).thenReturn("classroom-media");

        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(1024L);
        // asset mime is application/pdf, but actual is image/png
        when(stat.contentType()).thenReturn("image/png");
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(stat);

        AppException ex = assertThrows(AppException.class, () -> mediaService.completeUpload("asset-1", "uploader-1"));
        assertEquals(com.classroom.common.ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("Định dạng tệp"));
    }

    @Test
    @DisplayName("Abandoned upload cleanup removes expired staging object and metadata")
    void cleanupRemovesOnlyExpiredPendingUploads() throws Exception {
        asset.setCreatedAt(Instant.now().minus(3, ChronoUnit.HOURS));
        when(mediaAssetRepository.findByStatusAndCreatedAtBefore(eq("PENDING"), any())).thenReturn(List.of(asset));
        when(minioProperties.getBucket()).thenReturn("classroom-media");

        mediaService.cleanupAbandonedUploads();

        verify(minioClient, times(2)).removeObject(any(RemoveObjectArgs.class));
        verify(mediaAssetRepository).delete(asset);
    }

    private void stubPdfBytes() throws Exception {
        when(minioClient.getObject(any(io.minio.GetObjectArgs.class)))
                .thenReturn(new io.minio.GetObjectResponse(new okhttp3.Headers.Builder().build(), "", "", "",
                        new java.io.ByteArrayInputStream("%PDF-1.4".getBytes())));
    }
}
