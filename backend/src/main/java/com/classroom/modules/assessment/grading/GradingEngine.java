package com.classroom.modules.assessment.grading;

import com.classroom.modules.assessment.doc.ItemNode;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Map;

/**
 * D-34: grades one item. Three concerns are kept apart: the container type (Q13 / Q21 groups are never graded), the input type (picks the
 * {@link ItemGrader} strategy) and the grading mode, which overrides the strategy:
 * <ul>
 *   <li>AUTO - the strategy decides (BLANK answers are UNANSWERED with 0 points);</li>
 *   <li>HYBRID - the strategy's points are stored as a suggestion, the item still waits for the teacher (PENDING_MANUAL);</li>
 *   <li>MANUAL / PASS_FAIL, and Q12 / Q22 whatever the mode - PENDING_MANUAL, no automatic points.</li>
 * </ul>
 */
public final class GradingEngine {

    private static final ItemGrader CHOICE = new ChoiceGrader();
    private static final ItemGrader KEYED = new KeyedMapGrader();
    private static final ItemGrader SEQUENCE = new SequenceGrader();
    private static final ItemGrader TEXT = new TextMatchGrader();
    private static final ItemGrader NUMERIC = new NumericGrader();
    private static final ItemGrader MANUAL = new ManualGrader();
    private static final ItemGrader ADVANCED = new AdvancedGrader();

    private static final Map<String, ItemGrader> BY_TYPE = Map.ofEntries(
            Map.entry("Q01", CHOICE), Map.entry("Q02", CHOICE), Map.entry("Q03", CHOICE),
            Map.entry("Q04", KEYED), Map.entry("Q05", KEYED), Map.entry("Q09", KEYED),
            Map.entry("Q06", SEQUENCE),
            Map.entry("Q07", TEXT), Map.entry("Q08", TEXT), Map.entry("Q10", TEXT),
            Map.entry("Q11", NUMERIC),
            Map.entry("Q12", MANUAL), Map.entry("Q22", MANUAL),
            Map.entry("Q14", ADVANCED), Map.entry("Q15", ADVANCED), Map.entry("Q16", ADVANCED), Map.entry("Q17", ADVANCED),
            Map.entry("Q18", ADVANCED), Map.entry("Q19", ADVANCED), Map.entry("Q20", ADVANCED), Map.entry("Q27", ADVANCED),
            Map.entry("Q23", MANUAL), Map.entry("Q24", MANUAL), Map.entry("Q25", MANUAL), Map.entry("Q28", MANUAL));

    private GradingEngine() {}

    public static ItemGrader graderFor(String type) {
        return BY_TYPE.getOrDefault(type, MANUAL);
    }

    public static boolean isManualType(String type) {
        if("Q26".equals(type))return false;
        return graderFor(type) == MANUAL;
    }

    /** Whether the item waits for a teacher (MANUAL / PASS_FAIL / HYBRID mode, or a manual-only type). */
    public static boolean needsTeacher(ItemNode item) {
        String mode = item.mode();
        return isManualType(item.type()) || "MANUAL".equals(mode) || "PASS_FAIL".equals(mode) || "HYBRID".equals(mode);
    }

    public static GradeOutcome grade(ItemNode item, JsonNode response) {
        if("Q26".equals(item.type()) && !"MANUAL".equals(item.mode()) && !"PASS_FAIL".equals(item.mode())) {
            if(response==null || response.isNull() || response.path("code").asText().isBlank())
                return new GradeOutcome(GradeOutcome.UNANSWERED,"BLANK",BigDecimal.ZERO,BigDecimal.ZERO,null);
            return new GradeOutcome("PENDING_CODE","PENDING",null,null,null);
        }
        ItemGrader grader = graderFor(item.type());
        String mode = item.mode();
        if (grader == MANUAL || "MANUAL".equals(mode) || "PASS_FAIL".equals(mode)) {
            return new GradeOutcome(GradeOutcome.PENDING_MANUAL, "MANUAL", null, null, null);
        }
        ItemGrader.Evaluation evaluation = grader.evaluate(item, response);
        BigDecimal points = ScoreMath.ofFraction(evaluation.fraction(), item.points());
        String correctness = evaluation.blank() ? "BLANK"
                : ScoreMath.isOne(evaluation.fraction()) ? "CORRECT"
                : ScoreMath.isZero(evaluation.fraction()) ? "WRONG" : "PARTIAL";
        if ("HYBRID".equals(mode)) {
            return new GradeOutcome(GradeOutcome.PENDING_MANUAL, correctness, evaluation.fraction(), points, evaluation.subResults());
        }
        String status = evaluation.blank() ? GradeOutcome.UNANSWERED : GradeOutcome.AUTO_GRADED;
        return new GradeOutcome(status, correctness, evaluation.fraction(), points, evaluation.subResults());
    }

    /** The key as a learner response (Q12 / Q22: null). */
    public static JsonNode correctResponse(ItemNode item) {
        return graderFor(item.type()).correctResponse(item);
    }
}
