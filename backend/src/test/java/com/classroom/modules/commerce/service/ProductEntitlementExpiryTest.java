package com.classroom.modules.commerce.service;

import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.commerce.dto.ProductDto;
import com.classroom.modules.commerce.model.Entitlement;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The store card shows "access until <date>". Renewals are stacked with a future startsAt, so the
 * currently-running entitlement row is not the end of the buyer's paid access — reading the date
 * off that row understates a renewal.
 */
@ExtendWith(MockitoExtension.class)
public class ProductEntitlementExpiryTest {

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
        when(productRepository.findByClassIdAndStatusOrderByCreatedAtDesc("class-1", "PUBLISHED"))
                .thenReturn(List.of(product));
        when(priceRepository.findByProductId("prod-1")).thenReturn(Optional.empty());
    }

    private Entitlement entitlement(Instant startsAt, Instant expiresAt) {
        return new Entitlement("buyer-1", "class-1", "prod-1", null, startsAt, expiresAt);
    }

    @Test
    @DisplayName("After a renewal the store shows the end of the stacked chain, not the old expiry")
    void renewalExtendsDisplayedExpiry() {
        Instant now = Instant.now();
        Instant originalExpiry = now.plus(5, ChronoUnit.DAYS);
        Instant renewedExpiry = originalExpiry.plus(30, ChronoUnit.DAYS);

        Entitlement current = entitlement(now.minus(25, ChronoUnit.DAYS), originalExpiry);
        // The renewal starts when the current one ends, so findActiveEntitlements excludes it.
        Entitlement renewal = entitlement(originalExpiry, renewedExpiry);

        when(entitlementRepository.findActiveEntitlements(eq("buyer-1"), eq("class-1"), any()))
                .thenReturn(List.of(current));
        when(entitlementRepository.findLatestActiveByProduct(eq("buyer-1"), eq("class-1"), eq("prod-1"), any()))
                .thenReturn(List.of(renewal, current));

        ProductDto dto = commerceService.getProductsByClass("class-1", "buyer-1").get(0);

        assertTrue(dto.isUserHasActiveEntitlement());
        assertEquals(renewedExpiry, dto.getEntitlementExpiresAt());
    }

    @Test
    @DisplayName("A single purchase still shows its own expiry")
    void singlePurchaseShowsOwnExpiry() {
        Instant now = Instant.now();
        Instant expiry = now.plus(30, ChronoUnit.DAYS);
        Entitlement current = entitlement(now.minus(1, ChronoUnit.DAYS), expiry);

        when(entitlementRepository.findActiveEntitlements(eq("buyer-1"), eq("class-1"), any()))
                .thenReturn(List.of(current));
        when(entitlementRepository.findLatestActiveByProduct(eq("buyer-1"), eq("class-1"), eq("prod-1"), any()))
                .thenReturn(List.of(current));

        ProductDto dto = commerceService.getProductsByClass("class-1", "buyer-1").get(0);

        assertTrue(dto.isUserHasActiveEntitlement());
        assertEquals(expiry, dto.getEntitlementExpiresAt());
    }

    @Test
    @DisplayName("A refunded or expired buyer has no access and no expiry date")
    void noActiveEntitlementShowsNothing() {
        when(entitlementRepository.findActiveEntitlements(eq("buyer-1"), eq("class-1"), any()))
                .thenReturn(List.of());

        ProductDto dto = commerceService.getProductsByClass("class-1", "buyer-1").get(0);

        assertFalse(dto.isUserHasActiveEntitlement());
        assertNull(dto.getEntitlementExpiresAt());
    }
}
