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
import org.mockito.ArgumentCaptor;
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
    @DisplayName("R4-10: post-copy re-stat rejects and removes a final object whose size drifted from the registered size")
    void completeUploadRejectsAndCleansUpSizeMismatchAfterCopy() throws Exception {
        when(minioProperties.getBucket()).thenReturn("classroom-media");
        StatObjectResponse stagingStat = mock(StatObjectResponse.class);
        when(stagingStat.size()).thenReturn(1024L); // matches asset.getSizeBytes(), passes pre-copy check
        when(stagingStat.contentType()).thenReturn("application/pdf");

        // Staging object mismatches the registered size at the final-object re-stat: staging
        // exists (pre-copy check passes), the final object does not yet exist (forcing a copy),
        // and the freshly-copied final object comes back with a different size than registered —
        // simulating bytes swapped in at the staging key between the pre-copy check and the copy.
        StatObjectResponse driftedFinalStat = mock(StatObjectResponse.class);
        when(driftedFinalStat.size()).thenReturn(2048L); // != asset.getSizeBytes() (1024L)

        java.util.concurrent.atomic.AtomicInteger finalKeyStatCalls = new java.util.concurrent.atomic.AtomicInteger(0);
        when(minioClient.statObject(any(StatObjectArgs.class))).thenAnswer(invocation -> {
            StatObjectArgs args = invocation.getArgument(0);
            if (asset.getUploadObjectKey().equals(args.object())) {
                return stagingStat;
            }
            // First stat of the final key (inside the promotion try) finds nothing yet, forcing a
            // copy; the post-copy re-stat (this test's R4-10 path) then returns the drifted size.
            if (finalKeyStatCalls.getAndIncrement() == 0) {
                throw new RuntimeException("NoSuchKey: final object not yet promoted");
            }
            return driftedFinalStat;
        });

        assertThrows(AppException.class, () -> mediaService.completeUpload("asset-1", "uploader-1"));

        verify(minioClient).copyObject(any());
        // The size-mismatched object this call just created must be cleaned up.
        verify(minioClient).removeObject(argThat((RemoveObjectArgs args) -> asset.getObjectKey().equals(args.object())));
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
        stubPresignedGetUrl();

        DownloadUrlResponse res = mediaService.generateAuthorizedDownloadUrl("asset-1", "student-1");
        assertNotNull(res);
        assertPresignedGetUrl(res);
    }

    @Test
    @DisplayName("R14-02: media of an archived lesson / archived section is refused to a learner even with course access")
    void testDownloadDeniedForLessonHiddenFromLearner() throws Exception {
        asset.setStatus("UPLOADED");
        when(mediaAssetRepository.findById("asset-1")).thenReturn(Optional.of(asset));
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);

        Lesson lesson = new Lesson("sec-1", "course-1", "Lesson 1", "VIDEO", 1);
        lesson.setMediaAssetId("asset-1");
        Course course = new Course("class-1", "Free Course", "FREE");

        when(documentAssetRepository.findByMediaAssetId("asset-1")).thenReturn(List.of());
        when(lessonRepository.findByMediaAssetId("asset-1")).thenReturn(List.of(lesson));
        when(courseRepository.findById("course-1")).thenReturn(Optional.of(course));
        when(learningPolicy.isLessonHiddenFromLearner(lesson, course, "student-1")).thenReturn(true);
        lenient().when(learningPolicy.canLearn("student-1", course)).thenReturn(true);

        AppException ex = assertThrows(AppException.class, () -> mediaService.generateAuthorizedDownloadUrl("asset-1", "student-1"));
        assertEquals(com.classroom.common.ErrorCode.FORBIDDEN, ex.getErrorCode());
        verify(minioPresigningClient, never()).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
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
        stubPresignedGetUrl();

        DownloadUrlResponse res = mediaService.generateAuthorizedDownloadUrl("asset-1", "student-1");
        assertNotNull(res);
        assertPresignedGetUrl(res);
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
        stubPresignedGetUrl();

        DownloadUrlResponse res = mediaService.generateAuthorizedDownloadUrl("asset-1", "uploader-1");
        assertNotNull(res);
        assertPresignedGetUrl(res);
    }

    @Test
    @DisplayName("R8-01: the presigned GET URL is signed with a bounded TTL, and a content-disposition/type override for the true filename and MIME type")
    void presignedDownloadUrlUsesShortTtlAndContentOverrides() throws Exception {
        asset.setStatus("UPLOADED");
        asset.setOriginalFilename("Bài giảng \"1\".pdf");
        when(mediaAssetRepository.findById("asset-1")).thenReturn(Optional.of(asset));
        when(accessPolicy.isOwner("uploader-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("uploader-1", "class-1", "MEDIA", "CREATE", null)).thenReturn(true);
        when(documentAssetRepository.findByMediaAssetId("asset-1")).thenReturn(List.of());
        when(lessonRepository.findByMediaAssetId("asset-1")).thenReturn(List.of());
        when(minioProperties.getBucket()).thenReturn("classroom-media");
        stubPresignedGetUrl();

        Instant before = Instant.now();
        DownloadUrlResponse res = mediaService.generateAuthorizedDownloadUrl("asset-1", "uploader-1");
        Instant after = Instant.now();

        assertNotNull(res.getExpiresAt());
        assertFalse(res.getExpiresAt().isBefore(before.plus(MediaService.PRESIGNED_DOWNLOAD_TTL_MINUTES, ChronoUnit.MINUTES).minusSeconds(5)));
        assertFalse(res.getExpiresAt().isAfter(after.plus(MediaService.PRESIGNED_DOWNLOAD_TTL_MINUTES, ChronoUnit.MINUTES).plusSeconds(5)));

        ArgumentCaptor<GetPresignedObjectUrlArgs> captor = ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(minioPresigningClient).getPresignedObjectUrl(captor.capture());
        GetPresignedObjectUrlArgs args = captor.getValue();
        assertEquals(io.minio.http.Method.GET, args.method());
        assertEquals(asset.getObjectKey(), args.object());
        assertEquals("classroom-media", args.bucket());
        String extraQuery = args.extraQueryParams().toString();
        assertTrue(extraQuery.contains("response-content-disposition"));
        assertTrue(extraQuery.contains("attachment"));
        assertTrue(extraQuery.contains("response-content-type"));
        assertTrue(extraQuery.contains("application/pdf"));
        // The filename is sanitized before it reaches the header value (no raw quotes/newlines).
        assertFalse(extraQuery.contains("\"1\""));
    }

    @Test
    @DisplayName("R9-08: a Vietnamese filename is carried as RFC 5987 filename*=UTF-8''... with an ASCII fallback")
    void presignedDownloadUrlEncodesVietnameseFilenameCorrectly() throws Exception {
        asset.setStatus("UPLOADED");
        asset.setOriginalFilename("Bài giảng tiếng Việt.pdf");
        when(mediaAssetRepository.findById("asset-1")).thenReturn(Optional.of(asset));
        when(accessPolicy.isOwner("uploader-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("uploader-1", "class-1", "MEDIA", "CREATE", null)).thenReturn(true);
        when(documentAssetRepository.findByMediaAssetId("asset-1")).thenReturn(List.of());
        when(lessonRepository.findByMediaAssetId("asset-1")).thenReturn(List.of());
        when(minioProperties.getBucket()).thenReturn("classroom-media");
        stubPresignedGetUrl();

        mediaService.generateAuthorizedDownloadUrl("asset-1", "uploader-1");

        ArgumentCaptor<GetPresignedObjectUrlArgs> captor = ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(minioPresigningClient).getPresignedObjectUrl(captor.capture());
        String extraQuery = captor.getValue().extraQueryParams().toString();

        assertTrue(extraQuery.contains("attachment"));
        // RFC 5987 extended notation: percent-encoded UTF-8 bytes of the real filename.
        assertTrue(extraQuery.contains("filename*=UTF-8''"));
        String expectedEncoded = java.net.URLEncoder.encode("Bài giảng tiếng Việt.pdf", java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        assertTrue(extraQuery.contains(expectedEncoded), "expected encoded filename in: " + extraQuery);
        // No raw non-ASCII bytes leak into the plain filename="..." fallback form.
        assertFalse(extraQuery.contains("filename=\"Bài giảng tiếng Việt.pdf\""));
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
    @DisplayName("R1-08: magic-byte validation runs against the promoted final object, not staging (TOCTOU hardening)")
    void completeUploadValidatesMagicBytesAgainstFinalObjectAfterCopy() throws Exception {
        when(minioProperties.getBucket()).thenReturn("classroom-media");
        StatObjectResponse stagingStat = mock(StatObjectResponse.class);
        when(stagingStat.size()).thenReturn(1024L);
        when(stagingStat.contentType()).thenReturn("application/pdf");
        // Staging exists; the final key does not yet exist, forcing the copy path rather than the
        // already-promoted short-circuit.
        when(minioClient.statObject(any(StatObjectArgs.class))).thenAnswer(invocation -> {
            StatObjectArgs args = invocation.getArgument(0);
            if (asset.getObjectKey().equals(args.object())) {
                throw new RuntimeException("NoSuchKey: final object not promoted yet");
            }
            return stagingStat;
        });
        stubPdfBytes();
        when(mediaAssetRepository.saveAndFlush(any(MediaAsset.class))).thenAnswer(i -> i.getArgument(0));

        mediaService.completeUpload("asset-1", "uploader-1");

        // The object is copied to its final key before content is inspected, and inspection reads
        // from the final key - closing the window where a fresh PUT to the still-valid staging
        // presigned URL could swap the bytes out between validation and promotion.
        var inOrder = inOrder(minioClient);
        inOrder.verify(minioClient).copyObject(any(io.minio.CopyObjectArgs.class));
        inOrder.verify(minioClient).getObject(argThat(args -> asset.getObjectKey().equals(args.object())));
    }

    @Test
    @DisplayName("R1-08: a final object that fails magic-byte validation is deleted when THIS call created it by copy")
    void completeUploadRemovesFinalObjectWhenValidationFails() throws Exception {
        when(minioProperties.getBucket()).thenReturn("classroom-media");
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(1024L);
        when(stat.contentType()).thenReturn("application/pdf");
        // Staging exists; the final object does not yet exist, so this call promotes it via
        // copyObject (R2-03: only an object THIS call created by copy may be deleted below).
        when(minioClient.statObject(any(StatObjectArgs.class))).thenAnswer(invocation -> {
            StatObjectArgs args = invocation.getArgument(0);
            if (asset.getObjectKey().equals(args.object())) {
                throw new RuntimeException("NoSuchKey");
            }
            return stat;
        });
        // Bytes at the final key do not match the declared PDF mime type (e.g. swapped after the
        // presigned staging PUT was reused).
        when(minioClient.getObject(any(io.minio.GetObjectArgs.class)))
                .thenReturn(new io.minio.GetObjectResponse(new okhttp3.Headers.Builder().build(), "", "", "",
                        new java.io.ByteArrayInputStream("MZ-not-a-real-pdf".getBytes())));

        assertThrows(AppException.class, () -> mediaService.completeUpload("asset-1", "uploader-1"));

        verify(minioClient).copyObject(any());

        verify(minioClient).removeObject(argThat((RemoveObjectArgs args) -> asset.getObjectKey().equals(args.object())));
        verify(mediaAssetRepository, never()).saveAndFlush(any());
        assertEquals("PENDING", asset.getStatus());
    }

    @Test
    @DisplayName("R2-03: a genuine content mismatch on an object promoted by an earlier call is reported but not deleted")
    void completeUploadDoesNotDeletePreviouslyPromotedObjectEvenOnGenuineMismatch() throws Exception {
        // Staging is already gone and the final object already exists (an earlier call promoted
        // it): this call performs no copyObject. Its magic-byte check still genuinely fails, but
        // since this call did not create the final object, it must not delete bytes another
        // request (or a previous completed call) may already be relying on.
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
        when(minioClient.getObject(any(io.minio.GetObjectArgs.class)))
                .thenReturn(new io.minio.GetObjectResponse(new okhttp3.Headers.Builder().build(), "", "", "",
                        new java.io.ByteArrayInputStream("MZ-not-a-real-pdf".getBytes())));

        assertThrows(AppException.class, () -> mediaService.completeUpload("asset-1", "uploader-1"));

        verify(minioClient, never()).copyObject(any());
        verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
        assertEquals("PENDING", asset.getStatus());
    }

    @Test
    @DisplayName("R2-03: completeUpload on an already-UPLOADED asset is idempotent and touches no MinIO calls")
    void completeUploadOnAlreadyUploadedAssetReturnsEarlyWithoutRevalidating() throws Exception {
        asset.setStatus("UPLOADED");

        MediaAsset result = mediaService.completeUpload("asset-1", "uploader-1");

        assertEquals("UPLOADED", result.getStatus());
        verifyNoInteractions(minioClient);
        verify(mediaAssetRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("R2-03: a transient MinIO read error validating an already-promoted final object does not delete it")
    void completeUploadDoesNotDeleteFinalObjectOnTransientReadErrorWhenNotCreatedThisCall() throws Exception {
        // Staging is already gone and the final object stat succeeds (a previous call already
        // promoted it) - this call must not have copied anything - but the magic-byte read then
        // fails with a transient I/O error rather than a genuine content mismatch.
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
        when(minioClient.getObject(any(io.minio.GetObjectArgs.class)))
                .thenThrow(new RuntimeException("connection reset"));

        assertThrows(AppException.class, () -> mediaService.completeUpload("asset-1", "uploader-1"));

        verify(minioClient, never()).copyObject(any());
        verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
        verify(mediaAssetRepository, never()).saveAndFlush(any());
        assertEquals("PENDING", asset.getStatus());
    }

    @Test
    @DisplayName("R2-03: a transient read error on a final object copied by this same call still leaves it in place")
    void completeUploadDoesNotDeleteFinalObjectOnTransientReadErrorEvenWhenCreatedThisCall() throws Exception {
        when(minioProperties.getBucket()).thenReturn("classroom-media");
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(1024L);
        when(stat.contentType()).thenReturn("application/pdf");
        when(minioClient.statObject(any(StatObjectArgs.class))).thenAnswer(invocation -> {
            StatObjectArgs args = invocation.getArgument(0);
            if (asset.getObjectKey().equals(args.object())) {
                // Final object does not exist yet: this call promotes it via copyObject.
                throw new RuntimeException("NoSuchKey");
            }
            return stat;
        });
        when(minioClient.getObject(any(io.minio.GetObjectArgs.class)))
                .thenThrow(new RuntimeException("connection reset"));

        assertThrows(AppException.class, () -> mediaService.completeUpload("asset-1", "uploader-1"));

        verify(minioClient).copyObject(any());
        // Even though this call created the final object, a transient read error is not a genuine
        // content mismatch, so the freshly-promoted object must not be deleted.
        verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
        assertEquals("PENDING", asset.getStatus());
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

    /** R8-01: authorized downloads are now issued as a presigned MinIO GET URL, not a proxy path. */
    private void stubPresignedGetUrl() throws Exception {
        lenient().when(minioProperties.getBucket()).thenReturn("classroom-media");
        lenient().when(minioPresigningClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("http://localhost:9000/classroom-media/" + asset.getObjectKey() + "?X-Amz-Signature=stub");
    }

    private void assertPresignedGetUrl(DownloadUrlResponse res) {
        assertTrue(res.getDownloadUrl().startsWith("http://localhost:9000/"));
        assertNotNull(res.getExpiresAt());
        assertTrue(res.getExpiresAt().isAfter(Instant.now()));
    }
}
