package com.classroom.modules.assessment.catalog;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.assessment.dto.CatalogDtos.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** D-34: read side of the education catalog and the rubrics. */
@Service
public class AssessmentCatalogService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final EduLevelRepository levelRepository;
    private final EduGradeRepository gradeRepository;
    private final EduSubjectRepository subjectRepository;
    private final EduGradeSubjectRepository gradeSubjectRepository;
    private final EduSubjectComponentRepository componentRepository;
    private final EduLanguageRepository languageRepository;
    private final AssessmentRubricRepository rubricRepository;

    public AssessmentCatalogService(EduLevelRepository levelRepository, EduGradeRepository gradeRepository, EduSubjectRepository subjectRepository,
                                    EduGradeSubjectRepository gradeSubjectRepository, EduSubjectComponentRepository componentRepository,
                                    EduLanguageRepository languageRepository, AssessmentRubricRepository rubricRepository) {
        this.levelRepository = levelRepository;
        this.gradeRepository = gradeRepository;
        this.subjectRepository = subjectRepository;
        this.gradeSubjectRepository = gradeSubjectRepository;
        this.componentRepository = componentRepository;
        this.languageRepository = languageRepository;
        this.rubricRepository = rubricRepository;
    }

    public static List<TypeMeta> types() {
        return Arrays.stream(QuestionType.values())
                .map(t -> new TypeMeta(t.code(), t.key(), t.nameVi(), t.descVi(), t.family(), t.phase(), t.defaultMode(), t.container(), t.available()))
                .toList();
    }

    @Transactional(readOnly = true)
    public CatalogDto catalog() {
        Map<String, List<Integer>> gradesByLevel = new HashMap<>();
        gradeRepository.findAll().stream().sorted(Comparator.comparing(EduGrade::getGrade))
                .forEach(g -> gradesByLevel.computeIfAbsent(g.getLevelCode(), k -> new ArrayList<>()).add(g.getGrade()));
        List<LevelDto> levels = levelRepository.findAll().stream().sorted(Comparator.comparingInt(EduLevel::getSortOrder))
                .map(l -> new LevelDto(l.getCode(), l.getName(), gradesByLevel.getOrDefault(l.getCode(), List.of())))
                .toList();
        List<LanguageDto> languages = languageRepository.findAll().stream().sorted(Comparator.comparing(EduLanguage::getCode))
                .map(l -> new LanguageDto(l.getCode(), l.getName(), split(l.getVoices())))
                .toList();
        List<RubricDto> rubrics = rubricRepository.findBySystemPresetTrueOrderByIdAsc().stream().map(AssessmentCatalogService::toRubric).toList();
        return new CatalogDto(levels, subjects(null), languages, types(), rubrics);
    }

    /** All active subjects (every kind), or only those taught in {@code grade}. */
    @Transactional(readOnly = true)
    public List<SubjectDto> subjects(Integer grade) {
        Map<String, List<Integer>> gradesBySubject = new HashMap<>();
        gradeSubjectRepository.findAll().forEach(l -> gradesBySubject.computeIfAbsent(l.getSubjectCode(), k -> new ArrayList<>()).add(l.getGrade()));
        gradesBySubject.values().forEach(Collections::sort);
        Map<String, List<String>> componentsByParent = new HashMap<>();
        componentRepository.findAll().forEach(c -> componentsByParent.computeIfAbsent(c.getParentCode(), k -> new ArrayList<>()).add(c.getComponentCode()));
        return subjectRepository.findAll().stream()
                .filter(EduSubject::isActive)
                .filter(s -> grade == null || gradesBySubject.getOrDefault(s.getCode(), List.of()).contains(grade))
                .sorted(Comparator.comparingInt(EduSubject::getSortOrder).thenComparing(EduSubject::getCode))
                .map(s -> new SubjectDto(s.getCode(), s.getName(), s.getKind(), s.isIntegrated(), s.getGroupLabel(), s.getParentCode(),
                        componentsByParent.getOrDefault(s.getCode(), List.of()).stream().sorted().toList(),
                        gradesBySubject.getOrDefault(s.getCode(), List.of()), s.isLanguageRequired(), s.getDefaultLanguageCode(),
                        split(s.getSuggestedTypes()), s.getSortOrder()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<EduSubject> subject(String code) {
        return code == null ? Optional.empty() : subjectRepository.findById(code);
    }

    /** System presets plus the class's own rubrics. */
    @Transactional(readOnly = true)
    public List<RubricDto> rubricsForClass(String classId) {
        return rubricRepository.findAvailableForClass(classId).stream().map(AssessmentCatalogService::toRubric).toList();
    }

    /** A rubric usable in {@code classId} (system preset or the class's own), or empty. */
    @Transactional(readOnly = true)
    public Optional<RubricDto> rubric(String id, String classId) {
        if (id == null) return Optional.empty();
        return rubricRepository.findById(id)
                .filter(r -> r.isSystemPreset() || Objects.equals(r.getClassId(), classId))
                .map(AssessmentCatalogService::toRubric);
    }

    @Transactional
    public RubricDto createRubric(String classId,String user,RubricDto request) {
        if(request.title()==null || request.title().isBlank() || request.title().length()>200 || request.levels()==null || request.levels().isEmpty() || request.levels().size()>10
            || request.levels().stream().anyMatch(v->v==null || v.isBlank() || v.length()>200) || request.criteria()==null || request.criteria().isEmpty() || request.criteria().size()>30)
            throw new AppException(ErrorCode.BAD_REQUEST,"Rubric cần tên, 1–10 mức và 1–30 tiêu chí");
        Map<String,Integer> zero=new LinkedHashMap<>();
        for(var c:request.criteria()) {if(c.id()==null || !c.id().matches("[A-Za-z0-9_-]{1,40}") || c.title()==null || c.title().isBlank() || c.title().length()>200)
            throw new AppException(ErrorCode.BAD_REQUEST,"Tiêu chí rubric không hợp lệ");zero.put(c.id(),0);}
        com.classroom.modules.assessment.grading.RubricScorer.score(request,zero,java.math.BigDecimal.ONE);
        try {return toRubric(rubricRepository.save(new AssessmentRubric(UUID.randomUUID().toString(),classId,request.title(),JSON.writeValueAsString(request.levels()),JSON.writeValueAsString(request.criteria()),false,user)));}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new AppException(ErrorCode.BAD_REQUEST,"Không đọc được rubric");}
    }

    public static RubricDto toRubric(AssessmentRubric r) {
        try {
            List<String> levels = JSON.readValue(r.getLevelsJson(), new TypeReference<List<String>>() {});
            List<RubricCriterion> criteria = JSON.readValue(r.getCriteriaJson(), new TypeReference<List<RubricCriterion>>() {});
            return new RubricDto(r.getId(), r.getTitle(), levels, criteria, r.isSystemPreset());
        } catch (Exception e) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Rubric không đọc được: " + r.getId());
        }
    }

    private static List<String> split(String spaceSeparated) {
        if (spaceSeparated == null || spaceSeparated.isBlank()) return List.of();
        return Arrays.stream(spaceSeparated.trim().split("\\s+")).toList();
    }
}
