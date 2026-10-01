package com.classroom.integration;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.model.StaffAssignment;
import com.classroom.modules.classroom.model.StaffPermission;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.classroom.repository.StaffAssignmentRepository;
import com.classroom.modules.classroom.repository.StaffPermissionRepository;
import com.classroom.modules.classroom.service.MemberService;
import com.classroom.modules.commerce.model.Entitlement;
import com.classroom.modules.commerce.model.Product;
import com.classroom.modules.commerce.model.ProductPrice;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import com.classroom.modules.commerce.repository.ProductPriceRepository;
import com.classroom.modules.commerce.repository.ProductRepository;
import com.classroom.modules.exam.dto.ExamAttemptDto;
import com.classroom.modules.exam.dto.SubmitAttemptRequest;
import com.classroom.modules.exam.model.Exam;
import com.classroom.modules.exam.model.ExamAttempt;
import com.classroom.modules.exam.model.Question;
import com.classroom.modules.exam.repository.ExamAttemptRepository;
import com.classroom.modules.exam.repository.ExamRepository;
import com.classroom.modules.exam.repository.QuestionRepository;
import com.classroom.modules.exam.service.ExamService;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.learning.dto.CourseDto;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.model.Lesson;
import com.classroom.modules.learning.model.LessonProgress;
import com.classroom.modules.learning.model.Section;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.learning.repository.LessonProgressRepository;
import com.classroom.modules.learning.repository.LessonRepository;
import com.classroom.modules.learning.repository.SectionRepository;
import com.classroom.modules.learning.service.AssignmentService;
import com.classroom.modules.learning.service.LearningService;
import com.classroom.modules.ranking.dto.LeaderboardEntryDto;
import com.classroom.modules.ranking.model.LeaderboardEntry;
import com.classroom.modules.ranking.repository.LeaderboardEntryRepository;
import com.classroom.modules.ranking.service.LeaderboardService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round 14 behaviours against a real MySQL (Flyway schema, real JPQL, real unique indexes) - the
 * mock-based unit tests prove the rules, this proves the new repository queries and the
 * attempt_number / lesson cascade interactions actually hold on the production database engine.
 * Every test builds its own classroom so it cannot disturb the shared demo seed other suites use.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("integration")
