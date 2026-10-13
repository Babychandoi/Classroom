package com.classroom.modules.assessment.doc;

import com.classroom.modules.assessment.catalog.QuestionType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.math.BigDecimal;
import java.util.List;

/**
 * D-34: one gradable question. {@code interaction} (what the learner sees / does) and {@code answerKey} (authoring only, never sent to a
 * learner) are per-type JSON objects - see API.md §14 for their shapes. Q04 is ONE item whose {@code interaction.statements} are the a/b/c/d.
 */
public record ItemNode(
        String id,
        String type,
        String prompt,
        List<ContentBlock> promptBlocks,
        BigDecimal points,
        GradingSpec grading,
        String feedback,
        String difficulty,
        String outcome,
        List<String> tags,
        JsonNode interaction,
        JsonNode answerKey
) implements Node {
    public ItemNode {
        prompt = prompt == null ? "" : prompt;
        promptBlocks = promptBlocks == null ? List.of() : List.copyOf(promptBlocks);
        tags = tags == null ? List.of() : List.copyOf(tags);
        interaction = interaction == null || interaction.isNull() ? JsonNodeFactory.instance.objectNode() : interaction;
        answerKey = answerKey == null || answerKey.isNull() ? JsonNodeFactory.instance.objectNode() : answerKey;
        if (grading == null) {
            String defaultMode = QuestionType.of(type).map(QuestionType::defaultMode).orElse("AUTO");
            grading = new GradingSpec(defaultMode == null ? "AUTO" : defaultMode, false, null, null, null);
        } else if (grading.mode() == null) {
            String defaultMode = QuestionType.of(type).map(QuestionType::defaultMode).orElse("AUTO");
            grading = new GradingSpec(defaultMode == null ? "AUTO" : defaultMode, grading.partialCredit(), grading.stepTable(),
                    grading.rubricId(), grading.normalization());
        }
    }

    /** The grading mode, never null. */
    public String mode() {
        return grading.mode();
    }

    /** A copy with another answer key (tests / bank tooling). */
    public ItemNode withAnswerKey(JsonNode key) {
        return new ItemNode(id, type, prompt, promptBlocks, points, grading, feedback, difficulty, outcome, tags, interaction, key);
    }

    public ItemNode withInteraction(JsonNode newInteraction) {
        return new ItemNode(id, type, prompt, promptBlocks, points, grading, feedback, difficulty, outcome, tags, newInteraction, answerKey);
    }
}
