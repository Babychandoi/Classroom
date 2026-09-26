package com.classroom.modules.commerce.controller;

import com.classroom.common.ApiResponse;
import com.classroom.common.AppException;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.commerce.dto.OrderDto;
import com.classroom.modules.commerce.dto.WebhookPayload;
import com.classroom.modules.commerce.model.Order;
import com.classroom.modules.commerce.payment.MockPaymentProvider;
import com.classroom.modules.commerce.repository.OrderRepository;
import com.classroom.modules.commerce.service.CommerceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class MockPaymentSecurityTest {

    @Test
    @DisplayName("Mock settlement is available in explicitly configured Docker sandbox, never production")
    void testMockSettlementIsExcludedFromDockerAndFailsClosedByDefault() {
        Profile profile = MockPaymentSimulationController.class.getAnnotation(Profile.class);
        assertNotNull(profile);
        assertTrue(java.util.Arrays.asList(profile.value()).contains("docker"));
        assertFalse(java.util.Arrays.asList(profile.value()).contains("prod"));
        ConditionalOnProperty condition = MockPaymentSimulationController.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(condition);
        assertEquals("true", condition.havingValue());
        assertFalse(condition.matchIfMissing());
    }

    @Mock
    private CommerceService commerceService;
    @Mock
    private MockPaymentProvider mockPaymentProvider;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private AccessPolicy accessPolicy;

    private ObjectMapper objectMapper = new ObjectMapper();
    private MockPaymentSimulationController controller;

    private Order order;

    @BeforeEach
    void setUp() {
        controller = new MockPaymentSimulationController(
                commerceService,
                mockPaymentProvider,
                orderRepository,
                accessPolicy,
                objectMapper
        );

        order = new Order("ORD-1234", "buyer-1", "class-1", new BigDecimal("199000"), "VND", "MOCK");
        order.setStatus("PENDING");
    }

    @Test
    @DisplayName("Sandbox status reports the checkout rail truthfully, including its operator-simulated settlement")
    void sandboxReportsCheckoutAvailabilityAndSettlementMode() {
        when(mockPaymentProvider.getProviderCode()).thenReturn("MOCK");
        ReflectionTestUtils.setField(controller, "mockCheckoutEnabled", true);
        var response = controller.getSandboxStatus();
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().getData().get("sandboxAvailable"));
        assertEquals(true, response.getBody().getData().get("checkoutAvailable"));
        // Settlement is never presented as a real payment rail.
        assertEquals("OPERATOR_SIMULATED", response.getBody().getData().get("settlementMode"));
        assertEquals("MOCK", response.getBody().getData().get("providerCode"));
    }

    @Test
    @DisplayName("No checkout is advertised while the sandbox checkout rail is disabled")
    void sandboxReportsNoCheckoutWhenDisabled() {
        when(mockPaymentProvider.getProviderCode()).thenReturn("MOCK");
        ReflectionTestUtils.setField(controller, "mockCheckoutEnabled", false);
        var response = controller.getSandboxStatus();
        assertNotNull(response.getBody());
        assertEquals(false, response.getBody().getData().get("sandboxAvailable"));
        assertEquals(false, response.getBody().getData().get("checkoutAvailable"));
    }

    @Test
    @DisplayName("Finding 1: simulateWebhook rejects unauthenticated caller")
    void testSimulateRejectsUnauthenticated() {
        WebhookPayload payload = new WebhookPayload("ORD-1234", "TX-1", "PAYMENT_SUCCESS", new BigDecimal("199000"), "VND");
        assertThrows(AppException.class, () -> controller.simulateWebhook(null, payload));
    }

    @Test
    @DisplayName("Finding 1: simulateWebhook rejects unauthorized caller who is not buyer, owner, or store staff")
    void testSimulateRejectsUnauthorizedUser() {
        UserPrincipal principal = new UserPrincipal("attacker-id", "attacker@test.local", "", "Attacker", "USER", "ACTIVE");
        when(orderRepository.findByOrderNumber("ORD-1234")).thenReturn(Optional.of(order));
        when(accessPolicy.isOwner("attacker-id", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("attacker-id", "class-1", "STORE", "EDIT", null)).thenReturn(false);

        WebhookPayload payload = new WebhookPayload("ORD-1234", "TX-1", "PAYMENT_SUCCESS", new BigDecimal("199000"), "VND");
        assertThrows(AppException.class, () -> controller.simulateWebhook(principal, payload));
    }

    @Test
    @DisplayName("Buyer cannot settle their own pending order (no self-granted entitlement)")
    void testBuyerCannotSettleOwnPendingOrder() {
        UserPrincipal principal = new UserPrincipal("buyer-1", "buyer@test.local", "", "Buyer", "USER", "ACTIVE");
        when(orderRepository.findByOrderNumber("ORD-1234")).thenReturn(Optional.of(order));
        when(accessPolicy.isOwner("buyer-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("buyer-1", "class-1", "STORE", "EDIT", null)).thenReturn(false);

        WebhookPayload payload = new WebhookPayload("ORD-1234", "TX-1", "PAYMENT_SUCCESS", new BigDecimal("199000"), "VND");

        AppException ex = assertThrows(AppException.class, () -> controller.simulateWebhook(principal, payload));
        assertEquals(com.classroom.common.ErrorCode.FORBIDDEN, ex.getErrorCode());
        verifyNoInteractions(commerceService);
    }

    @Test
    @DisplayName("Buyer cannot settle their own order by omitting the event type either")
    void testBuyerCannotSettleWithDefaultEventType() {
        UserPrincipal principal = new UserPrincipal("buyer-1", "buyer@test.local", "", "Buyer", "USER", "ACTIVE");
        when(orderRepository.findByOrderNumber("ORD-1234")).thenReturn(Optional.of(order));
        when(accessPolicy.isOwner("buyer-1", "class-1")).thenReturn(false);
        when(accessPolicy.canManage("buyer-1", "class-1", "STORE", "EDIT", null)).thenReturn(false);

        WebhookPayload payload = new WebhookPayload("ORD-1234", "TX-1", null, new BigDecimal("199000"), "VND");

        assertThrows(AppException.class, () -> controller.simulateWebhook(principal, payload));
        verifyNoInteractions(commerceService);
    }

    @Test
    @DisplayName("Finding 1 & 7: simulateWebhook constructs safe payload from DB and signs server-side when called by OWNER")
    void testSimulateConstructsSafePayload() throws Exception {
        UserPrincipal principal = new UserPrincipal("owner-1", "owner@test.local", "", "Owner", "USER", "ACTIVE");
        when(orderRepository.findByOrderNumber("ORD-1234")).thenReturn(Optional.of(order));
        when(accessPolicy.isOwner("owner-1", "class-1")).thenReturn(true);
        when(mockPaymentProvider.generateSignature(any())).thenReturn("mock-sig");

        OrderDto orderDto = new OrderDto();
        orderDto.setStatus("PAID");
        when(commerceService.handlePaymentWebhook(eq("MOCK"), any(), any(), eq("mock-sig"))).thenReturn(orderDto);

        // Caller attempts to spoof amount (claims 100 VND instead of 199000)
        WebhookPayload callerPayload = new WebhookPayload("ORD-1234", "TX-1", "PAYMENT_SUCCESS", new BigDecimal("100"), "VND");

        ResponseEntity<ApiResponse<OrderDto>> response = controller.simulateWebhook(principal, callerPayload);

        assertNotNull(response.getBody());
        assertEquals("PAID", response.getBody().getData().getStatus());

        // Verify that handlePaymentWebhook was invoked with the authoritative amount (199000) from DB, not 100
        verify(commerceService).handlePaymentWebhook(
                eq("MOCK"),
                argThat(p -> p.getAmount().compareTo(new BigDecimal("199000")) == 0),
                any(),
                eq("mock-sig")
        );
    }
}
