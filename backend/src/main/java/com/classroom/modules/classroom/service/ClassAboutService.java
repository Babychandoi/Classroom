package com.classroom.modules.classroom.service;

import com.classroom.modules.classroom.model.ClassAbout;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.ClassAboutRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class ClassAboutService {

    private final ClassAboutRepository aboutRepository;
    private final AccessPolicy accessPolicy;

    public ClassAboutService(ClassAboutRepository aboutRepository, AccessPolicy accessPolicy) {
        this.aboutRepository = aboutRepository;
        this.accessPolicy = accessPolicy;
    }

    @Transactional(readOnly = true)
    public ClassAbout getAbout(String classId) {
        return aboutRepository.findByClassId(classId)
                .orElseGet(() -> new ClassAbout(classId, "# Giới thiệu lớp học", "Nội quy đang cập nhật."));
    }

    @Transactional
    public ClassAbout updateAbout(String classId, String contentMarkdown, String rulesMarkdown, String currentUserId) {
        accessPolicy.enforceManage(currentUserId, classId, "ABOUT", "EDIT", null);

        ClassAbout about = aboutRepository.findByClassId(classId)
                .orElseGet(() -> new ClassAbout(classId, "", ""));

        about.setContentMarkdown(contentMarkdown);
        about.setRulesMarkdown(rulesMarkdown);
        about.setPublishedVersion(about.getPublishedVersion() + 1);
        about.setUpdatedAt(Instant.now());

        return aboutRepository.save(about);
    }
}
