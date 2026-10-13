package com.classroom.modules.assessment.grading;

import com.classroom.modules.assessment.doc.ItemNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.*;

/**
 * D-34: Q01 (one option), Q02 (several options; with partial credit f = max(0, hits - wrong picks) / |key|, otherwise exact set) and Q03
 * (true / false).
 */
public class ChoiceGrader implements ItemGrader {

    @Override
    public Evaluation evaluate(ItemNode item, JsonNode response) {
        return switch (item.type()) {
            case "Q01" -> single(item, response);
            case "Q02" -> multiple(item, response);
            default -> trueFalse(item, response);
        };
    }

    private Evaluation single(ItemNode item, JsonNode response) {
        String chosen = Jsons.text(response, "optionId");
        if (chosen == null || chosen.isBlank()) return Evaluation.blankAnswer();
        String key = Jsons.text(item.answerKey(), "optionId");
        boolean ok = chosen.equals(key);
        return new Evaluation(false, ok ? BigDecimal.ONE : BigDecimal.ZERO, Map.of(chosen, ok));
    }

    private Evaluation multiple(ItemNode item, JsonNode response) {
        Set<String> chosen = new LinkedHashSet<>(Jsons.strings(response, "optionIds"));
        if (chosen.isEmpty()) return Evaluation.blankAnswer();
        Set<String> key = new LinkedHashSet<>(Jsons.strings(item.answerKey(), "optionIds"));
        Map<String, Boolean> sub = new LinkedHashMap<>();
        int hits = 0;
        int wrong = 0;
        for (String id : chosen) {
            boolean ok = key.contains(id);
            sub.put(id, ok);
            if (ok) hits++; else wrong++;
        }
        BigDecimal fraction;
        if (key.isEmpty()) {
            fraction = BigDecimal.ZERO;
        } else if (item.grading().partialCredit()) {
            fraction = ScoreMath.ratio(Math.max(0, hits - wrong), key.size());
        } else {
            fraction = chosen.equals(key) ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        return new Evaluation(false, fraction, sub);
    }

    private Evaluation trueFalse(ItemNode item, JsonNode response) {
        JsonNode v = response == null ? null : response.get("value");
        if (v == null || !v.isBoolean()) return Evaluation.blankAnswer();
        JsonNode key = item.answerKey().get("value");
        boolean ok = key != null && key.isBoolean() && key.booleanValue() == v.booleanValue();
        return new Evaluation(false, ok ? BigDecimal.ONE : BigDecimal.ZERO, null);
    }

    @Override
    public JsonNode correctResponse(ItemNode item) {
        ObjectNode out = Jsons.obj();
        switch (item.type()) {
            case "Q01" -> {
                String key = Jsons.text(item.answerKey(), "optionId");
                if (key == null) return null;
                out.put("optionId", key);
            }
            case "Q02" -> out.set("optionIds", Jsons.arr(Jsons.strings(item.answerKey(), "optionIds")));
            default -> {
                JsonNode key = item.answerKey().get("value");
                if (key == null || !key.isBoolean()) return null;
                out.put("value", key.booleanValue());
            }
        }
        return out;
    }
}
