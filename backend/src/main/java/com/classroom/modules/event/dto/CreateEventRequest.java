package com.classroom.modules.event.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** D-27: POST /classes/{classId}/events. Cross-field rules (endsAt &gt; startsAt, at most 7 days, http(s) meetingUrl, host) live in the service. */
public record CreateEventRequest(
        @NotBlank(message = "Tên sự kiện không được để trống")
        @Size(max = 200, message = "Tên sự kiện tối đa 200 ký tự")
        String title,
        @Size(max = 20_000, message = "Mô tả sự kiện tối đa 20.000 ký tự")
        String description,
        @Size(max = 500, message = "Mục \"Dành cho ai\" tối đa 500 ký tự")
        String forWhom,
        @Size(max = 8, message = "Tối đa 8 ý \"Mang về gì\"")
        List<@Size(max = 200, message = "Mỗi ý \"Mang về gì\" tối đa 200 ký tự") String> takeaways,
        @NotNull(message = "Hình thức sự kiện không được để trống")
        @Pattern(regexp = "^(?i)(ONLINE|OFFLINE)$", message = "Hình thức sự kiện chỉ có thể là ONLINE hoặc OFFLINE")
        String format,
        @Size(max = 300, message = "Địa điểm tối đa 300 ký tự")
        String location,
        @Size(max = 1000, message = "Đường dẫn tham gia tối đa 1000 ký tự")
        String meetingUrl,
        @NotNull(message = "Thời gian bắt đầu không được để trống")
        Instant startsAt,
        @NotNull(message = "Thời gian kết thúc không được để trống")
        Instant endsAt,
        @Min(value = 1, message = "Số chỗ tối thiểu là 1")
        @Max(value = 100_000, message = "Số chỗ tối đa là 100.000")
        Integer capacity,
        @Size(max = 36, message = "Người dẫn không hợp lệ")
        String hostUserId,
        @Size(max = 36, message = "Mã ảnh bìa không hợp lệ")
        String coverMediaId,
        @NotNull(message = "Đối tượng sự kiện không được để trống")
        @Pattern(regexp = "^(?i)(PUBLIC|MEMBERS)$", message = "Đối tượng sự kiện chỉ có thể là PUBLIC hoặc MEMBERS")
        String audience) {}
