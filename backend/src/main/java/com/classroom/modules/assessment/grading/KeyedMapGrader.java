package com.classroom.modules.assessment.grading;

import com.classroom.modules.assessment.doc.ItemNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.*;

/**
 * D-34: keyed answers - Q04 {@code values{statementId: bool}} (stepTable / partial k/n / all-or-nothing), Q05 {@code pairs{left: right}} and
 * Q09 {@code choices{dropdown: option}} (partial = fraction of correct entries, otherwise all-or-nothing). A missing entry counts as wrong.
 */
public class KeyedMapGrader implements ItemGrader {

    @Override
    public Evaluation evaluate(ItemNode item, JsonNode response) {
        return switch (item.type()) {
            case "Q04" -> statements(item, response);
            case "Q05" -> mapped(item, response, "pairs", "pairs", Jsons.ids(item.interaction(), "left"));
            default -> mapped(item, response, "choices", "choices", Jsons.ids(item.interaction(), "dropdowns"));
        };
    }

    private Evaluation statements(ItemNode item, JsonNode response) {
        Map<String, Boolean> answer = Jsons.boolMap(response, "values");
        if (answer.isEmpty()) return Evaluation.blankAnswer();
        Map<String, Boolean> key = Jsons.boolMap(item.answerKey(), "values");
        List<String> ids = new ArrayList<>(Jsons.ids(item.interaction(), "statements"));
        if (ids.isEmpty()) ids.addAll(key.keySet());
        Map<String, Boolean> sub = new LinkedHashMap<>();
        int k = 0;
        for (String id : ids) {
            boolean ok = answer.containsKey(id) && Objects.equals(answer.get(id), key.get(id));
            sub.put(id, ok);
            if (ok) k++;
        }
        int n = ids.size();
        BigDecimal fraction;
        List<BigDecimal> steps = item.grading().stepTable();
        if (steps != null && steps.size() == n + 1 && steps.stream().allMatch(Objects::nonNull)) {
            fraction = steps.get(k).max(BigDecimal.ZERO).min(BigDecimal.ONE);
        } else if (item.grading().partialCredit()) {
            fraction = ScoreMath.ratio(k, n);
        } else {
            fraction = n > 0 && k == n ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        return new Evaluation(false, fraction, sub);
    }

    private Evaluation mapped(ItemNode item, JsonNode response, String responseField, String keyField, List<String> interactionIds) {
        Map<String, String> answer = Jsons.textMap(response, responseField);
        answer.values().removeIf(v -> v == null || v.isBlank());
        if (answer.isEmpty()) return Evaluation.blankAnswer();
        Map<String, String> key = Jsons.textMap(item.answerKey(), keyField);
        List<String> ids = new ArrayList<>(key.keySet());
        if (ids.isEmpty()) ids.addAll(interactionIds);
        Map<String, Boolean> sub = new LinkedHashMap<>();
        int k = 0;
        for (String id : ids) {
            boolean ok = key.get(id) != null && key.get(id).equals(answer.get(id));
            sub.put(id, ok);
            if (ok) k++;
        }
        int n = ids.size();
        BigDecimal fraction = item.grading().partialCredit()
                ? ScoreMath.ratio(k, n)
                : (n > 0 && k == n ? BigDecimal.ONE : BigDecimal.ZERO);
        return new Evaluation(false, fraction, sub);
    }

    @Override
    public JsonNode correctResponse(ItemNode item) {
        ObjectNode out = Jsons.obj();
        switch (item.type()) {
            case "Q04" -> {
                ObjectNode values = Jsons.obj();
                Jsons.boolMap(item.answerKey(), "values").forEach(values::put);
                out.set("values", values);
            }
            case "Q05" -> out.set("pairs", Jsons.textObject(Jsons.textMap(item.answerKey(), "pairs")));
            default -> out.set("choices", Jsons.textObject(Jsons.textMap(item.answerKey(), "choices")));
        }
        return out;
    }
}
