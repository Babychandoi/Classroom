package com.classroom.modules.commerce.dto;

import java.math.BigDecimal;

public class WebhookPayload {
    private String orderNumber;
    private String providerRef;
    private String eventType; // PAYMENT_SUCCESS, PAYMENT_FAILED, PAYMENT_REFUNDED
    private BigDecimal amount;
    private String currency;

    public WebhookPayload() {}

    public WebhookPayload(String orderNumber, String providerRef, String eventType, BigDecimal amount, String currency) {
        this.orderNumber = orderNumber;
        this.providerRef = providerRef;
        this.eventType = eventType;
        this.amount = amount;
        this.currency = currency;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public String getProviderRef() {
        return providerRef;
    }

    public void setProviderRef(String providerRef) {
        this.providerRef = providerRef;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }
}
