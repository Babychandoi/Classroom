package com.classroom.modules.identity.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;

public class RegisterRequest {
    @NotBlank(message = "Email không được để trống")
    @Email(message = "Email không đúng định dạng")
    private String email;

    @NotBlank(message = "Mật khẩu không được để trống")
    @Size(min = 6, message = "Mật khẩu phải từ 6 ký tự trở lên")
    private String password;

    // R3-09: BCrypt silently truncates input beyond 72 bytes, so a longer password that differs
    // only after that point would be accepted as if it matched the first 72 bytes - reject it
    // outright instead of quietly weakening the hash. Counted in UTF-8 bytes, not characters,
    // since multi-byte characters can exceed the limit well before 72 code points.
    @AssertTrue(message = "Mật khẩu không được vượt quá 72 byte")
    private boolean isPasswordWithinBcryptLimit() {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    @NotBlank(message = "Họ và tên không được để trống")
    private String fullName;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }
}
