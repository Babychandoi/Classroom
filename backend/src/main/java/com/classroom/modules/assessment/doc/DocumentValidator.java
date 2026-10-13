package com.classroom.modules.assessment.doc;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.assessment.catalog.*;
import com.classroom.modules.assessment.dto.AssessmentRequests.Issue;
import com.classroom.modules.assessment.grading.GradingEngine;
import com.classroom.modules.assessment.grading.SafeExpression;
import com.classroom.modules.assessment.grading.ChemicalEquation;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.*;

/** Drafts may be incomplete, but never structurally unsafe. Assignment additionally requires no ERROR issues. */
@Component
public class DocumentValidator {
    private final AssessmentCatalogService catalog;
    public DocumentValidator(AssessmentCatalogService catalog) { this.catalog=catalog; }
    private static final Set<String> MODES=Set.of("AUTO","MANUAL","HYBRID","PASS_FAIL");
    public void structure(AssessmentDocument doc) {
        if(doc.schemaVersion()!=1 || doc.title().isBlank() || doc.title().length()>200
            || !Set.of("HOMEWORK","QUIZ","REVIEW","WORKSHEET","PRACTICE").contains(doc.kind())) bad("Tên, loại hoặc phiên bản đề không hợp lệ");
        if(doc.grade()!=null && (doc.grade()<1 || doc.grade()>12)) bad("Khối lớp phải từ 1 đến 12");
        if(doc.sections().size()>100 || doc.assets().size()>200) bad("Đề vượt giới hạn phần hoặc tệp");
        checkAudioScope(doc.audioAssetKey(),doc.playback(),doc);
        Set<String> ids=new HashSet<>(); int count=0;
        for(SectionNode section:doc.sections()) {
            checkAudioScope(section.audioAssetKey(),section.playback(),doc);
            id(section.id(),ids); bounded(section.title(),200); bounded(section.instructions(),100000);
            for(Node node:section.nodes()) {
                id(node.id(),ids);
                if(node instanceof GroupNode group) {
                    if(!QuestionType.GROUP_CODES.contains(group.groupType())) bad("Loại nhóm không hợp lệ");
                    if(!Set.of("ALL","ONE").contains(group.display())) bad("Chế độ hiển thị nhóm không hợp lệ");
                    PlaybackPolicy p=group.playback();
                    if(p.maxPlays()<0 || p.maxPlays()>100 || p.pauseBeforeAnswerSec()<0 || p.pauseBeforeAnswerSec()>3600) bad("Chính sách phát nghe không hợp lệ");
                    blocks(group.stimulus(),doc); bounded(group.title(),200); bounded(group.instructions(),100000);
                    for(ItemNode child:group.children()) { id(child.id(),ids); itemStructure(child,doc); count++; }
                } else { itemStructure((ItemNode)node,doc); count++; }
            }
        }
        if(count>500) bad("Đề tối đa 500 câu hỏi");
        for(var entry:doc.assets().entrySet()) {
            id(entry.getKey(),new HashSet<>());
            AssetRef asset=entry.getValue();
            if(asset==null || asset.assetId()==null || !asset.assetId().matches("[a-fA-F0-9-]{36}")) bad("Mã tệp không hợp lệ");
            if(!Set.of("AUDIO","IMAGE","VIDEO","DOCUMENT").contains(asset.type())) bad("Loại ngữ liệu không hợp lệ");
            if(!Set.of("NEVER","AFTER_SUBMISSION","AFTER_DUE").contains(asset.transcriptVisibility())) bad("Chính sách transcript không hợp lệ");
            bounded(asset.transcript(),200000);
        }
        if(doc.subjectCode()!=null) {
            var s=catalog.subject(doc.subjectCode()).orElseThrow(()->new AppException(ErrorCode.BAD_REQUEST,"Môn học không hợp lệ"));
            if(doc.grade()!=null && catalog.subjects(doc.grade()).stream().noneMatch(it->it.code().equals(s.getCode()))) bad("Môn học không thuộc khối đã chọn");
        }
    }
    private static void checkAudioScope(String key,PlaybackPolicy policy,AssessmentDocument doc){if(policy.maxPlays()<0 || policy.maxPlays()>100 || policy.pauseBeforeAnswerSec()<0 || policy.pauseBeforeAnswerSec()>3600)bad("Chính sách nghe không hợp lệ");if(key!=null && (!doc.assets().containsKey(key) || !"AUDIO".equals(doc.assets().get(key).type())))bad("Tệp nghe cấp đề/phần không hợp lệ");}
    private void itemStructure(ItemNode item,AssessmentDocument doc) {
        var type=QuestionType.of(item.type()).filter(t->!t.container()).orElseThrow(()->new AppException(ErrorCode.BAD_REQUEST,"Loại câu hỏi không hợp lệ"));
        if(!MODES.contains(item.mode()) || !item.interaction().isObject() || !item.answerKey().isObject()) bad("Cấu hình câu hỏi không hợp lệ");
        if(item.points()==null || item.points().signum()<=0 || item.points().compareTo(BigDecimal.valueOf(100))>0 || item.points().stripTrailingZeros().scale()>2) bad("Điểm phải lớn hơn 0, tối đa 100 và có tối đa hai chữ số thập phân");
        bounded(item.prompt(),100000); bounded(item.feedback(),100000); bounded(item.outcome(),2000);
        blocks(item.promptBlocks(),doc);
        validateJson(item.interaction(),0);validateJson(item.answerKey(),0);
        JsonNode x=item.interaction();
        for(String field:List.of("options","statements","left","right","items","blanks","dropdowns","regions","targets","labels","cards","categories","rows","cells","points","steps","controls","variables"))
            if(x.has(field)) {JsonNode entries=x.get(field);if(!entries.isArray() || entries.size()>200)bad("Danh sách tương tác không hợp lệ");Set<String> local=new HashSet<>();for(JsonNode entry:entries) {id(entry.path("id").asText(),local);if(entry.has("options")){Set<String> opts=new HashSet<>();for(JsonNode option:entry.path("options"))id(option.path("id").asText(),opts);}}}
        if(x.has("variables")){if(x.path("variables").size()>6)bad("Tối đa sáu biến số trong câu");for(JsonNode variable:x.path("variables")){double min=variable.path("min").asDouble(Double.NaN),max=variable.path("max").asDouble(Double.NaN),step=variable.path("step").asDouble(1);String name=variable.path("id").asText();
            if(!name.matches("[A-Za-z][A-Za-z0-9_]{0,15}") || !Double.isFinite(min+max+step) || min>max || Math.abs(min)>1000000 || Math.abs(max)>1000000 || step<=0 || (max-min)/step>10000000)bad("Miền biến số không hợp lệ");}}
        if(Set.of("Q14","Q15").contains(item.type()))for(JsonNode r:x.path("Q14".equals(item.type())?"regions":"targets")){
            double a=r.path("x").asDouble(-1),b=r.path("y").asDouble(-1),w=r.path("width").asDouble(0),h=r.path("height").asDouble(0);
            if(!Double.isFinite(a+b+w+h) || a<0 || b<0 || w<=0 || h<=0 || a+w>1.000001 || b+h>1.000001)bad("Vùng ảnh phải nằm trong ảnh và có kích thước dương");}
        if("Q23".equals(item.type()) && (x.path("width").asInt()<200 || x.path("width").asInt()>2000 || x.path("height").asInt()<200 || x.path("height").asInt()>2000))bad("Vùng vẽ cần kích thước 200–2000 px");
        if(Set.of("Q24","Q25","Q28").contains(item.type()) && (x.path("maxFiles").asInt()<1 || x.path("maxFiles").asInt()>10 || x.path("maxFileMb").asInt()<1 || x.path("maxFileMb").asInt()>100))bad("Giới hạn minh chứng không hợp lệ");
        if("Q26".equals(item.type()) && !Set.of("python","javascript").contains(x.path("language").asText()))bad("Ngôn ngữ code chưa hỗ trợ");
        if("Q27".equals(item.type())){try{SafeExpression.validate(x.path("formula").asText());}catch(IllegalArgumentException e){bad("Công thức mô phỏng không hợp lệ");}for(JsonNode c:x.path("controls")){double min=c.path("min").asDouble(Double.NaN),max=c.path("max").asDouble(Double.NaN),initial=c.path("initial").asDouble(Double.NaN);if(!Double.isFinite(min+max+initial) || min>=max || initial<min || initial>max || c.path("step").asDouble()<=0)bad("Giới hạn mô phỏng không hợp lệ");}}
        if("Q22".equals(item.type())) {
            JsonNode p=item.interaction().path("recording");
            if(!p.isObject() || p.path("maxSeconds").asInt()<1 || p.path("maxSeconds").asInt()>3600
                || p.path("maxRetakes").asInt()<0 || p.path("maxRetakes").asInt()>20
                || p.path("prepSeconds").asInt()<0 || p.path("prepSeconds").asInt()>600
                || p.path("maxFileMb").asInt()<1 || p.path("maxFileMb").asInt()>100) bad("Giới hạn ghi âm không hợp lệ");
        }
    }
    private static void validateJson(JsonNode node,int depth){if(depth>12)bad("Cấu hình tương tác lồng quá sâu");if(node.isTextual())bounded(node.asText(),100000);if(node.isContainerNode()){if(node.size()>500)bad("Cấu hình có quá nhiều phần tử");for(JsonNode child:node)validateJson(child,depth+1);}}
    private static void id(String id,Set<String> ids) {
        if(id==null || !id.matches("[A-Za-z0-9_-]{1,40}") || !ids.add(id)) bad("Mã nút không hợp lệ hoặc trùng lặp");
    }
    private static void bounded(String value,int max) { if(value!=null && value.length()>max) bad("Nội dung vượt độ dài cho phép"); }
    private static void blocks(List<ContentBlock> blocks,AssessmentDocument doc) {
        if(blocks.size()>100) bad("Quá nhiều khối nội dung");
        for(ContentBlock b:blocks) {
            if(!Set.of("TEXT","MATH","IMAGE","AUDIO","TABLE").contains(b.type())) bad("Khối nội dung không hợp lệ");
            bounded(b.text(),100000); bounded(b.latex(),10000);
            if("TABLE".equals(b.type()) && (b.rows()==null || b.rows().size()>100 || b.rows().stream().anyMatch(r->r.size()>30))) bad("Bảng tối đa 100 hàng, 30 cột");
        }
    }
    public List<Issue> issues(AssessmentDocument doc,String classId) {
        structure(doc); List<Issue> out=new ArrayList<>();
        if(doc.subjectCode()!=null && catalog.subject(doc.subjectCode()).map(EduSubject::isLanguageRequired).orElse(false) && doc.languageCode()==null)
            issue(out,"root","LANGUAGE_REQUIRED","Môn ngoại ngữ cần chọn ngôn ngữ",false);
        int n=0;
        for(SectionNode section:doc.sections()) {
            if(section.nodes().isEmpty()) issue(out,section.id(),"SECTION_EMPTY","Phần chưa có câu hỏi",false);
            for(Node node:section.nodes()) {
                if(node instanceof GroupNode group) {
                    if(group.children().isEmpty()) issue(out,group.id(),"GROUP_EMPTY","Nhóm chưa có câu hỏi con",false);
                    if("Q21".equals(group.groupType())) {
                        AssetRef a=doc.assets().get(group.audioAssetKey());
                        if(a==null || !"AUDIO".equals(a.type())) issue(out,group.id(),"LISTENING_AUDIO_MISSING","Nhóm bài nghe chưa có tệp nghe",false);
                        else if(a.transcript()==null || a.transcript().isBlank()) issue(out,group.id(),"TRANSCRIPT_MISSING","Nhóm bài nghe chưa có transcript",true);
                    }
                    checkRefs(out,group.id(),group.stimulus(),doc);
                    for(ItemNode it:group.children()) { checkItem(out,it,doc,classId); n++; }
                } else { checkItem(out,(ItemNode)node,doc,classId); n++; }
            }
        }
        if(n==0) issue(out,"root","NO_ITEMS","Đề chưa có câu hỏi nào",false);
        return out;
    }
    private void checkItem(List<Issue> out,ItemNode it,AssessmentDocument doc,String classId) {
        if(it.prompt().replace("___","").isBlank() && it.promptBlocks().isEmpty()) issue(out,it.id(),"PROMPT_EMPTY","Chưa có đề bài",false);
        checkRefs(out,it.id(),it.promptBlocks(),doc);
        if(GradingEngine.isManualType(it.type()) && "AUTO".equals(it.mode())) issue(out,it.id(),"AUTO_MODE_UNSUPPORTED","Dạng câu này cần giáo viên chấm",false);
        if(GradingEngine.needsTeacher(it)) {
            if(it.grading().rubricId()==null) issue(out,it.id(),"RUBRIC_MISSING","Chấm thủ công chưa gắn rubric",true);
            else if(catalog.rubric(it.grading().rubricId(),classId).isEmpty()) bad("Rubric không thuộc lớp");
        }
        if(!GradingEngine.isManualType(it.type()) && ("AUTO".equals(it.mode()) || "HYBRID".equals(it.mode())) && !keyPresent(it))
            issue(out,it.id(),"ANSWER_KEY_MISSING","Chấm tự động nhưng chưa có đáp án hợp lệ",false);
        JsonNode x=it.interaction(),key=it.answerKey();
        if(Set.of("Q14","Q15").contains(it.type()) && (doc.assets().get(x.path("assetKey").asText())==null || !"IMAGE".equals(doc.assets().get(x.path("assetKey").asText()).type())))issue(out,it.id(),"ASSET_MISSING","Câu tương tác trên ảnh cần chọn ảnh",false);
        if("Q19".equals(it.type()))for(JsonNode accepted:key.path("accepted")){try{if(x.path("chemical").asBoolean())ChemicalEquation.validate(accepted.asText());else SafeExpression.validate(accepted.asText());}catch(IllegalArgumentException e){issue(out,it.id(),"ANSWER_KEY_MISSING","Biểu thức/phương trình đáp án chưa hợp lệ hoặc chưa cân bằng",false);}}
        if(Set.of("Q01","Q02").contains(it.type())) {
            JsonNode choices=x.path("options");
            if(choices.size()<2 || choices.size()>20 || any(choices, o->o.path("text").asText().isBlank() && o.path("assetKey").asText().isBlank() && o.path("audioAssetKey").asText().isBlank()))
                issue(out,it.id(),"OPTION_EMPTY","Có phương án để trống",false);
            for(JsonNode choice:choices)for(String field:List.of("assetKey","audioAssetKey")){String ref=choice.path(field).asText("");if(!ref.isBlank() && (doc.assets().get(ref)==null || !(field.equals("assetKey")?"IMAGE":"AUDIO").equals(doc.assets().get(ref).type())))issue(out,it.id(),"ASSET_MISSING","Ngữ liệu phương án không đúng loại hoặc chưa có",false);}
        }
        if(Set.of("Q07","Q08","Q09").contains(it.type())) {
            int blanks=it.prompt().split("___",-1).length-1;
            if(blanks==0 || blanks!=x.path("Q09".equals(it.type())?"dropdowns":"blanks").size()) issue(out,it.id(),"BLANK_COUNT_MISMATCH","Số chỗ trống không khớp với cấu hình",false);
        }
        if("Q11".equals(it.type()) && (key.path("tolerance").decimalValue().signum()<0 || key.hasNonNull("range") && key.path("range").path("min").decimalValue().compareTo(key.path("range").path("max").decimalValue())>0))
            issue(out,it.id(),"NUMERIC_INVALID","Đáp án số không hợp lệ",false);
    }
    private static boolean any(JsonNode array,java.util.function.Predicate<JsonNode> p) { for(JsonNode n:array) if(p.test(n)) return true; return false; }
    private static boolean keyPresent(ItemNode it) {
        JsonNode k=it.answerKey(),x=it.interaction();
        return switch(it.type()) {
            case "Q01" -> k.path("optionId").isTextual() && any(x.path("options"),o->o.path("id").asText().equals(k.path("optionId").asText()));
            case "Q02" -> k.path("optionIds").isArray() && !k.path("optionIds").isEmpty() && !any(k.path("optionIds"),id->!any(x.path("options"),o->o.path("id").asText().equals(id.asText())));
            case "Q03" -> k.path("value").isBoolean();
            case "Q04" -> !x.path("statements").isEmpty() && !any(x.path("statements"),s->!k.path("values").path(s.path("id").asText()).isBoolean());
            case "Q05" -> !x.path("left").isEmpty() && !any(x.path("left"),s->!any(x.path("right"),r->r.path("id").asText().equals(k.path("pairs").path(s.path("id").asText()).asText())));
            case "Q06" -> k.path("order").isArray() && k.path("order").size()==x.path("items").size() && k.path("order").size()>1 && uniqueTexts(k.path("order")) && !any(k.path("order"),id->!any(x.path("items"),o->o.path("id").asText().equals(id.asText())));
            case "Q07","Q08" -> !x.path("blanks").isEmpty() && !any(x.path("blanks"),b->k.path("blanks").path(b.path("id").asText()).isMissingNode() || k.path("blanks").path(b.path("id").asText()).isEmpty() && k.path("blanks").path(b.path("id").asText()).asText().isBlank());
            case "Q09" -> !x.path("dropdowns").isEmpty() && !any(x.path("dropdowns"),d->!any(d.path("options"),o->o.path("id").asText().equals(k.path("choices").path(d.path("id").asText()).asText())));
            case "Q10" -> k.path("accepted").isArray() && any(k.path("accepted"),v->!v.asText().isBlank());
            case "Q11" -> k.path("value").isNumber() || k.path("expression").isTextual() && !x.path("variables").isEmpty() || k.path("range").path("min").isNumber() && k.path("range").path("max").isNumber();
            case "Q14" -> !x.path("regions").isEmpty() && !k.path("regionIds").isEmpty() && !any(k.path("regionIds"),id->!any(x.path("regions"),r->r.path("id").asText().equals(id.asText())));
            case "Q15" -> !x.path("targets").isEmpty() && !any(x.path("targets"),t->!any(x.path("labels"),l->l.path("id").asText().equals(k.path("labels").path(t.path("id").asText()).asText())));
            case "Q16" -> !x.path("cards").isEmpty() && !any(x.path("cards"),t->!any(x.path("categories"),l->l.path("id").asText().equals(k.path("categories").path(t.path("id").asText()).asText())));
            case "Q17" -> any(x.path("cells"),c->c.path("editable").asBoolean()) && !any(x.path("cells"),c->c.path("editable").asBoolean() && !nonempty(k.path("cells").path(c.path("id").asText())));
            case "Q18" -> !x.path("points").isEmpty() && !any(x.path("points"),p->!numberKey(k.path("values").path(p.path("id").asText())));
            case "Q19" -> !k.path("accepted").isEmpty() && !any(k.path("accepted"),v->v.asText().isBlank());
            case "Q20" -> !x.path("steps").isEmpty() && !any(x.path("steps"),s->s.path("points").asDouble()<=0 || !nonempty(k.path("steps").path(s.path("id").asText())));
            case "Q26" -> k.path("tests").isArray() && !k.path("tests").isEmpty() && k.path("tests").size()<=20 && !any(k.path("tests"),t->!t.path("input").isTextual() || !t.path("output").isTextual() || t.path("input").asText().length()>10000 || t.path("output").asText().length()>10000);
            case "Q27" -> numberKey(k.path("values").path("result")) && !x.path("controls").isEmpty();
            default -> !k.isEmpty();
        };
    }
    private static boolean uniqueTexts(JsonNode values){Set<String> seen=new HashSet<>();for(JsonNode v:values)if(!v.isTextual() || !seen.add(v.asText()))return false;return true;}
    private static boolean nonempty(JsonNode value){return value.isTextual()?!value.asText().isBlank():value.isArray() && !value.isEmpty() && !any(value,v->v.asText().isBlank());}
    private static boolean numberKey(JsonNode value){return value.isNumber() || value.isObject() && value.path("value").isNumber() && value.path("tolerance").asDouble()>=0;}
    private static void checkRefs(List<Issue> out,String id,List<ContentBlock> blocks,AssessmentDocument doc) {
        for(ContentBlock b:blocks) if(Set.of("IMAGE","AUDIO").contains(b.type()) && !doc.assets().containsKey(b.assetKey()))
            issue(out,id,"ASSET_MISSING","Tệp đính kèm không còn tồn tại",false);
    }
    private static void issue(List<Issue> out,String id,String code,String message,boolean warning) { out.add(new Issue(id,warning?"WARNING":"ERROR",code,message)); }
    private static void bad(String message) { throw new AppException(ErrorCode.BAD_REQUEST,message); }
}
