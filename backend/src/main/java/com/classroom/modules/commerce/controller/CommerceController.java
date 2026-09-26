package com.classroom.modules.commerce.controller;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.commerce.dto.CreateOrderRequest;
import com.classroom.modules.commerce.dto.OrderDto;
import com.classroom.modules.commerce.dto.ProductDto;
import com.classroom.modules.commerce.model.Product;
import com.classroom.modules.commerce.service.CommerceService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
            @RequestBody Map<String, Object> body) {
        String title = (String) body.get("title");
        String description = (String) body.get("description");
        String targetCourseId = (String) body.get("targetCourseId");
        BigDecimal price = new BigDecimal(body.getOrDefault("price", "0").toString());
        int durationDays = body.containsKey("durationDays") ? (int) body.get("durationDays") : 30;
        Instant accessStartsAt = body.containsKey("accessStartsAt")
                ? Instant.parse(body.get("accessStartsAt").toString()) : Instant.now();

        Product product = commerceService.createProduct(classId, targetCourseId, title, description, price, durationDays, accessStartsAt, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(product));
    }

    @PostMapping("/products/{productId}/publish")
    public ResponseEntity<ApiResponse<Product>> publishProduct(
            @PathVariable String productId,
            @CurrentUser UserPrincipal principal) {
        Product published = commerceService.publishProduct(productId, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok(published));
    }

    @PostMapping("/orders")
    public ResponseEntity<ApiResponse<OrderDto>> createOrder(
            @CurrentUser UserPrincipal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @Valid @RequestBody CreateOrderRequest request) {
        if (idempotencyKeyHeader != null && !idempotencyKeyHeader.isBlank()) {
            request.setIdempotencyKey(idempotencyKeyHeader);
        }
        if (request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank()) {
            throw new com.classroom.common.AppException(com.classroom.common.ErrorCode.BAD_REQUEST, "Yêu cầu tạo đơn hàng bắt buộc phải có Idempotency-Key");
        }
        OrderDto order = commerceService.createOrder(principal.getId(), request);
        return ResponseEntity.ok(ApiResponse.ok(order));
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
}
