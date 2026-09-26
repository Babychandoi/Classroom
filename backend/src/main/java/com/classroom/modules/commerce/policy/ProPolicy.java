package com.classroom.modules.commerce.policy;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ProPolicy {

    private final AccessPolicy accessPolicy;
    private final EntitlementRepository entitlementRepository;

    public ProPolicy(AccessPolicy accessPolicy, EntitlementRepository entitlementRepository) {
        this.accessPolicy = accessPolicy;
        this.entitlementRepository = entitlementRepository;
    }

    public boolean isPro(String userId, String classId) {
        if (userId == null || classId == null) return false;

        // OWNER is always PRO in their own classroom
        if (accessPolicy.isOwner(userId, classId)) {
            return true;
        }

        if (!accessPolicy.isMember(userId, classId)) return false;

        Instant now = Instant.now();
        return entitlementRepository.hasActiveProEntitlement(userId, classId, now);
    }

    public void enforcePro(String userId, String classId) {
        if (!isPro(userId, classId)) {
            throw new AppException(ErrorCode.PRO_MEMBERSHIP_REQUIRED, "Nội dung này dành riêng cho hội viên PRO của lớp học");
        }
    }
}
