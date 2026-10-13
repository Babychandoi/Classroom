package com.classroom.modules.assessment.service;

import com.classroom.common.*;
import com.classroom.modules.assessment.doc.*;
import com.classroom.modules.assessment.dto.AssessmentRequests.*;
import com.classroom.modules.assessment.grading.*;
import com.classroom.modules.assessment.policy.AssessmentPolicy;
import com.classroom.modules.assessment.repository.AssessmentStore;
import com.classroom.modules.assessment.repository.AssessmentStore.Row;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.media.dto.UploadIntentRequest;
import com.classroom.modules.media.model.MediaAsset;
import com.classroom.modules.media.service.MediaService;
import com.classroom.modules.segment.service.SegmentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static com.classroom.modules.assessment.doc.LearnerProjection.object;
import static com.classroom.modules.assessment.repository.AssessmentStore.time;

@Service
public class AssessmentAttemptService {
    private final AssessmentStore store;
    private final AssessmentPolicy policy;
    private final AssessmentAuthoringService authoring;
    private final SegmentService segments;
    private final MediaService media;
    private final AuditService audit;
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private AssessmentAttemptService self;
    public AssessmentAttemptService(AssessmentStore store,AssessmentPolicy policy,AssessmentAuthoringService authoring,
        SegmentService segments,MediaService media,AuditService audit) {
        this.store=store; this.policy=policy; this.authoring=authoring; this.segments=segments; this.media=media; this.audit=audit;
    }
    @Transactional
    public Map<String,Object> start(String assignmentId,String user) {
        // Per-student serialization prevents duplicate active attempts without serializing the whole class.
        store.lockStudent(user); Row assignment=store.require("SELECT * FROM assessment_assignments WHERE id=? FOR SHARE",assignmentId);
        String classId=assignment.text("class_id"); policy.requireStudentWrite(user,classId,true); eligible(assignment,user);
        List<Row> attempts=store.rows("SELECT * FROM assessment_attempts WHERE assignment_id=? AND user_id=? ORDER BY attempt_no DESC",assignmentId,user);
        Instant now=Instant.now();
        for(Row existing:attempts) if("IN_PROGRESS".equals(existing.text("status"))) {
            Row locked=store.attempt(existing.text("id"),true);
            if(expired(locked,now)) finish(locked,"SYSTEM"); else return attemptDto(locked,assignment);
        }
        if(!"OPEN".equals(assignment.text("status"))) conflict("Bài tập đã đóng");
        if(assignment.instant("open_at")!=null && now.isBefore(assignment.instant("open_at"))) conflict("Bài tập chưa mở");
        if(!assignment.flag("allow_late") && assignment.instant("due_at")!=null && !now.isBefore(assignment.instant("due_at"))) conflict("Đã hết hạn nộp");
        if(assignment.number("max_attempts")>0 && attempts.size()>=assignment.number("max_attempts")) conflict("Bạn đã hết lượt làm bài");
        int no=attempts.stream().mapToInt(r->r.number("attempt_no")).max().orElse(0)+1;
        Row version=store.version(assignment.text("version_id")); AssessmentDocument doc=DocumentCodec.parse(version.text("grading_json"));
        Instant deadline=assignment.number("time_limit_minutes")>0?now.plusSeconds(assignment.number("time_limit_minutes")*60L):null;
        if(!assignment.flag("allow_late") && assignment.instant("due_at")!=null && (deadline==null || assignment.instant("due_at").isBefore(deadline))) deadline=assignment.instant("due_at");
        String id=UUID.randomUUID().toString(); long seed=new java.security.SecureRandom().nextLong();
        doc=VariableDocument.instantiate(doc,seed);
        store.write("INSERT INTO assessment_attempts(id,assignment_id,class_id,user_id,attempt_no,version_id,status,started_at,deadline_at,late,shuffle_seed,order_json,playback_json,recording_takes_json,max_score,revision) VALUES(?,?,?,?,?,?,'IN_PROGRESS',?,?,?,?,?,'{}','{}',?,1)",
            id,assignmentId,classId,user,no,version.text("id"),time(now),time(deadline),assignment.instant("due_at")!=null && now.isAfter(assignment.instant("due_at")),seed,
            DocumentCodec.writeJson(LearnerProjection.project(doc,seed,assignment.flag("shuffle"),authoring.subjectName(doc))),version.decimal("total_points"));
        for(var ref:DocumentIndex.of(doc).items()) store.write("INSERT INTO assessment_attempt_answers(id,attempt_id,item_id,status,updated_at) VALUES(?,?,?,'UNANSWERED',?)",UUID.randomUUID().toString(),id,ref.item().id(),time(now));
        audit.record(classId,user,"ASSESSMENT_START","ASSESSMENT_ATTEMPT",id,DocumentCodec.writeJson(object("attemptNo",no)));
        return attemptDto(store.attempt(id,false),assignment);
    }
    @Transactional
    public Map<String,Object> get(String id,String user) {
        Row attempt=owned(id,user,true); if(expired(attempt,Instant.now())) { finish(attempt,"SYSTEM"); attempt=store.attempt(id,false); }
        return attemptDto(attempt,store.assignment(attempt.text("assignment_id"),false));
    }
    @Transactional
    public Map<String,Object> autosave(String id,String user,Answers request) {
        Row row=owned(id,user,true); writable(row,user);
        if(request.revision()==null || request.revision()!=row.number("revision")) conflict("ATTEMPT_REVISION_CONFLICT: bài làm đã thay đổi, hãy tải lại");
        Map<String,JsonNode> answers=request.answers(); if(answers==null || answers.size()>500) bad("Câu trả lời không hợp lệ");
        AssessmentDocument doc=document(row); DocumentIndex index=DocumentIndex.of(doc);
        for(var e:answers.entrySet()) {
            ItemNode item=index.item(e.getKey()).orElseThrow(()->new AppException(ErrorCode.BAD_REQUEST,"Câu hỏi không thuộc bài làm")).item();
            JsonNode response=e.getValue(); validateResponse(row,item,response);
            store.write("UPDATE assessment_attempt_answers SET response_json=?,updated_at=? WHERE attempt_id=? AND item_id=?",response==null || response.isNull()?null:DocumentCodec.writeJson(response),time(Instant.now()),id,item.id());
            if("Q22".equals(item.type())) store.write("UPDATE assessment_attempt_answers SET recording_asset_id=? WHERE attempt_id=? AND item_id=?",
                response==null || response.isNull()?null:response.path("recordingAssetId").asText(),id,item.id());
        }
        Instant now=Instant.now(); store.write("UPDATE assessment_attempts SET revision=revision+1 WHERE id=?",id);
        return object("revision",row.number("revision")+1,"savedAt",now,"answeredCount",answeredCount(id));
    }
    @Transactional
    public Map<String,Object> submit(String id,String user) {
        Row row=owned(id,user,true); policy.requireStudentWrite(user,row.text("class_id"),false);
        if("IN_PROGRESS".equals(row.text("status"))) finish(row,user);
        return resultDto(store.attempt(id,false),store.assignment(row.text("assignment_id"),false));
    }
    @Transactional
    public void expire(String id) { Row row=store.attempt(id,true); if(expired(row,Instant.now())) finish(row,"SYSTEM"); }
    @Transactional
    public Map<String,Object> result(String id,String user) {
        Row row=owned(id,user,true); if(expired(row,Instant.now())) { finish(row,"SYSTEM"); row=store.attempt(id,false); }
        if("IN_PROGRESS".equals(row.text("status"))) conflict("Bài làm chưa nộp"); return resultDto(row,store.assignment(row.text("assignment_id"),false));
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> mine(String assignmentId,String user) {
        Row assignment=store.assignment(assignmentId,false); policy.requireStudent(user,assignment.text("class_id")); eligible(assignment,user);
        return store.rows("SELECT * FROM assessment_attempts WHERE assignment_id=? AND user_id=? ORDER BY attempt_no DESC",assignmentId,user).stream()
            .map(r->object("attemptId",r.text("id"),"attemptNo",r.number("attempt_no"),"status",r.text("status"),"startedAt",r.instant("started_at"),"submittedAt",r.instant("submitted_at"),"totalScore",visible(r,assignment)?r.decimal("total_score"):null)).toList();
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> homework(String classId,String user,String filter) {
        policy.requireStudent(user,classId); Instant now=Instant.now(); List<Map<String,Object>> out=new ArrayList<>();
        for(Row assignment:store.rows("SELECT * FROM assessment_assignments WHERE class_id=? AND status<>'CANCELLED' ORDER BY due_at IS NULL,due_at DESC,created_at DESC LIMIT 500",classId)) {
            if(!isEligible(assignment,user)) continue;
            List<Row> attempts=store.rows("SELECT * FROM assessment_attempts WHERE assignment_id=? AND user_id=? ORDER BY attempt_no DESC",assignment.text("id"),user);
            Row last=attempts.isEmpty()?null:attempts.get(0); String status;
            if(last!=null) status=last.text("status");
            else if(assignment.instant("open_at")!=null && now.isBefore(assignment.instant("open_at"))) status="UPCOMING";
            else if(!"OPEN".equals(assignment.text("status"))) status="CLOSED";
            else if(!assignment.flag("allow_late") && assignment.instant("due_at")!=null && !now.isBefore(assignment.instant("due_at"))) status="MISSED";
            else status="NOT_STARTED";
            boolean done=Set.of("SUBMITTED","GRADED","RETURNED").contains(status);
            if("todo".equals(filter) && !Set.of("NOT_STARTED","IN_PROGRESS").contains(status) || "done".equals(filter) && !done || "upcoming".equals(filter) && !"UPCOMING".equals(status)) continue;
            Row version=store.version(assignment.text("version_id")); AssessmentDocument doc=DocumentCodec.parse(version.text("grading_json"));
            out.add(object("assignmentId",assignment.text("id"),"title",assignment.text("title"),"kind",assignment.text("kind"),"status",status,"openAt",assignment.instant("open_at"),"dueAt",assignment.instant("due_at"),
                "itemCount",version.number("item_count"),"totalPoints",version.decimal("total_points"),"timeLimitMinutes",assignment.number("time_limit_minutes"),"answeredCount",last==null?0:answeredCount(last.text("id")),
                "canRetry",done && "OPEN".equals(assignment.text("status")) && (assignment.flag("allow_late") || assignment.instant("due_at")==null || now.isBefore(assignment.instant("due_at"))) && (assignment.number("max_attempts")==0 || attempts.size()<assignment.number("max_attempts")),"attemptId",last==null?null:last.text("id"),"attemptsUsed",attempts.size(),"maxAttempts",assignment.number("max_attempts"),"score",last!=null && visible(last,assignment)?last.decimal("total_score"):null,"skills",DocumentIndex.of(doc).skills()));
        }
        return out;
    }
    @Transactional
    public Map<String,Object> play(String id,String user,String assetKey) {
        Row row=owned(id,user,true); writable(row,user); AssessmentDocument doc=document(row);
        AssetRef asset=doc.assets().get(assetKey); if(asset==null || !"AUDIO".equals(asset.type())) missing("Không tìm thấy tệp nghe");
        JsonNode plays=DocumentCodec.readTree(row.text("playback_json")); int used=plays.path(assetKey).asInt();
        int max=AudioPolicies.forAsset(doc,assetKey).maxPlays();
        if(max>0 && used>=max) conflict("Đã hết lượt nghe");
        var signed=media.signAssessmentAsset(asset.assetId(),row.text("class_id"),"ASSESSMENT"); ObjectNode changed=(ObjectNode)plays.deepCopy(); changed.put(assetKey,used+1);
        store.write("UPDATE assessment_attempts SET playback_json=? WHERE id=?",DocumentCodec.writeJson(changed),id);
        return object("assetKey",assetKey,"plays",used+1,"maxPlays",max,"remaining",max>0?max-used-1:null,"url",signed.getDownloadUrl(),"expiresAt",signed.getExpiresAt());
    }
    @Transactional
    public Map<String,Object> uploadIntent(String id,String itemId,String user,RecordingIntent request,boolean recording) {
        Row row=owned(id,user,true); writable(row,user); ItemNode item=item(row,itemId); JsonNode x=item.interaction();
        if(recording && !"Q22".equals(item.type()) || !recording && !Set.of("Q23","Q24","Q25","Q28").contains(item.type())) bad("Câu hỏi không nhận loại tệp này");
        if(request.filename()==null || request.filename().isBlank() || request.filename().length()>200 || request.mimeType()==null) bad("Tên hoặc loại tệp không hợp lệ");
        long maxMb=recording?x.path("recording").path("maxFileMb").asLong(10):x.path("maxFileMb").asLong(20);
        if(request.sizeBytes()<=0 || request.sizeBytes()>Math.min(100,maxMb)*1024*1024) bad("Tệp vượt dung lượng được phép");
        String mime=request.mimeType().toLowerCase(Locale.ROOT).split(";")[0].trim();
        if(recording && !mime.startsWith("audio/") || "Q25".equals(item.type()) && !mime.startsWith("video/") || "Q23".equals(item.type()) && !MediaService.IMAGE_MIME_TYPES.contains(mime)) bad("Loại tệp không phù hợp câu hỏi");
        if(recording) {
            int used=DocumentCodec.readTree(row.text("recording_takes_json")).path(itemId).asInt();
            if(used>x.path("recording").path("maxRetakes").asInt()) conflict("Đã hết lượt ghi âm");
            if(request.durationMs()<1 || request.durationMs()>x.path("recording").path("maxSeconds").asLong()*1000+1000) bad("Bản ghi vượt thời lượng được phép");
        }
        if(store.count("SELECT COUNT(*) FROM assessment_attempt_uploads WHERE attempt_id=? AND item_id=? AND completed=FALSE AND created_at>?",id,itemId,time(Instant.now().minusSeconds(86400)))>=5) conflict("Hãy hoàn tất phiên tải tệp đang có trước");
        UploadIntentRequest upload=new UploadIntentRequest(); upload.setFilename(request.filename()); upload.setMimeType(mime); upload.setSizeBytes(request.sizeBytes()); upload.setPurpose("ASSESSMENT_RESPONSE");
        var intent=media.createUploadIntent(row.text("class_id"),user,upload);
        store.write("INSERT INTO assessment_attempt_uploads(asset_id,attempt_id,item_id,duration_ms,completed,created_at) VALUES(?,?,?,?,FALSE,?)",intent.getAssetId(),id,itemId,Math.max(0,request.durationMs()),time(Instant.now()));
        return object("assetId",intent.getAssetId(),"uploadUrl",intent.getUploadUrl(),"method","PUT");
    }
    public Map<String,Object> uploadComplete(String id,String itemId,String user,RecordingComplete request,boolean recording) {
        self.checkCompletion(id,itemId,user,request);
        // Object-store inspection/promotion runs outside the attempt transaction and its row lock.
        media.completeUpload(request.assetId(),user);
        Long duration=recording?media.probeAssessmentRecording(request.assetId()):null;
        return self.commitCompletion(id,itemId,user,request,recording,duration);
    }
    @Transactional(readOnly=true)
    public void checkCompletion(String id,String itemId,String user,RecordingComplete request) {
        Row row=owned(id,user,false);writable(row,user);item(row,itemId);
        store.require("SELECT asset_id FROM assessment_attempt_uploads WHERE asset_id=? AND attempt_id=? AND item_id=?",request.assetId(),id,itemId);
    }
    @Transactional
    public Map<String,Object> commitCompletion(String id,String itemId,String user,RecordingComplete request,boolean recording,Long actualDuration) {
        Row row=owned(id,user,true); writable(row,user); ItemNode item=item(row,itemId);
        Row binding=store.require("SELECT * FROM assessment_attempt_uploads WHERE asset_id=? AND attempt_id=? AND item_id=? FOR UPDATE",request.assetId(),id,itemId);
        if(binding.flag("completed")) return recordingInfo(row,itemId,request.assetId(),binding.longNumber("duration_ms"));
        MediaAsset asset=media.getAsset(request.assetId());
        if(!user.equals(asset.getUploaderId()) || !row.text("class_id").equals(asset.getClassId()) || !"ASSESSMENT_RESPONSE".equals(asset.getUploadPurpose())) bad("Tệp không thuộc bài làm");
        if(recording && !"Q22".equals(item.type()) || !recording && !Set.of("Q23","Q24","Q25","Q28").contains(item.type())) bad("Câu hỏi không nhận tệp này");
        JsonNode takes=DocumentCodec.readTree(row.text("recording_takes_json")); int used=takes.path(itemId).asInt();
        if(recording && used>item.interaction().path("recording").path("maxRetakes").asInt()) conflict("Đã hết lượt ghi âm");
        if(!"UPLOADED".equals(asset.getStatus())) bad("Tệp chưa tải lên hoàn tất");
        store.write("UPDATE assessment_attempt_uploads SET completed=TRUE WHERE asset_id=?",request.assetId());
        long duration=recording?Objects.requireNonNull(actualDuration):binding.longNumber("duration_ms");
        if(recording && duration>item.interaction().path("recording").path("maxSeconds").asLong()*1000+1000)bad("Bản ghi thực tế vượt thời lượng được phép");
        if(recording)store.write("UPDATE assessment_attempt_uploads SET duration_ms=? WHERE asset_id=?",duration,request.assetId());
        if(recording) {
            ObjectNode changed=(ObjectNode)takes.deepCopy(); changed.put(itemId,used+1);
            store.write("UPDATE assessment_attempts SET recording_takes_json=?,revision=revision+1 WHERE id=?",DocumentCodec.writeJson(changed),id);
            store.write("UPDATE assessment_attempt_answers SET recording_asset_id=?,response_json=?,updated_at=? WHERE attempt_id=? AND item_id=?",request.assetId(),DocumentCodec.writeJson(object("recordingAssetId",request.assetId(),"durationMs",duration)),time(Instant.now()),id,itemId);
        }
        audit.record(row.text("class_id"),user,"ASSESSMENT_EVIDENCE_UPLOAD","ASSESSMENT_ATTEMPT",id,DocumentCodec.writeJson(object("itemId",itemId,"assetId",request.assetId())));
        return object("assetId",request.assetId(),"durationMs",duration,"takesUsed",recording?used+1:0,"revision",row.number("revision")+(recording?1:0));
    }
    @Transactional
    public void deleteRecording(String id,String itemId,String user) {
        Row row=owned(id,user,true); writable(row,user); if(!"Q22".equals(item(row,itemId).type())) bad("Không phải câu ghi âm");
        store.write("UPDATE assessment_attempt_answers SET recording_asset_id=NULL,response_json=NULL,updated_at=? WHERE attempt_id=? AND item_id=?",time(Instant.now()),id,itemId);
        store.write("UPDATE assessment_attempts SET revision=revision+1 WHERE id=?",id);
        audit.record(row.text("class_id"),user,"ASSESSMENT_RECORDING_REMOVE","ASSESSMENT_ATTEMPT",id,DocumentCodec.writeJson(object("itemId",itemId)));
    }
    @Transactional(readOnly=true)
    public Map<String,Object> recordingUrl(String id,String itemId,String user) {
        Row row=store.attempt(id,false); if(user.equals(row.text("user_id"))) policy.requireStudent(user,row.text("class_id")); else policy.requireRead(user,row.text("class_id"),"GRADE");
        Row answer=store.require("SELECT * FROM assessment_attempt_answers WHERE attempt_id=? AND item_id=? AND recording_asset_id IS NOT NULL",id,itemId);
        var signed=media.signAssessmentAsset(answer.text("recording_asset_id"),row.text("class_id"),"ASSESSMENT_RESPONSE");
        return object("assetId",answer.text("recording_asset_id"),"url",signed.getDownloadUrl(),"expiresAt",signed.getExpiresAt());
    }
    @Transactional(readOnly=true)
    public Map<String,Object> evidenceUrl(String id,String itemId,String assetId,String user) {
        Row row=store.attempt(id,false); if(user.equals(row.text("user_id"))) policy.requireStudent(user,row.text("class_id")); else policy.requireRead(user,row.text("class_id"),"GRADE");
        store.require("SELECT asset_id FROM assessment_attempt_uploads WHERE asset_id=? AND attempt_id=? AND item_id=? AND completed=TRUE",assetId,id,itemId);
        var signed=media.signAssessmentAsset(assetId,row.text("class_id"),"ASSESSMENT_RESPONSE"); return object("assetId",assetId,"url",signed.getDownloadUrl(),"expiresAt",signed.getExpiresAt());
    }
    public Row owned(String id,String user,boolean lock) {
        Row row=store.attempt(id,lock); if(!user.equals(row.text("user_id"))) missing("Không tìm thấy bài làm"); policy.requireStudent(user,row.text("class_id")); return row;
    }
    public AssessmentDocument document(Row attempt) { return VariableDocument.instantiate(DocumentCodec.parse(store.version(attempt.text("version_id")).text("grading_json")),attempt.longNumber("shuffle_seed")); }
    public ItemNode item(Row row,String itemId) { return DocumentIndex.of(document(row)).item(itemId).orElseThrow(()->new AppException(ErrorCode.NOT_FOUND,"Không tìm thấy câu hỏi")).item(); }
    public boolean visible(Row attempt,Row assignment) {
        if("RETURNED".equals(attempt.text("status"))) return true;
        if("IN_PROGRESS".equals(attempt.text("status"))) return false;
        return "IMMEDIATE".equals(assignment.text("result_visibility")) || "AFTER_DUE".equals(assignment.text("result_visibility")) && assignment.instant("due_at")!=null && !Instant.now().isBefore(assignment.instant("due_at"));
    }
    @Transactional(readOnly=true)
    public Map<String,Object> reviewAudio(String id,String assetKey,String user) {
        Row row=store.attempt(id,false);
        if(user.equals(row.text("user_id"))) {
            policy.requireStudent(user,row.text("class_id"));
            if(!visible(row,store.assignment(row.text("assignment_id"),false))) missing("Bài làm chưa được công bố");
        } else policy.requireRead(user,row.text("class_id"),"GRADE");
        AssetRef asset=document(row).assets().get(assetKey);
        if(asset==null || !"AUDIO".equals(asset.type())) missing("Không tìm thấy tệp nghe");
        var signed=media.signAssessmentAsset(asset.assetId(),row.text("class_id"),"ASSESSMENT");
        return object("assetId",asset.assetId(),"url",signed.getDownloadUrl(),"expiresAt",signed.getExpiresAt());
    }
    public Map<String,Object> resultDto(Row row,Row assignment) {
        AssessmentDocument doc=document(row); DocumentIndex index=DocumentIndex.of(LearnerProjection.ordered(doc,row.longNumber("shuffle_seed"),assignment.flag("shuffle")));
        boolean visible=visible(row,assignment); List<Map<String,Object>> items=new ArrayList<>(); int pending=0; BigDecimal autoMax=BigDecimal.ZERO;
        Map<String,Row> answers=new HashMap<>(); for(Row a:answerRows(row.text("id"))) answers.put(a.text("item_id"),a);
        for(var ref:index.items()) {
            ItemNode it=ref.item(); Row a=answers.get(it.id()); boolean manual=GradingEngine.needsTeacher(it);
            if(!manual) autoMax=autoMax.add(it.points()); if(Set.of("PENDING_MANUAL","PENDING_CODE","CODE_RUNNING").contains(a.text("status"))) pending++;
            String status=Set.of("PENDING_MANUAL","PENDING_CODE","CODE_RUNNING").contains(a.text("status"))?"PENDING":("GRADED".equals(a.text("status"))?"GRADED":a.text("correctness"));
            items.add(object("itemId",it.id(),"label",index.label(it.id()),"status",visible?status:"PENDING","points",visible?a.decimal("final_points"):null,"maxPoints",it.points(),
                "response",DocumentCodec.readTree(a.text("response_json")),"correctResponse",visible?GradingEngine.correctResponse(it):null,"subResults",visible?DocumentCodec.readTree(a.text("sub_results_json")):null,
                "feedback",visible?it.feedback():null,"teacherComment",visible?a.text("teacher_comment"):null,"passFail",visible?a.text("pass_fail"):null));
        }
        Map<String,Map<String,Object>> skills=new LinkedHashMap<>();
        if(visible)for(var ref:index.items()) {
            ItemNode item=ref.item();Row answer=answers.get(item.id());if(answer.decimal("final_points")==null)continue;
            Set<String> names=new LinkedHashSet<>(item.tags());if(item.outcome()!=null && !item.outcome().isBlank())names.add(item.outcome());
            for(String name:names){Map<String,Object> v=skills.computeIfAbsent(name,k->object("skill",k,"earned",BigDecimal.ZERO,"possible",BigDecimal.ZERO,"responses",0));
                v.put("earned",((BigDecimal)v.get("earned")).add(answer.decimal("final_points")));v.put("possible",((BigDecimal)v.get("possible")).add(item.points()));v.put("responses",(Integer)v.get("responses")+1);}
        }
        for(var v:skills.values()){BigDecimal possible=(BigDecimal)v.get("possible");v.put("masteryPercent",possible.signum()==0?null:((BigDecimal)v.get("earned")).multiply(BigDecimal.valueOf(100)).divide(possible,2,java.math.RoundingMode.HALF_UP));}
        Map<String,String> transcripts=new LinkedHashMap<>(); Instant now=Instant.now();
        for(var e:doc.assets().entrySet()) {
            AssetRef a=e.getValue(); boolean allowed="AFTER_SUBMISSION".equals(a.transcriptVisibility()) || "AFTER_DUE".equals(a.transcriptVisibility()) && assignment.instant("due_at")!=null && !now.isBefore(assignment.instant("due_at"));
            if(visible && allowed && a.transcript()!=null) transcripts.put(e.getKey(),a.transcript());
        }
        return object("attemptId",row.text("id"),"assignmentId",assignment.text("id"),"status",row.text("status"),"submittedAt",row.instant("submitted_at"),"late",row.flag("late"),
            "visibility",object("scores",visible,"answers",visible,"transcripts",!transcripts.isEmpty()),"autoScore",visible?row.decimal("auto_score"):null,"manualScore",visible?row.decimal("manual_score"):null,
            "totalScore",visible?row.decimal("total_score"):null,"maxScore",row.decimal("max_score"),"autoMaxScore",autoMax,"pendingManualCount",pending,"passFail",visible?row.text("pass_fail"):null,
            "teacherComment",visible?row.text("teacher_comment"):null,"document",authoring.learnerDocument(doc,row.longNumber("shuffle_seed"),assignment.flag("shuffle"),row.text("class_id")),"items",items,"transcripts",transcripts,"skills",new ArrayList<>(skills.values()));
    }
    public List<Row> answerRows(String id) { return store.rows("SELECT * FROM assessment_attempt_answers WHERE attempt_id=?",id); }
    private Map<String,Object> attemptDto(Row r,Row assignment) {
        Map<String,Object> answers=new LinkedHashMap<>(),recordings=new LinkedHashMap<>(); JsonNode takes=DocumentCodec.readTree(r.text("recording_takes_json"));
        for(Row a:answerRows(r.text("id"))) {
            if(a.text("response_json")!=null) answers.put(a.text("item_id"),DocumentCodec.readTree(a.text("response_json")));
            if(a.text("recording_asset_id")!=null) recordings.put(a.text("item_id"),recordingInfo(r,a.text("item_id"),a.text("recording_asset_id"),DocumentCodec.readTree(a.text("response_json")).path("durationMs").asLong()));
        }
        return object("attemptId",r.text("id"),"assignmentId",r.text("assignment_id"),"attemptNo",r.number("attempt_no"),"status",r.text("status"),"startedAt",r.instant("started_at"),"deadlineAt",r.instant("deadline_at"),
            "serverNow",Instant.now(),"revision",r.number("revision"),"resultVisibility",assignment.text("result_visibility"),"document",authoring.learnerDocument(document(r),r.longNumber("shuffle_seed"),assignment.flag("shuffle"),r.text("class_id")),
            "answers",answers,"plays",DocumentCodec.readTree(r.text("playback_json")),"recordings",recordings,"recordingTakes",takes);
    }
    private Map<String,Object> recordingInfo(Row r,String itemId,String assetId,long duration) {
        return object("assetId",assetId,"durationMs",duration,"takesUsed",DocumentCodec.readTree(r.text("recording_takes_json")).path(itemId).asInt(),"revision",r.number("revision"));
    }
    @Transactional
    public ItemNode prepareCodeRun(String id,String itemId,String user) {
        Row row=owned(id,user,true);writable(row,user);ItemNode item=item(row,itemId);
        if(!"Q26".equals(item.type()))bad("Không phải câu lập trình");
        Instant now=Instant.now();boolean reset=row.instant("code_run_window")==null || row.instant("code_run_window").isBefore(now.minusSeconds(60));
        if(!reset && row.number("code_run_count")>=10)conflict("Tối đa 10 lần chạy mỗi phút cho mỗi bài làm");
        store.write("UPDATE assessment_attempts SET code_run_window=?,code_run_count=? WHERE id=?",time(reset?now:row.instant("code_run_window")),reset?1:row.number("code_run_count")+1,id);
        return item;
    }
    private void writable(Row row,String user) {
        policy.requireStudentWrite(user,row.text("class_id"),false);
        if(!"IN_PROGRESS".equals(row.text("status"))) conflict("Bài làm đã nộp");
        if(expired(row,Instant.now())) conflict("Đã hết thời gian làm bài");
        Row assignment=store.assignment(row.text("assignment_id"),false);
        if(!"OPEN".equals(assignment.text("status"))) conflict("Bài tập đã đóng");
    }
    private boolean isEligible(Row assignment,String user) {
        return assignment.text("target_segment_id")==null || segments.isUserInSegment(assignment.text("target_segment_id"),user,assignment.text("class_id"));
    }
    private void eligible(Row assignment,String user) { if(!isEligible(assignment,user)) missing("Không tìm thấy bài tập được giao"); }
    private static boolean expired(Row r,Instant now) { return "IN_PROGRESS".equals(r.text("status")) && r.instant("deadline_at")!=null && !now.isBefore(r.instant("deadline_at")); }
    private long answeredCount(String id) { return store.count("SELECT COUNT(*) FROM assessment_attempt_answers WHERE attempt_id=? AND response_json IS NOT NULL",id); }
    private void finish(Row row,String actor) {
        AssessmentDocument doc=document(row); DocumentIndex index=DocumentIndex.of(doc); BigDecimal auto=BigDecimal.ZERO; int pending=0;
        for(Row answer:answerRows(row.text("id"))) {
            ItemNode item=index.item(answer.text("item_id")).orElseThrow().item(); JsonNode response=DocumentCodec.readTree(answer.text("response_json"));
            GradeOutcome result=GradingEngine.grade(item,response); if(Set.of("PENDING_MANUAL","PENDING_CODE").contains(result.status())) pending++;
            else if(result.points()!=null) auto=auto.add(result.points());
            store.write("UPDATE assessment_attempt_answers SET auto_points=?,final_points=?,status=?,correctness=?,sub_results_json=?,updated_at=? WHERE id=?",
                result.points(),"PENDING_MANUAL".equals(result.status())?null:result.points(),result.status(),result.correctness(),result.subResults()==null?null:DocumentCodec.writeJson(result.subResults()),time(Instant.now()),answer.text("id"));
        }
        Instant now=Instant.now();
        if(row.instant("deadline_at")!=null && now.isAfter(row.instant("deadline_at"))) now=row.instant("deadline_at");
        Row assignment=store.assignment(row.text("assignment_id"),false); boolean late=assignment.instant("due_at")!=null && now.isAfter(assignment.instant("due_at"));
        store.write("UPDATE assessment_attempts SET status=?,submitted_at=?,late=?,auto_score=?,manual_score=?,total_score=?,revision=revision+1 WHERE id=?",
            pending>0?"SUBMITTED":"GRADED",time(now),late,ScoreMath.round(auto),pending>0?null:BigDecimal.ZERO,pending>0?null:ScoreMath.round(auto),row.text("id"));
        audit.record(row.text("class_id"),"SYSTEM".equals(actor)?null:actor,"ASSESSMENT_SUBMIT","ASSESSMENT_ATTEMPT",row.text("id"),DocumentCodec.writeJson(object("automatic","SYSTEM".equals(actor),"pendingManual",pending)));
    }
    private void validateResponse(Row attempt,ItemNode item,JsonNode response) {
        if(response==null || response.isNull()) return;
        if(!response.isObject() || DocumentCodec.writeJson(response).length()>200000) bad("Câu trả lời không hợp lệ hoặc quá dài");
        responseShape(response,0);
        if("Q22".equals(item.type())) {
            String id=response.path("recordingAssetId").asText(); if(store.count("SELECT COUNT(*) FROM assessment_attempt_uploads WHERE asset_id=? AND attempt_id=? AND item_id=? AND completed=TRUE",id,attempt.text("id"),item.id())!=1) bad("Bản ghi không thuộc câu hỏi này");
        }
        if("Q26".equals(item.type()) && response.path("code").asText().length()>20000)bad("Code tối đa 20.000 ký tự");
        if(Set.of("Q23","Q24","Q25","Q28").contains(item.type())) {
            if(!response.path("files").isArray() || response.path("files").size()>("Q23".equals(item.type())?10:item.interaction().path("maxFiles").asInt(3))) bad("Minh chứng không hợp lệ");
            for(JsonNode id:response.path("files")) if(store.count("SELECT COUNT(*) FROM assessment_attempt_uploads WHERE asset_id=? AND attempt_id=? AND item_id=? AND completed=TRUE",id.asText(),attempt.text("id"),item.id())!=1) bad("Tệp không thuộc câu hỏi này");
        }
    }
    private static void responseShape(JsonNode node,int depth){if(depth>12 || node.isContainerNode() && node.size()>6000)bad("Câu trả lời có cấu trúc quá lớn");if(node.isContainerNode())for(JsonNode child:node)responseShape(child,depth+1);}
    private static void missing(String message) { throw new AppException(ErrorCode.NOT_FOUND,message); }
    private static void bad(String message) { throw new AppException(ErrorCode.BAD_REQUEST,message); }
    private static void conflict(String message) { throw new AppException(ErrorCode.CONFLICT,message); }
}
