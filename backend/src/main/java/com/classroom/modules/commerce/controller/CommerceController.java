package com.classroom.modules.commerce.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.commerce.dto.CreateOrderRequest;
import com.classroom.modules.commerce.dto.CreateProductRequest;
import com.classroom.modules.commerce.dto.OrderDto;
import com.classroom.modules.commerce.dto.ProductDto;
import com.classroom.modules.commerce.model.Product;
import com.classroom.modules.commerce.service.CommerceService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1")
public class CommerceController {

    private final CommerceService commerceService;

    public CommerceController(CommerceService commerceService) {
        this.commerceService = commerceService;
    }

    @GetMapping("/classes/{classId}/products")
    public ResponseEntity<ApiResponse<List<ProductDto>>> getProducts(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal) {
        String userId = (principal != null) ? principal.getId() : null;
        List<ProductDto> products = commerceService.getProductsByClass(classId, userId);
        return ResponseEntity.ok(ApiResponse.ok(products));
    }

    @GetMapping("/classes/{classId}/studio/products")
    public ResponseEntity<ApiResponse<List<ProductDto>>> getStudioProducts(
            @PathVariable String classId, @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(commerceService.getStudioProducts(classId, principal.getId())));
    }

    @PostMapping("/classes/{classId}/products")
    public ResponseEntity<ApiResponse<Product>> createProduct(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal,
            @RequestBody CreateProductRequest body) {
        BigDecimal price = body.getPrice() != null ? body.getPrice() : BigDecimal.ZERO;
        int durationDays = body.getDurationDays() != null ? body.getDurationDays() : 30;
        Instant accessStartsAt;
        try {
            accessStartsAt = (body.getAccessStartsAt() != null && !body.getAccessStartsAt().isBlank())
                    ? Instant.parse(body.getAccessStartsAt())
                    : Instant.now();
        } catch (java.time.format.DateTimeParseException ex) {
            throw new com.classroom.common.AppException(com.classroom.common.ErrorCode.BAD_REQUEST,
                    "Định dạng accessStartsAt không hợp lệ");
        }

        Product product = commerceService.createProduct(classId, body.getTargetCourseId(), body.getTitle(),
                body.getDescription(), price, durationDays, accessStartsAt, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(product));
    }

    @PostMapping("/products/{productId}/publish")
    public ResponseEntity<ApiResponse<Product>> publishProduct(
            @PathVariable String productId,
            @CurrentUser UserPrincipal principal) {
        Product published = commerceService.publishProduct(productId, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(published));
    }

    public record UpdateProductRequest(String title, String description, BigDecimal price, Integer durationDays) {}

    @PutMapping("/products/{productId}")
    public ResponseEntity<ApiResponse<Product>> updateProduct(
            @PathVariable String productId,
            @CurrentUser UserPrincipal principal,
            @RequestBody UpdateProductRequest body) {
        Product updated = commerceService.updateProduct(productId, body.title(), body.description(),
                body.price(), body.durationDays(), principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(updated));
    }

    @PostMapping("/products/{productId}/archive")
    public ResponseEntity<ApiResponse<Product>> archiveProduct(
            @PathVariable String productId, @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(commerceService.archiveProduct(productId, principal.getId())));
    }

    @PostMapping("/products/{productId}/restore")
    public ResponseEntity<ApiResponse<Product>> restoreProduct(
            @PathVariable String productId, @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(commerceService.restoreProduct(productId, principal.getId())));
    }

    @PostMapping("/orders/{id}/cancel")
    public ResponseEntity<ApiResponse<OrderDto>> cancelOrder(
            @PathVariable String id, @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(commerceService.cancelOrder(id, principal.getId())));
    }

    @PostMapping("/orders")
    public ResponseEntity<ApiResponse<OrderDto>> createOrder(
            @CurrentUser UserPrincipal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @Valid @RequestBody CreateOrderRequest request) {
        request.setIdempotencyKey(resolveIdempotencyKey(idempotencyKeyHeader, request.getIdempotencyKey()));
        OrderDto order = commerceService.createOrder(principal.getId(), request);
        return ResponseEntity.ok(ApiResponse.ok(order));
    }

    /**
     * R20-12: the idempotency key may be sent as the {@code Idempotency-Key} header, as the body field {@code idempotencyKey}, or
     * both. The body field is deliberately NOT bean-validated as required (that ran before this method and rejected a header-only
     * request with 400): the contract is enforced here, once, on the effective key. The two must agree when both are present -
     * silently preferring one would let a retry with a stale body key create a second order.
     *
     * @return the trimmed key
     */
    static String resolveIdempotencyKey(String header, String bodyKey) {
        String fromHeader = header == null || header.isBlank() ? null : header.trim();
        String fromBody = bodyKey == null || bodyKey.isBlank() ? null : bodyKey.trim();
        if (fromHeader != null && fromBody != null && !fromHeader.equals(fromBody)) {
            throw new com.classroom.common.AppException(com.classroom.common.ErrorCode.BAD_REQUEST,
                    "Idempotency-Key trong header và trong nội dung yêu cầu không khớp nhau");
        }
        String key = fromHeader != null ? fromHeader : fromBody;
        if (key == null) {
            throw new com.classroom.common.AppException(com.classroom.common.ErrorCode.BAD_REQUEST,
                    "Yêu cầu tạo đơn hàng bắt buộc phải có Idempotency-Key");
        }
        return key;
    }

    @GetMapping("/orders/{id}")
    public ResponseEntity<ApiResponse<OrderDto>> getOrder(
            @PathVariable String id,
            @CurrentUser UserPrincipal principal) {
        OrderDto order = commerceService.getOrder(id, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(order));
    }

    @GetMapping("/classes/{classId}/orders")
    public ResponseEntity<ApiResponse<List<OrderDto>>> getClassOrders(
            @PathVariable String classId,
            @CurrentUser UserPrincipal principal) {
        List<OrderDto> orders = commerceService.getOrdersByClass(classId, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(orders));
    }

    /** R13-06 (FR-07/TC-17): the signed-in buyer's own orders, optionally scoped to one class. */
    @GetMapping("/me/orders")
    public ResponseEntity<ApiResponse<List<OrderDto>>> getMyOrders(
            @CurrentUser UserPrincipal principal,
            @RequestParam(required = false) String classId) {
        List<OrderDto> orders = commerceService.getMyOrders(principal.getId(), classId);
        return ResponseEntity.ok(ApiResponse.ok(orders));
    }
}
