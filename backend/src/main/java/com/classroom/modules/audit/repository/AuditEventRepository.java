package com.classroom.modules.audit.repository;

import com.classroom.modules.audit.model.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, String> {
    List<AuditEvent> findByClassIdOrderByCreatedAtDesc(String classId);

    /** R19-10: the most recent event with one of {@code actions} on one target (who last restricted a member). */
    Optional<AuditEvent> findFirstByClassIdAndTargetTypeAndTargetIdAndActionInOrderByCreatedAtDesc(
            String classId, String targetType, String targetId, Collection<String> actions);
}
