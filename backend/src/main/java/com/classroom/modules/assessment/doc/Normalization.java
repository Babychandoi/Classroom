package com.classroom.modules.assessment.doc;

/**
 * D-34: optional text normalization of a text-matching item. NFC, NBSP and curly quotes are always normalized; diacritics never are
 * ("Hà Nội" != "Ha Noi").
 */
public record Normalization(boolean caseInsensitive, boolean collapseWhitespace, boolean stripTrailingPunctuation) {
    public static final Normalization NONE = new Normalization(false, false, false);
}
