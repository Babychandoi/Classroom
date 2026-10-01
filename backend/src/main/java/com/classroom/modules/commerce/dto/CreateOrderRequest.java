package com.classroom.modules.commerce.dto;

import jakarta.validation.constraints.NotBlank;

public class CreateOrderRequest {
    @NotBlank(message = "Lớp học không được để trống")
    private String classId;

    @NotBlank(message = "Sản phẩm không được để trống")
    private String productId;

    // R20-12: optional in the body - may come from the Idempotency-Key header instead; CommerceController#resolveIdempotencyKey
    // enforces "present and consistent" on the effective key.
    private String idempotencyKey;

    /**
     * D-19: only for the class-access product of a PRIVATE paid class: the invite code that opened the checkout (a person who is not on the
     * roster cannot otherwise even see the class). A use of the invite is reserved while the order is open. Ignored for every other product.
     */
    private String inviteCode;

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

    public String getInviteCode() {
        return inviteCode;
    }

    public void setInviteCode(String inviteCode) {
        this.inviteCode = inviteCode;
    }
}
