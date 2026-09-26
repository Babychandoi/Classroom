package com.classroom.modules.audit.repository;

import com.classroom.modules.audit.model.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, String> {
    List<AuditEvent> findByClassIdOrderByCreatedAtDesc(String classId);
}
