package com.classroom.modules.assessment.catalog;

import com.classroom.common.ApiResponse;
import com.classroom.config.CurrentUser;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.assessment.dto.CatalogDtos.CatalogDto;
import com.classroom.modules.assessment.dto.CatalogDtos.RubricDto;
import com.classroom.modules.assessment.dto.CatalogDtos.SubjectDto;
import com.classroom.modules.assessment.policy.AssessmentPolicy;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** D-34: the education catalog (any signed-in user) and the rubrics of a class (ASSESSMENT grant). */
@RestController
@RequestMapping("/api/v1")
public class AssessmentCatalogController {

    private final AssessmentCatalogService catalogService;
    private final AssessmentPolicy policy;

    public AssessmentCatalogController(AssessmentCatalogService catalogService, AssessmentPolicy policy) {
        this.catalogService = catalogService;
        this.policy = policy;
    }

    @GetMapping("/assessment-catalog")
    public ResponseEntity<ApiResponse<CatalogDto>> catalog() {
        return ResponseEntity.ok(ApiResponse.ok(catalogService.catalog()));
    }

    @GetMapping("/assessment-catalog/subjects")
    public ResponseEntity<ApiResponse<List<SubjectDto>>> subjects(@RequestParam(required = false) Integer grade) {
        return ResponseEntity.ok(ApiResponse.ok(catalogService.subjects(grade)));
    }

    @GetMapping("/classes/{classId}/rubrics")
    public ResponseEntity<ApiResponse<List<RubricDto>>> rubrics(@PathVariable String classId, @CurrentUser UserPrincipal principal) {
        policy.requireAnyStudioGrant(principal.getId(), classId);
        return ResponseEntity.ok(ApiResponse.ok(catalogService.rubricsForClass(classId)));
    }
    @PostMapping("/classes/{classId}/rubrics")
    public ResponseEntity<ApiResponse<RubricDto>> createRubric(@PathVariable String classId,@CurrentUser UserPrincipal principal,@RequestBody RubricDto body) {
        policy.require(principal.getId(),classId,"EDIT");
        return ResponseEntity.ok(ApiResponse.ok(catalogService.createRubric(classId,principal.getId(),body)));
    }
}
