package com.classroom.modules.assessment.grading;

import com.classroom.modules.assessment.doc.ItemNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * D-34: Q11 numbers. The key is {@code {value, tolerance, toleranceType ABSOLUTE | RELATIVE_PERCENT, range?{min,max}, acceptedUnits?}}; an
 * inclusive range wins over value ± tolerance. A unit suffix must be accepted (the item's own {@code interaction.unit} always is); text that
 * is not a number is WRONG.
 */
public class NumericGrader implements ItemGrader {

    @Override
    public Evaluation evaluate(ItemNode item, JsonNode response) {
        String text = Jsons.text(response, "text");
        if (text == null || text.isBlank()) return Evaluation.blankAnswer();
        JsonNode key = item.answerKey();
        List<String> units = new ArrayList<>(Jsons.strings(key, "acceptedUnits"));
        String unit = Jsons.text(item.interaction(), "unit");
        if (unit != null && !unit.isBlank()) units.add(unit);
        Optional<BigDecimal> parsed = NumericParser.parse(text, units);
        if (parsed.isEmpty()) return new Evaluation(false, BigDecimal.ZERO, null);
        return new Evaluation(false, matches(parsed.get(), key) ? BigDecimal.ONE : BigDecimal.ZERO, null);
    }

    static boolean matches(BigDecimal x, JsonNode key) {
        JsonNode range = key.get("range");
        if (range != null && range.isObject() && range.get("min") != null && range.get("min").isNumber()
                && range.get("max") != null && range.get("max").isNumber()) {
            return x.compareTo(range.get("min").decimalValue()) >= 0 && x.compareTo(range.get("max").decimalValue()) <= 0;
        }
        JsonNode value = key.get("value");
        if (value == null || !value.isNumber()) return false;
        BigDecimal v = value.decimalValue();
        JsonNode tolNode = key.get("tolerance");
        BigDecimal tol = tolNode != null && tolNode.isNumber() ? tolNode.decimalValue().abs() : BigDecimal.ZERO;
        BigDecimal diff = x.subtract(v).abs();
        if ("RELATIVE_PERCENT".equals(Jsons.text(key, "toleranceType"))) {
            BigDecimal allowed = v.abs().multiply(tol, ScoreMath.MC).divide(BigDecimal.valueOf(100), ScoreMath.MC);
            return diff.compareTo(allowed) <= 0;
        }
        return diff.compareTo(tol) <= 0;
    }

    @Override
    public JsonNode correctResponse(ItemNode item) {
        JsonNode key = item.answerKey();
        String unit = Jsons.text(item.interaction(), "unit");
        String suffix = unit == null || unit.isBlank() ? "" : " " + unit;
        ObjectNode out = Jsons.obj();
        JsonNode range = key.get("range");
        if (range != null && range.isObject() && range.get("min") != null && range.get("max") != null) {
            out.put("text", range.get("min").asText() + " – " + range.get("max").asText() + suffix);
            return out;
        }
        JsonNode value = key.get("value");
        if (value == null || !value.isNumber()) return null;
        out.put("text", value.decimalValue().stripTrailingZeros().toPlainString() + suffix);
        return out;
    }
}
