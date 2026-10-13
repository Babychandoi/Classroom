package com.classroom.modules.assessment.doc;

/**
 * D-34: a media file referenced by the document ({@code assets[assetKey]}), type AUDIO | IMAGE. {@code transcript} and {@code voice} are
 * authoring data: the learner projection never carries them; the transcript is released in a result only when
 * {@code transcriptVisibility} (NEVER | AFTER_SUBMISSION | AFTER_DUE) allows.
 */
public record AssetRef(String assetId, String type, String name, String mime, Long sizeBytes, Long durationMs, String languageCode,
                       String voice, String transcript, String transcriptVisibility) {
    public AssetRef {
        transcriptVisibility = transcriptVisibility == null ? "AFTER_SUBMISSION" : transcriptVisibility;
        name = name == null ? "" : name;
        mime = mime == null ? "" : mime;
    }
}
