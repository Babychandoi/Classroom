package com.classroom.modules.assessment.doc;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Count and UI controls share the same inherited policy, including block/option audio. */
public final class AudioPolicies {
    private AudioPolicies(){}
    public static PlaybackPolicy forAsset(AssessmentDocument doc,String key){
        List<PlaybackPolicy> values=new ArrayList<>();
        if(Objects.equals(doc.audioAssetKey(),key) || !doc.playback().equals(PlaybackPolicy.DEFAULT))values.add(doc.playback());
        for(SectionNode section:doc.sections())if(references(DocumentCodec.toTree(section),key) && (Objects.equals(section.audioAssetKey(),key) || !section.playback().equals(PlaybackPolicy.DEFAULT)))values.add(section.playback());
        for(GroupNode group:DocumentIndex.of(doc).groups())if(references(DocumentCodec.toTree(group),key) && (Objects.equals(group.audioAssetKey(),key) || !group.playback().equals(PlaybackPolicy.DEFAULT)))values.add(group.playback());
        if(values.isEmpty())return PlaybackPolicy.DEFAULT;
        int max=values.stream().mapToInt(PlaybackPolicy::maxPlays).filter(n->n>0).min().orElse(0);
        return new PlaybackPolicy(max,values.stream().allMatch(PlaybackPolicy::allowSeek),values.stream().allMatch(PlaybackPolicy::allowSpeed),values.stream().anyMatch(PlaybackPolicy::autoPlay),values.stream().mapToInt(PlaybackPolicy::pauseBeforeAnswerSec).max().orElse(0));
    }
    private static boolean references(JsonNode node,String key){if(node.isObject()){var fields=node.fields();while(fields.hasNext()){var e=fields.next();if(Set.of("assetKey","audioAssetKey").contains(e.getKey()) && key.equals(e.getValue().asText()) || references(e.getValue(),key))return true;}}else if(node.isArray())for(JsonNode child:node)if(references(child,key))return true;return false;}
}
