package com.classroom.modules.assessment.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * D-34: loads the education catalog (levels, grades, subjects, grade-subject links, integrated-subject components, languages, rubric presets)
 * from {@code assessment/catalog-v1.json}. The catalog is data, not code, and it must exist on every database: production (Flyway) and the H2
 * test schema (no Flyway) alike, so it is seeded here rather than by a migration.
 *
 * <p>Idempotent and additive: a row whose key already exists is never updated or deleted (an operator may have edited it); only missing keys
 * are inserted. Two nodes starting at once are tolerated: each missing row is inserted in its own REQUIRES_NEW transaction and a duplicate-key
 * failure (the other node won) is ignored.</p>
 */
@Component
@ConditionalOnProperty(name = "classroom.assessment.catalog-seed.enabled", havingValue = "true", matchIfMissing = true)
public class AssessmentCatalogSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AssessmentCatalogSeeder.class);
    static final String RESOURCE = "assessment/catalog-v1.json";

    private final EduLevelRepository levelRepository;
    private final EduGradeRepository gradeRepository;
    private final EduSubjectRepository subjectRepository;
    private final EduGradeSubjectRepository gradeSubjectRepository;
    private final EduSubjectComponentRepository componentRepository;
    private final EduLanguageRepository languageRepository;
    private final AssessmentRubricRepository rubricRepository;
    private final TransactionTemplate rowTx;
    private final ObjectMapper mapper = new ObjectMapper();

    public AssessmentCatalogSeeder(EduLevelRepository levelRepository, EduGradeRepository gradeRepository, EduSubjectRepository subjectRepository,
                                   EduGradeSubjectRepository gradeSubjectRepository, EduSubjectComponentRepository componentRepository,
                                   EduLanguageRepository languageRepository, AssessmentRubricRepository rubricRepository,
                                   PlatformTransactionManager transactionManager) {
        this.levelRepository = levelRepository;
        this.gradeRepository = gradeRepository;
        this.subjectRepository = subjectRepository;
        this.gradeSubjectRepository = gradeSubjectRepository;
        this.componentRepository = componentRepository;
        this.languageRepository = languageRepository;
        this.rubricRepository = rubricRepository;
        this.rowTx = new TransactionTemplate(transactionManager);
        this.rowTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void run(String... args) {
        try {
            int inserted = seed();
            if (inserted > 0) log.info("Assessment catalog: {} rows inserted from {}", inserted, RESOURCE);
        } catch (RuntimeException e) {
            // The catalog is needed by the Question Builder only; a failure here must not stop the rest of the application.
            log.error("Assessment catalog seeding failed", e);
        }
    }

    /** Inserts every missing catalog row; returns how many rows were inserted (0 on a second run). */
    public int seed() {
        JsonNode root;
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            root = mapper.readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read " + RESOURCE, e);
        }
        int inserted = 0;

        Set<String> levels = new HashSet<>();
        levelRepository.findAll().forEach(l -> levels.add(l.getCode()));
        Set<Integer> grades = new HashSet<>();
        gradeRepository.findAll().forEach(g -> grades.add(g.getGrade()));
        for (JsonNode level : root.path("levels")) {
            String code = level.path("code").asText();
            if (!levels.contains(code)) {
                inserted += insert(() -> levelRepository.save(new EduLevel(code, level.path("name").asText(), level.path("sortOrder").asInt())));
            }
            for (int g = level.path("gradeFrom").asInt(); g <= level.path("gradeTo").asInt(); g++) {
                if (!grades.contains(g)) {
                    int grade = g;
                    inserted += insert(() -> gradeRepository.save(new EduGrade(grade, code, "Lớp " + grade)));
                }
            }
        }

        Set<String> subjects = new HashSet<>();
        subjectRepository.findAll().forEach(s -> subjects.add(s.getCode()));
        Set<EduGradeSubject.Key> links = new HashSet<>();
        gradeSubjectRepository.findAll().forEach(l -> links.add(new EduGradeSubject.Key(l.getGrade(), l.getSubjectCode())));
        Set<EduSubjectComponent.Key> components = new HashSet<>();
        componentRepository.findAll().forEach(c -> components.add(new EduSubjectComponent.Key(c.getParentCode(), c.getComponentCode())));

        int sort = 0;
        for (JsonNode subject : root.path("subjects")) {
            sort += 10;
            String code = subject.path("code").asText();
            String suggested = subject.path("suggestedTypes").asText("");
            int from = subject.path("gradeFrom").asInt();
            int to = subject.path("gradeTo").asInt();
            boolean integrated = subject.path("integrated").asBoolean(false);
            if (!subjects.contains(code)) {
                EduSubject row = new EduSubject(code, subject.path("name").asText(), subject.path("kind").asText("SUBJECT"), integrated,
                        text(subject, "groupLabel"), null, subject.path("languageRequired").asBoolean(false),
                        text(subject, "defaultLanguageCode"), suggested, sort);
                inserted += insert(() -> subjectRepository.save(row));
            }
            inserted += linkGrades(code, from, to, links);
            int componentSort = sort;
            for (JsonNode component : subject.path("components")) {
                componentSort += 1;
                String componentCode = component.path("code").asText();
                if (!subjects.contains(componentCode)) {
                    EduSubject row = new EduSubject(componentCode, component.path("name").asText(), "COMPONENT", false,
                            text(subject, "groupLabel"), code, false, null, suggested, componentSort);
                    inserted += insert(() -> subjectRepository.save(row));
                }
                if (!components.contains(new EduSubjectComponent.Key(code, componentCode))) {
                    inserted += insert(() -> componentRepository.save(new EduSubjectComponent(code, componentCode)));
                }
                inserted += linkGrades(componentCode, from, to, links);
            }
        }

        Set<String> languages = new HashSet<>();
        languageRepository.findAll().forEach(l -> languages.add(l.getCode()));
        for (JsonNode language : root.path("languages")) {
            String code = language.path("code").asText();
            if (languages.contains(code)) continue;
            List<String> voices = new ArrayList<>();
            language.path("voices").forEach(v -> voices.add(v.asText()));
            inserted += insert(() -> languageRepository.save(new EduLanguage(code, language.path("name").asText(), String.join(" ", voices))));
        }

        Set<String> rubrics = new HashSet<>();
        rubricRepository.findAll().forEach(r -> rubrics.add(r.getId()));
        for (JsonNode rubric : root.path("rubrics")) {
            String id = rubric.path("id").asText();
            if (rubrics.contains(id)) continue;
            String levelsJson = rubric.path("levels").toString();
            String criteriaJson = rubric.path("criteria").toString();
            inserted += insert(() -> rubricRepository.save(new AssessmentRubric(id, null, rubric.path("title").asText(), levelsJson, criteriaJson, true, null)));
        }
        return inserted;
    }

    private int linkGrades(String subjectCode, int from, int to, Set<EduGradeSubject.Key> links) {
        int inserted = 0;
        for (int g = from; g <= to; g++) {
            if (links.contains(new EduGradeSubject.Key(g, subjectCode))) continue;
            int grade = g;
            inserted += insert(() -> gradeSubjectRepository.save(new EduGradeSubject(grade, subjectCode)));
        }
        return inserted;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    /** One row in its own transaction; a duplicate key (another node inserted it first) counts as "already there". */
    private int insert(Runnable save) {
        try {
            rowTx.executeWithoutResult(status -> save.run());
            return 1;
        } catch (DataIntegrityViolationException duplicate) {
            return 0;
        }
    }
}
