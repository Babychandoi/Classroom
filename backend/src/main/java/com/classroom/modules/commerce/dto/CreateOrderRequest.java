package com.classroom.modules.commerce.dto;

import jakarta.validation.constraints.NotBlank;

public class CreateOrderRequest {
    @NotBlank(message = "Lớp học không được để trống")
    private String classId;

    @NotBlank(message = "Sản phẩm không được để trống")
    private String productId;

    @NotBlank(message = "Idempotency-Key không được để trống")
    private String idempotencyKey;

    public CreateOrderRequest() {}

    public String getClassId() {
        return classId;
    }

    public void setClassId(String classId) {
        this.classId = classId;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
