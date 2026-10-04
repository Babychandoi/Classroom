package com.classroom.modules.classroom.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SlugGeneratorTest {

    @Test
    @DisplayName("D-28: Vietnamese diacritics and đ are stripped, everything else becomes single dashes")
    void vietnamese() {
        assertEquals("tieng-anh-giao-tiep", SlugGenerator.fromTitle("Tiếng Anh giao tiếp"));
        assertEquals("duong-den-thanh-cong", SlugGenerator.fromTitle("  Đường đến THÀNH CÔNG!!! "));
        assertEquals("on-thi-thpt-2026-toan-ly", SlugGenerator.fromTitle("Ôn thi THPT 2026 — Toán & Lý"));
        assertEquals("suc-khoe-an-chay", SlugGenerator.fromTitle("Sức khoẻ / Ăn chay 🌱"));
        assertEquals("ky-nang-mem", SlugGenerator.fromTitle("Kỹ năng mềm"));
    }

    @Test
    @DisplayName("D-28: at most 60 characters (no trailing dash), at least 3 (padded with 'lop'), empty -> 'lop'")
    void lengthBounds() {
        String slug = SlugGenerator.fromTitle("Lớp học " + "rất dài ".repeat(20));
        assertTrue(slug.length() <= 60, slug);
        assertFalse(slug.endsWith("-"));
        assertEquals("lop", SlugGenerator.fromTitle("!!!"));
        assertEquals("lop", SlugGenerator.fromTitle(null));
        assertEquals("lop-ai", SlugGenerator.fromTitle("AI"));
        assertEquals("lop-d", SlugGenerator.fromTitle("Đ"));
        assertEquals("abc", SlugGenerator.fromTitle("abc"));
    }

    @Test
    @DisplayName("D-28: a collision suffix keeps the slug within 60 characters")
    void suffix() {
        String base = "a".repeat(60);
        String withSuffix = SlugGenerator.withSuffix(base, "2");
        assertEquals(60, withSuffix.length());
        assertTrue(withSuffix.endsWith("-2"));
        assertEquals("lop-hoc-3", SlugGenerator.withSuffix("lop-hoc", "3"));
    }

    @Test
    @DisplayName("D-28: object positions 'X% Y%' 0..100")
    void positions() {
        assertEquals("50% 30%", ObjectPositions.require(" 50% 30% ", "bìa"));
        assertEquals("0% 100%", ObjectPositions.require("0% 100%", "bìa"));
        assertNull(ObjectPositions.require("", "bìa"));
        assertThrows(com.classroom.common.AppException.class, () -> ObjectPositions.require("100% 101%", "bìa"));
        assertThrows(com.classroom.common.AppException.class, () -> ObjectPositions.require("50 % 30%", "bìa"));
    }
}
