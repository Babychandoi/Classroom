package com.classroom.modules.assessment.doc;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Explicit whitelist: authoring metadata, keys, hidden tests, feedback and transcripts never cross this boundary. */
public final class LearnerProjection {
    private LearnerProjection() {}
    public static Map<String,Object> object(Object... pairs) {
        Map<String,Object> out=new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2) out.put((String)pairs[i],pairs[i+1]);
        return out;
    }
    public static AssessmentDocument ordered(AssessmentDocument doc,long seed,boolean shuffle) {
        Random random=new Random(seed); List<SectionNode> sections=new ArrayList<>();
        for(SectionNode section:doc.sections()) {
            List<Node> nodes=new ArrayList<>(section.nodes());
            if(shuffle && section.shuffle()) Collections.shuffle(nodes,random);
            List<Node> result=new ArrayList<>();
            for(Node node:nodes) {
                if(node instanceof GroupNode group) {
                    List<ItemNode> children=new ArrayList<>(group.children());
                    if(shuffle && group.shuffleChildren()) Collections.shuffle(children,random);
                    result.add(new GroupNode(group.id(),group.groupType(),group.title(),group.instructions(),group.stimulus(),group.audioAssetKey(),
                        group.playback(),group.keepStimulusVisible(),group.display(),group.shuffleChildren(),children));
                } else result.add(node);
            }
            sections.add(new SectionNode(section.id(),section.title(),section.instructions(),section.shuffle(),result,section.audioAssetKey(),section.playback()));
        }
        return doc.withSections(sections);
    }
    public static Map<String,Object> project(AssessmentDocument original,long seed,boolean shuffle,String subjectName) {
        AssessmentDocument doc=ordered(original,seed,shuffle); DocumentIndex index=DocumentIndex.of(doc);
        List<Object> sections=new ArrayList<>();
        for(SectionNode section:doc.sections()) {
            List<Object> nodes=new ArrayList<>();
            for(Node node:section.nodes()) {
                if(node instanceof GroupNode g) {
                    AssetRef asset=doc.assets().get(g.audioAssetKey());
                    Object audio=asset==null?null:object("assetKey",g.audioAssetKey(),"name",asset.name(),"durationMs",asset.durationMs(),"languageCode",asset.languageCode());
                    nodes.add(object("kind","GROUP","id",g.id(),"groupType",g.groupType(),"label",index.labels().get(g.id()),"title",g.title(),
                        "instructions",g.instructions(),"stimulus",g.stimulus(),"audio",audio,"playback",g.playback(),"keepStimulusVisible",g.keepStimulusVisible(),
                        "display",g.display(),"points",index.points(g.id()),"children",g.children().stream().map(it->item(it,index,seed,shuffle)).toList()));
                } else nodes.add(item((ItemNode)node,index,seed,shuffle));
            }
            sections.add(object("id",section.id(),"title",section.title(),"instructions",section.instructions(),"points",index.points(section.id()),"nodes",nodes,"audio",audio(doc,section.audioAssetKey()),"playback",section.playback()));
        }
        return object("title",doc.title(),"kind",doc.kind(),"grade",doc.grade(),"subjectCode",doc.subjectCode(),"subjectName",subjectName,
            "languageCode",doc.languageCode(),"itemCount",index.items().size(),"totalPoints",index.total(),"sections",sections,"audio",audio(doc,doc.audioAssetKey()),"playback",doc.playback(),"images",new LinkedHashMap<>(),"audios",audioMap(doc));
    }
    private static Map<String,Object> audioMap(AssessmentDocument doc){Map<String,Object> out=new LinkedHashMap<>();doc.assets().forEach((key,a)->{if("AUDIO".equals(a.type()))out.put(key,audio(doc,key));});return out;}
    private static Object audio(AssessmentDocument doc,String key){AssetRef a=doc.assets().get(key);return a==null?null:object("assetKey",key,"name",a.name(),"durationMs",a.durationMs(),"languageCode",a.languageCode(),"playback",AudioPolicies.forAsset(doc,key));}
    private static Map<String,Object> item(ItemNode it,DocumentIndex index,long seed,boolean shuffle) {
        return object("kind","ITEM","id",it.id(),"type",it.type(),"label",index.labels().get(it.id()),"prompt",it.prompt(),"promptBlocks",it.promptBlocks(),
            "points",it.points(),"gradingMode",it.mode(),"interaction",interaction(it,seed,shuffle));
    }
    private static Map<String,Object> interaction(ItemNode it,long seed,boolean shuffle) {
        JsonNode x=it.interaction(); Map<String,Object> out=new LinkedHashMap<>();
        switch(it.type()) {
            case "Q01","Q02" -> {
                List<Object> options=list(x.path("options"),"id","text","assetKey","audioAssetKey");
                if(shuffle && x.path("shuffleOptions").asBoolean()) Collections.shuffle(options,new Random(seed^it.id().hashCode()));
                out.put("options",options);
            }
            case "Q04" -> out.put("statements",list(x.path("statements"),"id","text"));
            case "Q05" -> { out.put("left",list(x.path("left"),"id","text")); List<Object> right=list(x.path("right"),"id","text"); Collections.shuffle(right,new Random(seed^it.id().hashCode())); out.put("right",right); }
            case "Q06" -> { List<Object> items=list(x.path("items"),"id","text"); Collections.shuffle(items,new Random(seed^it.id().hashCode())); out.put("items",items); }
            case "Q07","Q08" -> {
                out.put("blanks",list(x.path("blanks"),"id"));
                if("Q08".equals(it.type())) {
                    List<String> bank=new ArrayList<>(); it.answerKey().path("blanks").elements().forEachRemaining(v->bank.add(v.asText()));
                    x.path("distractors").forEach(v->bank.add(v.asText())); Collections.shuffle(bank,new Random(seed^it.id().hashCode())); out.put("bank",bank);
                }
            }
            case "Q09" -> { List<Object> list=new ArrayList<>(); for(JsonNode d:x.path("dropdowns")) list.add(object("id",d.path("id").asText(),"options",list(d.path("options"),"id","text"))); out.put("dropdowns",list); }
            case "Q10" -> copy(out,x,"maxLength");
            case "Q11" -> copy(out,x,"unit");
            case "Q12" -> { copy(out,x,"minWords"); copy(out,x,"maxWords"); }
            case "Q22" -> out.put("recording",fields(x.path("recording"),"prepSeconds","maxSeconds","maxRetakes","allowFileFallback","maxFileMb"));
            case "Q14" -> { copy(out,x,"assetKey"); out.put("regions",list(x.path("regions"),"id","label","x","y","width","height")); }
            case "Q15" -> { copy(out,x,"assetKey"); out.put("labels",list(x.path("labels"),"id","text")); out.put("targets",list(x.path("targets"),"id","label","x","y","width","height")); }
            case "Q16" -> { out.put("cards",list(x.path("cards"),"id","text")); out.put("categories",list(x.path("categories"),"id","text")); }
            case "Q17" -> { copy(out,x,"headers"); out.put("rows",list(x.path("rows"),"id","label")); out.put("cells",list(x.path("cells"),"id","row","column","text","editable")); }
            case "Q18" -> { copy(out,x,"xLabel"); copy(out,x,"yLabel"); copy(out,x,"min"); copy(out,x,"max"); out.put("points",list(x.path("points"),"id","x","label")); }
            case "Q19" -> { copy(out,x,"variables"); copy(out,x,"chemical"); }
            case "Q20" -> out.put("steps",list(x.path("steps"),"id","prompt","points"));
            case "Q23" -> { copy(out,x,"width"); copy(out,x,"height"); copy(out,x,"backgroundAssetKey"); }
            case "Q24","Q25","Q28" -> { copy(out,x,"maxFileMb"); copy(out,x,"maxFiles"); copy(out,x,"accept"); copy(out,x,"instructions"); }
            case "Q26" -> { copy(out,x,"language"); copy(out,x,"starterCode"); out.put("examples",list(x.path("examples"),"input","output")); }
            case "Q27" -> { copy(out,x,"simulation"); out.put("controls",list(x.path("controls"),"id","label","min","max","step","initial")); copy(out,x,"formula"); }
            default -> { }
        }
        return out;
    }
    private static void copy(Map<String,Object> out,JsonNode source,String key) {
        if(source.has(key)) out.put(key,DocumentCodec.mapper().convertValue(source.get(key),Object.class));
    }
    private static Map<String,Object> fields(JsonNode node,String... keys) { Map<String,Object> out=new LinkedHashMap<>(); for(String k:keys) copy(out,node,k); return out; }
    private static List<Object> list(JsonNode nodes,String... keys) { List<Object> out=new ArrayList<>(); for(JsonNode n:nodes) out.add(fields(n,keys)); return out; }
}
