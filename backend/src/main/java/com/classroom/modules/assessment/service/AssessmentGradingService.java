package com.classroom.modules.assessment.service;

import com.classroom.common.*;
import com.classroom.modules.assessment.doc.*;
import com.classroom.modules.assessment.dto.AssessmentRequests.*;
import com.classroom.modules.assessment.dto.CatalogDtos.RubricDto;
import com.classroom.modules.assessment.grading.*;
import com.classroom.modules.assessment.policy.AssessmentPolicy;
import com.classroom.modules.assessment.repository.AssessmentStore;
import com.classroom.modules.assessment.repository.AssessmentStore.Row;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.segment.service.SegmentService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.classroom.modules.assessment.doc.LearnerProjection.object;
import static com.classroom.modules.assessment.repository.AssessmentStore.time;

@Service
public class AssessmentGradingService {
    private final AssessmentStore store;
    private final AssessmentPolicy policy;
    private final AssessmentAuthoringService authoring;
    private final AssessmentAttemptService attempts;
    private final SegmentService segments;
    private final AuditService audit;
    public AssessmentGradingService(AssessmentStore store,AssessmentPolicy policy,AssessmentAuthoringService authoring,
        AssessmentAttemptService attempts,SegmentService segments,AuditService audit) {
        this.store=store;this.policy=policy;this.authoring=authoring;this.attempts=attempts;this.segments=segments;this.audit=audit;
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> submissions(String id,String user) {
        Row assignment=store.assignment(id,false); policy.requireRead(user,assignment.text("class_id"),"GRADE");
        Map<String,Row> latest=latest(id); List<Map<String,Object>> out=new ArrayList<>(); Set<String> seen=new HashSet<>();
        for(Row member:store.rows("SELECT user_id FROM class_members WHERE class_id=? AND state='ACTIVE' AND (access_expires_at IS NULL OR access_expires_at>?)",assignment.text("class_id"),time(Instant.now()))) {
            String student=member.text("user_id");
            if(assignment.text("target_segment_id")!=null && !segments.isUserInSegment(assignment.text("target_segment_id"),student,assignment.text("class_id"))) continue;
            seen.add(student);out.add(submission(latest.get(student),student,assignment));
        }
        // Retain completed work even when membership or a segment subsequently changes.
        latest.forEach((student,attempt)->{if(!seen.contains(student))out.add(submission(attempt,student,assignment));});
        return out;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> grading(String id,String user) {
        Row row=store.attempt(id,false);policy.requireRead(user,row.text("class_id"),"GRADE");
        if("IN_PROGRESS".equals(row.text("status"))) conflict("Bài làm chưa nộp");
        AssessmentDocument doc=attempts.document(row);Row assignment=store.assignment(row.text("assignment_id"),false);
        DocumentIndex ordered=DocumentIndex.of(LearnerProjection.ordered(doc,row.longNumber("shuffle_seed"),assignment.flag("shuffle")));
        Map<String,Row> answers=answerMap(id);List<Map<String,Object>> values=new ArrayList<>();
        for(var ref:ordered.items()) values.add(answerDto(answers.get(ref.item().id()),ref.item(),ordered.label(ref.item().id())));
        JsonNode rubrics=DocumentCodec.readTree(store.version(row.text("version_id")).text("rubrics_json"));
        return object("attemptId",id,"assignmentId",row.text("assignment_id"),"student",authoring.person(row.text("user_id")),"status",row.text("status"),"submittedAt",row.instant("submitted_at"),
            "autoScore",row.decimal("auto_score"),"manualScore",row.decimal("manual_score"),"totalScore",row.decimal("total_score"),"maxScore",row.decimal("max_score"),
            "document",doc,"images",authoring.learnerDocument(doc,0,false,row.text("class_id")).get("images"),"labels",ordered.labels(),"displayOrder",ordered.items().stream().map(r->r.item().id()).toList(),"answers",values,
            "rubrics",rubrics==null?List.of():DocumentCodec.mapper().convertValue(rubrics,Map.class).values(),"teacherComment",row.text("teacher_comment"),"passFail",row.text("pass_fail"));
    }
    @Transactional
    public Map<String,Object> grade(String id,String itemId,String user,Grade request) {
        Row row=store.attempt(id,true);policy.require(user,row.text("class_id"),"GRADE");
        if("IN_PROGRESS".equals(row.text("status"))) conflict("Bài làm chưa nộp");
        ItemNode item=attempts.item(row,itemId);BigDecimal points=request.points();
        if(request.rubricLevels()!=null) {
            String rubricId=item.grading().rubricId();
            JsonNode frozen=DocumentCodec.readTree(store.version(row.text("version_id")).text("rubrics_json"));
            if(rubricId==null || frozen==null || !frozen.has(rubricId)) bad("Câu hỏi không có rubric trong phiên bản được giao");
            RubricDto rubric=DocumentCodec.read(DocumentCodec.writeJson(frozen.get(rubricId)),RubricDto.class);
            points=RubricScorer.score(rubric,request.rubricLevels(),item.points());
        }
        checkPass(request.passFail());
        if("PASS_FAIL".equals(item.mode())) {
            if(request.passFail()==null) bad("Chọn đạt hoặc chưa đạt");
            points="PASS".equals(request.passFail())?item.points():BigDecimal.ZERO;
        }
        if(points==null || points.signum()<0 || points.compareTo(item.points())>0 || points.scale()>2) bad("Điểm phải từ 0 đến điểm tối đa, tối đa hai chữ số thập phân");
        checkComment(request.comment());
        store.write("UPDATE assessment_attempt_answers SET manual_points=?,final_points=?,status='GRADED',rubric_json=?,pass_fail=?,teacher_comment=?,updated_at=? WHERE attempt_id=? AND item_id=?",
            points,points,request.rubricLevels()==null?null:DocumentCodec.writeJson(request.rubricLevels()),request.passFail(),request.comment(),time(Instant.now()),id,itemId);
        // Editing a released grade returns it to review until the teacher finalizes it again.
        store.write("UPDATE assessment_attempts SET status='SUBMITTED',total_score=NULL,manual_score=NULL,returned_at=NULL,revision=revision+1 WHERE id=?",id);
        audit.record(row.text("class_id"),user,"ASSESSMENT_GRADE_ITEM","ASSESSMENT_ATTEMPT",id,DocumentCodec.writeJson(object("itemId",itemId,"points",points)));
        DocumentIndex index=DocumentIndex.of(attempts.document(row));
        return answerDto(store.require("SELECT * FROM assessment_attempt_answers WHERE attempt_id=? AND item_id=?",id,itemId),item,index.label(itemId));
    }
    @Transactional
    public Map<String,Object> finalizeAttempt(String id,String user,Finalize request) {
        Row row=store.attempt(id,true);policy.require(user,row.text("class_id"),"GRADE");
        if("IN_PROGRESS".equals(row.text("status"))) conflict("Bài làm chưa nộp");
        List<Row> answers=attempts.answerRows(id);
        if(answers.stream().anyMatch(a->Set.of("PENDING_MANUAL","PENDING_CODE","CODE_RUNNING").contains(a.text("status")))) conflict("Còn câu hỏi chưa chấm");
        BigDecimal total=BigDecimal.ZERO,auto=BigDecimal.ZERO,manual=BigDecimal.ZERO;
        for(Row answer:answers) {
            if(answer.decimal("final_points")==null) conflict("Còn câu hỏi chưa có điểm");
            total=total.add(answer.decimal("final_points"));
            if(answer.decimal("manual_points")!=null) manual=manual.add(answer.decimal("final_points"));else auto=auto.add(answer.decimal("final_points"));
        }
        checkPass(request.passFail());checkComment(request.teacherComment());Instant now=Instant.now();
        store.write("UPDATE assessment_attempts SET status=?,auto_score=?,manual_score=?,total_score=?,pass_fail=?,teacher_comment=?,graded_by=?,graded_at=?,returned_at=?,revision=revision+1 WHERE id=?",
            request.release()?"RETURNED":"GRADED",ScoreMath.round(auto),ScoreMath.round(manual),ScoreMath.round(total),request.passFail(),request.teacherComment(),user,time(now),request.release()?time(now):null,id);
        audit.record(row.text("class_id"),user,"ASSESSMENT_FINALIZE","ASSESSMENT_ATTEMPT",id,DocumentCodec.writeJson(object("release",request.release(),"score",total)));
        return submission(store.attempt(id,false),row.text("user_id"),store.assignment(row.text("assignment_id"),false));
    }
    @Transactional
    public Map<String,Object> release(String id,String user) {
        Row assignment=store.assignment(id,true);policy.require(user,assignment.text("class_id"),"GRADE");
        int count=store.write("UPDATE assessment_attempts SET status='RETURNED',returned_at=?,revision=revision+1 WHERE assignment_id=? AND status='GRADED'",time(Instant.now()),id);
        audit.record(assignment.text("class_id"),user,"ASSESSMENT_RELEASE","ASSIGNMENT",id,DocumentCodec.writeJson(object("released",count)));return object("released",count);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> stats(String id,String user) {
        Row assignment=store.assignment(id,false);policy.requireRead(user,assignment.text("class_id"),"GRADE");
        AssessmentDocument doc=DocumentCodec.parse(store.version(assignment.text("version_id")).text("grading_json"));DocumentIndex index=DocumentIndex.of(doc);
        List<Map<String,Object>> roster=submissions(id,user);List<Row> latest=new ArrayList<>(latest(id).values());
        List<Row> submitted=latest.stream().filter(a->!"IN_PROGRESS".equals(a.text("status"))).toList();
        Set<String> submittedIds=new HashSet<>();submitted.forEach(a->submittedIds.add(a.text("id")));
        Map<String,List<Row>> answers=new HashMap<>();
        for(Row answer:store.rows("SELECT a.* FROM assessment_attempt_answers a JOIN assessment_attempts t ON t.id=a.attempt_id WHERE t.assignment_id=? AND t.status<>'IN_PROGRESS'",id))
            if(submittedIds.contains(answer.text("attempt_id"))) answers.computeIfAbsent(answer.text("item_id"),k->new ArrayList<>()).add(answer);
        List<Map<String,Object>> items=new ArrayList<>();Map<String,Map<String,Object>> skillStats=new LinkedHashMap<>();
        for(var ref:index.items()) {
            ItemNode item=ref.item();List<Row> rows=answers.getOrDefault(item.id(),List.of());
            List<BigDecimal> graded=rows.stream().map(a->a.decimal("final_points")).filter(Objects::nonNull).toList();
            long count=rows.stream().filter(a->a.text("response_json")!=null).count();
            long correct=rows.stream().filter(a->"CORRECT".equals(a.text("correctness"))).count();
            items.add(object("id",item.id(),"label",index.label(item.id()),"typeCode",item.type(),"sectionId",ref.section().id(),"groupId",ref.group()==null?null:ref.group().id(),
                "avgPoints",average(graded),"maxPoints",item.points(),"correctRate",graded.isEmpty()?null:100.0*correct/graded.size(),"answeredCount",count,"pendingManual",rows.stream().filter(a->Set.of("PENDING_MANUAL","PENDING_CODE","CODE_RUNNING").contains(a.text("status"))).count()));
            Set<String> skills=new LinkedHashSet<>(item.tags());if(item.outcome()!=null && !item.outcome().isBlank()) skills.add(item.outcome());
            for(String skill:skills) { Map<String,Object> value=skillStats.computeIfAbsent(skill,k->object("skill",k,"earned",BigDecimal.ZERO,"possible",BigDecimal.ZERO,"responses",0L));
                for(Row answer:rows) if(answer.decimal("final_points")!=null) {value.put("earned",((BigDecimal)value.get("earned")).add(answer.decimal("final_points")));value.put("possible",((BigDecimal)value.get("possible")).add(item.points()));value.put("responses",(Long)value.get("responses")+1);}
            }
        }
        for(var skill:skillStats.values()) {BigDecimal possible=(BigDecimal)skill.get("possible");skill.put("masteryPercent",possible.signum()==0?null:((BigDecimal)skill.get("earned")).multiply(BigDecimal.valueOf(100)).divide(possible,2,java.math.RoundingMode.HALF_UP));}
        List<Map<String,Object>> students=new ArrayList<>();
        for(Row attempt:submitted){Map<String,Map<String,Object>> values=new LinkedHashMap<>();for(var ref:index.items()){ItemNode item=ref.item();Row answer=answers.getOrDefault(item.id(),List.of()).stream().filter(a->attempt.text("id").equals(a.text("attempt_id"))).findFirst().orElse(null);if(answer==null || answer.decimal("final_points")==null)continue;
            Set<String> skills=new LinkedHashSet<>(item.tags());if(item.outcome()!=null && !item.outcome().isBlank())skills.add(item.outcome());for(String skill:skills){Map<String,Object> v=values.computeIfAbsent(skill,k->object("skill",k,"earned",BigDecimal.ZERO,"possible",BigDecimal.ZERO,"responses",0));v.put("earned",((BigDecimal)v.get("earned")).add(answer.decimal("final_points")));v.put("possible",((BigDecimal)v.get("possible")).add(item.points()));v.put("responses",(Integer)v.get("responses")+1);}}
            for(var v:values.values()){BigDecimal possible=(BigDecimal)v.get("possible");v.put("masteryPercent",possible.signum()==0?null:((BigDecimal)v.get("earned")).multiply(BigDecimal.valueOf(100)).divide(possible,2,java.math.RoundingMode.HALF_UP));}students.add(object("student",authoring.person(attempt.text("user_id")),"attemptId",attempt.text("id"),"skills",values.values()));}
        List<Map<String,Object>> groups=new ArrayList<>(),sections=new ArrayList<>();
        for(GroupNode group:index.groups()) groups.add(nodeStat(group.id(),index.label(group.id()),group.children().stream().map(ItemNode::id).toList(),submitted,answers,index.points(group.id())));
        for(SectionNode section:doc.sections()) sections.add(nodeStat(section.id(),section.title(),index.items().stream().filter(r->r.section().id().equals(section.id())).map(r->r.item().id()).toList(),submitted,answers,index.points(section.id())));
        List<BigDecimal> scores=submitted.stream().map(r->r.decimal("total_score")).filter(Objects::nonNull).toList();
        Double duration=submitted.stream().filter(r->r.instant("submitted_at")!=null).mapToLong(r->Math.max(0,Duration.between(r.instant("started_at"),r.instant("submitted_at")).toSeconds())).average().stream().boxed().findFirst().orElse(null);
        return object("assignmentId",id,"studentCount",roster.size(),"submittedCount",submitted.size(),"inProgressCount",latest.size()-submitted.size(),"notStartedCount",roster.stream().filter(r->"NOT_STARTED".equals(r.get("status"))).count(),
            "gradedCount",scores.size(),"pendingGradingCount",submitted.stream().filter(r->"SUBMITTED".equals(r.text("status"))).count(),"avgScore",average(scores),"maxScore",index.total(),"avgDurationSec",duration,
            "items",items,"groups",groups,"sections",sections,"skills",new ArrayList<>(skillStats.values()),"students",students);
    }
    private Map<String,Object> nodeStat(String id,String label,List<String> itemIds,List<Row> submitted,Map<String,List<Row>> answers,BigDecimal max) {
        List<BigDecimal> sums=new ArrayList<>();
        for(Row attempt:submitted) {
            BigDecimal sum=BigDecimal.ZERO;boolean ready=true;
            for(String item:itemIds) {Optional<Row> a=answers.getOrDefault(item,List.of()).stream().filter(r->r.text("attempt_id").equals(attempt.text("id"))).findFirst();
                if(a.isEmpty() || a.get().decimal("final_points")==null){ready=false;break;}sum=sum.add(a.get().decimal("final_points"));}
            if(ready)sums.add(sum);
        }
        return object("id",id,"label",label,"avgPoints",average(sums),"maxPoints",max);
    }
    private Map<String,Row> latest(String assignmentId) {
        Map<String,Row> out=new LinkedHashMap<>();for(Row row:store.rows("SELECT * FROM assessment_attempts WHERE assignment_id=? ORDER BY attempt_no DESC",assignmentId))out.putIfAbsent(row.text("user_id"),row);return out;
    }
    private Map<String,Row> answerMap(String id){Map<String,Row> out=new HashMap<>();attempts.answerRows(id).forEach(a->out.put(a.text("item_id"),a));return out;}
    private Map<String,Object> submission(Row r,String student,Row assignment) {
        return object("student",authoring.person(student),"attemptId",r==null?null:r.text("id"),"attemptNo",r==null?null:r.number("attempt_no"),"status",r==null?"NOT_STARTED":r.text("status"),
            "submittedAt",r==null?null:r.instant("submitted_at"),"late",r!=null && r.flag("late"),"autoScore",r==null?null:r.decimal("auto_score"),"manualScore",r==null?null:r.decimal("manual_score"),"totalScore",r==null?null:r.decimal("total_score"),
            "maxScore",store.version(assignment.text("version_id")).decimal("total_points"),"pendingManualCount",r==null?0:store.count("SELECT COUNT(*) FROM assessment_attempt_answers WHERE attempt_id=? AND status='PENDING_MANUAL'",r.text("id")),"passFail",r==null?null:r.text("pass_fail"));
    }
    private Map<String,Object> answerDto(Row r,ItemNode item,String label) {
        JsonNode response=DocumentCodec.readTree(r.text("response_json"));
        return object("itemId",item.id(),"label",label,"typeCode",item.type(),"mode",item.mode(),"maxPoints",item.points(),"response",response,"autoPoints",r.decimal("auto_points"),"manualPoints",r.decimal("manual_points"),"finalPoints",r.decimal("final_points"),
            "status",r.text("status"),"correctness",r.text("correctness"),"subResults",DocumentCodec.readTree(r.text("sub_results_json")),"rubricLevels",DocumentCodec.readTree(r.text("rubric_json")),"passFail",r.text("pass_fail"),"comment",r.text("teacher_comment"),
            "recording",r.text("recording_asset_id")==null?null:object("assetId",r.text("recording_asset_id"),"durationMs",response==null?0:response.path("durationMs").asLong()));
    }
    private static BigDecimal average(List<BigDecimal> values) {return values.isEmpty()?null:values.stream().reduce(BigDecimal.ZERO,BigDecimal::add).divide(BigDecimal.valueOf(values.size()),2,java.math.RoundingMode.HALF_UP);}
    private static void checkPass(String value){if(value!=null && !Set.of("PASS","FAIL").contains(value))bad("Kết quả đạt/chưa đạt không hợp lệ");}
    private static void checkComment(String value){if(value!=null && value.length()>10000)bad("Nhận xét tối đa 10.000 ký tự");}
    private static void bad(String message){throw new AppException(ErrorCode.BAD_REQUEST,message);}
    private static void conflict(String message){throw new AppException(ErrorCode.CONFLICT,message);}
}
