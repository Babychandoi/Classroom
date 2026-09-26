package com.classroom.modules.commerce.controller;

import com.classroom.common.ApiResponse;
import com.classroom.modules.commerce.dto.OrderDto;
import com.classroom.modules.commerce.dto.WebhookPayload;
import com.classroom.modules.commerce.payment.MockPaymentProvider;
import com.classroom.modules.commerce.service.CommerceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentWebhookController {

    private final CommerceService commerceService;
    private final MockPaymentProvider mockPaymentProvider;
    private final ObjectMapper objectMapper;

    public PaymentWebhookController(CommerceService commerceService,
                                    MockPaymentProvider mockPaymentProvider,
                                    ObjectMapper objectMapper) {
        this.commerceService = commerceService;
        this.mockPaymentProvider = mockPaymentProvider;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{provider}/webhook")
    public ResponseEntity<ApiResponse<OrderDto>> handleWebhook(
            @PathVariable String provider,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestBody String rawBody) throws Exception {

        WebhookPayload payload;
        try {
            payload = objectMapper.readValue(rawBody, WebhookPayload.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new com.classroom.common.AppException(com.classroom.common.ErrorCode.BAD_REQUEST,
                    "Nội dung webhook không hợp lệ");
        }
        OrderDto processed = commerceService.handlePaymentWebhook(provider, payload, rawBody, signature);
        return ResponseEntity.ok(ApiResponse.ok(processed));
    }
}
