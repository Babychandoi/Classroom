package com.classroom.modules.commerce.policy;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * R16-08: batch form of {@link #isPro} for a class listing. {@code ownedClassIds} are classes the user
     * owns (always PRO there); {@code activeMemberClassIds} are classes where the user has an ACTIVE
     * membership - of those, PRO needs an active entitlement, resolved for all of them in one query.
     * A class the user neither owns nor actively belongs to is never PRO.
     */
    public Set<String> proClassIds(String userId, Collection<String> ownedClassIds,
                                   Collection<String> activeMemberClassIds) {
        Set<String> pro = new HashSet<>(ownedClassIds);
        if (userId == null) return pro;
        List<String> candidates = activeMemberClassIds.stream().filter(id -> !pro.contains(id)).distinct().toList();
        if (!candidates.isEmpty()) {
            pro.addAll(entitlementRepository.findClassIdsWithActiveEntitlement(userId, candidates, Instant.now()));
        }
        return pro;
    }

    /**
     * R20-03: batch form of {@link #isPro} for a page of members of ONE class. {@code activeMemberIds} are the page's members
     * whose roster row is ACTIVE; the owner is always PRO, everybody else needs an active entitlement, resolved for all of them
     * in one query. A member who is not ACTIVE (removed / blocked) is never PRO, exactly as {@link #isPro} answers.
     */
    public Set<String> proUserIds(String classId, Collection<String> pageUserIds, Collection<String> activeMemberIds) {
        Set<String> pro = new HashSet<>();
        if (classId == null || pageUserIds == null || pageUserIds.isEmpty()) return pro;
        for (String userId : pageUserIds) {
            if (accessPolicy.isOwner(userId, classId)) pro.add(userId);
        }
        List<String> candidates = activeMemberIds.stream().filter(id -> !pro.contains(id)).distinct().toList();
        if (!candidates.isEmpty()) {
            pro.addAll(entitlementRepository.findUserIdsWithActiveEntitlement(classId, candidates, Instant.now()));
        }
        return pro;
    }

    public void enforcePro(String userId, String classId) {
        if (!isPro(userId, classId)) {
            throw new AppException(ErrorCode.PRO_MEMBERSHIP_REQUIRED, "Nội dung này dành riêng cho hội viên PRO của lớp học");
        }
    }
}
