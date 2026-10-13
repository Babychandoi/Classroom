package com.classroom.modules.assessment.grading;

import com.classroom.modules.assessment.doc.ItemNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** D-34: Q06 ordering - exact order = 1; with partial credit, positions correct / n. The key defaults to the authored item order. */
public class SequenceGrader implements ItemGrader {

    static List<String> keyOrder(ItemNode item) {
        List<String> key = Jsons.strings(item.answerKey(), "order");
        return key.isEmpty() ? Jsons.ids(item.interaction(), "items") : key;
    }

    @Override
    public Evaluation evaluate(ItemNode item, JsonNode response) {
        List<String> answer = Jsons.strings(response, "order");
        if (answer.isEmpty()) return Evaluation.blankAnswer();
        List<String> key = keyOrder(item);
        Map<String, Boolean> sub = new LinkedHashMap<>();
        int correct = 0;
        for (int i = 0; i < key.size(); i++) {
            boolean ok = i < answer.size() && key.get(i).equals(answer.get(i));
            sub.put(key.get(i), ok);
            if (ok) correct++;
        }
        boolean exact = answer.equals(key);
        BigDecimal fraction = exact ? BigDecimal.ONE
                : item.grading().partialCredit() ? ScoreMath.ratio(correct, key.size()) : BigDecimal.ZERO;
        return new Evaluation(false, fraction, sub);
    }

    @Override
    public JsonNode correctResponse(ItemNode item) {
        ObjectNode out = Jsons.obj();
        out.set("order", Jsons.arr(keyOrder(item)));
        return out;
    }
}
