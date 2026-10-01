package com.classroom.modules.commerce.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.policy.AccessPolicy;
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
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * R19-01 / R19-03 on the webhook path with mocked repositories: the lock ORDER (order row, then product rows,
 * then locking entitlement reads - never a plain read in front of the product lock), what happens when the
 * transaction fails (retryable conflict vs 500 with an audit trail vs refusal past the horizon), and the
 * stacking arithmetic. The behaviour under real MySQL concurrency is in Round19CommerceIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
class PaymentSettleHardeningTest {

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
        ReflectionTestUtils.setField(commerceService, "mockCheckoutEnabled", true);
        lenient().when(paymentProvider.getProviderCode()).thenReturn("MOCK");
        lenient().when(paymentProvider.verifyWebhookSignature(any(), any())).thenReturn(true);
        product = new Product("class-1", null, "Gói", "d");
        product.setId("prod-1");
    }

    private Order pendingOrder() {
        Order order = new Order("ORD-R19", "buyer-1", "class-1", new BigDecimal("1000"), "VND", "MOCK");
        order.setId("order-1");
        order.setStatus("PENDING");
        return order;
    }

    private OrderItem item(int days, Instant accessStartsAt) {
        return new OrderItem("order-1", "prod-1", "Gói", new BigDecimal("1000"), days, accessStartsAt);
    }

    private WebhookPayload success() {
        return new WebhookPayload("ORD-R19", "REF-1", "PAYMENT_SUCCESS", new BigDecimal("1000"), "VND");
    }

    /** A fresh Order per call, like a new transaction reading the (rolled back) row again. */
    private void orderIsReloadedEachAttempt() {
        when(orderRepository.findByOrderNumberForUpdate("ORD-R19")).thenAnswer(inv -> Optional.of(pendingOrder()));
    }

    // ----- lock order -----

    @Test
    @DisplayName("R19-01: settle takes the order row, then the product row, and only then reads anything else")
    void settleLocksOrderThenProductBeforeAnyOtherRead() {
        when(orderRepository.findByOrderNumberForUpdate("ORD-R19")).thenReturn(Optional.of(pendingOrder()));
        when(orderItemRepository.findByOrderId("order-1")).thenReturn(List.of(item(30, Instant.now().minusSeconds(60))));
        when(productRepository.findByIdForUpdate("prod-1")).thenReturn(Optional.of(product));
        when(entitlementRepository.findLatestActiveByProductForUpdate(eq("buyer-1"), eq("class-1"), eq("prod-1"), any())).thenReturn(List.of());

        OrderDto result = commerceService.handlePaymentWebhook(success(), "{}", "sig");

        assertEquals("PAID", result.getStatus());
        InOrder inOrder = inOrder(orderRepository, orderItemRepository, productRepository, entitlementRepository);
        inOrder.verify(orderRepository).findByOrderNumberForUpdate("ORD-R19");
        inOrder.verify(orderItemRepository).findByOrderId("order-1");
        inOrder.verify(productRepository).findByIdForUpdate("prod-1");
        inOrder.verify(orderRepository).findByProviderRef("REF-1"); // the plain read that used to sit BEFORE the lock
        inOrder.verify(entitlementRepository).findLatestActiveByProductForUpdate(eq("buyer-1"), eq("class-1"), eq("prod-1"), any());
        inOrder.verify(entitlementRepository).save(any(Entitlement.class));
        // The stale-snapshot readers are never used on the settle path.
        verify(entitlementRepository, never()).findLatestActiveByProduct(any(), any(), any(), any());
    }

    @Test
    @DisplayName("R19-01: an order with several products locks them in ascending id order")
    void multiProductOrderLocksProductsInAscendingOrder() {
        Product other = new Product("class-1", null, "Gói 2", "d");
        other.setId("prod-0");
        when(orderRepository.findByOrderNumberForUpdate("ORD-R19")).thenReturn(Optional.of(pendingOrder()));
        OrderItem second = new OrderItem("order-1", "prod-0", "Gói 2", new BigDecimal("1000"), 30, Instant.now().minusSeconds(60));
        when(orderItemRepository.findByOrderId("order-1")).thenReturn(List.of(item(30, Instant.now().minusSeconds(60)), second));
        when(productRepository.findByIdForUpdate("prod-1")).thenReturn(Optional.of(product));
        when(productRepository.findByIdForUpdate("prod-0")).thenReturn(Optional.of(other));

        commerceService.handlePaymentWebhook(success(), "{}", "sig");

        InOrder inOrder = inOrder(productRepository, entitlementRepository);
        inOrder.verify(productRepository).findByIdForUpdate("prod-0");
        inOrder.verify(productRepository).findByIdForUpdate("prod-1");
        inOrder.verify(entitlementRepository, atLeastOnce()).findLatestActiveByProductForUpdate(any(), any(), any(), any());
    }

    @Test
    @DisplayName("R19-01: refund takes order, then product, then reads the order's entitlements FOR UPDATE and re-chains with a locking read")
    void refundUsesTheSameLockOrderAndOnlyLockingReads() {
        Order paid = pendingOrder();
        paid.setStatus("PAID");
        paid.setProviderRef("REF-1");
        Entitlement e = new Entitlement("buyer-1", "class-1", "prod-1", null, Instant.now(), Instant.now().plus(30, ChronoUnit.DAYS));
        e.setOrderId("order-1");
        when(orderRepository.findByOrderNumberForUpdate("ORD-R19")).thenReturn(Optional.of(paid));
        when(orderItemRepository.findByOrderId("order-1")).thenReturn(List.of(item(30, Instant.now().minusSeconds(60))));
        when(productRepository.findByIdForUpdate("prod-1")).thenReturn(Optional.of(product));
        when(entitlementRepository.findByOrderIdForUpdate("order-1")).thenReturn(List.of(e));
        when(entitlementRepository.findFutureActiveByProductAscForUpdate(eq("buyer-1"), eq("class-1"), eq("prod-1"), any())).thenReturn(List.of());

        WebhookPayload refund = new WebhookPayload("ORD-R19", "REF-1", "PAYMENT_REFUNDED", new BigDecimal("1000"), "VND");
        commerceService.handlePaymentWebhook(refund, "{}", "sig");

        InOrder inOrder = inOrder(orderRepository, productRepository, entitlementRepository);
        inOrder.verify(orderRepository).findByOrderNumberForUpdate("ORD-R19");
        inOrder.verify(productRepository).findByIdForUpdate("prod-1");
        inOrder.verify(entitlementRepository).findByOrderIdForUpdate("order-1");
        inOrder.verify(entitlementRepository).findFutureActiveByProductAscForUpdate(eq("buyer-1"), eq("class-1"), eq("prod-1"), any());
        assertEquals("REVOKED", e.getState());
        verify(entitlementRepository, never()).findByOrderId(anyString());
        verify(entitlementRepository, never()).findFutureActiveByProductAsc(any(), any(), any(), any());
    }

    // ----- failure handling -----

    @Test
    @DisplayName("R19-03: a persistence failure answers 500 PAYMENT_SETTLE_FAILED and leaves an audit row + outbox event")
    void persistenceFailureIsRetryableAndRecorded() {
        orderIsReloadedEachAttempt();
        when(orderItemRepository.findByOrderId("order-1")).thenReturn(List.of(item(30, Instant.now().minusSeconds(60))));
        when(productRepository.findByIdForUpdate("prod-1")).thenReturn(Optional.of(product));
        when(entitlementRepository.save(any(Entitlement.class)))
                .thenThrow(new DataIntegrityViolationException("Incorrect datetime value: '2046-01-01 00:00:00' for column 'expires_at'"));
        when(orderRepository.findByOrderNumber("ORD-R19")).thenReturn(Optional.of(pendingOrder()));

        AppException ex = assertThrows(AppException.class, () -> commerceService.handlePaymentWebhook(success(), "{}", "sig"));

        assertEquals(ErrorCode.PAYMENT_SETTLE_FAILED, ex.getErrorCode());
        assertEquals(500, ex.getErrorCode().getHttpStatus().value());
        verify(orderRepository, times(1)).findByOrderNumberForUpdate("ORD-R19"); // a data error is not retried
        verify(outboxService).recordEventIfNotExists(eq("COMMERCE"), eq("order-1"), eq("PAYMENT_SETTLE_FAILED"), any());
        verify(auditService).record(eq("class-1"), eq("buyer-1"), eq("PAYMENT_SETTLE_FAILED"), eq("ORDER"), eq("order-1"), any());
        verify(outboxService, never()).recordEvent(eq("COMMERCE"), any(), eq("ORDER_PAID"), any());
    }

    @Test
    @DisplayName("R19-01: a deadlock/lock-timeout victim is re-run in a fresh transaction and then succeeds")
    void lockConflictIsRetried() {
        orderIsReloadedEachAttempt();
        when(orderItemRepository.findByOrderId("order-1")).thenReturn(List.of(item(30, Instant.now().minusSeconds(60))));
        when(productRepository.findByIdForUpdate("prod-1")).thenReturn(Optional.of(product));
        when(entitlementRepository.save(any(Entitlement.class)))
                .thenThrow(new CannotAcquireLockException("Deadlock found when trying to get lock"))
                .thenAnswer(inv -> inv.getArgument(0));

        OrderDto result = commerceService.handlePaymentWebhook(success(), "{}", "sig");

        assertEquals("PAID", result.getStatus());
        verify(orderRepository, times(2)).findByOrderNumberForUpdate("ORD-R19");
        verify(auditService, never()).record(any(), any(), eq("PAYMENT_SETTLE_FAILED"), any(), any(), any());
    }

    @Test
    @DisplayName("R19-01: a conflict that keeps happening gives up after the bounded attempts with 500 and a record")
    void persistentLockConflictGivesUp() {
        orderIsReloadedEachAttempt();
        when(orderItemRepository.findByOrderId("order-1")).thenReturn(List.of(item(30, Instant.now().minusSeconds(60))));
        when(productRepository.findByIdForUpdate("prod-1")).thenReturn(Optional.of(product));
        when(entitlementRepository.save(any(Entitlement.class))).thenThrow(new CannotAcquireLockException("Lock wait timeout exceeded"));
        when(orderRepository.findByOrderNumber("ORD-R19")).thenReturn(Optional.of(pendingOrder()));

        AppException ex = assertThrows(AppException.class, () -> commerceService.handlePaymentWebhook(success(), "{}", "sig"));

        assertEquals(ErrorCode.PAYMENT_SETTLE_FAILED, ex.getErrorCode());
        verify(orderRepository, times(CommerceService.MAX_WEBHOOK_ATTEMPTS)).findByOrderNumberForUpdate("ORD-R19");
        verify(auditService).record(eq("class-1"), eq("buyer-1"), eq("PAYMENT_SETTLE_FAILED"), eq("ORDER"), eq("order-1"), any());
    }

    @Test
    @DisplayName("R19-03: an entitlement past the horizon is refused with a clear 400 (not retried), and recorded")
    void pastTheHorizonIsRefusedAndRecorded() {
        Instant farStart = Instant.now().atZone(ZoneOffset.UTC).plusYears(49).toInstant();
        orderIsReloadedEachAttempt();
        when(orderItemRepository.findByOrderId("order-1")).thenReturn(List.of(item(3650, farStart)));
        when(productRepository.findByIdForUpdate("prod-1")).thenReturn(Optional.of(product));
        when(orderRepository.findByOrderNumber("ORD-R19")).thenReturn(Optional.of(pendingOrder()));

        AppException ex = assertThrows(AppException.class, () -> commerceService.handlePaymentWebhook(success(), "{}", "sig"));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("giới hạn"), ex.getMessage());
        verify(orderRepository, times(1)).findByOrderNumberForUpdate("ORD-R19");
        verify(entitlementRepository, never()).save(any(Entitlement.class));
        verify(auditService).record(eq("class-1"), eq("buyer-1"), eq("PAYMENT_SETTLE_FAILED"), eq("ORDER"), eq("order-1"), any());
    }

    @Test
    @DisplayName("R19-03: an ordinary rejection (wrong amount) is a plain 400 and is NOT reported as a settle failure")
    void businessRejectionIsNotASettleFailure() {
        orderIsReloadedEachAttempt();
        WebhookPayload wrongAmount = new WebhookPayload("ORD-R19", "REF-1", "PAYMENT_SUCCESS", new BigDecimal("1"), "VND");

        AppException ex = assertThrows(AppException.class, () -> commerceService.handlePaymentWebhook(wrongAmount, "{}", "sig"));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        verify(auditService, never()).record(any(), any(), eq("PAYMENT_SETTLE_FAILED"), any(), any(), any());
    }

    // ----- stacking arithmetic -----

    @Test
    @DisplayName("computeGrant: no chain starts at the configured start, or now when that is already past")
    void grantWithoutChain() {
        Instant now = Instant.parse("2026-09-30T00:00:00Z");
        CommerceService.Grant past = CommerceService.computeGrant(now, null, Instant.parse("2026-01-01T00:00:00Z"), 30);
        assertEquals(now, past.startsAt());
        assertEquals(now.plus(30, ChronoUnit.DAYS), past.expiresAt());

        Instant configured = Instant.parse("2039-01-01T00:00:00Z");
        CommerceService.Grant future = CommerceService.computeGrant(now, null, configured, 30);
        assertEquals(configured, future.startsAt());
        assertEquals(configured.plus(30, ChronoUnit.DAYS), future.expiresAt());

        assertEquals(now, CommerceService.computeGrant(now, null, null, 30).startsAt());
    }

    @Test
    @DisplayName("computeGrant: a new purchase starts where the current chain ends")
    void grantStacksOnTheChain() {
        Instant now = Instant.parse("2026-09-30T00:00:00Z");
        Instant chainEnd = now.plus(12, ChronoUnit.DAYS);
        CommerceService.Grant grant = CommerceService.computeGrant(now, chainEnd, Instant.EPOCH, 30);
        assertEquals(chainEnd, grant.startsAt());
        assertEquals(chainEnd.plus(30, ChronoUnit.DAYS), grant.expiresAt());
    }

    @Test
    @DisplayName("computeGrant: two 3650-day purchases reach ~2046, the 50-year horizon is the ceiling")
    void grantHorizon() {
        Instant now = Instant.parse("2026-09-30T00:00:00Z");
        CommerceService.Grant first = CommerceService.computeGrant(now, null, Instant.EPOCH, 3650);
        CommerceService.Grant second = CommerceService.computeGrant(now, first.expiresAt(), Instant.EPOCH, 3650);
        assertEquals(2046, second.expiresAt().atZone(ZoneOffset.UTC).getYear());
        assertEquals(Duration.ofDays(7300), Duration.between(first.startsAt(), second.expiresAt()));

        // Five stacked 3650-day periods (~50 years) still fit; a sixth does not.
        Instant chainEnd = null;
        for (int i = 0; i < 5; i++) chainEnd = CommerceService.computeGrant(now, chainEnd, Instant.EPOCH, 3650).expiresAt();
        final Instant fiveDecades = chainEnd;
        AppException tooFar = assertThrows(AppException.class, () -> CommerceService.computeGrant(now, fiveDecades, Instant.EPOCH, 3650));
        assertEquals(ErrorCode.BAD_REQUEST, tooFar.getErrorCode());
        assertTrue(tooFar.getMessage().contains("giới hạn"));
    }
}
