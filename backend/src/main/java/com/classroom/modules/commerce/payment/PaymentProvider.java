package com.classroom.modules.commerce.payment;

import com.classroom.modules.commerce.model.Order;

public interface PaymentProvider {
    String getProviderCode();
    String createPaymentSession(Order order);
    boolean verifyWebhookSignature(String payload, String signature);
}
