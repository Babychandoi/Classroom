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
import static org.mockito.Mockito.when;

/**
 * The store card shows "access until <date>". Renewals are stacked with a future startsAt, so the
 * currently-running entitlement row is not the end of the buyer's paid access — reading the date
 * off that row understates a renewal.
 *
 * <p>R19-06: a buyer whose ONLY entitlement starts in the future (pre-sale purchase) owns the product
 * even though it is not running yet, and the listing has to say so instead of looking "not owned".</p>
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
        // R4-06: getProductsByClass now checks class visibility before listing.
        when(accessPolicy.isClassVisibleToUser("class-1", "buyer-1")).thenReturn(true);
    }

    private Entitlement entitlement(Instant startsAt, Instant expiresAt) {
        return new Entitlement("buyer-1", "class-1", "prod-1", null, startsAt, expiresAt);
    }

    private void owns(Entitlement... rows) {
        when(entitlementRepository.findByUserIdAndClassId("buyer-1", "class-1")).thenReturn(List.of(rows));
    }

    @Test
    @DisplayName("After a renewal the store shows the end of the stacked chain, not the old expiry")
    void renewalExtendsDisplayedExpiry() {
        Instant now = Instant.now();
        Instant originalExpiry = now.plus(5, ChronoUnit.DAYS);
        Instant renewedExpiry = originalExpiry.plus(30, ChronoUnit.DAYS);

        Entitlement current = entitlement(now.minus(25, ChronoUnit.DAYS), originalExpiry);
        // The renewal starts when the current one ends, so it is not "running" yet.
        Entitlement renewal = entitlement(originalExpiry, renewedExpiry);
        owns(renewal, current);

        ProductDto dto = commerceService.getProductsByClass("class-1", "buyer-1").get(0);

        assertTrue(dto.isUserHasActiveEntitlement());
        assertFalse(dto.isUserOwnsUpcoming(), "a running entitlement means the product is owned NOW, not 'upcoming'");
        assertEquals(renewedExpiry, dto.getEntitlementExpiresAt());
    }

    @Test
    @DisplayName("A single purchase still shows its own expiry")
    void singlePurchaseShowsOwnExpiry() {
        Instant now = Instant.now();
        Instant expiry = now.plus(30, ChronoUnit.DAYS);
        owns(entitlement(now.minus(1, ChronoUnit.DAYS), expiry));

        ProductDto dto = commerceService.getProductsByClass("class-1", "buyer-1").get(0);

        assertTrue(dto.isUserHasActiveEntitlement());
        assertEquals(expiry, dto.getEntitlementExpiresAt());
    }

    @Test
    @DisplayName("A refunded or expired buyer has no access and no expiry date")
    void noActiveEntitlementShowsNothing() {
        Instant now = Instant.now();
        Entitlement revoked = entitlement(now.minus(5, ChronoUnit.DAYS), now.plus(20, ChronoUnit.DAYS));
        revoked.setState("REVOKED");
        Entitlement lapsed = entitlement(now.minus(60, ChronoUnit.DAYS), now.minus(30, ChronoUnit.DAYS));
        owns(revoked, lapsed);

        ProductDto dto = commerceService.getProductsByClass("class-1", "buyer-1").get(0);

        assertFalse(dto.isUserHasActiveEntitlement());
        assertFalse(dto.isUserOwnsUpcoming());
        assertNull(dto.getEntitlementExpiresAt());
        assertNull(dto.getEntitlementStartsAt());
    }

    @Test
    @DisplayName("R19-06: a pre-sale purchase (entitlement starts in the future) is reported as owned-upcoming with its start date")
    void presalePurchaseIsOwnedUpcoming() {
        Instant now = Instant.now();
        Instant start = now.plus(40, ChronoUnit.DAYS);
        Instant expiry = start.plus(30, ChronoUnit.DAYS);
        owns(entitlement(start, expiry));

        ProductDto dto = commerceService.getProductsByClass("class-1", "buyer-1").get(0);

        assertFalse(dto.isUserHasActiveEntitlement(), "no access yet");
        assertTrue(dto.isUserOwnsUpcoming(), "but it is already paid for");
        assertEquals(start, dto.getEntitlementStartsAt());
        assertEquals(expiry, dto.getEntitlementExpiresAt());
    }

    @Test
    @DisplayName("R19-06: with two upcoming purchases the earliest start and the end of the chain are reported")
    void upcomingChainReportsEarliestStartAndChainEnd() {
        Instant now = Instant.now();
        Instant firstStart = now.plus(10, ChronoUnit.DAYS);
        Instant firstEnd = firstStart.plus(30, ChronoUnit.DAYS);
        Instant secondEnd = firstEnd.plus(30, ChronoUnit.DAYS);
        owns(entitlement(firstEnd, secondEnd), entitlement(firstStart, firstEnd));

        ProductDto dto = commerceService.getProductsByClass("class-1", "buyer-1").get(0);

        assertTrue(dto.isUserOwnsUpcoming());
        assertEquals(firstStart, dto.getEntitlementStartsAt());
        assertEquals(secondEnd, dto.getEntitlementExpiresAt());
    }

    @Test
    @DisplayName("R19-06: an entitlement for ANOTHER product does not mark this product as owned")
    void otherProductsEntitlementIsIgnored() {
        Instant now = Instant.now();
        Entitlement other = new Entitlement("buyer-1", "class-1", "prod-OTHER", null,
                now.minus(1, ChronoUnit.DAYS), now.plus(30, ChronoUnit.DAYS));
        owns(other);

        ProductDto dto = commerceService.getProductsByClass("class-1", "buyer-1").get(0);

        assertFalse(dto.isUserHasActiveEntitlement());
        assertFalse(dto.isUserOwnsUpcoming());
    }
}
