package com.classroom.modules.classroom.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** D-28: CSS object-position of a class cover / avatar - "X% Y%", both 0..100; null / blank = centre. */
public final class ObjectPositions {

    private static final Pattern FORMAT = Pattern.compile("^(\\d{1,3})% (\\d{1,3})%$");

    private ObjectPositions() {}

    public static String require(String raw, String field) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        Matcher m = FORMAT.matcher(value);
        if (!m.matches() || Integer.parseInt(m.group(1)) > 100 || Integer.parseInt(m.group(2)) > 100) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Vị trí ảnh " + field + " phải có dạng \"X% Y%\" với X, Y từ 0 đến 100");
        }
        return value;
    }
}
