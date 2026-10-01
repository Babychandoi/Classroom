package com.classroom.modules.classroom.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.model.ClassAbout;
import com.classroom.modules.classroom.dto.AboutSectionDto;
import com.classroom.modules.classroom.dto.ClassAboutDto;
import com.classroom.modules.classroom.dto.UpdateClassAboutRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.ClassAboutRepository;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.net.URI;
import java.util.List;

@Service
public class ClassAboutService {

    private final ClassAboutRepository aboutRepository;
    private final AccessPolicy accessPolicy;
    private final ClassroomRepository classroomRepository;
    private final ObjectMapper mapper = new ObjectMapper();
    @org.springframework.beans.factory.annotation.Autowired
    private com.classroom.modules.media.repository.MediaAssetRepository mediaAssets;

    public ClassAboutDto toDto(ClassAbout about) {
        try {
            List<AboutSectionDto> sections = about.getSectionsJson() == null ? List.of()
                    : mapper.readValue(about.getSectionsJson(), new TypeReference<List<AboutSectionDto>>() {});
            return new ClassAboutDto(about.getId(), about.getClassId(), about.getContentMarkdown(), about.getRulesMarkdown(),
                    about.getPublishedVersion(), about.getUpdatedAt(), sections);
        } catch (java.io.IOException e) { throw new IllegalStateException("Invalid persisted introduction sections", e); }
    }

    public ClassAboutService(ClassAboutRepository aboutRepository, AccessPolicy accessPolicy,
                              ClassroomRepository classroomRepository) {
        this.aboutRepository = aboutRepository;
        this.accessPolicy = accessPolicy;
        this.classroomRepository = classroomRepository;
    }

    /**
     * R4-06: a non-ACTIVE (e.g. draft/archived) class's "about" content must be hidden from
     * viewers who cannot see the class itself, anonymous callers included — previously this
     * endpoint skipped the visibility check entirely.
     */
    @Transactional(readOnly = true)
    public ClassAbout getAbout(String classId, String currentUserId) {
        Classroom classroom = classroomRepository.findById(classId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy lớp học"));
        if (accessPolicy.isHiddenPrivateClass(classroom, currentUserId)) {
            // D-19: a PRIVATE class does not exist for a viewer with no relation to it.
            throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy lớp học");
        }
        if (!accessPolicy.isClassVisibleToUser(classroom, currentUserId)) {
            if (currentUserId == null) {
                throw new AppException(ErrorCode.UNAUTHORIZED, "Yêu cầu đăng nhập để xem thông tin lớp học này");
            }
            throw new AppException(ErrorCode.FORBIDDEN, "Bạn không có quyền truy cập thông tin lớp học không công khai này");
        }
        return aboutRepository.findByClassId(classId)
                .orElseGet(() -> new ClassAbout(classId, "# Giới thiệu lớp học", "Nội quy đang cập nhật."));
    }

    @Transactional
    public ClassAbout updateAbout(String classId, String contentMarkdown, String rulesMarkdown, String currentUserId) {
        return updateAbout(classId, new UpdateClassAboutRequest(contentMarkdown, rulesMarkdown, null, null), currentUserId);
    }

    @Transactional
    public ClassAbout updateAbout(String classId, UpdateClassAboutRequest request, String currentUserId) {
        accessPolicy.enforceManage(currentUserId, classId, "ABOUT", "EDIT", null);

        classroomRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy lớp học"));

        ClassAbout about = aboutRepository.findByClassId(classId)
                .orElseGet(() -> new ClassAbout(classId, "", ""));

        if (request.publishedVersion() != null && request.publishedVersion() != about.getPublishedVersion()) {
            throw new AppException(ErrorCode.CONFLICT, "Giới thiệu đã được cập nhật. Tải lại trước khi lưu.");
        }
        if (request.contentMarkdown() != null) about.setContentMarkdown(request.contentMarkdown());
        if (request.rulesMarkdown() != null) about.setRulesMarkdown(request.rulesMarkdown());
        if (request.sections() != null) {
            for (AboutSectionDto section : request.sections()) {
                if (section.mediaAssetId() != null && !section.mediaAssetId().isBlank()) {
                    var asset = mediaAssets.findById(section.mediaAssetId())
                            .orElseThrow(() -> new AppException(ErrorCode.BAD_REQUEST, "Không tìm thấy ảnh giới thiệu"));
                    if (!classId.equals(asset.getClassId()) || !"UPLOADED".equals(asset.getStatus())
                            || !"ABOUT".equals(asset.getUploadPurpose()) || !asset.getMimeType().startsWith("image/")
                            || section.imageAlt() == null || section.imageAlt().isBlank()) {
                        throw new AppException(ErrorCode.BAD_REQUEST, "Ảnh phải thuộc lớp, đã tải xong và có mô tả ảnh");
                    }
                } else if (section.imageUrl() != null && !section.imageUrl().isBlank()) {
                    try {
                        URI url = URI.create(section.imageUrl());
                        if (!"https".equals(url.getScheme()) || url.getHost() == null || url.getUserInfo() != null
                                || section.imageAlt() == null || section.imageAlt().isBlank()) {
                            throw new IllegalArgumentException();
                        }
                    } catch (IllegalArgumentException e) {
                        throw new AppException(ErrorCode.BAD_REQUEST, "Ảnh cần địa chỉ HTTPS hợp lệ và mô tả cho người đọc màn hình.");
                    }
                }
            }
            try { about.setSectionsJson(mapper.writeValueAsString(request.sections())); }
            catch (java.io.IOException e) { throw new IllegalStateException(e); }
        }
        about.setPublishedVersion(about.getPublishedVersion() + 1);
        about.setUpdatedAt(Instant.now());

        return aboutRepository.save(about);
    }
}
