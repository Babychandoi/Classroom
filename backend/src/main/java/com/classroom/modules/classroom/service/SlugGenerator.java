package com.classroom.modules.classroom.service;

import java.text.Normalizer;
import java.util.Locale;

/**
 * D-28: the slug the server derives from a (Vietnamese) class title when the client sends none: diacritics stripped (đ -&gt; d), lower case,
 * every run of characters outside [a-z0-9] becomes one "-", trimmed of dashes, at most 60 characters, at least 3 (padded with "lop").
 */
public final class SlugGenerator {

    public static final int MAX_LENGTH = 60;

    private SlugGenerator() {}

    public static String fromTitle(String title) {
        String s = title == null ? "" : title.replace('đ', 'd').replace('Đ', 'D');
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        s = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > MAX_LENGTH) {
            s = s.substring(0, MAX_LENGTH).replaceAll("-+$", "");
        }
        if (s.isEmpty()) {
            return "lop";
        }
        if (s.length() < 3) {
            s = "lop-" + s;
        }
        return s;
    }

    /** {@code base} with "-n" appended, keeping the whole slug within {@link #MAX_LENGTH}. */
    public static String withSuffix(String base, String suffix) {
        String tail = "-" + suffix;
        String head = base.length() + tail.length() > MAX_LENGTH ? base.substring(0, MAX_LENGTH - tail.length()).replaceAll("-+$", "") : base;
        return head + tail;
    }
}
