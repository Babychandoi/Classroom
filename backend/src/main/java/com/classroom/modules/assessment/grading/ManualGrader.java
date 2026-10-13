package com.classroom.modules.assessment.grading;

import com.classroom.modules.assessment.doc.ItemNode;
import com.fasterxml.jackson.databind.JsonNode;

/** D-34: Q12 essay and Q22 speaking - never graded automatically; the teacher grades them (points, rubric or pass / fail). */
public class ManualGrader implements ItemGrader {

    @Override
    public Evaluation evaluate(ItemNode item, JsonNode response) {
        return null;
    }

    @Override
    public JsonNode correctResponse(ItemNode item) {
        return null;
    }

    static boolean answered(ItemNode item, JsonNode response) {
        if (!Jsons.present(response)) return false;
        if ("Q22".equals(item.type())) return Jsons.text(response, "recordingAssetId") != null;
        String text = Jsons.text(response, "text");
        return text != null && !TextNormalizer.isBlank(text);
    }
}
