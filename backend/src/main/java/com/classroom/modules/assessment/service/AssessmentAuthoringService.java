package com.classroom.modules.assessment.service;

import com.classroom.common.*;
import com.classroom.modules.assessment.catalog.*;
import com.classroom.modules.assessment.doc.*;
import com.classroom.modules.assessment.dto.AssessmentRequests.*;
import com.classroom.modules.assessment.policy.AssessmentPolicy;
import com.classroom.modules.assessment.repository.AssessmentStore;
import com.classroom.modules.assessment.repository.AssessmentStore.Row;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.media.model.MediaAsset;
import com.classroom.modules.media.service.MediaService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import static com.classroom.modules.assessment.doc.LearnerProjection.object;
import static com.classroom.modules.assessment.repository.AssessmentStore.time;

@Service
public class AssessmentAuthoringService {
    private final AssessmentStore store;
    private final AssessmentPolicy policy;
    private final AssessmentCatalogService catalog;
    private final DocumentValidator validator;
    private final MediaService media;
    private final AuditService audit;
    public AssessmentAuthoringService(AssessmentStore store,AssessmentPolicy policy,AssessmentCatalogService catalog,
                                      DocumentValidator validator,MediaService media,AuditService audit) {
        this.store=store; this.policy=policy; this.catalog=catalog; this.validator=validator; this.media=media; this.audit=audit;
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> list(String classId,String user,String status,String query) {
        policy.requireAnyStudioGrant(user,classId);
        if(status!=null && !Set.of("DRAFT","ARCHIVED").contains(status)) bad("Trạng thái không hợp lệ");
        String q=query==null?"":query.trim(); if(q.length()>200) bad("Từ khóa quá dài");
        return store.rows("SELECT * FROM assessments WHERE class_id=? AND status=? ORDER BY updated_at DESC LIMIT 500",classId,status==null?"DRAFT":status)
            .stream().filter(r->q.isBlank() || r.text("title").toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))).map(this::summary).toList();
    }
    @Transactional
    public Map<String,Object> create(String classId,String user,Create request) {
        policy.require(user,classId,"CREATE");
        AssessmentDocument doc=request.document()==null || request.document().isNull()
            ? AssessmentDocument.empty(request.title(),request.kind(),request.grade(),request.subjectCode(),request.languageCode(),request.topicTitle())
            : DocumentCodec.parse(request.document());
        validator.structure(doc); validateAssets(doc,classId,false);
        String id=UUID.randomUUID().toString(); Instant now=Instant.now(); DocumentIndex index=DocumentIndex.of(doc);
        store.write("INSERT INTO assessments(id,class_id,created_by,title,kind,grade,subject_code,language_code,topic_title,status,schema_version,content_json,revision,latest_version_no,item_count,total_points,skills,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'DRAFT',1,?,1,0,?,?,?,?,?)",
            id,classId,user,doc.title(),doc.kind(),doc.grade(),doc.subjectCode(),doc.languageCode(),doc.topicTitle(),DocumentCodec.write(doc),
            index.itemCount(),index.total(),String.join(" ",index.skills()),time(now),time(now));
        indexDraft(id,classId,doc,now);
        audit.record(classId,user,"ASSESSMENT_CREATE","ASSESSMENT",id,"{}");
        return authoring(store.assessment(id,false),user);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> get(String id,String user) {
        Row row=store.assessment(id,false); policy.requireAuthorRead(user,row.text("class_id")); return authoring(row,user);
    }
    @Transactional
    public Map<String,Object> save(String id,String user,Save request) {
        Row row=store.assessment(id,true); policy.require(user,row.text("class_id"),"EDIT"); editable(row);
        revision(row,request.revision()); AssessmentDocument doc=DocumentCodec.parse(request.document());
        validator.structure(doc); validateAssets(doc,row.text("class_id"),false); DocumentIndex index=DocumentIndex.of(doc); Instant now=Instant.now();
        store.write("UPDATE assessments SET title=?,kind=?,grade=?,subject_code=?,language_code=?,topic_title=?,content_json=?,revision=revision+1,item_count=?,total_points=?,skills=?,updated_at=? WHERE id=?",
            doc.title(),doc.kind(),doc.grade(),doc.subjectCode(),doc.languageCode(),doc.topicTitle(),DocumentCodec.write(doc),index.itemCount(),index.total(),String.join(" ",index.skills()),time(now),id);
        indexDraft(id,row.text("class_id"),doc,now);
        audit.record(row.text("class_id"),user,"ASSESSMENT_SAVE","ASSESSMENT",id,DocumentCodec.writeJson(object("revision",row.number("revision")+1)));
        return object("revision",row.number("revision")+1,"updatedAt",now,"itemCount",index.itemCount(),"totalPoints",index.total(),"issues",validator.issues(doc,row.text("class_id")));
    }
    @Transactional(readOnly=true)
    public Map<String,Object> validate(String id,String user,Validate request) {
        Row row=store.assessment(id,false); policy.requireAuthorRead(user,row.text("class_id"));
        AssessmentDocument doc=request==null || request.document()==null?DocumentCodec.parse(row.text("content_json")):DocumentCodec.parse(request.document());
        List<Issue> issues=new ArrayList<>(validator.issues(doc,row.text("class_id")));
        for(var entry:assetStatuses(doc,row.text("class_id")).entrySet()) if(!"UPLOADED".equals(((Map<?,?>)entry.getValue()).get("status")))
            issues.add(new Issue("root","ERROR","ASSET_MISSING","Tệp đính kèm chưa tải lên xong"));
        return object("issues",issues,"ready",issues.stream().noneMatch(i->"ERROR".equals(i.level())));
    }
    @Transactional(readOnly=true)
    public Map<String,Object> preview(String id,String user) {
        Row row=store.assessment(id,false); policy.requireAuthorRead(user,row.text("class_id"));
        return learnerDocument(DocumentCodec.parse(row.text("content_json")),0,false,row.text("class_id"));
    }
    @Transactional
    public Map<String,Object> duplicate(String id,String user) {
        Row row=store.assessment(id,false); policy.requireAuthorRead(user,row.text("class_id")); AssessmentDocument doc=DocumentCodec.parse(row.text("content_json"));
        String title=doc.title().substring(0,Math.min(185,doc.title().length()))+" (bản sao)";
        AssessmentDocument copy=doc.withMeta(title,doc.kind(),doc.grade(),doc.subjectCode(),doc.languageCode(),doc.topicTitle());
        return create(row.text("class_id"),user,new Create(title,copy.kind(),copy.grade(),copy.subjectCode(),copy.languageCode(),copy.topicTitle(),DocumentCodec.toTree(copy)));
    }
    @Transactional
    public void archive(String id,String user) {
        Row row=store.assessment(id,true); policy.require(user,row.text("class_id"),"DELETE");
        store.write("UPDATE assessments SET status='ARCHIVED',revision=revision+1,updated_at=? WHERE id=?",time(Instant.now()),id);
        audit.record(row.text("class_id"),user,"ASSESSMENT_ARCHIVE","ASSESSMENT",id,"{}");
    }
    @Transactional
    public Map<String,Object> assign(String id,String user,Assign request) {
        Row row=store.assessment(id,true); String classId=row.text("class_id"); policy.require(user,classId,"PUBLISH"); editable(row); revision(row,request.revision());
        AssessmentDocument doc=DocumentCodec.parse(row.text("content_json")); List<Issue> issues=validator.issues(doc,classId);
        if(issues.stream().anyMatch(i->"ERROR".equals(i.level()))) bad("Đề chưa sẵn sàng giao: "+issues.stream().filter(i->"ERROR".equals(i.level())).findFirst().orElseThrow().message());
        validateAssets(doc,classId,true);
        if(request.timeLimitMinutes()<0 || request.timeLimitMinutes()>1440 || request.maxAttempts()<0 || request.maxAttempts()>100) bad("Giới hạn thời gian/lượt làm không hợp lệ");
        String visibility=request.resultVisibility()==null?"MANUAL":request.resultVisibility();
        if(!Set.of("IMMEDIATE","AFTER_DUE","MANUAL").contains(visibility)) bad("Chính sách kết quả không hợp lệ");
        if("AFTER_DUE".equals(visibility) && request.dueAt()==null) bad("Công bố sau hạn nộp cần có hạn nộp");
        if(request.openAt()!=null && request.dueAt()!=null && !request.openAt().isBefore(request.dueAt())) bad("Hạn nộp phải sau thời gian mở");
        if(request.targetSegmentId()!=null && store.count("SELECT COUNT(*) FROM segments WHERE id=? AND class_id=?",request.targetSegmentId(),classId)!=1) bad("Phân khúc không thuộc lớp");
        Map<String,Object> rubrics=new LinkedHashMap<>();
        for(var it:DocumentIndex.of(doc).items()) if(it.item().grading().rubricId()!=null)
            catalog.rubric(it.item().grading().rubricId(),classId).ifPresent(r->rubrics.put(r.id(),r));
        Instant now=Instant.now(); String hash=DocumentCodec.canonicalHash(object("document",doc,"rubrics",rubrics)); int versionNo=row.number("latest_version_no");
        Optional<Row> existing=store.first("SELECT * FROM assessment_versions WHERE assessment_id=? AND version_no=? AND content_hash=?",id,versionNo,hash);
        String version;
        if(existing.isPresent()) version=existing.get().text("id");
        else {
            version=UUID.randomUUID().toString(); versionNo++;
            store.write("INSERT INTO assessment_versions(id,assessment_id,version_no,learner_json,grading_json,rubrics_json,content_hash,item_count,total_points,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                version,id,versionNo,DocumentCodec.writeJson(LearnerProjection.project(doc,0,false,subjectName(doc))),DocumentCodec.write(doc),DocumentCodec.writeJson(rubrics),hash,row.number("item_count"),row.decimal("total_points"),user,time(now));
            for(AssetRef asset:doc.assets().values()) store.write("INSERT IGNORE INTO assessment_version_media(version_id,media_asset_id) VALUES(?,?)",version,asset.assetId());
            store.write("UPDATE assessments SET latest_version_no=? WHERE id=?",versionNo,id);
        }
        String assignment=UUID.randomUUID().toString();
        store.write("INSERT INTO assessment_assignments(id,class_id,assessment_id,version_id,title,kind,assigned_by,target_segment_id,open_at,due_at,time_limit_minutes,max_attempts,result_visibility,allow_late,shuffle,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'OPEN',?,?)",
            assignment,classId,id,version,doc.title(),doc.kind(),user,request.targetSegmentId(),time(request.openAt()),time(request.dueAt()),request.timeLimitMinutes(),request.maxAttempts(),visibility,request.allowLate(),request.shuffle(),time(now),time(now));
        audit.record(classId,user,"ASSESSMENT_ASSIGN","ASSIGNMENT",assignment,DocumentCodec.writeJson(object("versionId",version,"versionNo",versionNo)));
        return assignmentDto(store.assignment(assignment,false));
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> assignments(String classId,String user) {
        policy.requireAnyStudioGrant(user,classId);
        return store.rows("SELECT * FROM assessment_assignments WHERE class_id=? ORDER BY created_at DESC LIMIT 500",classId).stream().map(this::assignmentDto).toList();
    }
    @Transactional
    public Map<String,Object> patchAssignment(String id,String user,com.fasterxml.jackson.databind.JsonNode body) {
        Row row=store.assignment(id,true); policy.require(user,row.text("class_id"),"PUBLISH"); Instant due=row.instant("due_at"); String status=row.text("status");
        if(body.has("dueAt")) { try { due=body.get("dueAt").isNull()?null:Instant.parse(body.get("dueAt").asText()); } catch(Exception e) { bad("Hạn nộp không hợp lệ"); } }
        if(body.has("status")) { status=body.get("status").asText(); if(!Set.of("OPEN","CLOSED").contains(status)) bad("Trạng thái không hợp lệ"); }
        if(due!=null && row.instant("open_at")!=null && !due.isAfter(row.instant("open_at"))) bad("Hạn nộp phải sau thời gian mở");
        if(due==null && "AFTER_DUE".equals(row.text("result_visibility"))) bad("Chính sách kết quả yêu cầu hạn nộp");
        store.write("UPDATE assessment_assignments SET due_at=?,status=?,updated_at=? WHERE id=?",time(due),status,time(Instant.now()),id);
        audit.record(row.text("class_id"),user,"ASSESSMENT_ASSIGNMENT_UPDATE","ASSIGNMENT",id,"{}"); return assignmentDto(store.assignment(id,false));
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> bank(String classId,String user,String q,String type,String mode,Integer grade,String subject,int page,int size) {
        policy.requireAuthorRead(user,classId); size=Math.min(100,Math.max(1,size)); page=Math.max(0,Math.min(page,10000));
        String sql="SELECT b.*,a.title AS assessment_title FROM question_bank_items b JOIN assessments a ON a.id=b.assessment_id WHERE b.class_id=? AND a.status='DRAFT'";
        List<Object> args=new ArrayList<>(); args.add(classId);
        if(q!=null && !q.isBlank()) { sql+=" AND b.prompt_excerpt LIKE ?"; args.add("%"+q.substring(0,Math.min(200,q.length()))+"%"); }
        if(type!=null && !type.isBlank()) { sql+=" AND b.type_code=?"; args.add(type); }
        if(mode!=null && !mode.isBlank()) { sql+=" AND b.grading_mode=?"; args.add(mode); }
        if(grade!=null) { sql+=" AND b.grade=?"; args.add(grade); }
        if(subject!=null && !subject.isBlank()) { sql+=" AND b.subject_code=?"; args.add(subject); }
        sql+=" ORDER BY b.updated_at DESC,b.id LIMIT ? OFFSET ?"; args.add(size); args.add(page*size);
        return store.rows(sql,args.toArray()).stream().map(r->object("id",r.text("id"),"assessmentId",r.text("assessment_id"),"assessmentTitle",r.text("assessment_title"),
            "itemId",r.text("item_id"),"typeCode",r.text("type_code"),"gradingMode",r.text("grading_mode"),"promptExcerpt",r.text("prompt_excerpt"),"groupTitle",r.text("group_title"),
            "maxPoints",r.decimal("max_points"),"grade",r.data().get("grade"),"subjectCode",r.text("subject_code"),"updatedAt",r.instant("updated_at"))).toList();
    }
    @Transactional(readOnly=true)
    public Map<String,Object> bankItem(String id,String user) {
        Row r=store.require("SELECT * FROM question_bank_items WHERE id=?",id); policy.requireAuthorRead(user,r.text("class_id"));
        AssessmentDocument doc=DocumentCodec.parse(store.assessment(r.text("assessment_id"),false).text("content_json"));
        var ref=DocumentIndex.of(doc).item(r.text("item_id")).orElseThrow();
        Node node=ref.item();GroupNode g=ref.group();
        if(g!=null)node=new GroupNode(g.id(),g.groupType(),g.title(),g.instructions(),g.stimulus(),g.audioAssetKey(),g.playback(),g.keepStimulusVisible(),g.display(),g.shuffleChildren(),List.of(ref.item()));
        SectionNode s=ref.section();AssessmentDocument fragment=doc.withSections(List.of(new SectionNode(s.id(),s.title(),s.instructions(),s.shuffle(),List.of(node),s.audioAssetKey(),s.playback())));
        return object("item",ref.item(),"assets",doc.assets(),"document",fragment);
    }
    public Map<String,Object> assignmentDto(Row r) {
        Row v=store.version(r.text("version_id"));
        long students=store.count("SELECT COUNT(*) FROM class_members WHERE class_id=? AND state='ACTIVE' AND (access_expires_at IS NULL OR access_expires_at>?)",r.text("class_id"),time(Instant.now()));
        List<Row> attempts=store.rows("SELECT * FROM assessment_attempts WHERE assignment_id=?",r.text("id"));
        long submitted=attempts.stream().filter(a->!"IN_PROGRESS".equals(a.text("status"))).map(a->a.text("user_id")).distinct().count();
        return object("id",r.text("id"),"assessmentId",r.text("assessment_id"),"classId",r.text("class_id"),"title",r.text("title"),"kind",r.text("kind"),
            "versionId",r.text("version_id"),"versionNo",v.number("version_no"),"status",r.text("status"),"itemCount",v.number("item_count"),"totalPoints",v.decimal("total_points"),
            "createdAt",r.instant("created_at"),"assignedBy",person(r.text("assigned_by")),"targetSegmentId",r.text("target_segment_id"),"openAt",r.instant("open_at"),"dueAt",r.instant("due_at"),
            "timeLimitMinutes",r.number("time_limit_minutes"),"maxAttempts",r.number("max_attempts"),"resultVisibility",r.text("result_visibility"),"allowLate",r.flag("allow_late"),"shuffle",r.flag("shuffle"),
            "counts",object("students",students,"submitted",submitted,"inProgress",attempts.stream().filter(a->"IN_PROGRESS".equals(a.text("status"))).map(a->a.text("user_id")).distinct().count(),
                "pendingGrading",attempts.stream().filter(a->"SUBMITTED".equals(a.text("status"))).count(),"graded",attempts.stream().filter(a->Set.of("GRADED","RETURNED").contains(a.text("status"))).map(a->a.text("user_id")).distinct().count()));
    }
    public Map<String,Object> person(String id) {
        return store.first("SELECT id,full_name,avatar_url FROM users WHERE id=?",id).map(r->object("id",id,"fullName",r.text("full_name"),"avatarUrl",r.text("avatar_url"))).orElse(object("id",id,"fullName","Tài khoản đã đóng","avatarUrl",null));
    }
    public String subjectName(AssessmentDocument doc) { return catalog.subject(doc.subjectCode()).map(EduSubject::getName).orElse(null); }
    public Map<String,Object> learnerDocument(AssessmentDocument doc,long seed,boolean shuffle,String classId) {
        Map<String,Object> projection=LearnerProjection.project(doc,seed,shuffle,subjectName(doc)); Map<String,Object> images=new LinkedHashMap<>();
        for(var e:doc.assets().entrySet()) if("IMAGE".equals(e.getValue().type())) {
            var signed=media.signAssessmentAsset(e.getValue().assetId(),classId,"ASSESSMENT"); images.put(e.getKey(),object("url",signed.getDownloadUrl(),"alt",e.getValue().name()));
        }
        projection.put("images",images); return projection;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> mediaUrl(String id,String assetId,String user) {
        Row row=store.assessment(id,false); policy.requireAuthorRead(user,row.text("class_id")); AssessmentDocument doc=DocumentCodec.parse(row.text("content_json"));
        if(doc.assets().values().stream().noneMatch(a->a.assetId().equals(assetId))) throw new AppException(ErrorCode.NOT_FOUND,"Không tìm thấy tệp của đề");
        MediaAsset asset=media.getAsset(assetId); if(!row.text("class_id").equals(asset.getClassId())) bad("Tệp không thuộc lớp");
        var signed=media.signAssessmentAsset(assetId,row.text("class_id"),"ASSESSMENT"); return object("assetId",assetId,"url",signed.getDownloadUrl(),"expiresAt",signed.getExpiresAt());
    }
    private Map<String,Object> authoring(Row row,String user) {
        AssessmentDocument doc=DocumentCodec.parse(row.text("content_json"));
        return object("id",row.text("id"),"classId",row.text("class_id"),"status",row.text("status"),"revision",row.number("revision"),"latestVersionNo",row.number("latest_version_no"),
            "updatedAt",row.instant("updated_at"),"document",doc,"assets",assetStatuses(doc,row.text("class_id")),"canEdit",policy.can(user,row.text("class_id"),"EDIT"),"canAssign",policy.can(user,row.text("class_id"),"PUBLISH"));
    }
    private Map<String,Object> summary(Row row) {
        return object("id",row.text("id"),"classId",row.text("class_id"),"title",row.text("title"),"kind",row.text("kind"),"grade",row.data().get("grade"),"subjectCode",row.text("subject_code"),
            "subjectName",catalog.subject(row.text("subject_code")).map(EduSubject::getName).orElse(null),"status",row.text("status"),"revision",row.number("revision"),"latestVersionNo",row.number("latest_version_no"),
            "itemCount",row.number("item_count"),"totalPoints",row.decimal("total_points"),"updatedAt",row.instant("updated_at"),"createdBy",person(row.text("created_by")),
            "skills",row.text("skills").isBlank()?List.of():List.of(row.text("skills").split(" ")),"assignments",store.rows("SELECT * FROM assessment_assignments WHERE assessment_id=? ORDER BY created_at DESC",row.text("id")).stream().map(this::assignmentDto).toList());
    }
    private Map<String,Object> assetStatuses(AssessmentDocument doc,String classId) {
        Map<String,MediaAsset> found=media.getAssetsById(doc.assets().values().stream().map(AssetRef::assetId).toList()); Map<String,Object> out=new LinkedHashMap<>();
        for(var e:doc.assets().entrySet()) { MediaAsset a=found.get(e.getValue().assetId());
            boolean valid=a!=null && classId.equals(a.getClassId()) && "ASSESSMENT".equals(a.getUploadPurpose());
            out.put(e.getKey(),object("assetId",e.getValue().assetId(),"status",valid?a.getStatus():"MISSING","mime",valid?a.getMimeType():"","sizeBytes",valid?a.getSizeBytes():0)); }
        return out;
    }
    private void validateAssets(AssessmentDocument doc,String classId,boolean uploaded) {
        for(AssetRef ref:doc.assets().values()) {
            MediaAsset a=media.getAssetForUpdate(ref.assetId());
            if(!classId.equals(a.getClassId()) || !"ASSESSMENT".equals(a.getUploadPurpose())) bad("Tệp không thuộc ngữ liệu của lớp");
            if(uploaded && !"UPLOADED".equals(a.getStatus())) bad("Tệp đính kèm chưa tải lên hoàn tất");
            if("AUDIO".equals(ref.type()) && !a.getMimeType().startsWith("audio/") || "IMAGE".equals(ref.type()) && !MediaService.IMAGE_MIME_TYPES.contains(a.getMimeType())) bad("Loại tệp ngữ liệu không khớp");
        }
    }
    private void indexDraft(String id,String classId,AssessmentDocument doc,Instant now) {
        store.write("DELETE FROM assessment_media_refs WHERE assessment_id=?",id);
        for(AssetRef ref:doc.assets().values()) store.write("INSERT IGNORE INTO assessment_media_refs(assessment_id,media_asset_id) VALUES(?,?)",id,ref.assetId());
        store.write("DELETE FROM question_bank_items WHERE assessment_id=?",id);
        for(var ref:DocumentIndex.of(doc).items()) { ItemNode it=ref.item(); String excerpt=it.prompt().substring(0,Math.min(300,it.prompt().length()));
            store.write("INSERT INTO question_bank_items(id,class_id,assessment_id,item_id,group_title,type_code,grading_mode,prompt_excerpt,grade,subject_code,max_points,item_json,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),classId,id,it.id(),ref.group()==null?null:ref.group().title(),it.type(),it.mode(),excerpt,doc.grade(),doc.subjectCode(),it.points(),DocumentCodec.writeJson(it),time(now)); }
    }
    private static void editable(Row row) { if(!"DRAFT".equals(row.text("status"))) throw new AppException(ErrorCode.CONFLICT,"Đề đã lưu trữ"); }
    private static void revision(Row row,Integer revision) { if(revision==null || revision!=row.number("revision")) throw new AppException(ErrorCode.CONFLICT,"ASSESSMENT_REVISION_CONFLICT: đề đã được sửa ở phiên khác"); }
    private static void bad(String message) { throw new AppException(ErrorCode.BAD_REQUEST,message); }
}
