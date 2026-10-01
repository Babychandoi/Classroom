package com.classroom.modules.commerce.controller;

import com.classroom.common.ApiResponse;
import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.commerce.dto.OrderDto;
import com.classroom.modules.commerce.dto.WebhookPayload;
import com.classroom.modules.commerce.model.Order;
import com.classroom.modules.commerce.payment.MockPaymentProvider;
import com.classroom.modules.commerce.repository.OrderRepository;
import com.classroom.modules.commerce.service.CommerceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Simulation controller gated to explicit sandbox configuration and dev/test/sandbox/docker profiles.
 * Fails closed if classroom.payment.sandbox.enabled is not true, and every simulated settlement
 * requires class-owner or STORE-manage authority (buyers can never settle their own orders).
 */
@RestController
@RequestMapping("/api/v1/payments")
@ConditionalOnProperty(name = "classroom.payment.sandbox.enabled", havingValue = "true", matchIfMissing = false)
@Profile({"dev", "test", "sandbox", "docker"})
public class MockPaymentSimulationController {

    private final CommerceService commerceService;
    private final MockPaymentProvider mockPaymentProvider;
    private final OrderRepository orderRepository;
    private final AccessPolicy accessPolicy;
    private final ObjectMapper objectMapper;

    @Value("${classroom.payment.mock.checkout.enabled:false}")
    private boolean mockCheckoutEnabled;

    public MockPaymentSimulationController(CommerceService commerceService,
                                           MockPaymentProvider mockPaymentProvider,
                                           OrderRepository orderRepository,
                                           AccessPolicy accessPolicy,
                                           ObjectMapper objectMapper) {
        this.commerceService = commerceService;
        this.mockPaymentProvider = mockPaymentProvider;
        this.orderRepository = orderRepository;
        this.accessPolicy = accessPolicy;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/sandbox-status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSandboxStatus() {
        // Finding 2: report the checkout rail truthfully instead of hardcoding it off. When the
        // sandbox rail is enabled a buyer really can create an order and reach checkout, so the
        // purchase -> entitlement flow is exercisable end to end. `settlementMode` keeps the
        // answer honest: settlement here is operator-simulated, never a real payment rail, and
        // this controller only exists under the sandbox property and dev/test/sandbox/docker
        // profiles, so a production deployment still reports no checkout at all.
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "sandboxAvailable", mockCheckoutEnabled,
                "checkoutAvailable", mockCheckoutEnabled,
                "providerCode", mockPaymentProvider.getProviderCode(),
                "settlementMode", "OPERATOR_SIMULATED",
                "supportedEvents", List.of("PAYMENT_SUCCESS", "PAYMENT_FAILED", "PAYMENT_REFUNDED")
        )));
    }

    @PostMapping("/mock/simulate")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<OrderDto>> simulateWebhook(
            @CurrentUser UserPrincipal principal,
            @RequestBody WebhookPayload payload) throws Exception {

        if (principal == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "Yêu cầu đăng nhập để thực hiện mô phỏng thanh toán");
        }
        if (payload == null || payload.getOrderNumber() == null || payload.getOrderNumber().isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Thiếu mã đơn hàng cần mô phỏng");
        }

        Order order = orderRepository.findByOrderNumber(payload.getOrderNumber().trim())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn hàng: " + payload.getOrderNumber()));

        // Settlement simulation is operator-only. A buyer must never be able to move their own
        // order to PAID (that would grant a paid entitlement without any real payment), so
        // being the buyer confers no privilege here regardless of the order state or event type.
        boolean isOwner = accessPolicy.isOwner(principal.getId(), order.getClassId());
        boolean canManage = accessPolicy.canManage(principal.getId(), order.getClassId(), "STORE", "EDIT", null);

        if (!isOwner && !canManage) {
            throw new AppException(ErrorCode.FORBIDDEN, "Chỉ chủ lớp hoặc nhân sự quản lý cửa hàng mới có quyền mô phỏng thanh toán sandbox");
        }
        // R4-07: staff holding STORE:EDIT (a non-owner) can be the buyer on their own order —
        // that combination must not let them settle their own purchase, even though they pass the
        // canManage check above. The class owner is deliberately exempt from this self-buy check:
        // they already hold full authority over every order in their own class regardless of who
        // the buyer is.
        if (!isOwner && principal.getId().equals(order.getBuyerId())) {
            throw new AppException(ErrorCode.FORBIDDEN, "Không thể tự mô phỏng thanh toán cho đơn hàng của chính mình");
        }

        String eventType = (payload.getEventType() != null && !payload.getEventType().isBlank())
                ? payload.getEventType().toUpperCase().trim()
                : "PAYMENT_SUCCESS";

        // Enforce legal state transitions before simulating
        if ("PAYMENT_SUCCESS".equals(eventType) && !"PENDING".equalsIgnoreCase(order.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Chỉ có thể mô phỏng thanh toán thành công cho đơn hàng PENDING");
        }
        if ("PAYMENT_REFUNDED".equals(eventType) && !"PAID".equalsIgnoreCase(order.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Chỉ có thể mô phỏng hoàn tiền cho đơn hàng đã thanh toán (PAID)");
        }
        if ("PAYMENT_FAILED".equals(eventType) && !"PENDING".equalsIgnoreCase(order.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Chỉ có thể mô phỏng thất bại cho đơn hàng PENDING");
        }

        // Do NOT trust caller-supplied amount or currency! Construct authoritative payload from DB
        String providerRef;
        if ("PAYMENT_REFUNDED".equals(eventType)) {
            providerRef = order.getProviderRef();
            if (providerRef == null || providerRef.isBlank()) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Đơn hàng không có mã giao dịch gốc để hoàn tiền");
            }
        } else {
            providerRef = (payload.getProviderRef() != null && !payload.getProviderRef().isBlank())
                    ? payload.getProviderRef().trim()
                    : "MOCK-TX-" + UUID.randomUUID();
        }

        WebhookPayload safePayload = new WebhookPayload(
                order.getOrderNumber(),
                providerRef,
                eventType,
                order.getTotalAmount(),
                order.getCurrency()
        );

        String rawBody = objectMapper.writeValueAsString(safePayload);
        String signature = mockPaymentProvider.generateSignature(rawBody);

        OrderDto processed = commerceService.handlePaymentWebhook("MOCK", safePayload, rawBody, signature);
        return ResponseEntity.ok(ApiResponse.ok(processed));
    }
}
