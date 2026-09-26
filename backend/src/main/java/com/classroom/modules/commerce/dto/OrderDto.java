package com.classroom.modules.commerce.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class OrderDto {
    private String id;
    private String orderNumber;
    private String buyerId;
    private String classId;
    private String status;
    private BigDecimal totalAmount;
    private String currency;
    private String provider;
    private String checkoutUrl;
    private Instant paidAt;
    private Instant createdAt;
    private List<OrderItemDto> items;

    public OrderDto() {}

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public void setOrderNumber(String orderNumber) {
        this.orderNumber = orderNumber;
    }

    public String getBuyerId() {
        return buyerId;
    }

    public void setBuyerId(String buyerId) {
        this.buyerId = buyerId;
    }

    public String getClassId() {
        return classId;
    }

    public void setClassId(String classId) {
        this.classId = classId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getCheckoutUrl() {
        return checkoutUrl;
    }

    public void setCheckoutUrl(String checkoutUrl) {
        this.checkoutUrl = checkoutUrl;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(Instant paidAt) {
        this.paidAt = paidAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public List<OrderItemDto> getItems() {
        return items;
    }

    public void setItems(List<OrderItemDto> items) {
        this.items = items;
    }

    public static class OrderItemDto {
        private String productId;
        private String productName;
        private BigDecimal price;
        private int durationDays;

        public OrderItemDto() {}

        public OrderItemDto(String productId, String productName, BigDecimal price, int durationDays) {
            this.productId = productId;
            this.productName = productName;
            this.price = price;
            this.durationDays = durationDays;
        }

        public String getProductId() {
            return productId;
        }

        public void setProductId(String productId) {
            this.productId = productId;
        }

        public String getProductName() {
            return productName;
        }

        public void setProductName(String productName) {
            this.productName = productName;
        }

        public BigDecimal getPrice() {
            return price;
        }

        public void setPrice(BigDecimal price) {
            this.price = price;
        }

        public int getDurationDays() {
            return durationDays;
        }

        public void setDurationDays(int durationDays) {
            this.durationDays = durationDays;
        }
    }
}
