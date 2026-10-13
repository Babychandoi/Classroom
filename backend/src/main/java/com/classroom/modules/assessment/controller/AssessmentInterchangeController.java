package com.classroom.modules.assessment.controller;

import com.classroom.common.*;
import com.classroom.config.*;
import com.classroom.modules.assessment.doc.*;
import com.classroom.modules.assessment.policy.AssessmentPolicy;
import com.classroom.modules.assessment.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.classroom.modules.assessment.doc.LearnerProjection.object;

@RestController
@RequestMapping("/api/v1")
public class AssessmentInterchangeController {
    private final AssessmentPolicy policy;private final DocumentValidator validator;private final AssessmentAuthoringService author;private final AssessmentMatrixService matrix;
    public AssessmentInterchangeController(AssessmentPolicy policy,DocumentValidator validator,AssessmentAuthoringService author,AssessmentMatrixService matrix){this.policy=policy;this.validator=validator;this.author=author;this.matrix=matrix;}
    @PostMapping("/classes/{classId}/assessment-import/preview")
    public ApiResponse<?> importPreview(@PathVariable String classId,@CurrentUser UserPrincipal user,@RequestBody JsonNode body){
        policy.require(user.getId(),classId,"CREATE");String encoded=body.path("data").asText();
        if(encoded.length()>14000000)throw new AppException(ErrorCode.BAD_REQUEST,"Tệp import tối đa 10 MB");
        byte[] bytes;try{bytes=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException e){throw new AppException(ErrorCode.BAD_REQUEST,"Nội dung tệp không hợp lệ");}
        var result=AssessmentInterchange.read(bytes,body.path("filename").asText("import.json"));validator.structure(result.document());
        var issues=validator.issues(result.document(),classId);return ApiResponse.ok(object("document",result.document(),"warnings",result.warnings(),"embeddedAssets",result.embeddedAssets(),"issues",issues,"itemCount",DocumentIndex.of(result.document()).itemCount(),"totalPoints",DocumentIndex.of(result.document()).total()));
    }
    @GetMapping("/classes/{classId}/assessment-import/template")
    public ApiResponse<?> template(@PathVariable String classId,@CurrentUser UserPrincipal user,@RequestParam(defaultValue="xlsx") String format){policy.require(user.getId(),classId,"CREATE");return ApiResponse.ok(file(AssessmentInterchange.write(AssessmentInterchange.read(AssessmentInterchange.template("json"),"template.json").document(),format),"mau-import"));}
    @GetMapping("/assessments/{id}/export")
    public ApiResponse<?> export(@PathVariable String id,@CurrentUser UserPrincipal user,@RequestParam(defaultValue="json") String format){var data=author.get(id,user.getId());AssessmentDocument doc=(AssessmentDocument)data.get("document");return ApiResponse.ok(file(AssessmentInterchange.write(doc,format),"de-classroom"));}
    @PostMapping("/classes/{classId}/assessment-matrix/preview")
    public ApiResponse<?> matrixPreview(@PathVariable String classId,@CurrentUser UserPrincipal user,@RequestBody JsonNode body){return ApiResponse.ok(matrix.preview(classId,user.getId(),body));}
    @PostMapping("/classes/{classId}/assessment-matrix")
    public ApiResponse<?> matrixCreate(@PathVariable String classId,@CurrentUser UserPrincipal user,@RequestBody JsonNode body){return ApiResponse.ok(matrix.create(classId,user.getId(),body));}
    @GetMapping("/classes/{classId}/question-suggestions")
    public ApiResponse<?> suggestions(@PathVariable String classId,@CurrentUser UserPrincipal user,@RequestParam(defaultValue="") String outcome,@RequestParam(required=false) String type,@RequestParam(required=false) Integer grade,@RequestParam(required=false) String subjectCode){if(outcome.length()>2000)throw new AppException(ErrorCode.BAD_REQUEST,"Từ khóa quá dài");return ApiResponse.ok(matrix.suggestions(classId,user.getId(),outcome,type,grade,subjectCode));}
    private static Map<String,Object> file(AssessmentInterchange.Exported export,String name){return object("data",Base64.getEncoder().encodeToString(export.bytes()),"mime",export.mime(),"filename",name+"."+export.extension(),"warnings",export.warnings());}
}
