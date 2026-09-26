package com.classroom.modules.community.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.commerce.policy.ProPolicy;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import com.classroom.modules.commerce.repository.ProductRepository;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.community.dto.DocumentAssetDto;
import com.classroom.modules.community.model.DocumentAsset;
import com.classroom.modules.community.repository.DocumentAssetRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class DocumentSecurityTest {

    @Mock
    private DocumentAssetRepository documentRepository;
    @Mock
    private MediaService mediaService;
    @Mock
    private AccessPolicy accessPolicy;
    @Mock
    private ProPolicy proPolicy;
    @Mock
    private EntitlementRepository entitlementRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private CourseRepository courseRepository;

    @InjectMocks
    private DocumentService documentService;

    private DocumentAsset freeDoc;
    private DocumentAsset proDoc;
    private DocumentAsset productDoc;

    @BeforeEach
    void setUp() {
        freeDoc = new DocumentAsset("class-1", "Giáo trình miễn phí", "Mô tả", "media-1", "FREE");
        freeDoc.setId("doc-free");

        proDoc = new DocumentAsset("class-1", "Tài liệu PRO", "Mô tả PRO", "media-2", "PRO");
        proDoc.setId("doc-pro");

        productDoc = new DocumentAsset("class-1", "Tài liệu Khóa Chuyên Sâu", "Mô tả Chuyên Sâu", "media-3", "PRODUCT_OWNER", "prod-A", null);
        productDoc.setId("doc-prod");
    }

    @Test
    @DisplayName("Finding 4: getDocuments filters out restricted documents for FREE students")
    void testGetDocumentsFiltersRestrictedForFreeStudent() {
        when(accessPolicy.isOwner("student-free", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("student-free", "class-1", "DOCUMENT", "VIEW", null)).thenReturn(false);
        when(accessPolicy.isMember("student-free", "class-1")).thenReturn(true);
        when(proPolicy.isPro("student-free", "class-1")).thenReturn(false);
        when(entitlementRepository.hasProductAccess(eq("student-free"), eq("class-1"), eq("prod-A"), any())).thenReturn(false);

        when(documentRepository.findByClassIdOrderByCreatedAtDesc("class-1"))
                .thenReturn(List.of(freeDoc, proDoc, productDoc));

        MediaAsset media = new MediaAsset("class-1", "owner-1", "path/free.pdf", "free.pdf", "application/pdf", 1024L);
        when(mediaService.getAsset("media-1")).thenReturn(media);

        List<DocumentAssetDto> result = documentService.getDocuments("class-1", "student-free");

        // Only FREE document is returned; PRO and PRODUCT_OWNER documents are not leaked
        assertEquals(1, result.size());
        assertEquals("doc-free", result.get(0).getId());
        assertEquals("free.pdf", result.get(0).getFilename());
    }

    @Test
    @DisplayName("Finding 4: getDocuments returns all documents for OWNER and privileged staff")
    void testGetDocumentsReturnsAllForOwner() {
        when(accessPolicy.isOwner("owner-1", "class-1")).thenReturn(true);
        when(documentRepository.findByClassIdOrderByCreatedAtDesc("class-1"))
                .thenReturn(List.of(freeDoc, proDoc, productDoc));

        MediaAsset media = new MediaAsset("class-1", "owner-1", "path/doc.pdf", "doc.pdf", "application/pdf", 1024L);
        when(mediaService.getAsset(anyString())).thenReturn(media);

        List<DocumentAssetDto> result = documentService.getDocuments("class-1", "owner-1");

        assertEquals(3, result.size());
    }

    @Test
    @DisplayName("Finding 5: getDocumentDownloadUrl denies access when student does not own target product")
    void testDownloadUrlDeniedForUnownedProduct() {
        when(documentRepository.findById("doc-prod")).thenReturn(Optional.of(productDoc));
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("student-1", "class-1", "DOCUMENT", "VIEW", null)).thenReturn(false);
        when(accessPolicy.isMember("student-1", "class-1")).thenReturn(true);

        // Student owns prod-B, NOT prod-A
        when(entitlementRepository.hasProductAccess(eq("student-1"), eq("class-1"), eq("prod-A"), any())).thenReturn(false);

        AppException ex = assertThrows(AppException.class, () ->
                documentService.getDocumentDownloadUrl("doc-prod", "student-1")
        );
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        verify(mediaService, never()).generateDownloadUrl(any());
    }

    @Test
    @DisplayName("Finding 5: getDocumentDownloadUrl grants access when student owns target product")
    void testDownloadUrlGrantedForOwnedProduct() {
        when(documentRepository.findById("doc-prod")).thenReturn(Optional.of(productDoc));
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("student-1", "class-1", "DOCUMENT", "VIEW", null)).thenReturn(false);
        when(accessPolicy.isMember("student-1", "class-1")).thenReturn(true);
        when(entitlementRepository.hasProductAccess(eq("student-1"), eq("class-1"), eq("prod-A"), any())).thenReturn(true);

        MediaAsset media = new MediaAsset("class-1", "owner-1", "path/prod.pdf", "prod.pdf", "application/pdf", 2048L);
        media.setStatus("UPLOADED");
        when(mediaService.getAsset("media-3")).thenReturn(media);
        when(mediaService.generateAuthorizedDownloadUrl("media-3", "student-1"))
                .thenReturn(new DownloadUrlResponse("media-3", "http://localhost:9000/signed", Instant.now().plusSeconds(900)));

        DownloadUrlResponse res = documentService.getDocumentDownloadUrl("doc-prod", "student-1");

        assertNotNull(res);
        assertEquals("http://localhost:9000/signed", res.getDownloadUrl());
    }

    @Test
    @DisplayName("PRODUCT_OWNER document without an explicit target never falls back to any active entitlement")
    void productOwnerDocumentWithoutTargetIsDenied() {
        DocumentAsset malformed = new DocumentAsset("class-1", "Orphan target", "", "media-x", "PRODUCT_OWNER");
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("student-1", "class-1", "DOCUMENT", "VIEW", null)).thenReturn(false);
        when(accessPolicy.isMember("student-1", "class-1")).thenReturn(true);
        assertFalse(documentService.canAccessDocument(malformed, "student-1"));
        verify(entitlementRepository, never()).findActiveEntitlements(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Course-targeted document checks the course's linked product entitlement")
    void courseTargetDocumentUsesLinkedProduct() {
        DocumentAsset courseDoc = new DocumentAsset("class-1", "Course notes", "", "media-course", "PRODUCT_OWNER", null, "course-A");
        Course course = new Course("class-1", "Course A", "PURCHASE_REQUIRED");
        course.setId("course-A");
        course.setProductId("product-A");
        when(accessPolicy.isOwner("student-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("student-1", "class-1", "DOCUMENT", "VIEW", null)).thenReturn(false);
        when(accessPolicy.isMember("student-1", "class-1")).thenReturn(true);
        when(courseRepository.findById("course-A")).thenReturn(Optional.of(course));
        when(entitlementRepository.hasCourseAccess(eq("student-1"), eq("class-1"), eq("course-A"), eq("product-A"), any())).thenReturn(true);

        assertTrue(documentService.canAccessDocument(courseDoc, "student-1"));
        verify(entitlementRepository).hasCourseAccess(eq("student-1"), eq("class-1"), eq("course-A"), eq("product-A"), any());
    }

    @Test
    @DisplayName("A document creator cannot attach another user's asset")
    void createDocumentRejectsAssetOwnedByAnotherUploader() {
        when(mediaService.getAssetForUpdate("paid-lesson-media"))
                .thenReturn(new MediaAsset("class-1", "teacher-owner", "key", "lesson.mp4", "video/mp4", 10L));
        when(accessPolicy.isOwner("document-staff", "class-1")).thenReturn(false);

        assertThrows(AppException.class, () -> documentService.createDocument(
                "class-1", "Free copy", "", "paid-lesson-media", "FREE", "document-staff"));
        verify(documentRepository, never()).save(any());
    }

    @Test
    @DisplayName("A document cannot reuse media already protected by a lesson")
    void createDocumentRejectsMediaAlreadyReferencedByLesson() {
        MediaAsset uploaded = new MediaAsset("class-1", "document-staff", "key", "lesson.mp4", "video/mp4", 10L);
        uploaded.setStatus("UPLOADED");
        when(mediaService.getAssetForUpdate("lesson-media")).thenReturn(uploaded);
        when(mediaService.isReferencedByLesson("lesson-media")).thenReturn(true);

        assertThrows(AppException.class, () -> documentService.createDocument(
                "class-1", "Free copy", "", "lesson-media", "FREE", "document-staff"));
        verify(documentRepository, never()).save(any());
    }
}
