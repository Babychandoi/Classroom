package com.classroom.modules.audit.service;

import com.classroom.modules.audit.model.AuditEvent;
import com.classroom.modules.audit.repository.AuditEventRepository;
import com.classroom.modules.classroom.policy.AccessPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AuditService {

    private final AuditEventRepository auditEventRepository;
    private final AccessPolicy accessPolicy;

    public AuditService(AuditEventRepository auditEventRepository, AccessPolicy accessPolicy) {
        this.auditEventRepository = auditEventRepository;
        this.accessPolicy = accessPolicy;
    }

    @Transactional
    public void record(String classId, String actorId, String action, String targetType, String targetId, String detailsJson) {
        AuditEvent event = new AuditEvent(classId, actorId, action, targetType, targetId, detailsJson);
        auditEventRepository.save(event);
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> getAuditLogs(String classId, String currentUserId) {
        accessPolicy.enforceManage(currentUserId, classId, "AUDIT", "VIEW", null);
        return auditEventRepository.findByClassIdOrderByCreatedAtDesc(classId);
    }
}
