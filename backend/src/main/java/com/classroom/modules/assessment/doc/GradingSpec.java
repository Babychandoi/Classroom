package com.classroom.modules.assessment.doc;

import java.math.BigDecimal;
import java.util.List;

/**
 * D-34: how an item is graded. {@code mode} AUTO | MANUAL | HYBRID | PASS_FAIL. {@code stepTable} (Q04, length n+1) maps "k statements
 * right" to a fraction of the points. {@code normalization} applies to text answers (diacritics are never stripped).
 */
public record GradingSpec(String mode, boolean partialCredit, List<BigDecimal> stepTable, String rubricId, Normalization normalization) {
    public GradingSpec {
        rubricId = rubricId == null || rubricId.isBlank() ? null : rubricId;
    }
}
