package com.classroom.modules.classroom.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;

import java.util.List;

/** D-28: the fixed list of class categories (exact strings, in the order the UI shows them as chips). */
public final class ClassCategories {

    public static final List<String> ALL = List.of(
            "Nấu ăn", "Ăn chay", "Sức khoẻ", "Chạy bộ", "Thể hình", "YouTube",
            "Kinh doanh", "Tiếng Anh", "Ôn thi", "AI", "Âm nhạc", "Phát triển bản thân");

    private ClassCategories() {}

    /** Exact match (after trimming); 400 otherwise. Null stays null. */
    public static String require(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (!ALL.contains(value)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Danh mục lớp học không hợp lệ: " + raw);
        }
        return value;
    }
}
