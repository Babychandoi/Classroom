package com.classroom.modules.audit.service;

import com.classroom.modules.audit.model.AuditEvent;
import com.classroom.modules.audit.repository.AuditEventRepository;
import com.classroom.modules.classroom.policy.AccessPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

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

    /**
     * R19-10: the latest event with one of {@code actions} recorded against one target - e.g. who last blocked or
     * removed a class member (MEMBER_BLOCK / MEMBER_REMOVE on the CLASS_MEMBER row), which is the only persisted
     * record of that actor once the member's staff assignment and role have been reset.
     */
    @Transactional(readOnly = true)
    public Optional<AuditEvent> findLatestEvent(String classId, String targetType, String targetId, Collection<String> actions) {
        return auditEventRepository.findFirstByClassIdAndTargetTypeAndTargetIdAndActionInOrderByCreatedAtDesc(
                classId, targetType, targetId, actions);
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> getAuditLogs(String classId, String currentUserId) {
        accessPolicy.enforceManage(currentUserId, classId, "AUDIT", "VIEW", null);
        return auditEventRepository.findByClassIdOrderByCreatedAtDesc(classId);
    }
}
