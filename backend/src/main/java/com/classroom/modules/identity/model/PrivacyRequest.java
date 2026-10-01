package com.classroom.modules.identity.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "privacy_requests")
public class PrivacyRequest {
    @Id @Column(name = "user_id", length = 36) public String userId;
    @Column(length = 36, nullable = false, unique = true) public String id;
    @Column(length = 24, nullable = false) public String status;
    @Column(length = 2000) public String reason;
    @Column(length = 2000) public String resolution;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
    @Column(name = "resolved_by", length = 36) public String resolvedBy;
}
