package com.classroom.modules.commerce.service;

import com.classroom.common.AppException;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.commerce.dto.CreateOrderRequest;
import com.classroom.modules.commerce.dto.OrderDto;
import com.classroom.modules.commerce.dto.WebhookPayload;
import com.classroom.modules.commerce.model.Entitlement;
import com.classroom.modules.commerce.model.Order;
import com.classroom.modules.commerce.model.OrderItem;
import com.classroom.modules.commerce.model.Product;
import com.classroom.modules.commerce.payment.PaymentProvider;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import com.classroom.modules.commerce.repository.OrderItemRepository;
import com.classroom.modules.commerce.repository.OrderRepository;
import com.classroom.modules.commerce.repository.ProductPriceRepository;
import com.classroom.modules.commerce.repository.ProductRepository;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.outbox.service.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class OrderIdempotencyTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private ProductPriceRepository priceRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private EntitlementRepository entitlementRepository;
    @Mock
    private CourseRepository courseRepository;
    @Mock
    private AccessPolicy accessPolicy;
    @Mock
    private PaymentProvider paymentProvider;
    @Mock
    private OutboxService outboxService;
    @Mock
    private AuditService auditService;

    @InjectMocks
    private CommerceService commerceService;

    private Order order;
    private OrderItem item;
    private Product product;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(commerceService, "mockCheckoutEnabled", true);
        lenient().when(paymentProvider.getProviderCode()).thenReturn("MOCK");
        order = new Order("ORD-TEST-123", "buyer-1", "class-1", new BigDecimal("299000"), "VND", "MOCK");
        order.setId("order-id-1");

        product = new Product("class-1", null, "Gói PRO 30 Ngày", "Mô tả PRO");
        product.setId("prod-1");

        item = new OrderItem(order.getId(), product.getId(), product.getTitle(), new BigDecimal("299000"), 30);
        // R19-01: a refund locks the products of the order's ITEMS before it reads the entitlements.
        lenient().when(orderItemRepository.findByOrderId("order-id-1")).thenReturn(List.of(item));
    }

    @Test
    @DisplayName("Idempotency key is normalized once: a retry with surrounding whitespace replays the original order")
    void testWhitespaceIdempotencyKeyReplaysOriginalOrder() {
        // The first request stored the key. It must be stored trimmed, so the trimmed lookup finds it.
        order.setIdempotencyKey("key-1");
        when(orderRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId("order-id-1")).thenReturn(List.of(item));

        CreateOrderRequest retry = new CreateOrderRequest();
        retry.setClassId("class-1");
        retry.setProductId("prod-1");
        retry.setIdempotencyKey("  key-1  ");

        OrderDto replayed = commerceService.createOrder("buyer-1", retry);

        assertEquals("ORD-TEST-123", replayed.getOrderNumber());
        // A replay must not create a second order.
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Idempotency key is stored trimmed so a later trimmed lookup can find the order")
    void testIdempotencyKeyIsStoredNormalized() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setClassId("class-1");
        request.setProductId("prod-1");
        request.setIdempotencyKey("  key-2  ");

        product.setStatus("PUBLISHED");
        when(orderRepository.findByIdempotencyKey("key-2")).thenReturn(Optional.empty());
        when(productRepository.findByIdForUpdate("prod-1")).thenReturn(Optional.of(product));
        when(priceRepository.findByProductId("prod-1")).thenReturn(
                Optional.of(new com.classroom.modules.commerce.model.ProductPrice("prod-1", new BigDecimal("299000"), "VND", 30)));
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        commerceService.createOrder("buyer-1", request);

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).saveAndFlush(saved.capture());
        assertEquals("key-2", saved.getValue().getIdempotencyKey());
    }

    @Test
    @DisplayName("UT-06 / TC-06: Duplicate webhook for an already PAID order is ignored idempotently without duplicating entitlements")
    void testDuplicateWebhookIgnored() {
        order.setStatus("PAID");
        order.setProviderRef("MOCK-REF-1");
        when(paymentProvider.verifyWebhookSignature(any(), any())).thenReturn(true);
        when(orderRepository.findByOrderNumber("ORD-TEST-123")).thenReturn(Optional.of(order));

        WebhookPayload payload = new WebhookPayload("ORD-TEST-123", "MOCK-REF-1", "PAYMENT_SUCCESS", new BigDecimal("299000"), "VND");

        OrderDto result = commerceService.handlePaymentWebhook(payload, "{}", "valid-sig");

        assertEquals("PAID", result.getStatus());
        // Verify no new entitlement is saved
        verify(entitlementRepository, never()).save(any());
        verify(outboxService, never()).recordEvent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Reject a signed duplicate success event with a conflicting provider reference")
    void testDuplicateWebhookRejectsConflictingProviderReference() {
        order.setStatus("PAID");
        order.setProviderRef("MOCK-REF-1");
        when(paymentProvider.verifyWebhookSignature(any(), any())).thenReturn(true);
        when(orderRepository.findByOrderNumber("ORD-TEST-123")).thenReturn(Optional.of(order));

        WebhookPayload payload = new WebhookPayload("ORD-TEST-123", "MOCK-REF-ATTACKER",
                "PAYMENT_SUCCESS", new BigDecimal("299000"), "VND");

        assertThrows(AppException.class,
                () -> commerceService.handlePaymentWebhook(payload, "{}", "valid-sig"));
        verify(entitlementRepository, never()).save(any());
        verify(outboxService, never()).recordEvent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("TC-06: First PAID webhook successfully marks order PAID, creates entitlement and outbox event")
    void testFirstSuccessfulWebhook() {
        order.setStatus("PENDING");
        when(paymentProvider.verifyWebhookSignature(any(), any())).thenReturn(true);
        when(orderRepository.findByOrderNumber("ORD-TEST-123")).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId("order-id-1")).thenReturn(List.of(item));
        when(productRepository.findByIdForUpdate("prod-1")).thenReturn(Optional.of(product));
        when(entitlementRepository.findLatestActiveByProductForUpdate(eq("buyer-1"), eq("class-1"), eq("prod-1"), any())).thenReturn(List.of());

        WebhookPayload payload = new WebhookPayload("ORD-TEST-123", "MOCK-REF-1", "PAYMENT_SUCCESS", new BigDecimal("299000"), "VND");

        OrderDto result = commerceService.handlePaymentWebhook(payload, "{}", "valid-sig");

        assertEquals("PAID", result.getStatus());
        verify(orderRepository).save(order);
        verify(entitlementRepository).save(any(Entitlement.class));
        verify(outboxService).recordEvent(eq("COMMERCE"), eq("order-id-1"), eq("ORDER_PAID"), any());
    }

    @Test
    @DisplayName("TC-07: PAYMENT_REFUNDED revokes entitlement immediately")
    void testRefundWebhookRevokesEntitlement() {
        order.setStatus("PAID");
        order.setProviderRef("MOCK-REF-1");
        Entitlement entitlement = new Entitlement("buyer-1", "class-1", "prod-1", null, Instant.now(), Instant.now().plus(30, ChronoUnit.DAYS));
        entitlement.setState("ACTIVE");
        entitlement.setOrderId("order-id-1");

        when(paymentProvider.verifyWebhookSignature(any(), any())).thenReturn(true);
        when(orderRepository.findByOrderNumber("ORD-TEST-123")).thenReturn(Optional.of(order));
        when(entitlementRepository.findByOrderIdForUpdate("order-id-1")).thenReturn(List.of(entitlement));
        when(productRepository.findByIdForUpdate("prod-1")).thenReturn(Optional.of(product));
        when(entitlementRepository.findFutureActiveByProductAscForUpdate(eq("buyer-1"), eq("class-1"), eq("prod-1"), any())).thenReturn(List.of(entitlement));

        WebhookPayload payload = new WebhookPayload("ORD-TEST-123", "MOCK-REF-1", "PAYMENT_REFUNDED", new BigDecimal("299000"), "VND");

        OrderDto result = commerceService.handlePaymentWebhook(payload, "{}", "valid-sig");

        assertEquals("REFUNDED", result.getStatus());
        assertEquals("REVOKED", entitlement.getState());
        verify(entitlementRepository, atLeastOnce()).save(entitlement);
        verify(outboxService).recordEvent(eq("COMMERCE"), eq("order-id-1"), eq("ORDER_REFUNDED"), any());
    }

    @Test
    @DisplayName("Finding 6: Replay of existing idempotency key by different buyer is rejected with FORBIDDEN")
    void testIdempotencyKeyRejectedForDifferentBuyer() {
        Order existingOrder = new Order("ORD-EXISTING", "buyer-1", "class-1", new BigDecimal("299000"), "VND", "MOCK");
        existingOrder.setIdempotencyKey("idemp-key-xyz");

        when(orderRepository.findByIdempotencyKey("idemp-key-xyz")).thenReturn(Optional.of(existingOrder));

        com.classroom.modules.commerce.dto.CreateOrderRequest req = new com.classroom.modules.commerce.dto.CreateOrderRequest();
        req.setClassId("class-1");
        req.setProductId("prod-1");
        req.setIdempotencyKey("idemp-key-xyz");

        // Buyer-2 attempts to use buyer-1's idempotency key
        AppException ex = assertThrows(AppException.class, () ->
                commerceService.createOrder("buyer-2", req)
        );
        assertEquals(com.classroom.common.ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    @DisplayName("Finding 7: createOrder without Idempotency-Key is rejected")
    void testCreateOrderWithoutIdempotencyKeyRejected() {
        CreateOrderRequest req = new CreateOrderRequest();
        req.setClassId("class-1");
        req.setProductId("prod-1");
        // No idempotency key

        AppException ex = assertThrows(AppException.class, () ->
                commerceService.createOrder("buyer-1", req)
        );
        assertEquals(com.classroom.common.ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    @DisplayName("Refunding an earlier order preserves a future-dated surviving entitlement")
    void testRefundEarlierOrderPreservesFutureStartOfSurvivingEntitlement() {
        order.setStatus("PAID");
        order.setProviderRef("MOCK-REF-1");
        Instant now = Instant.now();

        // Entitlement 1 from order 1 (revoked)
        Entitlement e1 = new Entitlement("buyer-1", "class-1", "prod-1", null, now.minus(5, ChronoUnit.DAYS), now.plus(25, ChronoUnit.DAYS));
        e1.setId("e-1");
        e1.setState("ACTIVE");
        e1.setOrderId("order-id-1");

        // Entitlement 2 from order 2 (renewal scheduled after e1)
        Instant e2OriginalStart = now.plus(25, ChronoUnit.DAYS);
        Instant e2OriginalEnd = e2OriginalStart.plus(30, ChronoUnit.DAYS);
        Entitlement e2 = new Entitlement("buyer-1", "class-1", "prod-1", null, e2OriginalStart, e2OriginalEnd);
        e2.setId("e-2");
        e2.setState("ACTIVE");
        e2.setOrderId("order-id-2");

        when(paymentProvider.verifyWebhookSignature(any(), any())).thenReturn(true);
        when(orderRepository.findByOrderNumber("ORD-TEST-123")).thenReturn(Optional.of(order));
        when(entitlementRepository.findByOrderIdForUpdate("order-id-1")).thenReturn(List.of(e1));
        when(productRepository.findByIdForUpdate("prod-1")).thenReturn(Optional.of(product));
        when(orderItemRepository.findByOrderId("order-id-2")).thenReturn(List.of(
                new OrderItem("order-id-2", "prod-1", "Product", BigDecimal.ONE, 30, e2OriginalStart)));
        // Remaining active after e1 is revoked has an independently configured start.
        when(entitlementRepository.findFutureActiveByProductAscForUpdate(eq("buyer-1"), eq("class-1"), eq("prod-1"), any()))
                .thenReturn(List.of(e2));

        WebhookPayload payload = new WebhookPayload("ORD-TEST-123", "MOCK-REF-1", "PAYMENT_REFUNDED", new BigDecimal("299000"), "VND");
        commerceService.handlePaymentWebhook(payload, "{}", "valid-sig");

        assertEquals("REVOKED", e1.getState());
        // Refund reconciliation must not start the future purchase before its contract date.
        assertEquals(e2OriginalStart, e2.getStartsAt());
        assertEquals(java.time.Duration.ofDays(30), java.time.Duration.between(e2.getStartsAt(), e2.getExpiresAt()));
        verify(entitlementRepository).save(e2);
    }

    @Test
    @DisplayName("Refund reconciliation shifts overlapping renewals without violating their configured start")
    void testRefundReconciliationDoesNotMoveLaterRenewalBeforeConfiguredStart() {
        order.setStatus("PAID");
        order.setProviderRef("MOCK-REF-1");
        Instant now = Instant.now();

        Entitlement refunded = new Entitlement("buyer-1", "class-1", "prod-1", null,
                now.minus(5, ChronoUnit.DAYS), now.plus(25, ChronoUnit.DAYS));
        refunded.setState("ACTIVE");
        refunded.setOrderId("order-id-1");

        Entitlement futureEntitlement = new Entitlement("buyer-1", "class-1", "prod-1", null,
                now.plus(40, ChronoUnit.DAYS), now.plus(70, ChronoUnit.DAYS));
        futureEntitlement.setState("ACTIVE");
        futureEntitlement.setOrderId("order-id-2");

        Entitlement overlappingRenewal = new Entitlement("buyer-1", "class-1", "prod-1", null,
                now.plus(60, ChronoUnit.DAYS), now.plus(90, ChronoUnit.DAYS));
        overlappingRenewal.setState("ACTIVE");
        overlappingRenewal.setOrderId("order-id-3");

        when(paymentProvider.verifyWebhookSignature(any(), any())).thenReturn(true);
        when(orderRepository.findByOrderNumber("ORD-TEST-123")).thenReturn(Optional.of(order));
        when(entitlementRepository.findByOrderIdForUpdate("order-id-1")).thenReturn(List.of(refunded));
        when(productRepository.findByIdForUpdate("prod-1")).thenReturn(Optional.of(product));
        when(orderItemRepository.findByOrderId("order-id-2")).thenReturn(List.of(
                new OrderItem("order-id-2", "prod-1", "Product", BigDecimal.ONE, 30, now.plus(40, ChronoUnit.DAYS))));
        when(orderItemRepository.findByOrderId("order-id-3")).thenReturn(List.of(
                new OrderItem("order-id-3", "prod-1", "Product", BigDecimal.ONE, 30, now.plus(60, ChronoUnit.DAYS))));
        when(entitlementRepository.findFutureActiveByProductAscForUpdate(eq("buyer-1"), eq("class-1"), eq("prod-1"), any()))
                .thenReturn(List.of(futureEntitlement, overlappingRenewal));

        WebhookPayload payload = new WebhookPayload("ORD-TEST-123", "MOCK-REF-1", "PAYMENT_REFUNDED", new BigDecimal("299000"), "VND");
        commerceService.handlePaymentWebhook(payload, "{}", "valid-sig");

        assertEquals(now.plus(40, ChronoUnit.DAYS), futureEntitlement.getStartsAt());
        assertEquals(now.plus(70, ChronoUnit.DAYS), overlappingRenewal.getStartsAt());
        assertEquals(java.time.Duration.ofDays(30), java.time.Duration.between(overlappingRenewal.getStartsAt(), overlappingRenewal.getExpiresAt()));
    }

    @Test
    @DisplayName("Refunding a later renewal preserves the elapsed and remaining time of an active earlier purchase")
    void testRefundLaterRenewalPreservesEarlierActiveEntitlement() {
        order.setStatus("PAID");
        order.setProviderRef("MOCK-REF-1");
        Instant now = Instant.now();
        Instant originalStart = now.minus(20, ChronoUnit.DAYS);
        Instant originalExpiry = originalStart.plus(30, ChronoUnit.DAYS);
        Entitlement refundedRenewal = new Entitlement("buyer-1", "class-1", "prod-1", null,
                originalExpiry, originalExpiry.plus(30, ChronoUnit.DAYS));
        refundedRenewal.setState("ACTIVE");
        refundedRenewal.setOrderId("order-id-1");
        Entitlement survivingEarlierPurchase = new Entitlement("buyer-1", "class-1", "prod-1", null,
                originalStart, originalExpiry);
        survivingEarlierPurchase.setState("ACTIVE");
        survivingEarlierPurchase.setOrderId("order-id-earlier");

        when(paymentProvider.verifyWebhookSignature(any(), any())).thenReturn(true);
        when(orderRepository.findByOrderNumber("ORD-TEST-123")).thenReturn(Optional.of(order));
        when(entitlementRepository.findByOrderIdForUpdate("order-id-1")).thenReturn(List.of(refundedRenewal));
        when(productRepository.findByIdForUpdate("prod-1")).thenReturn(Optional.of(product));
        when(entitlementRepository.findFutureActiveByProductAscForUpdate(eq("buyer-1"), eq("class-1"), eq("prod-1"), any()))
                .thenReturn(List.of(survivingEarlierPurchase));

        WebhookPayload payload = new WebhookPayload("ORD-TEST-123", "MOCK-REF-1", "PAYMENT_REFUNDED", new BigDecimal("299000"), "VND");
        commerceService.handlePaymentWebhook(payload, "{}", "valid-sig");

        assertEquals("REVOKED", refundedRenewal.getState());
        assertEquals(originalStart, survivingEarlierPurchase.getStartsAt());
        assertEquals(originalExpiry, survivingEarlierPurchase.getExpiresAt());
        verify(entitlementRepository, never()).save(survivingEarlierPurchase);
    }

    @Test
    @DisplayName("Finding 3: Rejects refund on unpaid (PENDING) orders")
    void testRejectRefundOnUnpaidOrder() {
        order.setStatus("PENDING");
        when(paymentProvider.verifyWebhookSignature(any(), any())).thenReturn(true);
        when(orderRepository.findByOrderNumber("ORD-TEST-123")).thenReturn(Optional.of(order));

        WebhookPayload payload = new WebhookPayload("ORD-TEST-123", "MOCK-REF-1", "PAYMENT_REFUNDED", new BigDecimal("299000"), "VND");

        assertThrows(AppException.class, () -> commerceService.handlePaymentWebhook(payload, "{}", "valid-sig"));
    }

    @Test
    @DisplayName("Refund webhook must identify the paid provider transaction")
    void testRefundRejectsMismatchedProviderReference() {
        order.setStatus("PAID");
        order.setProviderRef("REAL-REF");
        when(paymentProvider.verifyWebhookSignature(any(), any())).thenReturn(true);
        when(orderRepository.findByOrderNumber("ORD-TEST-123")).thenReturn(Optional.of(order));
        WebhookPayload payload = new WebhookPayload("ORD-TEST-123", "OTHER-REF", "PAYMENT_REFUNDED", new BigDecimal("299000"), "VND");

        assertThrows(AppException.class, () -> commerceService.handlePaymentWebhook(payload, "{}", "valid-sig"));
        verify(orderRepository, never()).save(argThat(o -> "REFUNDED".equals(o.getStatus())));
        verify(entitlementRepository, never()).findByOrderIdForUpdate(anyString());
    }

    @Test
    @DisplayName("Refund webhook requires exact amount and currency")
    void testRefundRequiresAmountAndCurrency() {
        order.setStatus("PAID");
        order.setProviderRef("MOCK-REF-1");
        when(paymentProvider.verifyWebhookSignature(any(), any())).thenReturn(true);
        when(orderRepository.findByOrderNumber("ORD-TEST-123")).thenReturn(Optional.of(order));
        WebhookPayload missingAmount = new WebhookPayload("ORD-TEST-123", "MOCK-REF-1", "PAYMENT_REFUNDED", null, "VND");
        assertThrows(AppException.class, () -> commerceService.handlePaymentWebhook(missingAmount, "{}", "valid-sig"));
        WebhookPayload missingCurrency = new WebhookPayload("ORD-TEST-123", "MOCK-REF-1", "PAYMENT_REFUNDED", new BigDecimal("299000"), null);
        assertThrows(AppException.class, () -> commerceService.handlePaymentWebhook(missingCurrency, "{}", "valid-sig"));
        verify(entitlementRepository, never()).findByOrderIdForUpdate(anyString());
    }

    @Test
    @DisplayName("Finding 3: Rejects webhook when amount mismatches persisted order")
    void testRejectAmountMismatch() {
        order.setStatus("PENDING");
        when(paymentProvider.verifyWebhookSignature(any(), any())).thenReturn(true);
        when(orderRepository.findByOrderNumber("ORD-TEST-123")).thenReturn(Optional.of(order));

        // Tampered amount: 1000 instead of 299000
        WebhookPayload payload = new WebhookPayload("ORD-TEST-123", "MOCK-REF-1", "PAYMENT_SUCCESS", new BigDecimal("1000"), "VND");

        assertThrows(AppException.class, () -> commerceService.handlePaymentWebhook(payload, "{}", "valid-sig"));
    }

    @Test
    @DisplayName("Signed PAYMENT_SUCCESS webhook must include amount and currency before granting access")
    void successfulWebhookRequiresAmountAndCurrency() {
        order.setStatus("PENDING");
        when(paymentProvider.verifyWebhookSignature(any(), any())).thenReturn(true);
        when(orderRepository.findByOrderNumber("ORD-TEST-123")).thenReturn(Optional.of(order));

        WebhookPayload missingBoth = new WebhookPayload("ORD-TEST-123", "MOCK-REF-1", "PAYMENT_SUCCESS", null, null);
        assertThrows(AppException.class, () -> commerceService.handlePaymentWebhook(missingBoth, "{}", "valid-sig"));
        WebhookPayload missingAmount = new WebhookPayload("ORD-TEST-123", "MOCK-REF-1", "PAYMENT_SUCCESS", null, "VND");
        assertThrows(AppException.class, () -> commerceService.handlePaymentWebhook(missingAmount, "{}", "valid-sig"));
        WebhookPayload missingCurrency = new WebhookPayload("ORD-TEST-123", "MOCK-REF-1", "PAYMENT_SUCCESS", new BigDecimal("299000"), null);
        assertThrows(AppException.class, () -> commerceService.handlePaymentWebhook(missingCurrency, "{}", "valid-sig"));

        verify(orderRepository, never()).save(any());
        verify(entitlementRepository, never()).save(any());
        verify(outboxService, never()).recordEvent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Finding 7: Payment success records audit event and emits outbox event")
    void testAuditRecordedOnPaymentSuccess() {
        order.setStatus("PENDING");
        when(paymentProvider.verifyWebhookSignature(any(), any())).thenReturn(true);
        when(orderRepository.findByOrderNumber("ORD-TEST-123")).thenReturn(Optional.of(order));
        when(orderRepository.findByProviderRef("MOCK-REF-1")).thenReturn(Optional.empty());
        when(orderItemRepository.findByOrderId("order-id-1")).thenReturn(List.of(item));
        when(productRepository.findByIdForUpdate("prod-1")).thenReturn(Optional.of(product));
        when(entitlementRepository.findLatestActiveByProductForUpdate(any(), any(), any(), any())).thenReturn(List.of());

        WebhookPayload payload = new WebhookPayload("ORD-TEST-123", "MOCK-REF-1", "PAYMENT_SUCCESS", new BigDecimal("299000"), "VND");
        OrderDto result = commerceService.handlePaymentWebhook(payload, "{}", "valid-sig");

        assertEquals("PAID", result.getStatus());
        verify(auditService).record(eq("class-1"), eq("buyer-1"), eq("ORDER_PAID"), eq("ORDER"), eq("order-id-1"), any());
        verify(outboxService).recordEvent(eq("COMMERCE"), eq("order-id-1"), eq("ORDER_PAID"), any());
    }

    @Test
    @DisplayName("Finding 3: Webhook rejects unsupported payment provider path")
    void testWebhookRejectsUnsupportedProvider() {
        WebhookPayload payload = new WebhookPayload("ORD-TEST-123", "STRIPE-1", "PAYMENT_SUCCESS", new BigDecimal("299000"), "VND");
        AppException ex = assertThrows(AppException.class, () ->
                commerceService.handlePaymentWebhook("STRIPE", payload, "{}", "sig")
        );
        assertEquals(com.classroom.common.ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("không được hỗ trợ"));
    }

    @Test
    @DisplayName("Finding 3: Webhook rejects mismatched provider for order")
    void testWebhookRejectsMismatchedProviderForOrder() {
        // Order was created with provider "MOCK"
        order.setStatus("PENDING");
        PaymentProvider otherProvider = mock(PaymentProvider.class);
        when(otherProvider.getProviderCode()).thenReturn("VNPAY");
        when(otherProvider.verifyWebhookSignature(any(), any())).thenReturn(true);

        when(orderRepository.findByOrderNumber("ORD-TEST-123")).thenReturn(Optional.of(order));

        CommerceService serviceWithMultiple = new CommerceService(
                productRepository, priceRepository, orderRepository, orderItemRepository,
                entitlementRepository, courseRepository, accessPolicy,
                paymentProvider, List.of(paymentProvider, otherProvider), outboxService, auditService
        );

        WebhookPayload payload = new WebhookPayload("ORD-TEST-123", "VNPAY-1", "PAYMENT_SUCCESS", new BigDecimal("299000"), "VND");

        // Webhook arrives on /api/v1/payments/VNPAY/webhook, but order.provider is "MOCK"
        AppException ex = assertThrows(AppException.class, () ->
                serviceWithMultiple.handlePaymentWebhook("VNPAY", payload, "{}", "sig")
        );
        assertEquals(com.classroom.common.ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("không khớp với đơn hàng"));
    }

    // ----- R14-13 (D-14): an ARCHIVED class takes no NEW orders, but still replays an existing one -----

    @Test
    @DisplayName("R14-13: creating a NEW order in an ARCHIVED class is refused with a clear Vietnamese message")
    void newOrderRefusedInArchivedClass() {
        CreateOrderRequest request = new CreateOrderRequest();
        request.setClassId("class-1");
        request.setProductId("prod-1");
        request.setIdempotencyKey("archived-key");
        when(orderRepository.findByIdempotencyKey("archived-key")).thenReturn(Optional.empty());
        when(accessPolicy.isClassArchived("class-1")).thenReturn(true);

        AppException ex = assertThrows(AppException.class, () -> commerceService.createOrder("buyer-1", request));

        assertEquals(com.classroom.common.ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("Lớp học đã được lưu trữ"));
        verify(orderRepository, never()).saveAndFlush(any());
        verify(productRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("R14-13: replaying an order that already exists still works after the class is archived")
    void existingOrderStillReplaysInArchivedClass() {
        order.setIdempotencyKey("key-1");
        when(orderRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId("order-id-1")).thenReturn(List.of(item));

        CreateOrderRequest retry = new CreateOrderRequest();
        retry.setClassId("class-1");
        retry.setProductId("prod-1");
        retry.setIdempotencyKey("key-1");

        OrderDto replayed = commerceService.createOrder("buyer-1", retry);

        assertEquals("ORD-TEST-123", replayed.getOrderNumber());
        verify(accessPolicy, never()).isClassArchived(any());
    }
}
