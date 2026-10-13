package com.classroom.modules.assessment.doc;

import java.util.List;

/** D-34: a section ("Phần") of an assessment: an ordered list of groups and standalone items. */
public record SectionNode(String id, String title, String instructions, boolean shuffle, List<Node> nodes,String audioAssetKey,PlaybackPolicy playback) {
    public SectionNode(String id,String title,String instructions,boolean shuffle,List<Node> nodes){this(id,title,instructions,shuffle,nodes,null,PlaybackPolicy.DEFAULT);}
    public SectionNode {
        title = title == null ? "" : title;
        playback=playback==null?PlaybackPolicy.DEFAULT:playback;
        instructions = instructions == null ? "" : instructions;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }
}
