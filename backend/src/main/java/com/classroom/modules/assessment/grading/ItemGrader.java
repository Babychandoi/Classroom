package com.classroom.modules.assessment.grading;

import com.classroom.modules.assessment.doc.ItemNode;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Map;

/** D-34: a grading strategy - evaluates a response against the item's answer key, independent of the grading mode. */
public interface ItemGrader {

    /** {@code blank}: nothing was answered; otherwise {@code fraction} 0..1 and optional per-part results. */
    record Evaluation(boolean blank, BigDecimal fraction, Map<String, Boolean> subResults) {
        public static Evaluation blankAnswer() {
            return new Evaluation(true, BigDecimal.ZERO, null);
        }
    }

    Evaluation evaluate(ItemNode item, JsonNode response);

    /** The answer key expressed as a learner response (shown as "đáp án đúng" in a result), or null. */
    JsonNode correctResponse(ItemNode item);
}
