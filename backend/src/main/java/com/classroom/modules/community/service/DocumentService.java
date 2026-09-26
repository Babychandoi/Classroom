package com.classroom.modules.community.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.commerce.model.Entitlement;
import com.classroom.modules.commerce.policy.ProPolicy;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import com.classroom.modules.commerce.repository.ProductRepository;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.community.dto.DocumentAssetDto;
import com.classroom.modules.community.model.DocumentAsset;
import com.classroom.modules.community.repository.DocumentAssetRepository;
import com.classroom.modules.media.dto.DownloadUrlResponse;
import com.classroom.modules.media.model.MediaAsset;
import com.classroom.modules.media.service.MediaService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class DocumentService {

    private final DocumentAssetRepository documentRepository;
    private final MediaService mediaService;
    private final AccessPolicy accessPolicy;
    private final ProPolicy proPolicy;
    private final EntitlementRepository entitlementRepository;
    private final ProductRepository productRepository;
    private final CourseRepository courseRepository;

    public DocumentService(DocumentAssetRepository documentRepository,
                           MediaService mediaService,
                           AccessPolicy accessPolicy,
                           ProPolicy proPolicy,
                           EntitlementRepository entitlementRepository,
                           ProductRepository productRepository,
                           CourseRepository courseRepository) {
        this.documentRepository = documentRepository;
        this.mediaService = mediaService;
        this.accessPolicy = accessPolicy;
        this.proPolicy = proPolicy;
        this.entitlementRepository = entitlementRepository;
        this.productRepository = productRepository;
        this.courseRepository = courseRepository;
    }

    @Transactional(readOnly = true)
    public boolean canAccessDocument(DocumentAsset doc, String userId) {
        if (userId == null || doc == null) return false;

        boolean isOwner = accessPolicy.isOwner(userId, doc.getClassId());
        if (isOwner) return true;

        boolean isStaff = accessPolicy.canManage(userId, doc.getClassId(), "DOCUMENT", "VIEW", null);
        if (isStaff) return true;

        if (!accessPolicy.isMember(userId, doc.getClassId())) return false;

        String visibility = doc.getVisibility() != null ? doc.getVisibility().toUpperCase() : "FREE";
        if ("FREE".equals(visibility)) {
            return true;
        }
        if ("PRO".equals(visibility)) {
            return proPolicy.isPro(userId, doc.getClassId());
        }
        if ("PRODUCT_OWNER".equals(visibility)) {
            Instant now = Instant.now();
            if (doc.getTargetProductId() != null && !doc.getTargetProductId().isBlank()) {
                return entitlementRepository.hasProductAccess(userId, doc.getClassId(), doc.getTargetProductId(), now);
            }
            if (doc.getTargetCourseId() != null && !doc.getTargetCourseId().isBlank()) {
                var course = courseRepository.findById(doc.getTargetCourseId());
                return course.filter(c -> doc.getClassId().equals(c.getClassId())
                                && c.getProductId() != null && !c.getProductId().isBlank())
                        .map(c -> entitlementRepository.hasCourseAccess(userId, doc.getClassId(), c.getId(), c.getProductId(), now))
                        .orElse(false);
            }
            return false;
        }
        return false;
    }

    @Transactional(readOnly = true)
    public List<DocumentAssetDto> getDocuments(String classId, String userId) {
        accessPolicy.enforceMember(userId, classId);

        List<DocumentAsset> docs = documentRepository.findByClassIdOrderByCreatedAtDesc(classId);
        List<DocumentAssetDto> dtos = new ArrayList<>();

        for (DocumentAsset d : docs) {
            // Filter restricted-document metadata by effective document access (Finding 4)
            if (!canAccessDocument(d, userId)) {
                continue;
            }

            DocumentAssetDto dto = new DocumentAssetDto();
            dto.setId(d.getId());
            dto.setClassId(d.getClassId());
            dto.setTitle(d.getTitle());
            dto.setDescription(d.getDescription());
            dto.setMediaAssetId(d.getMediaAssetId());
            dto.setVisibility(d.getVisibility());
            dto.setTargetProductId(d.getTargetProductId());
            dto.setTargetCourseId(d.getTargetCourseId());
            dto.setCreatedAt(d.getCreatedAt());

            try {
                MediaAsset media = mediaService.getAsset(d.getMediaAssetId());
                dto.setFilename(media.getOriginalFilename());
                dto.setMimeType(media.getMimeType());
                dto.setSizeBytes(media.getSizeBytes());
            } catch (Exception ignored) {}

            dtos.add(dto);
        }

        return dtos;
    }

    @Transactional(readOnly = true)
    public DownloadUrlResponse getDocumentDownloadUrl(String documentId, String userId) {
        DocumentAsset doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy tài liệu"));

        accessPolicy.enforceMember(userId, doc.getClassId());

        if (!canAccessDocument(doc, userId)) {
            if ("PRO".equalsIgnoreCase(doc.getVisibility())) {
                throw new AppException(ErrorCode.PRO_MEMBERSHIP_REQUIRED, "Tài liệu này chỉ dành riêng cho hội viên PRO của lớp");
            }
            if ("PRODUCT_OWNER".equalsIgnoreCase(doc.getVisibility())) {
                if (doc.getTargetProductId() != null && !doc.getTargetProductId().isBlank()) {
                    throw new AppException(ErrorCode.FORBIDDEN, "Tài liệu này yêu cầu sở hữu sản phẩm: " + doc.getTargetProductId());
                }
                throw new AppException(ErrorCode.FORBIDDEN, "Tài liệu này yêu cầu sở hữu sản phẩm/gói dịch vụ của lớp");
            }
            throw new AppException(ErrorCode.FORBIDDEN, "Bạn không có quyền truy cập tài liệu này");
        }

        MediaAsset media = mediaService.getAsset(doc.getMediaAssetId());
        if (!media.getClassId().equals(doc.getClassId())) {
            throw new AppException(ErrorCode.FORBIDDEN, "Tệp đính kèm không thuộc lớp học này");
        }
        if (!"UPLOADED".equalsIgnoreCase(media.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Tệp chưa hoàn tất quá trình tải lên");
        }

        return mediaService.generateAuthorizedDownloadUrl(doc.getMediaAssetId(), userId);
    }

    @Transactional
    public DocumentAsset createDocument(String classId, String title, String description, String mediaAssetId, String visibility, String userId) {
        return createDocument(classId, title, description, mediaAssetId, visibility, null, null, userId);
    }

    @Transactional
    public DocumentAsset createDocument(String classId, String title, String description, String mediaAssetId, String visibility, String targetProductId, String targetCourseId, String userId) {
        accessPolicy.enforceManage(userId, classId, "DOCUMENT", "CREATE", null);
        String normalizedVisibility = visibility == null || visibility.isBlank() ? "FREE" : visibility.trim().toUpperCase();
        if (!List.of("FREE", "PRO", "PRODUCT_OWNER").contains(normalizedVisibility)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Mức hiển thị tài liệu không hợp lệ");
        }

        if ("PRODUCT_OWNER".equals(normalizedVisibility)) {
            boolean hasProduct = targetProductId != null && !targetProductId.isBlank();
            boolean hasCourse = targetCourseId != null && !targetCourseId.isBlank();
            if (hasProduct == hasCourse) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Tài liệu PRODUCT_OWNER phải gắn đúng một sản phẩm hoặc khóa học mục tiêu");
            }
            if (hasProduct) {
                var product = productRepository.findById(targetProductId.trim())
                        .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy sản phẩm mục tiêu"));
                if (!classId.equals(product.getClassId())) throw new AppException(ErrorCode.BAD_REQUEST, "Sản phẩm mục tiêu không thuộc lớp này");
            }
            if (hasCourse) {
                var course = courseRepository.findById(targetCourseId.trim())
                        .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học mục tiêu"));
                if (!classId.equals(course.getClassId())) throw new AppException(ErrorCode.BAD_REQUEST, "Khóa học mục tiêu không thuộc lớp này");
            }
        }

        MediaAsset media = mediaService.getAssetForUpdate(mediaAssetId);
        if (!media.getClassId().equals(classId)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Tệp đính kèm không thuộc lớp học này");
        }
        if (!userId.equals(media.getUploaderId()) && !accessPolicy.isOwner(userId, classId)) {
            throw new AppException(ErrorCode.FORBIDDEN, "Chỉ người tải tệp lên hoặc OWNER mới được gắn tệp này vào tài liệu");
        }
        if (!"UPLOADED".equalsIgnoreCase(media.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Tệp chưa hoàn tất quá trình tải lên");
        }
        // One media asset has one audience policy. Reusing it across resources could
        // let the least restrictive reference bypass a lesson/document entitlement.
        if (!documentRepository.findByMediaAssetId(mediaAssetId).isEmpty()
                || mediaService.isReferencedByLesson(mediaAssetId)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Tệp đã được gắn với nội dung khác và không thể tái sử dụng");
        }

        DocumentAsset doc = new DocumentAsset(classId, title, description, mediaAssetId, normalizedVisibility, targetProductId, targetCourseId);
        return documentRepository.save(doc);
    }
}
