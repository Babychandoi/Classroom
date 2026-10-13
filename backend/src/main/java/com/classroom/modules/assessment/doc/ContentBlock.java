package com.classroom.modules.assessment.doc;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * D-34: a rich content block - {@code TEXT{text}} (Markdown, rendered without raw HTML), {@code MATH{latex, display?}},
 * {@code IMAGE{assetKey, alt?}}, {@code AUDIO{assetKey}} or {@code TABLE{header, rows}}. Unused fields are omitted from the JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContentBlock(String type, String text, String latex, Boolean display, String assetKey, String alt, Boolean header,
                           List<List<String>> rows) {

    public static ContentBlock text(String text) {
        return new ContentBlock("TEXT", text, null, null, null, null, null, null);
    }
}
