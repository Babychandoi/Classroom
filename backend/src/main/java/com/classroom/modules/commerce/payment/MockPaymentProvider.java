package com.classroom.modules.commerce.payment;

import com.classroom.config.DevSecretGuard;
import com.classroom.modules.commerce.model.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class MockPaymentProvider implements PaymentProvider {
    private static final Logger log = LoggerFactory.getLogger(MockPaymentProvider.class);

    private final String webhookSecret;

    public MockPaymentProvider(@Value("${app.payment.mock.webhook-secret}") String webhookSecret, Environment environment) {
        if (webhookSecret == null || webhookSecret.isBlank() || webhookSecret.length() < 32) {
            throw new IllegalStateException(
                    "Mock payment webhook secret (app.payment.mock.webhook-secret / MOCK_PAYMENT_WEBHOOK_SECRET) " +
                    "must be set and at least 32 characters. Refusing to start with an absent or weak secret.");
        }
        DevSecretGuard.rejectKnownSampleValue("MOCK_PAYMENT_WEBHOOK_SECRET", webhookSecret, environment);
        this.webhookSecret = webhookSecret;
    }

    @Override
    public String getProviderCode() {
        return "MOCK";
    }

    @Override
    public String createPaymentSession(Order order) {
        // Mock payment is operator-settled; it has no buyer checkout page.
        return null;
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature) {
        if (signature == null || payload == null) return false;
        try {
            String expected = generateSignature(payload);
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signature.trim().getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            log.error("Signature verification error", e);
            return false;
        }
    }

    public String generateSignature(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] hmacBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hmacBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate HMAC signature", e);
        }
    }
}
