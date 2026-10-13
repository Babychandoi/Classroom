package com.classroom.modules.assessment.doc;

/** D-34: listening playback policy of a Q21 group. {@code maxPlays} 0 = unlimited; enforced by the server (plays endpoint). */
public record PlaybackPolicy(int maxPlays, boolean allowSeek, boolean allowSpeed, boolean autoPlay, int pauseBeforeAnswerSec) {
    public static final PlaybackPolicy DEFAULT = new PlaybackPolicy(0, true, false, false, 0);
}
