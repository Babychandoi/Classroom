package com.classroom.modules.commerce.policy;

import com.classroom.common.AppException;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ProPolicyTest {

    @Mock
    private AccessPolicy accessPolicy;
    @Mock
    private EntitlementRepository entitlementRepository;

    @InjectMocks
    private ProPolicy proPolicy;

    @Test
    @DisplayName("UT-03: Active entitlement grants PRO status; expired/revoked reverts to FREE")
    void testProStatusLifecycle() {
        String proUserId = "student-pro";
        String classId = "class-1";

        when(accessPolicy.isOwner(proUserId, classId)).thenReturn(false);
        when(accessPolicy.isMember(proUserId, classId)).thenReturn(true);

        // Active entitlement -> isPro = true
        when(entitlementRepository.hasActiveProEntitlement(eq(proUserId), eq(classId), any(Instant.class)))
                .thenReturn(true);
        assertTrue(proPolicy.isPro(proUserId, classId));

        // When entitlement expires or is revoked -> isPro = false
        when(entitlementRepository.hasActiveProEntitlement(eq(proUserId), eq(classId), any(Instant.class)))
                .thenReturn(false);
        assertFalse(proPolicy.isPro(proUserId, classId));
        assertThrows(AppException.class, () -> proPolicy.enforcePro(proUserId, classId));
    }

    @Test
    @DisplayName("OWNER is always considered PRO in their own classroom")
    void testOwnerIsAlwaysPro() {
        when(accessPolicy.isOwner("owner-1", "class-1")).thenReturn(true);
        assertTrue(proPolicy.isPro("owner-1", "class-1"));
    }
}