class Round14BehaviourIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private ClassroomRepository classroomRepository;
    @Autowired private ClassMemberRepository memberRepository;
    @Autowired private StaffAssignmentRepository staffAssignmentRepository;
    @Autowired private StaffPermissionRepository staffPermissionRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private LessonProgressRepository progressRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductPriceRepository priceRepository;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private ExamRepository examRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private ExamAttemptRepository attemptRepository;
    @Autowired private LeaderboardEntryRepository leaderboardEntryRepository;
    @Autowired private LearningService learningService;
    @Autowired private AssignmentService assignmentService;
    @Autowired private ExamService examService;
    @Autowired private MemberService memberService;
    @Autowired private LeaderboardService leaderboardService;

    // ----- fixtures -----

    private User newUser(String prefix) {
        String unique = prefix + "-" + System.nanoTime();
        return userRepository.save(new User(java.util.UUID.randomUUID().toString(), unique + "@r14.test", "hash", unique, "USER"));
    }

    private Classroom newClass(User owner) {
        Classroom c = new Classroom();
        c.setOwnerId(owner.getId());
        c.setSlug("r14-" + System.nanoTime());
        c.setTitle("Round 14");
        c.setStatus("ACTIVE");
        c = classroomRepository.save(c);
        memberRepository.save(new ClassMember(c.getId(), owner.getId(), "OWNER"));
        return c;
    }

    private ClassMember join(Classroom c, User u, String role) {
        return memberRepository.save(new ClassMember(c.getId(), u.getId(), role));
    }

    private Course publishedFreeCourse(Classroom c) {
        Course course = new Course(c.getId(), "Khóa R14 " + System.nanoTime(), "FREE");
        course.setStatus("PUBLISHED");
        return courseRepository.save(course);
    }

    private Exam publishedExam(Classroom c, int attemptLimit) {
        Exam exam = new Exam(c.getId(), "Kỳ thi R14", "ALL", 30);
        exam.setAttemptLimit(attemptLimit);
        exam.setStatus("PUBLISHED");
        exam = examRepository.save(exam);
        questionRepository.save(new Question(exam.getId(), "1 + 1 = ?", "MULTIPLE_CHOICE", 10, 1, "B"));
        return exam;
    }

    private static void assertCode(ErrorCode expected, org.junit.jupiter.api.function.Executable call) {
        AppException ex = assertThrows(AppException.class, call);
        assertEquals(expected, ex.getErrorCode(), ex.getMessage());
    }

    // ----- R14-01 -----

    @Test
    @DisplayName("R14-01: a MEMBER:EDIT delegate cannot remove a STAFF member; the OWNER can; a plain student can be removed by the delegate")
    void delegateCannotRemoveStaff() {
        User owner = newUser("owner");
        User delegate = newUser("delegate");
        User otherStaff = newUser("staff");
        User student = newUser("student");
        Classroom c = newClass(owner);
        join(c, delegate, "STAFF");
        join(c, otherStaff, "STAFF");
        join(c, student, "STUDENT");
        StaffAssignment delegateAssignment = staffAssignmentRepository.save(new StaffAssignment(c.getId(), delegate.getId()));
        staffPermissionRepository.save(new StaffPermission(delegateAssignment.getId(), "MEMBER", "EDIT", null));
        StaffAssignment otherAssignment = staffAssignmentRepository.save(new StaffAssignment(c.getId(), otherStaff.getId()));
        staffPermissionRepository.save(new StaffPermission(otherAssignment.getId(), "EXAM", "VIEW", null));

        assertCode(ErrorCode.FORBIDDEN, () -> memberService.removeMember(c.getId(), otherStaff.getId(), delegate.getId()));
        assertCode(ErrorCode.FORBIDDEN, () -> memberService.blockMember(c.getId(), otherStaff.getId(), delegate.getId()));
        assertEquals("ACTIVE", memberRepository.findByClassIdAndUserId(c.getId(), otherStaff.getId()).orElseThrow().getState());
        assertTrue(staffAssignmentRepository.findByClassIdAndUserId(c.getId(), otherStaff.getId()).isPresent());
        assertEquals(1, staffPermissionRepository.findByAssignmentId(otherAssignment.getId()).size());

        assertCode(ErrorCode.BAD_REQUEST, () -> memberService.removeMember(c.getId(), delegate.getId(), delegate.getId()));

        assertEquals("REMOVED", memberService.removeMember(c.getId(), student.getId(), delegate.getId()).getState());

        assertEquals("REMOVED", memberService.removeMember(c.getId(), otherStaff.getId(), owner.getId()).getState());
        assertTrue(staffAssignmentRepository.findByClassIdAndUserId(c.getId(), otherStaff.getId()).isEmpty());
    }

    // ----- R14-02 / R14-03 / R14-04 -----

    @Test
    @DisplayName("R14-03: progress denominators/numerators count only visible lessons on MySQL, in grouped queries, and can reach 100%")
    void progressCountsOnlyVisibleLessons() {
        User owner = newUser("owner");
        User student = newUser("student");
        Classroom c = newClass(owner);
        join(c, student, "STUDENT");
        Course course = publishedFreeCourse(c);
        Section liveSection = sectionRepository.save(new Section(course.getId(), "Chương sống", 0));
        Section oldSection = sectionRepository.save(new Section(course.getId(), "Chương cũ", 1));
        oldSection.setArchived(true);
        sectionRepository.save(oldSection);
        Lesson visible = lessonRepository.save(new Lesson(liveSection.getId(), course.getId(), "Bài hiện", "TEXT", 0));
        Lesson archived = new Lesson(liveSection.getId(), course.getId(), "Bài lưu trữ", "TEXT", 1);
        archived.setArchived(true);
        archived = lessonRepository.save(archived);
        Lesson inArchivedSection = lessonRepository.save(new Lesson(oldSection.getId(), course.getId(), "Bài trong chương cũ", "TEXT", 0));
        for (Lesson l : List.of(visible, archived, inArchivedSection)) {
            progressRepository.save(new LessonProgress(student.getId(), l.getId(), course.getId(), c.getId()));
        }

        var totals = lessonRepository.countVisibleByCourseIdIn(List.of(course.getId()));
        var done = progressRepository.countCompletedVisibleByUserAndCourseIdIn(student.getId(), List.of(course.getId()));
        assertEquals(1, totals.size());
        assertEquals(1L, totals.get(0).getTotal());
        assertEquals(1L, done.get(0).getTotal());
        assertEquals(1L, progressRepository.countCompletedVisibleByUserIdAndClassId(student.getId(), c.getId()));

        CourseDto dto = learningService.getCoursesByClass(c.getId(), student.getId()).get(0);
        assertEquals(1, dto.getTotalLessons());
        assertEquals(1, dto.getCompletedLessons());
        assertEquals(100.0, dto.getProgressPercentage(), 0.0001);

        // Restoring the archived section brings its lesson back into both sides of the fraction.
        oldSection.setArchived(false);
        sectionRepository.save(oldSection);
        CourseDto restored = learningService.getCoursesByClass(c.getId(), student.getId()).get(0);
        assertEquals(2, restored.getTotalLessons());
        assertEquals(2, restored.getCompletedLessons());
    }

    @Test
    @DisplayName("R14-02 + R14-04: archiving hides an assignment from the learner everywhere, and a lesson with submissions cannot be deleted")
    void archivedAssignmentHiddenAndUndeletableWithSubmissions() {
        User owner = newUser("owner");
        User student = newUser("student");
        Classroom c = newClass(owner);
        join(c, student, "STUDENT");
        Course course = publishedFreeCourse(c);
        Section section = sectionRepository.save(new Section(course.getId(), "Chương", 0));
        Lesson assignment = lessonRepository.save(new Lesson(section.getId(), course.getId(), "Bài tập", "ASSIGNMENT", 0));

        assignmentService.submit(assignment.getId(), student.getId(), "Bài làm của tôi");

        assertCode(ErrorCode.CONFLICT, () -> learningService.deleteLesson(assignment.getId(), owner.getId()));
        assertCode(ErrorCode.CONFLICT, () -> learningService.deleteSection(section.getId(), owner.getId()));
        assertTrue(lessonRepository.findById(assignment.getId()).isPresent(), "graded work must survive a refused delete");

        learningService.archiveSection(section.getId(), true, owner.getId());

        assertCode(ErrorCode.NOT_FOUND, () -> learningService.getLesson(assignment.getId(), student.getId()));
        assertCode(ErrorCode.NOT_FOUND, () -> learningService.markLessonProgress(assignment.getId(), student.getId(), true));
        assertCode(ErrorCode.NOT_FOUND, () -> learningService.getLessonQuestions(assignment.getId(), student.getId()));
        assertCode(ErrorCode.NOT_FOUND, () -> learningService.askQuestion(assignment.getId(), student.getId(), "Hỏi?"));
        assertCode(ErrorCode.NOT_FOUND, () -> assignmentService.submit(assignment.getId(), student.getId(), "Nộp thêm"));
        assertCode(ErrorCode.NOT_FOUND, () -> assignmentService.mySubmissions(assignment.getId(), student.getId()));
        // A course editor (the OWNER) can still open it to restore it.
        assertEquals(assignment.getId(), learningService.getLesson(assignment.getId(), owner.getId()).getId());
    }

    // ----- R14-11 -----

    @Test
    @DisplayName("R14-11: findActiveUserIdsByClassId and the class board drop REMOVED members; ranks are recomputed")
    void removedMembersDropOffBoard() {
        User owner = newUser("owner");
        User keep = newUser("keep");
        User removed = newUser("removed");
        Classroom c = newClass(owner);
        join(c, keep, "STUDENT");
        ClassMember removedMember = join(c, removed, "STUDENT");
        leaderboardEntryRepository.save(new LeaderboardEntry(c.getId(), removed.getId(), 500, "Vàng"));
        leaderboardEntryRepository.save(new LeaderboardEntry(c.getId(), keep.getId(), 100, "Đồng"));

        assertTrue(memberRepository.findActiveUserIdsByClassId(c.getId()).containsAll(List.of(owner.getId(), keep.getId(), removed.getId())));
        removedMember.setState("REMOVED");
        memberRepository.save(removedMember);
        List<String> active = memberRepository.findActiveUserIdsByClassId(c.getId());
        assertTrue(active.contains(keep.getId()));
        assertFalse(active.contains(removed.getId()));

        List<LeaderboardEntryDto> board = leaderboardService.getLeaderboard(c.getId(), owner.getId());
        assertEquals(1, board.size());
        assertEquals(1, board.get(0).getRank());
        assertEquals(100, board.get(0).getTotalPoints());
    }

    // ----- R14-12 -----

    @Test
    @DisplayName("R14-12: a not-yet-started entitlement is reported as OWNED_UPCOMING with its start date")
    void upcomingEntitlementIsOwnedUpcoming() {
        User owner = newUser("owner");
        User student = newUser("student");
        Classroom c = newClass(owner);
        join(c, student, "STUDENT");
        Course course = new Course(c.getId(), "Khóa trả phí", "PURCHASE_REQUIRED");
        course.setStatus("PUBLISHED");
        course = courseRepository.save(course);
        Product product = new Product(c.getId(), course.getId(), "Gói khóa", "Mô tả");
        product.setStatus("PUBLISHED");
        product = productRepository.save(product);
        priceRepository.save(new ProductPrice(product.getId(), new BigDecimal("100000"), "VND", 30));
        course.setProductId(product.getId());
        courseRepository.save(course);
        Instant startsAt = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        entitlementRepository.save(new Entitlement(student.getId(), c.getId(), product.getId(), course.getId(),
                startsAt, startsAt.plus(30, ChronoUnit.DAYS)));

        CourseDto dto = learningService.getCoursesByClass(c.getId(), student.getId()).get(0);

        assertFalse(dto.isCanLearn());
        assertEquals("OWNED_UPCOMING", dto.getAccessReason());
        assertEquals(startsAt, dto.getAccessStartsAt());
    }

    // ----- R14-05 / R14-14 / R14-15 / R14-13 -----

    @Test void batchedExamListingPreservesCountsAndClosedResumeOnMySql() {
        User owner = newUser("batch-owner"), student = newUser("batch-student"), other = newUser("batch-other");
        Classroom c = newClass(owner);
        join(c, student, "STUDENT"); join(c, other, "STUDENT");
        Exam first = publishedExam(c, 1), second = publishedExam(c, 1);
        var cancelled = examService.startAttempt(first.getId(), student.getId(), false);
        examService.cancelAttempt(cancelled.getId(), owner.getId(), "Batch fixture");
        examService.startAttempt(first.getId(), student.getId(), false);
        examService.startAttempt(second.getId(), owner.getId(), true);
        examService.startAttempt(second.getId(), other.getId(), false);
        examService.closeExam(first.getId(), owner.getId());
        var listed = examService.getExamsByClass(c.getId(), student.getId()).stream()
                .collect(java.util.stream.Collectors.toMap(com.classroom.modules.exam.dto.ExamDto::getId, e -> e));
        assertEquals(2, listed.size());
        assertEquals(1, listed.get(first.getId()).getUserAttemptsCount());
        assertTrue(listed.get(first.getId()).isCanEnter(), "Closed exam allows this learner's live attempt");
        assertEquals(0, listed.get(second.getId()).getUserAttemptsCount(), "Other learners and preview do not consume this learner's budget");
        assertTrue(listed.get(second.getId()).isCanEnter());
        for (var dto : listed.values()) { assertEquals(1, dto.getQuestionCount()); assertNull(dto.getQuestions()); }
        var ownerListing = examService.getExamsByClass(c.getId(), owner.getId());
        assertTrue(ownerListing.stream().allMatch(dto -> dto.getUserAttemptsCount() == 0), "Preview attempts excluded");
    }

    @Test
    @DisplayName("R14-14: a CANCELLED attempt does not consume the limit, and the next attempt_number does not collide on MySQL")
    void cancelledAttemptDoesNotConsumeLimit() {
        User owner = newUser("owner");
        User student = newUser("student");
        Classroom c = newClass(owner);
        join(c, student, "STUDENT");
        Exam exam = publishedExam(c, 1);

        ExamAttemptDto first = examService.startAttempt(exam.getId(), student.getId(), false);
        examService.cancelAttempt(first.getId(), owner.getId(), "Bắt đầu nhầm");
        assertEquals(0L, attemptRepository.countAttemptsTowardLimit(exam.getId(), student.getId()));
        assertEquals(1, attemptRepository.findMaxAttemptNumber(exam.getId(), student.getId()));

        ExamAttemptDto second = examService.startAttempt(exam.getId(), student.getId(), false);

        assertNotEquals(first.getId(), second.getId());
        assertEquals(2, attemptRepository.findById(second.getId()).orElseThrow().getAttemptNumber());
        assertEquals(1L, attemptRepository.countAttemptsTowardLimit(exam.getId(), student.getId()));
        // The limit (1) is now genuinely used up by the live attempt: a further NEW attempt is refused
        // once that one is submitted.
        examService.submitAttempt(second.getId(), student.getId(), new SubmitAttemptRequest());
        assertCode(ErrorCode.EXAM_ATTEMPT_LIMIT_REACHED, () -> examService.startAttempt(exam.getId(), student.getId(), false));
    }

    @Test
    @DisplayName("R14-05: after the exam is closed a running attempt can be resumed, autosaved and submitted; a new attempt is refused")
    void closedExamAllowsRunningAttemptToFinish() {
        User owner = newUser("owner");
        User student = newUser("student");
        User latecomer = newUser("latecomer");
        Classroom c = newClass(owner);
        join(c, student, "STUDENT");
        join(c, latecomer, "STUDENT");
        Exam exam = publishedExam(c, 1);
        String questionId = questionRepository.findByExamIdOrderByPositionAsc(exam.getId()).get(0).getId();

        ExamAttemptDto started = examService.startAttempt(exam.getId(), student.getId(), false);
        examService.closeExam(exam.getId(), owner.getId());
        assertEquals("CLOSED", examRepository.findById(exam.getId()).orElseThrow().getStatus());

        ExamAttemptDto resumed = examService.startAttempt(exam.getId(), student.getId(), false, true);
        assertEquals(started.getId(), resumed.getId());
        examService.saveAnswers(started.getId(), student.getId(), Map.of(questionId, "B"));
        SubmitAttemptRequest request = new SubmitAttemptRequest();
        request.setAnswers(Map.of(questionId, "B"));
        ExamAttemptDto submitted = examService.submitAttempt(started.getId(), student.getId(), request);
        assertEquals("PUBLISHED", submitted.getStatus());
        assertEquals(0, new BigDecimal("100").compareTo(submitted.getScore()));

        assertCode(ErrorCode.EXAM_NOT_OPEN, () -> examService.startAttempt(exam.getId(), latecomer.getId(), false));
    }

    @Test
    @DisplayName("R14-15: starting a preview twice returns the same preview attempt; only one IN_PROGRESS preview row exists")
    void previewStartIsIdempotent() {
        User owner = newUser("owner");
        Classroom c = newClass(owner);
        Exam exam = publishedExam(c, 1);

        ExamAttemptDto first = examService.startAttempt(exam.getId(), owner.getId(), true);
        ExamAttemptDto second = examService.startAttempt(exam.getId(), owner.getId(), true);

        assertEquals(first.getId(), second.getId());
        assertEquals(1, second.getQuestions().size());
        List<ExamAttempt> previews = attemptRepository.findByExamIdAndUserIdOrderByStartedAtDesc(exam.getId(), owner.getId()).stream()
                .filter(a -> a.isPreview() && "IN_PROGRESS".equals(a.getStatus()))
                .toList();
        assertEquals(1, previews.size());
    }

    @Test
    @DisplayName("R14-13: an ARCHIVED class accepts no NEW attempt, but a running attempt can still be resumed")
    void archivedClassBlocksNewAttemptsOnly() {
        User owner = newUser("owner");
        User runner = newUser("runner");
        User newcomer = newUser("newcomer");
        Classroom c = newClass(owner);
        join(c, runner, "STUDENT");
        join(c, newcomer, "STUDENT");
        Exam exam = publishedExam(c, 1);

        ExamAttemptDto running = examService.startAttempt(exam.getId(), runner.getId(), false);
        c.setStatus("ARCHIVED");
        classroomRepository.save(c);

        AppException ex = assertThrows(AppException.class, () -> examService.startAttempt(exam.getId(), newcomer.getId(), false));
        assertEquals(ErrorCode.EXAM_NOT_OPEN, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("Lớp học đã được lưu trữ"));
        assertEquals(running.getId(), examService.startAttempt(exam.getId(), runner.getId(), false, true).getId());
    }
}
