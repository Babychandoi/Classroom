package com.classroom.modules.assessment.service;

import com.classroom.common.*;
import com.classroom.modules.assessment.doc.*;
import com.classroom.modules.assessment.dto.AssessmentRequests.Create;
import com.classroom.modules.assessment.policy.AssessmentPolicy;
import com.classroom.modules.assessment.repository.AssessmentStore;
import com.classroom.modules.assessment.repository.AssessmentStore.Row;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
import static com.classroom.modules.assessment.doc.LearnerProjection.object;

@Service
public class AssessmentMatrixService {
    private final AssessmentStore store;
    private final AssessmentPolicy policy;
    private final AssessmentAuthoringService author;
    public AssessmentMatrixService(AssessmentStore store,AssessmentPolicy policy,AssessmentAuthoringService author){this.store=store;this.policy=policy;this.author=author;}
    @Transactional(readOnly=true)
    public Map<String,Object> preview(String classId,String user,JsonNode request) {
        policy.requireAuthorRead(user,classId);JsonNode cells=request.path("cells");if(!cells.isArray() || cells.isEmpty() || cells.size()>100)bad("Ma trận cần 1–100 ô yêu cầu");
        Integer grade=request.path("grade").isNumber()?request.path("grade").asInt():null;String subject=request.path("subjectCode").asText("");long seed=request.path("seed").asLong(0);
        List<Row> bank=new ArrayList<>(store.rows("SELECT b.* FROM question_bank_items b JOIN assessments a ON a.id=b.assessment_id WHERE b.class_id=? AND a.status='DRAFT' ORDER BY b.id LIMIT 10000",classId));
        Collections.shuffle(bank,new Random(seed));Map<String,AssessmentDocument> docs=new HashMap<>();List<Row> picked=new ArrayList<>();Set<String> used=new HashSet<>();Map<String,BigDecimal> selectedPoints=new HashMap<>();List<Map<String,Object>> coverage=new ArrayList<>();
        for(JsonNode cell:cells){int count=cell.path("count").asInt();if(count<1 || count>500 || picked.size()+count>500)bad("Ma trận tối đa 500 câu");
            BigDecimal perPoint=cell.has("points")?cell.path("points").decimalValue():null;if(perPoint!=null && (perPoint.signum()<=0 || perPoint.compareTo(BigDecimal.valueOf(100))>0 || perPoint.stripTrailingZeros().scale()>2))bad("Điểm mỗi câu không hợp lệ");
            String type=cell.path("type").asText(""),difficulty=cell.path("difficulty").asText(""),outcome=cell.path("outcome").asText(""),mode=cell.path("mode").asText("");int found=0;
            for(Row row:bank){if(found>=count)break;if(used.contains(row.text("id")) || !type.isBlank() && !type.equals(row.text("type_code")) || !mode.isBlank() && !mode.equals(row.text("grading_mode")) || grade!=null && row.number("grade")!=grade || !subject.isBlank() && !subject.equals(row.text("subject_code")))continue;
                AssessmentDocument doc=docs.computeIfAbsent(row.text("assessment_id"),id->DocumentCodec.parse(store.assessment(id,false).text("content_json")));ItemNode item=DocumentIndex.of(doc).item(row.text("item_id")).orElseThrow().item();
                if(!difficulty.isBlank() && !difficulty.equals(item.difficulty()) || !outcome.isBlank() && !(Objects.toString(item.outcome(),"")+" "+String.join(" ",item.tags())).toLowerCase(Locale.ROOT).contains(outcome.toLowerCase(Locale.ROOT)))continue;
                picked.add(row);if(perPoint!=null)selectedPoints.put(row.text("id"),perPoint);used.add(row.text("id"));found++;
            }
            coverage.add(object("type",type,"difficulty",difficulty,"outcome",outcome,"requested",count,"selected",found));
        }
        Map<String,AssetRef> assets=new LinkedHashMap<>();List<Node> nodes=new ArrayList<>();Map<String,Integer> groups=new HashMap<>();Map<String,String> nodeSections=new HashMap<>();Map<String,SectionNode> originalSections=new LinkedHashMap<>();Map<String,AssessmentDocument> sectionDocuments=new HashMap<>();Map<String,String> sectionPrefixes=new HashMap<>();
        for(Row row:picked){AssessmentDocument source=docs.get(row.text("assessment_id"));String prefix="a"+row.text("assessment_id").replace("-","").substring(0,8)+"_";
            source.assets().forEach((key,asset)->assets.put(assetKey(prefix,key),asset));var ref=DocumentIndex.of(source).item(row.text("item_id")).orElseThrow();ItemNode item=copyItem(ref.item(),prefix);if(selectedPoints.containsKey(row.text("id"))){ObjectNode tree=(ObjectNode)DocumentCodec.toTree(item);tree.put("points",selectedPoints.get(row.text("id")));item=DocumentCodec.mapper().convertValue(tree,ItemNode.class);}
            String sectionKey=row.text("assessment_id")+":"+ref.section().id();originalSections.putIfAbsent(sectionKey,ref.section());sectionDocuments.put(sectionKey,source);sectionPrefixes.put(sectionKey,prefix);
            if(ref.group()==null){nodes.add(item);nodeSections.put(item.id(),sectionKey);}else{
                String groupKey=row.text("assessment_id")+":"+ref.group().id();Integer at=groups.get(groupKey);GroupNode g=ref.group();
                if(at==null){at=nodes.size();groups.put(groupKey,at);ObjectNode group=(ObjectNode)rebase(DocumentCodec.toTree(g),prefix);String groupId=UUID.randomUUID().toString();group.put("id",groupId);nodeSections.put(groupId,sectionKey);group.set("children",DocumentCodec.mapper().createArrayNode());
                    GroupNode copied=DocumentCodec.mapper().convertValue(group,GroupNode.class);nodes.add(new GroupNode(copied.id(),copied.groupType(),copied.title(),copied.instructions(),copied.stimulus(),copied.audioAssetKey(),copied.playback(),copied.keepStimulusVisible(),copied.display(),copied.shuffleChildren(),List.of(item)));
                }else{GroupNode existing=(GroupNode)nodes.get(at);List<ItemNode> children=new ArrayList<>(existing.children());children.add(item);nodes.set(at,new GroupNode(existing.id(),existing.groupType(),existing.title(),existing.instructions(),existing.stimulus(),existing.audioAssetKey(),existing.playback(),existing.keepStimulusVisible(),existing.display(),existing.shuffleChildren(),children));}
            }
        }
        List<SectionNode> sections=new ArrayList<>();
        for(var entry:originalSections.entrySet()){
            String key=entry.getKey(),prefix=sectionPrefixes.get(key);SectionNode original=entry.getValue();AssessmentDocument source=sectionDocuments.get(key);
            List<Node> children=new ArrayList<>(nodes.stream().filter(n->key.equals(nodeSections.get(n.id()))).toList());
            String audio=original.audioAssetKey()!=null?assetKey(prefix,original.audioAssetKey()):source.audioAssetKey()!=null?assetKey(prefix,source.audioAssetKey()):null;
            PlaybackPolicy playback=original.audioAssetKey()!=null?original.playback():source.playback();
            if(original.audioAssetKey()!=null && source.audioAssetKey()!=null && !original.audioAssetKey().equals(source.audioAssetKey())){
                playback=strictPolicy(original.playback(),source.playback());String rootAudio=assetKey(prefix,source.audioAssetKey());
                for(int i=0;i<children.size();i++){ObjectNode n=(ObjectNode)DocumentCodec.toTree(children.get(i));String field=children.get(i) instanceof GroupNode?"stimulus":"promptBlocks";ArrayNode blocks=DocumentCodec.mapper().createArrayNode();ObjectNode block=blocks.addObject();block.put("type","AUDIO");block.put("assetKey",rootAudio);n.path(field).forEach(blocks::add);n.set(field,blocks);children.set(i,children.get(i) instanceof GroupNode?DocumentCodec.mapper().convertValue(n,GroupNode.class):DocumentCodec.mapper().convertValue(n,ItemNode.class));}
            }
            sections.add(new SectionNode(UUID.randomUUID().toString(),original.title(),original.instructions(),true,children,audio,playback));
        }
        Set<String> referenced=new HashSet<>();collectAssets(DocumentCodec.toTree(sections),referenced);assets.keySet().retainAll(referenced);
        AssessmentDocument doc=new AssessmentDocument(1,request.path("title").asText("Đề từ ma trận"),request.path("kind").asText("QUIZ"),grade,subject.isBlank()?null:subject,request.path("languageCode").asText(null),request.path("topicTitle").asText(null),assets,sections);
        return object("document",doc,"coverage",coverage,"ready",coverage.stream().allMatch(c->c.get("requested").equals(c.get("selected"))),"itemCount",picked.size(),"totalPoints",DocumentIndex.of(doc).total());
    }
    @Transactional
    public Map<String,Object> create(String classId,String user,JsonNode request){policy.require(user,classId,"CREATE");Map<String,Object> preview=preview(classId,user,request);if(!Boolean.TRUE.equals(preview.get("ready")))bad("Ngân hàng không đủ câu theo ma trận. Điều chỉnh tiêu chí rồi xem trước lại.");AssessmentDocument doc=(AssessmentDocument)preview.get("document");return author.create(classId,user,new Create(doc.title(),doc.kind(),doc.grade(),doc.subjectCode(),doc.languageCode(),doc.topicTitle(),DocumentCodec.toTree(doc)));}
    @Transactional(readOnly=true)
    public List<Map<String,Object>> suggestions(String classId,String user,String outcome,String type,Integer grade,String subject){
        List<Map<String,Object>> bank=author.bank(classId,user,null,type,null,grade,subject,0,100);String text=Objects.toString(outcome,"").toLowerCase(Locale.ROOT);
        return bank.stream().sorted(Comparator.comparingInt((Map<String,Object> row)->score(Objects.toString(row.get("promptExcerpt"),""),text)).reversed()).limit(12).toList();
    }
    private static PlaybackPolicy strictPolicy(PlaybackPolicy a,PlaybackPolicy b){int max=a.maxPlays()==0?b.maxPlays():b.maxPlays()==0?a.maxPlays():Math.min(a.maxPlays(),b.maxPlays());return new PlaybackPolicy(max,a.allowSeek()&&b.allowSeek(),a.allowSpeed()&&b.allowSpeed(),a.autoPlay()||b.autoPlay(),Math.max(a.pauseBeforeAnswerSec(),b.pauseBeforeAnswerSec()));}
    private static int score(String prompt,String outcome){if(outcome.isBlank())return 0;String p=prompt.toLowerCase(Locale.ROOT);return (int)Arrays.stream(outcome.split("\\s+")).filter(s->s.length()>2 && p.contains(s)).count();}
    private static ItemNode copyItem(ItemNode item,String prefix){ObjectNode node=(ObjectNode)rebase(DocumentCodec.toTree(item),prefix);node.put("id",UUID.randomUUID().toString());return DocumentCodec.mapper().convertValue(node,ItemNode.class);}
    private static void collectAssets(JsonNode node,Set<String> keys){if(node.isObject())node.fields().forEachRemaining(e->{if(Set.of("assetKey","audioAssetKey","backgroundAssetKey").contains(e.getKey()) && e.getValue().isTextual())keys.add(e.getValue().asText());else collectAssets(e.getValue(),keys);});else if(node.isArray())node.forEach(n->collectAssets(n,keys));}
    private static String assetKey(String prefix,String key){return prefix+DocumentCodec.canonicalHash(key).substring(0,16);}
    private static JsonNode rebase(JsonNode node,String prefix){if(node.isArray()){ArrayNode a=DocumentCodec.mapper().createArrayNode();node.forEach(n->a.add(rebase(n,prefix)));return a;}if(node.isObject()){ObjectNode o=DocumentCodec.mapper().createObjectNode();node.fields().forEachRemaining(e->{if(Set.of("assetKey","audioAssetKey","backgroundAssetKey").contains(e.getKey()) && e.getValue().isTextual())o.put(e.getKey(),assetKey(prefix,e.getValue().asText()));else o.set(e.getKey(),rebase(e.getValue(),prefix));});return o;}return node.deepCopy();}
    private static void bad(String message){throw new AppException(ErrorCode.BAD_REQUEST,message);}
}
