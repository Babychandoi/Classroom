package com.classroom.modules.assessment.doc;

import java.util.List;

/**
 * D-34: a group container - Q13 (shared reading passage / table / image) or Q21 (shared listening audio). It is never graded itself; its
 * points are the sum of its children. {@code audioAssetKey} points into {@link AssessmentDocument#assets()}; several groups may share one
 * asset. {@code display} ALL | ONE.
 */
public record GroupNode(
        String id,
        String groupType,
        String title,
        String instructions,
        List<ContentBlock> stimulus,
        String audioAssetKey,
        PlaybackPolicy playback,
        boolean keepStimulusVisible,
        String display,
        boolean shuffleChildren,
        List<ItemNode> children
) implements Node {
    public GroupNode {
        title = title == null ? "" : title;
        instructions = instructions == null ? "" : instructions;
        stimulus = stimulus == null ? List.of() : List.copyOf(stimulus);
        playback = playback == null ? PlaybackPolicy.DEFAULT : playback;
        display = display == null ? "ALL" : display;
        children = children == null ? List.of() : List.copyOf(children);
        audioAssetKey = audioAssetKey == null || audioAssetKey.isBlank() ? null : audioAssetKey;
    }
}
