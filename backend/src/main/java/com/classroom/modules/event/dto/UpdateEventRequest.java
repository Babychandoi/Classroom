package com.classroom.modules.event.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * D-27: PUT /events/{id} - every field optional. A property ABSENT from the JSON keeps its value. For the optional fields (description,
 * forWhom, takeaways, location, meetingUrl, capacity, coverMediaId) an explicit {@code null} clears the value ({@code capacity: null} =
 * unlimited) and an empty string clears a text field. The presence flags are set by the setters, which Jackson calls only for properties
 * that are in the body.
 */
public class UpdateEventRequest {
    @Size(min = 1, max = 200, message = "Tên sự kiện từ 1 đến 200 ký tự")
    private String title;
    @Size(max = 20_000, message = "Mô tả sự kiện tối đa 20.000 ký tự")
    private String description;
    @Size(max = 500, message = "Mục \"Dành cho ai\" tối đa 500 ký tự")
    private String forWhom;
    @Size(max = 8, message = "Tối đa 8 ý \"Mang về gì\"")
    private List<@Size(max = 200, message = "Mỗi ý \"Mang về gì\" tối đa 200 ký tự") String> takeaways;
    @Pattern(regexp = "^(?i)(ONLINE|OFFLINE)$", message = "Hình thức sự kiện chỉ có thể là ONLINE hoặc OFFLINE")
    private String format;
    @Size(max = 300, message = "Địa điểm tối đa 300 ký tự")
    private String location;
    @Size(max = 1000, message = "Đường dẫn tham gia tối đa 1000 ký tự")
    private String meetingUrl;
    private Instant startsAt;
    private Instant endsAt;
    @Min(value = 1, message = "Số chỗ tối thiểu là 1")
    @Max(value = 100_000, message = "Số chỗ tối đa là 100.000")
    private Integer capacity;
    @Size(max = 36, message = "Người dẫn không hợp lệ")
    private String hostUserId;
    @Size(max = 36, message = "Mã ảnh bìa không hợp lệ")
    private String coverMediaId;
    @Pattern(regexp = "^(?i)(PUBLIC|MEMBERS)$", message = "Đối tượng sự kiện chỉ có thể là PUBLIC hoặc MEMBERS")
    private String audience;

    private boolean descriptionPresent;
    private boolean forWhomPresent;
    private boolean takeawaysPresent;
    private boolean locationPresent;
    private boolean meetingUrlPresent;
    private boolean capacityPresent;
    private boolean coverMediaIdPresent;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; this.descriptionPresent = true; }
    public String getForWhom() { return forWhom; }
    public void setForWhom(String forWhom) { this.forWhom = forWhom; this.forWhomPresent = true; }
    public List<String> getTakeaways() { return takeaways; }
    public void setTakeaways(List<String> takeaways) { this.takeaways = takeaways; this.takeawaysPresent = true; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; this.locationPresent = true; }
    public String getMeetingUrl() { return meetingUrl; }
    public void setMeetingUrl(String meetingUrl) { this.meetingUrl = meetingUrl; this.meetingUrlPresent = true; }
    public Instant getStartsAt() { return startsAt; }
    public void setStartsAt(Instant startsAt) { this.startsAt = startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public void setEndsAt(Instant endsAt) { this.endsAt = endsAt; }
    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer capacity) { this.capacity = capacity; this.capacityPresent = true; }
    public String getHostUserId() { return hostUserId; }
    public void setHostUserId(String hostUserId) { this.hostUserId = hostUserId; }
    public String getCoverMediaId() { return coverMediaId; }
    public void setCoverMediaId(String coverMediaId) { this.coverMediaId = coverMediaId; this.coverMediaIdPresent = true; }
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }

    public boolean hasDescription() { return descriptionPresent; }
    public boolean hasForWhom() { return forWhomPresent; }
    public boolean hasTakeaways() { return takeawaysPresent; }
    public boolean hasLocation() { return locationPresent; }
    public boolean hasMeetingUrl() { return meetingUrlPresent; }
    public boolean hasCapacity() { return capacityPresent; }
    public boolean hasCoverMediaId() { return coverMediaIdPresent; }
}
