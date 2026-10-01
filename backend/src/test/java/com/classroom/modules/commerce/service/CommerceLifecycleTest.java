package com.classroom.modules.commerce.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.commerce.dto.OrderDto;
import com.classroom.modules.commerce.dto.WebhookPayload;
import com.classroom.modules.commerce.model.Entitlement;
import com.classroom.modules.commerce.model.Order;
import com.classroom.modules.commerce.model.Product;
import com.classroom.modules.commerce.model.ProductPrice;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * R13-03: product archive ("gỡ bán") keeps existing entitlements valid while blocking new
 * purchases, product edits only affect future orders, buyer order-cancel state machine, and the
 * late-webhook-on-cancelled-order edge case.
 */
@ExtendWith(MockitoExtension.class)
class CommerceLifecycleTest {

    @Mock private ProductRepository productRepository;
    @Mock private ProductPriceRepository priceRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private EntitlementRepository entitlementRepository;
    @Mock private CourseRepository courseRepository;
    @Mock private AccessPolicy accessPolicy;
    @Mock private PaymentProvider paymentProvider;
    @Mock private OutboxService outboxService;
    @Mock private AuditService auditService;

    @InjectMocks
    private CommerceService commerceService;

    private Product product;

    @BeforeEach
    void setUp() {
        product = new Product("class-1", null, "Gói PRO", "Mô tả");
        product.setId("prod-1");
        product.setStatus("PUBLISHED");
    }

    @Test
    @DisplayName("archiveProduct flips PUBLISHED -> ARCHIVED and is gated on STORE:EDIT")
    void archiveProductRequiresPermission() {
        when(productRepository.findByIdForUpdate("prod-1")).thenReturn(Optional.of(product));
        doThrow(new AppException(ErrorCode.STAFF_PERMISSION_DENIED, "denied"))
                .when(accessPolicy).enforceManage("intruder", "class-1", "STORE", "EDIT", null);
        assertThrows(AppException.class, () -> commerceService.archiveProduct("prod-1", "intruder"));

        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        Product result = commerceService.archiveProduct("prod-1", "owner-1");
        assertEquals("ARCHIVED", result.getStatus());
    }

    @Test
    @DisplayName("updateProduct changing price/duration never touches existing order price snapshots (edits only apply to future orders)")
    void updateProductOnlyTouchesProductPriceRow() {
        when(productRepository.findByIdForUpdate("prod-1")).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        ProductPrice price = new ProductPrice("prod-1", new BigDecimal("100000"), "VND", 30, Instant.now());
        when(priceRepository.findByProductId("prod-1")).thenReturn(Optional.of(price));
        when(priceRepository.save(any(ProductPrice.class))).thenAnswer(inv -> inv.getArgument(0));

        commerceService.updateProduct("prod-1", "Tên mới", null, new BigDecimal("200000"), 60, "owner-1");

        // Only product_prices row is touched for future orders; no order/order_item repository writes.
        verify(priceRepository).save(argThat(p -> p.getPrice().compareTo(new BigDecimal("200000")) == 0 && p.getDurationDays() == 60));
        verifyNoInteractions(orderItemRepository);
    }

