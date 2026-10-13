package com.classroom.modules.assessment.grading;

import java.math.BigDecimal;
import java.util.Map;

/**
 * D-34: the result of grading one item automatically.
 *
 * @param status      UNANSWERED | AUTO_GRADED | PENDING_MANUAL
 * @param correctness CORRECT | PARTIAL | WRONG | BLANK | MANUAL
 * @param fraction    0..1 (null when nothing could be evaluated automatically)
 * @param points      the automatic points (for HYBRID a suggestion; null for MANUAL / PASS_FAIL)
 * @param subResults  per statement / pair / blank / dropdown / option correctness, or null
 */
public record GradeOutcome(String status, String correctness, BigDecimal fraction, BigDecimal points, Map<String, Boolean> subResults) {
    public static final String UNANSWERED = "UNANSWERED";
    public static final String AUTO_GRADED = "AUTO_GRADED";
    public static final String PENDING_MANUAL = "PENDING_MANUAL";
    public static final String GRADED = "GRADED";
}
