package com.classroom.integration;

import com.classroom.common.AppException;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.exam.model.Exam;
import com.classroom.modules.exam.model.Question;
import com.classroom.modules.exam.repository.ExamAttemptRepository;
import com.classroom.modules.exam.repository.ExamPublicationSnapshotRepository;
import com.classroom.modules.exam.repository.ExamRepository;
import com.classroom.modules.exam.repository.QuestionRepository;
import com.classroom.modules.exam.service.ExamService;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@SpringBootTest
@ActiveProfiles("integration")
class ExamPublicationSnapshotIntegrationTest {
    @Autowired ExamService service;
    @Autowired ExamRepository exams;
    @Autowired QuestionRepository questions;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    ExamPublicationSnapshotRepository snapshots;
    @Autowired ExamAttemptRepository attempts;
    @Autowired UserRepository users;
    @Autowired ClassroomRepository classrooms;
    @Autowired ClassMemberRepository members;
    @Autowired ObjectMapper mapper;

    private User user() {
        String id = UUID.randomUUID().toString();
        return users.save(new User(id, id + "@snapshot.test", "hash", "Snapshot test", "USER"));
    }
    private Classroom classroom(User owner) {
        var c = new Classroom();
        c.setOwnerId(owner.getId()); c.setSlug("snapshot-" + UUID.randomUUID());
        c.setTitle("Snapshot test"); c.setStatus("ACTIVE");
        c = classrooms.save(c);
        members.save(new ClassMember(c.getId(), owner.getId(), "OWNER"));
        return c;
    }

    @Test
    void publicationFreezesLearnerAndGradingContentAndStillChecksEntryAndLimits() throws Exception {
        var owner = user(); var learner = user(); var outsider = user(); var c = classroom(owner);
        members.save(new ClassMember(c.getId(), learner.getId(), "STUDENT"));
        var draft = service.createExam(c.getId(), new Exam(c.getId(), "Frozen publication", "ALL", 30), owner.getId());
        var q = service.addQuestion(draft.getId(),
                new Question(draft.getId(), "Original prompt", "ESSAY", 10, 1, "private rubric"), List.of(), owner.getId());
        assertFalse(snapshots.existsById(draft.getId()));
        service.publishExam(draft.getId(), owner.getId());
        var snapshot = snapshots.findById(draft.getId()).orElseThrow();
        assertFalse(snapshot.getLearnerJson().contains("private rubric"));
        assertTrue(snapshot.getGradingJson().contains("private rubric"));
        assertFalse(mapper.writeValueAsString(snapshot).contains("private rubric"));
        assertThrows(AppException.class, () -> service.startAttempt(draft.getId(), outsider.getId(), false));
        assertThrows(AppException.class, () -> service.addQuestion(draft.getId(), q, List.of(), owner.getId()));
        // Direct operator edits are outside authoring API; they cannot mutate an already published payload.
        q.setQuestionText("Operator changed source"); q.setAnswerKey("changed rubric"); questions.save(q);
        var attempt = service.startAttempt(draft.getId(), learner.getId(), false);
        assertEquals("Original prompt", attempt.getQuestions().get(0).getQuestionText());
        assertFalse(mapper.writeValueAsString(attempt).contains("private rubric"));
        var persisted = attempts.findById(attempt.getId()).orElseThrow();
        assertFalse(persisted.isNew());
        assertEquals(snapshot.getGradingJson(), persisted.getGradingSnapshotJson());
        assertFalse(mapper.readTree(mapper.writeValueAsString(persisted)).has("new"));
        persisted.setCancelReason("Lifecycle update verification"); attempts.save(persisted);
        assertEquals("Lifecycle update verification", attempts.findById(attempt.getId()).orElseThrow().getCancelReason());
        service.prepareLegacyPublicationSnapshot(draft.getId());
        assertEquals(snapshot.getGradingJson(), snapshots.findById(draft.getId()).orElseThrow().getGradingJson());
    }

    @Test
    void failedSnapshotWriteRollsBackPublicationAndCanBeRetried() {
        var owner = user(); var c = classroom(owner);
        var draft = service.createExam(c.getId(), new Exam(c.getId(), "Rollback publication", "ALL", 30), owner.getId());
        service.addQuestion(draft.getId(), new Question(draft.getId(), "Prompt", "ESSAY", 5, 1, "rubric"), List.of(), owner.getId());
        // Inject a repository failure; verify rollback against the real MySQL transaction.
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("snapshot write failure"))
                .when(snapshots).save(org.mockito.ArgumentMatchers.argThat(s -> draft.getId().equals(s.getExamId())));
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> service.publishExam(draft.getId(), owner.getId()));
            assertEquals("DRAFT", exams.findById(draft.getId()).orElseThrow().getStatus());
            assertFalse(snapshots.existsById(draft.getId()));
        } finally { org.mockito.Mockito.reset(snapshots); }
        service.publishExam(draft.getId(), owner.getId());
        assertEquals("PUBLISHED", exams.findById(draft.getId()).orElseThrow().getStatus());
        assertTrue(snapshots.existsById(draft.getId()));
    }

    @Test
    void legacyBackfillIsIdempotentAndLeavesDraftsAndEmptyExamsUntouched() {
        var owner = user(); var c = classroom(owner);
        var legacy = exams.save(new Exam(c.getId(), "Legacy", "ALL", 30));
        legacy.setStatus("PUBLISHED"); exams.save(legacy);
        questions.save(new Question(legacy.getId(), "Legacy prompt", "ESSAY", 5, 1, "rubric"));
        service.prepareLegacyPublicationSnapshot(legacy.getId());
        String frozen = snapshots.findById(legacy.getId()).orElseThrow().getGradingJson();
        service.prepareLegacyPublicationSnapshot(legacy.getId());
        assertEquals(frozen, snapshots.findById(legacy.getId()).orElseThrow().getGradingJson());
        var draft = new Exam(c.getId(), "Draft", "ALL", 30); draft.setStatus("DRAFT"); draft = exams.save(draft);
        questions.save(new Question(draft.getId(), "Draft prompt", "ESSAY", 5, 1, "rubric"));
        service.prepareLegacyPublicationSnapshot(draft.getId());
        assertFalse(snapshots.existsById(draft.getId()));
        var empty = new Exam(c.getId(), "Empty", "ALL", 30); empty.setStatus("PUBLISHED"); empty = exams.save(empty);
        service.prepareLegacyPublicationSnapshot(empty.getId());
        assertFalse(snapshots.existsById(empty.getId()));
        assertTrue(exams.findPublicationSnapshotBacklog("", org.springframework.data.domain.PageRequest.of(0, 1000))
                .contains(empty.getId()));
    }
}