    @Test
    @DisplayName("updateProduct is rejected once the product has been archived")
    void updateProductRejectedAfterArchive() {
        product.setStatus("ARCHIVED");
        when(productRepository.findByIdForUpdate("prod-1")).thenReturn(Optional.of(product));
        AppException ex = assertThrows(AppException.class,
                () -> commerceService.updateProduct("prod-1", "x", null, null, null, "owner-1"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    @DisplayName("cancelOrder: buyer can cancel own PENDING order; rejects cancelling another buyer's order or a non-PENDING order")
    void cancelOrderStateMachine() {
        Order pending = new Order("ORD-1", "buyer-1", "class-1", new BigDecimal("100000"), "VND", "MOCK");
        pending.setId("order-1");
        pending.setStatus("PENDING");
        when(orderRepository.findByIdForUpdate("order-1")).thenReturn(Optional.of(pending));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(paymentProvider.createPaymentSession(any())).thenReturn("http://checkout");

        OrderDto dto = commerceService.cancelOrder("order-1", "buyer-1");
        assertEquals("CANCELLED", dto.getStatus());

        // Cross-buyer cancellation must be forbidden (IDOR)
        Order pending2 = new Order("ORD-2", "buyer-1", "class-1", new BigDecimal("100000"), "VND", "MOCK");
        pending2.setId("order-2");
        pending2.setStatus("PENDING");
        when(orderRepository.findByIdForUpdate("order-2")).thenReturn(Optional.of(pending2));
        assertThrows(AppException.class, () -> commerceService.cancelOrder("order-2", "other-buyer"));

        // Already-PAID order cannot be cancelled through this path
        Order paid = new Order("ORD-3", "buyer-1", "class-1", new BigDecimal("100000"), "VND", "MOCK");
        paid.setId("order-3");
        paid.setStatus("PAID");
        when(orderRepository.findByIdForUpdate("order-3")).thenReturn(Optional.of(paid));
        AppException ex = assertThrows(AppException.class, () -> commerceService.cancelOrder("order-3", "buyer-1"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    @DisplayName("late PAYMENT_SUCCESS webhook for a CANCELLED order is handled idempotently without granting an entitlement")
    void lateWebhookOnCancelledOrderDoesNotGrantEntitlement() {
        Order cancelled = new Order("ORD-9", "buyer-1", "class-1", new BigDecimal("100000"), "VND", "MOCK");
        cancelled.setId("order-9");
        cancelled.setStatus("CANCELLED");
        when(orderRepository.findByOrderNumberForUpdate("ORD-9")).thenReturn(Optional.of(cancelled));
        when(paymentProvider.getProviderCode()).thenReturn("MOCK");
        when(paymentProvider.verifyWebhookSignature(any(), any())).thenReturn(true);

        WebhookPayload payload = new WebhookPayload();
        payload.setOrderNumber("ORD-9");
        payload.setEventType("PAYMENT_SUCCESS");
        payload.setAmount(new BigDecimal("100000"));
        payload.setCurrency("VND");
        payload.setProviderRef("ref-9");

        OrderDto result = commerceService.handlePaymentWebhook(payload, "raw", "sig");
        assertEquals("CANCELLED", result.getStatus());
        verify(entitlementRepository, never()).save(any(Entitlement.class));
    }

    // R13-06 (FR-07/TC-17): GET /me/orders.

    @Test
    @DisplayName("R13-06: getMyOrders returns only the caller's own orders, optionally scoped to one class")
    void getMyOrdersReturnsOwnOrdersScopedToClass() {
        Order orderInClassA = new Order("ORD-A", "buyer-1", "class-a", new BigDecimal("50000"), "VND", "MOCK");
        orderInClassA.setId("order-a");
        orderInClassA.setStatus("PAID");
        Order orderInClassB = new Order("ORD-B", "buyer-1", "class-b", new BigDecimal("70000"), "VND", "MOCK");
        orderInClassB.setId("order-b");
        orderInClassB.setStatus("PENDING");

        when(orderRepository.findByBuyerIdOrderByCreatedAtDesc("buyer-1"))
                .thenReturn(List.of(orderInClassA, orderInClassB));
        when(orderItemRepository.findByOrderIdIn(anyList())).thenReturn(List.of());
        when(paymentProvider.createPaymentSession(any())).thenReturn(null);

        List<OrderDto> scoped = commerceService.getMyOrders("buyer-1", "class-a");
        assertEquals(1, scoped.size());
        assertEquals("order-a", scoped.get(0).getId());

        List<OrderDto> all = commerceService.getMyOrders("buyer-1", null);
        assertEquals(2, all.size());
    }

    @Test
    @DisplayName("R13-06: getMyOrders surfaces refundedAt for a REFUNDED order")
    void getMyOrdersIncludesRefundedAt() {
        Order refunded = new Order("ORD-R", "buyer-1", "class-a", new BigDecimal("50000"), "VND", "MOCK");
        refunded.setId("order-r");
        refunded.setStatus("REFUNDED");
        Instant refundedAt = Instant.parse("2026-02-01T00:00:00Z");
        refunded.setRefundedAt(refundedAt);

        when(orderRepository.findByBuyerIdOrderByCreatedAtDesc("buyer-1")).thenReturn(List.of(refunded));
        when(orderItemRepository.findByOrderIdIn(anyList())).thenReturn(List.of());
        when(paymentProvider.createPaymentSession(any())).thenReturn(null);

        List<OrderDto> result = commerceService.getMyOrders("buyer-1", null);
        assertEquals(1, result.size());
        assertEquals(refundedAt, result.get(0).getRefundedAt());
    }

    // ----- R16-02: publish is DRAFT -> PUBLISHED only, and audited -----

    @Test
    @DisplayName("R16-02: publishProduct moves DRAFT -> PUBLISHED and writes a PRODUCT_PUBLISH audit event")
    void publishProductFromDraftIsAudited() {
        product.setStatus("DRAFT");
        when(productRepository.findByIdForUpdate("prod-1")).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        Product result = commerceService.publishProduct("prod-1", "owner-1");

        assertEquals("PUBLISHED", result.getStatus());
        verify(auditService).record(eq("class-1"), eq("owner-1"), eq("PRODUCT_PUBLISH"), eq("PRODUCT"), eq("prod-1"), anyString());
    }

    @Test
    @DisplayName("R16-02: publishProduct refuses an ARCHIVED product (must go through restore) - no state change, no audit")
    void publishProductRefusesArchived() {
        product.setStatus("ARCHIVED");
        when(productRepository.findByIdForUpdate("prod-1")).thenReturn(Optional.of(product));

        AppException ex = assertThrows(AppException.class, () -> commerceService.publishProduct("prod-1", "owner-1"));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertEquals("ARCHIVED", product.getStatus());
        verify(productRepository, never()).save(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("R16-02: publishProduct on an already PUBLISHED product is an idempotent no-op")
    void publishProductAlreadyPublishedIsIdempotent() {
        when(productRepository.findByIdForUpdate("prod-1")).thenReturn(Optional.of(product));

        Product result = commerceService.publishProduct("prod-1", "owner-1");

        assertEquals("PUBLISHED", result.getStatus());
        verify(productRepository, never()).save(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("R16-02: publishProduct still requires STORE:PUBLISH")
    void publishProductStillNeedsPublishPermission() {
        product.setStatus("DRAFT");
        when(productRepository.findByIdForUpdate("prod-1")).thenReturn(Optional.of(product));
        doThrow(new AppException(ErrorCode.STAFF_PERMISSION_DENIED, "denied"))
                .when(accessPolicy).enforceManage("intruder", "class-1", "STORE", "PUBLISH", null);

        assertThrows(AppException.class, () -> commerceService.publishProduct("prod-1", "intruder"));
        assertEquals("DRAFT", product.getStatus());
        verify(productRepository, never()).save(any());
    }

    @Test
    @DisplayName("R16-02: the archive -> restore -> publish route still works end to end, each step audited")
    void archivedProductCanBeRepublishedViaRestore() {
        when(productRepository.findByIdForUpdate("prod-1")).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        commerceService.archiveProduct("prod-1", "owner-1");
        commerceService.restoreProduct("prod-1", "owner-1");
        Product result = commerceService.publishProduct("prod-1", "owner-1");

        assertEquals("PUBLISHED", result.getStatus());
        verify(auditService).record(any(), any(), eq("PRODUCT_ARCHIVE"), any(), any(), any());
        verify(auditService).record(any(), any(), eq("PRODUCT_RESTORE"), any(), any(), any());
        verify(auditService).record(any(), any(), eq("PRODUCT_PUBLISH"), any(), any(), any());
    }}
