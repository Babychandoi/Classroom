package com.classroom.modules.assessment.grading;

import com.classroom.modules.assessment.doc.ItemNode;
import com.classroom.modules.assessment.doc.Normalization;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.*;

/**
 * D-34: text answers matched after {@link TextNormalizer} - Q07 blanks (each blank has accepted variants), Q08 dragged words (one word per
 * blank) and Q10 short text (any accepted variant). Multi-blank items: partial = fraction of correct blanks, otherwise all-or-nothing.
 */
public class TextMatchGrader implements ItemGrader {

    @Override
    public Evaluation evaluate(ItemNode item, JsonNode response) {
        Normalization norm = item.grading().normalization();
        if ("Q10".equals(item.type())) {
            String text = Jsons.text(response, "text");
            if (text == null || TextNormalizer.isBlank(text)) return Evaluation.blankAnswer();
            boolean ok = Jsons.strings(item.answerKey(), "accepted").stream().anyMatch(a -> TextNormalizer.matches(text, a, norm));
            return new Evaluation(false, ok ? BigDecimal.ONE : BigDecimal.ZERO, null);
        }
        Map<String, String> answer = Jsons.textMap(response, "blanks");
        if (answer.values().stream().allMatch(v -> v == null || TextNormalizer.isBlank(v))) return Evaluation.blankAnswer();
        Map<String, List<String>> accepted = Jsons.listMap(item.answerKey(), "blanks");
        List<String> ids = new ArrayList<>(Jsons.ids(item.interaction(), "blanks"));
        if (ids.isEmpty()) ids.addAll(accepted.keySet());
        Map<String, Boolean> sub = new LinkedHashMap<>();
        int k = 0;
        for (String id : ids) {
            String given = answer.get(id);
            boolean ok = given != null && accepted.getOrDefault(id, List.of()).stream().anyMatch(a -> TextNormalizer.matches(given, a, norm));
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
        if ("Q10".equals(item.type())) {
            List<String> accepted = Jsons.strings(item.answerKey(), "accepted");
            if (accepted.isEmpty()) return null;
            out.put("text", accepted.get(0));
            return out;
        }
        Map<String, String> first = new LinkedHashMap<>();
        Jsons.listMap(item.answerKey(), "blanks").forEach((id, values) -> { if (!values.isEmpty()) first.put(id, values.get(0)); });
        out.set("blanks", Jsons.textObject(first));
        return out;
    }
}
