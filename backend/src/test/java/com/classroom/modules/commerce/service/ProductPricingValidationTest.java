package com.classroom.modules.commerce.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.policy.AccessPolicy;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * R19-09: product_prices.price is DECIMAL(12,2) - a price the column cannot hold used to be rounded silently on
 * write (0.001 became 0.00 = a FREE product). R19-03: the access start date is bounded too.
 */
@ExtendWith(MockitoExtension.class)
class ProductPricingValidationTest {

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

    private AppException rejectCreate(String price) {
        return assertThrows(AppException.class, () -> commerceService.createProduct(
                "class-1", null, "Gói", "d", new BigDecimal(price), 30, Instant.now(), "owner-1"));
    }

    private void assertBadRequest(AppException ex, String messagePart) {
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains(messagePart), ex.getMessage());
    }

    @Test
    @DisplayName("createProduct refuses a sub-dong price that would be stored as 0.00 (a free product)")
    void subDongPriceRefused() {
        assertBadRequest(rejectCreate("0.001"), "số nguyên");
        assertBadRequest(rejectCreate("0.4"), "số nguyên");
        verify(productRepository, never()).save(any(Product.class));
        verify(priceRepository, never()).save(any(ProductPrice.class));
    }

    @Test
    @DisplayName("createProduct refuses zero, negative and fractional VND prices")
    void nonPositiveAndFractionalRefused() {
        assertBadRequest(rejectCreate("0"), "lớn hơn 0");
        assertBadRequest(rejectCreate("0.00"), "lớn hơn 0");
        assertBadRequest(rejectCreate("-1"), "lớn hơn 0");
        assertBadRequest(rejectCreate("1000.5"), "số nguyên");
        assertBadRequest(rejectCreate("1000.555"), "số nguyên");
        assertThrows(AppException.class, () -> commerceService.createProduct(
                "class-1", null, "Gói", "d", null, 30, Instant.now(), "owner-1"));
    }

    @Test
    @DisplayName("createProduct refuses a price that does not fit DECIMAL(12,2)")
    void overflowingPriceRefused() {
        assertBadRequest(rejectCreate("12345678901"), "giới hạn");
        assertBadRequest(rejectCreate("99999999999.99"), "số nguyên");
        assertBadRequest(rejectCreate("1E+15"), "giới hạn");
    }

    @Test
    @DisplayName("createProduct accepts whole-dong prices, including ones written with trailing zeros or an exponent, and the column maximum")
    void wholeDongPricesAccepted() {
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        for (String price : new String[]{"1", "199000", "199000.00", "1E+5", "9999999999"}) {
            commerceService.createProduct("class-1", null, "Gói", "d", new BigDecimal(price), 30, Instant.now(), "owner-1");
        }
        ArgumentCaptor<ProductPrice> saved = ArgumentCaptor.forClass(ProductPrice.class);
        verify(priceRepository, times(5)).save(saved.capture());
        assertEquals(0, new BigDecimal("100000").compareTo(saved.getAllValues().get(3).getPrice()));
    }

    @Test
    @DisplayName("updateProduct applies the same price rules")
    void updateProductValidatesPrice() {
        Product product = new Product("class-1", null, "Gói", "d");
        product.setId("prod-1");
        product.setStatus("PUBLISHED");
        when(productRepository.findByIdForUpdate("prod-1")).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        ProductPrice existing = new ProductPrice("prod-1", new BigDecimal("1000"), "VND", 30, Instant.now());
        when(priceRepository.findByProductId("prod-1")).thenReturn(Optional.of(existing));

        assertBadRequest(assertThrows(AppException.class, () ->
                commerceService.updateProduct("prod-1", null, null, new BigDecimal("0.001"), null, "owner-1")), "số nguyên");
        assertBadRequest(assertThrows(AppException.class, () ->
                commerceService.updateProduct("prod-1", null, null, new BigDecimal("1500.75"), null, "owner-1")), "số nguyên");
        assertBadRequest(assertThrows(AppException.class, () ->
                commerceService.updateProduct("prod-1", null, null, new BigDecimal("12345678901"), null, "owner-1")), "giới hạn");
        assertEquals(0, new BigDecimal("1000").compareTo(existing.getPrice()), "a refused price must not be applied");

        commerceService.updateProduct("prod-1", null, null, new BigDecimal("250000"), null, "owner-1");
        assertEquals(0, new BigDecimal("250000").compareTo(existing.getPrice()));
    }

    @Test
    @DisplayName("validatePrice: a currency with minor units keeps at most 2 decimals and the rounded value must stay positive")
    void minorUnitCurrency() {
        assertDoesNotThrow(() -> CommerceService.validatePrice(new BigDecimal("12.34"), "USD"));
        assertDoesNotThrow(() -> CommerceService.validatePrice(new BigDecimal("0.01"), "USD"));
        assertDoesNotThrow(() -> CommerceService.validatePrice(new BigDecimal("12.500"), "USD"));
        assertBadRequest(assertThrows(AppException.class, () -> CommerceService.validatePrice(new BigDecimal("12.345"), "USD")), "2 chữ số");
        assertBadRequest(assertThrows(AppException.class, () -> CommerceService.validatePrice(new BigDecimal("0.004"), "USD")), "2 chữ số");
        assertDoesNotThrow(() -> CommerceService.validatePrice(new BigDecimal("9999999999.99"), "USD"));
        assertBadRequest(assertThrows(AppException.class, () -> CommerceService.validatePrice(new BigDecimal("10000000000.00"), "USD")), "giới hạn");
    }

    // ----- access start bounds (R19-03) -----

    @Test
    @DisplayName("createProduct accepts an access start after 2038 and refuses one beyond the horizon, before 1970, or missing")
    void accessStartBounds() {
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));
        assertDoesNotThrow(() -> commerceService.createProduct("class-1", null, "Gói", "d", new BigDecimal("1000"), 30,
                Instant.parse("2039-01-01T00:00:00Z"), "owner-1"));

        Instant tooFar = Instant.now().atZone(ZoneOffset.UTC).plusYears(60).toInstant();
        assertBadRequest(assertThrows(AppException.class, () -> commerceService.createProduct(
                "class-1", null, "Gói", "d", new BigDecimal("1000"), 30, tooFar, "owner-1")), "năm");
        // the first period itself must end inside the horizon
        Instant lastDay = Instant.now().atZone(ZoneOffset.UTC).plusYears(50).minusDays(1).toInstant();
        assertBadRequest(assertThrows(AppException.class, () -> commerceService.createProduct(
                "class-1", null, "Gói", "d", new BigDecimal("1000"), 30, lastDay, "owner-1")), "năm");
        assertBadRequest(assertThrows(AppException.class, () -> commerceService.createProduct(
                "class-1", null, "Gói", "d", new BigDecimal("1000"), 30, Instant.parse("1969-12-31T00:00:00Z"), "owner-1")), "1970");
        assertBadRequest(assertThrows(AppException.class, () -> commerceService.createProduct(
                "class-1", null, "Gói", "d", new BigDecimal("1000"), 30, (Instant) null, "owner-1")), "ngày bắt đầu");
    }
}
