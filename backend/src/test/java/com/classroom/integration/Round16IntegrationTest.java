package com.classroom.integration;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.audit.model.AuditEvent;
import com.classroom.modules.audit.repository.AuditEventRepository;
import com.classroom.modules.classroom.dto.ClassMemberDto;
import com.classroom.modules.classroom.dto.ClassroomDto;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.model.StaffAssignment;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.classroom.repository.StaffAssignmentRepository;
import com.classroom.modules.classroom.service.ClassroomService;
import com.classroom.modules.commerce.model.Product;
import com.classroom.modules.commerce.repository.ProductRepository;
import com.classroom.modules.commerce.service.CommerceService;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.learning.service.LearningService;
import com.classroom.modules.ranking.dto.LeaderboardConfigRequest;
import com.classroom.modules.ranking.service.LeaderboardService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round 16 behaviours against a real MySQL: the V29 foreign key and its data repair, the visibility-in-SQL
 * class listing, membership-state reporting, and the new audit events. Every test builds its own
 * classroom so it cannot disturb the shared demo seed other suites use.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("integration")
class Round16IntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private ClassroomRepository classroomRepository;
    @Autowired private ClassMemberRepository memberRepository;
    @Autowired private StaffAssignmentRepository staffAssignmentRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private AuditEventRepository auditEventRepository;
    @Autowired private LearningService learningService;
    @Autowired private CommerceService commerceService;
    @Autowired private ClassroomService classroomService;
    @Autowired private LeaderboardService leaderboardService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;

    // ----- fixtures -----

    private User newUser(String prefix) {
        String unique = prefix + "-" + System.nanoTime();
        return userRepository.save(new User(UUID.randomUUID().toString(), unique + "@r16.test", "hash", unique, "USER"));
    }

    private Classroom newClass(User owner, String status) {
        Classroom c = new Classroom();
        c.setOwnerId(owner.getId());
        c.setSlug("r16-" + System.nanoTime());
        c.setTitle("Round 16");
        c.setStatus(status);
        c = classroomRepository.save(c);
        memberRepository.save(new ClassMember(c.getId(), owner.getId(), "OWNER"));
        return c;
    }

    private ClassMember join(Classroom c, User u, String state) {
        ClassMember m = new ClassMember(c.getId(), u.getId(), "STUDENT");
        m.setState(state);
        return memberRepository.save(m);
    }

    private Course draftCourse(Classroom c) {
        Course course = new Course(c.getId(), "Khóa R16 " + System.nanoTime(), "FREE");
        course.setStatus("DRAFT");
        return courseRepository.save(course);
    }

    private boolean auditHas(String classId, String action) {
        return auditEventRepository.findByClassIdOrderByCreatedAtDesc(classId).stream()
                .map(AuditEvent::getAction).anyMatch(action::equals);
    }

    private boolean fkExists() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema = DATABASE() "
                        + "AND table_name = 'products' AND constraint_name = 'fk_prod_target_course'", Integer.class);
        return n != null && n > 0;
    }

    private void runMigrationV29() throws Exception {
        // One connection: the script uses session variables (SET @ddl ...; PREPARE ...).
        try (Connection conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("db/migration/V29__products_target_course_foreign_key.sql"));
        }
    }

    // ----- R16-03 -----

    @Test
    @DisplayName("R16-03: V29 created fk_prod_target_course, and the database itself now refuses to delete a targeted course")
    void foreignKeyBlocksDeletingATargetedCourse() {
        assertTrue(fkExists(), "V29 must have added fk_prod_target_course");

        User owner = newUser("owner");
        Classroom c = newClass(owner, "ACTIVE");
        Course course = draftCourse(c);
        Product product = commerceService.createProduct(c.getId(), course.getId(), "Gói khóa", "d",
                new BigDecimal("100000"), 30, owner.getId());
        assertEquals(course.getId(), product.getTargetCourseId());

        // Bypass the service guard entirely: the constraint is the backstop.
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("DELETE FROM courses WHERE id = ?", course.getId()));
        assertTrue(courseRepository.existsById(course.getId()));
    }

    @Test
    @DisplayName("R16-03: deleteCourse refuses a DRAFT course a product still targets (400), and the product keeps its target")
    void deleteCourseRefusedWhileProductTargetsIt() {
        User owner = newUser("owner");
        Classroom c = newClass(owner, "ACTIVE");
        Course course = draftCourse(c);
        Product product = commerceService.createProduct(c.getId(), course.getId(), "Gói khóa", "d",
                new BigDecimal("100000"), 30, owner.getId());
        commerceService.publishProduct(product.getId(), owner.getId());

        AppException ex = assertThrows(AppException.class, () -> learningService.deleteCourse(course.getId(), owner.getId()));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(courseRepository.existsById(course.getId()));
        assertEquals(course.getId(), productRepository.findById(product.getId()).orElseThrow().getTargetCourseId());
        assertFalse(auditHas(c.getId(), "COURSE_DELETE"));
    }

    @Test
    @DisplayName("R16-03: a course with no product can still be deleted")
    void deleteCourseWithoutProductStillWorks() {
        User owner = newUser("owner");
        Classroom c = newClass(owner, "ACTIVE");
        Course course = draftCourse(c);

        learningService.deleteCourse(course.getId(), owner.getId());

        assertFalse(courseRepository.existsById(course.getId()));
        assertTrue(auditHas(c.getId(), "COURSE_DELETE"));
    }

    @Test
    @DisplayName("R16-03: V29 repairs dangling / blank target_course_id BEFORE adding the FK, keeps NULL legal, and is replay-safe")
    void migrationRepairsDanglingProductsThenAddsForeignKey() throws Exception {
        User owner = newUser("owner");
        Classroom c = newClass(owner, "ACTIVE");
        Course realCourse = draftCourse(c);
        String danglingId = UUID.randomUUID().toString();
        String blankId = UUID.randomUUID().toString();
        String validId = UUID.randomUUID().toString();
        String nullId = UUID.randomUUID().toString();

        try {
            // Recreate the pre-V29 world: no FK, so the bad rows can exist.
            jdbcTemplate.execute("ALTER TABLE products DROP FOREIGN KEY fk_prod_target_course");
            jdbcTemplate.update("INSERT INTO products (id, class_id, target_course_id, title, status) VALUES (?, ?, ?, 'dangling', 'PUBLISHED')",
                    danglingId, c.getId(), "ghost-" + UUID.randomUUID().toString().substring(0, 20));
            jdbcTemplate.update("INSERT INTO products (id, class_id, target_course_id, title, status) VALUES (?, ?, '', 'blank', 'PUBLISHED')",
                    blankId, c.getId());
            jdbcTemplate.update("INSERT INTO products (id, class_id, target_course_id, title, status) VALUES (?, ?, ?, 'valid', 'PUBLISHED')",
                    validId, c.getId(), realCourse.getId());
            jdbcTemplate.update("INSERT INTO products (id, class_id, target_course_id, title, status) VALUES (?, ?, NULL, 'null-target', 'DRAFT')",
                    nullId, c.getId());
            assertFalse(fkExists());

            runMigrationV29();

            assertTrue(fkExists(), "the FK must be (re)created");
            // Dangling: taken off sale, pointer cleared.
            Product dangling = productRepository.findById(danglingId).orElseThrow();
            assertEquals("ARCHIVED", dangling.getStatus());
            assertNull(dangling.getTargetCourseId());
            // Blank pointer: normalised to NULL, status untouched (it was never course-bound).
            Product blank = productRepository.findById(blankId).orElseThrow();
            assertEquals("PUBLISHED", blank.getStatus());
            assertNull(blank.getTargetCourseId());
            // A valid link and a NULL target are left exactly as they were.
            Product valid = productRepository.findById(validId).orElseThrow();
            assertEquals("PUBLISHED", valid.getStatus());
            assertEquals(realCourse.getId(), valid.getTargetCourseId());
            Product nullTarget = productRepository.findById(nullId).orElseThrow();
            assertEquals("DRAFT", nullTarget.getStatus());
            assertNull(nullTarget.getTargetCourseId());

            // Replaying the whole script is a no-op (the DDL implicitly commits, so a half-applied run must be re-runnable).
            runMigrationV29();
            assertTrue(fkExists());
            assertEquals("ARCHIVED", productRepository.findById(danglingId).orElseThrow().getStatus());
        } finally {
            if (!fkExists()) {
                runMigrationV29();
            }
        }
    }

    // ----- R16-01 / R16-08: the class listing is visibility-aware in SQL and reports the membership state -----

    @Test
    @DisplayName("R16-08/R16-01: listing applies visibility in SQL (owner / ACTIVE member / staff see an ARCHIVED class; REMOVED, BLOCKED and strangers do not) and reports memberState")
    void listingVisibilityAndMemberState() {
        User owner = newUser("owner");
        User activeMember = newUser("active");
        User removed = newUser("removed");
        User blocked = newUser("blocked");
        User staff = newUser("staff");
        User stranger = newUser("stranger");

        Classroom open = newClass(owner, "ACTIVE");
        Classroom archived = newClass(owner, "ARCHIVED");
        join(archived, activeMember, "ACTIVE");
        join(archived, removed, "REMOVED");
        join(archived, blocked, "BLOCKED");
        StaffAssignment assignment = new StaffAssignment(archived.getId(), staff.getId());
        assignment.setStatus("ACTIVE");
        staffAssignmentRepository.save(assignment);

        java.util.function.BiPredicate<String, String> sees = (userId, classId) ->
                classroomService.getAllClassrooms(userId, 0, 100).stream().anyMatch(d -> d.getId().equals(classId));

        // The ACTIVE class is public to everyone, anonymous included.
        assertTrue(sees.test(null, open.getId()));
        assertTrue(sees.test(stranger.getId(), open.getId()));
        // The ARCHIVED class only to owner, its ACTIVE members and its staff.
        assertFalse(sees.test(null, archived.getId()));
        assertFalse(sees.test(stranger.getId(), archived.getId()));
        assertFalse(sees.test(removed.getId(), archived.getId()));
        assertFalse(sees.test(blocked.getId(), archived.getId()));
        assertTrue(sees.test(owner.getId(), archived.getId()));
        assertTrue(sees.test(activeMember.getId(), archived.getId()));
        assertTrue(sees.test(staff.getId(), archived.getId()));

        // memberState / isMember / userRole / memberCount on the ACTIVE class as seen by each kind of caller.
        join(open, activeMember, "ACTIVE");
        join(open, removed, "REMOVED");
        join(open, blocked, "BLOCKED");
        ClassroomDto asActive = classroomService.getBySlug(open.getSlug(), activeMember.getId());
        assertTrue(asActive.isMember());
        assertEquals("ACTIVE", asActive.getMemberState());
        assertEquals("STUDENT", asActive.getUserRole());

        ClassroomDto asRemoved = classroomService.getBySlug(open.getSlug(), removed.getId());
        assertFalse(asRemoved.isMember());
        assertEquals("REMOVED", asRemoved.getMemberState());
        assertEquals("GUEST", asRemoved.getUserRole());

        ClassroomDto asBlocked = classroomService.getBySlug(open.getSlug(), blocked.getId());
        assertFalse(asBlocked.isMember());
        assertEquals("BLOCKED", asBlocked.getMemberState());

        ClassroomDto asStranger = classroomService.getBySlug(open.getSlug(), stranger.getId());
        assertFalse(asStranger.isMember());
        assertEquals("NONE", asStranger.getMemberState());

        // owner row + the one ACTIVE member; REMOVED / BLOCKED are not counted.
        assertEquals(2L, asActive.getMemberCount());

        // The same values come out of the batched listing path.
        ClassroomDto listedForRemoved = classroomService.getAllClassrooms(removed.getId(), 0, 100).stream()
                .filter(d -> d.getId().equals(open.getId())).findFirst().orElseThrow();
        assertFalse(listedForRemoved.isMember());
        assertEquals("REMOVED", listedForRemoved.getMemberState());
        assertEquals(2L, listedForRemoved.getMemberCount());
        assertNotNull(listedForRemoved.getOwnerName());
        ClassroomDto listedForActive = classroomService.getAllClassrooms(activeMember.getId(), 0, 100).stream()
                .filter(d -> d.getId().equals(open.getId())).findFirst().orElseThrow();
        assertTrue(listedForActive.isMember());
        assertEquals("ACTIVE", listedForActive.getMemberState());
    }

    @Test
    @DisplayName("R16-08: a page is bounded - size is clamped to 100, page 1 continues where page 0 ended, no row repeats")
    void listingPagesAreBoundedAndDisjoint() {
        User owner = newUser("owner");
        for (int i = 0; i < 3; i++) {
            newClass(owner, "ACTIVE");
        }
        List<ClassroomDto> page0 = classroomService.getAllClassrooms(null, 0, 2);
        List<ClassroomDto> page1 = classroomService.getAllClassrooms(null, 1, 2);

        assertEquals(2, page0.size());
        assertEquals(2, page1.size());
        assertTrue(page0.stream().noneMatch(a -> page1.stream().anyMatch(b -> b.getId().equals(a.getId()))));
        assertTrue(classroomService.getAllClassrooms(null, 0, 10_000).size() <= 100);
        assertTrue(classroomService.getAllClassrooms(null).size() <= ClassroomService.DEFAULT_PAGE_SIZE);
    }

    // ----- R16-07 -----

    @Test
    @DisplayName("R16-07: the owner sees the real name of a PRIVATE BLOCKED member in the member listing; an ordinary member does not even see the row")
    void managersSeePrivateNonActiveMemberIdentity() {
        User owner = newUser("owner");
        User peer = newUser("peer");
        User blocked = newUser("blocked");
        blocked.setProfileVisibility("PRIVATE");
        userRepository.save(blocked);
        Classroom c = newClass(owner, "ACTIVE");
        join(c, peer, "ACTIVE");
        join(c, blocked, "BLOCKED");

        List<ClassMemberDto> forOwner = classroomService.getClassMembers(c.getId(), owner.getId());
        ClassMemberDto row = forOwner.stream().filter(m -> "BLOCKED".equals(m.getState())).findFirst().orElseThrow();
        assertEquals(blocked.getFullName(), row.getUserFullName());
        assertEquals(blocked.getId(), row.getUserId());

        List<ClassMemberDto> forPeer = classroomService.getClassMembers(c.getId(), peer.getId());
        assertTrue(forPeer.stream().noneMatch(m -> "BLOCKED".equals(m.getState())));
    }

    // ----- R16-02 / R16-09: audit events are persisted -----

    @Test
    @DisplayName("R16-02: publishing a course and a product leaves COURSE_PUBLISH / PRODUCT_PUBLISH audit rows; publishing an ARCHIVED one is refused")
    void publishIsAuditedAndArchivedIsRefused() {
        User owner = newUser("owner");
        Classroom c = newClass(owner, "ACTIVE");
        Course course = draftCourse(c);

        learningService.publishCourse(course.getId(), owner.getId());
        assertTrue(auditHas(c.getId(), "COURSE_PUBLISH"));
        assertEquals("PUBLISHED", courseRepository.findById(course.getId()).orElseThrow().getStatus());

        learningService.archiveCourse(course.getId(), owner.getId());
        AppException ex = assertThrows(AppException.class, () -> learningService.publishCourse(course.getId(), owner.getId()));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertEquals("ARCHIVED", courseRepository.findById(course.getId()).orElseThrow().getStatus());

        Product product = commerceService.createProduct(c.getId(), null, "Gói PRO", "d", new BigDecimal("50000"), 30, owner.getId());
        commerceService.publishProduct(product.getId(), owner.getId());
        assertTrue(auditHas(c.getId(), "PRODUCT_PUBLISH"));
        commerceService.archiveProduct(product.getId(), owner.getId());
        assertThrows(AppException.class, () -> commerceService.publishProduct(product.getId(), owner.getId()));
        assertEquals("ARCHIVED", productRepository.findById(product.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("R16-09: configure and rebuild each persist a LEADERBOARD_* audit row with a before/after summary")
    void leaderboardConfigureAndRebuildAreAudited() {
        User owner = newUser("owner");
        Classroom c = newClass(owner, "ACTIVE");

        leaderboardService.configure(c.getId(), owner.getId(), new LeaderboardConfigRequest(
                List.of(new LeaderboardConfigRequest.Tier("Đồng", 0, null, null),
                        new LeaderboardConfigRequest.Tier("Bạc", 100, null, null)),
                List.of()));
        leaderboardService.rebuildLeaderboard(c.getId(), owner.getId());

        List<AuditEvent> events = auditEventRepository.findByClassIdOrderByCreatedAtDesc(c.getId());
        AuditEvent configure = events.stream().filter(e -> "LEADERBOARD_CONFIGURE".equals(e.getAction())).findFirst().orElseThrow();
        assertTrue(configure.getDetailsJson().contains("Bạc:100"), configure.getDetailsJson());
        assertTrue(configure.getDetailsJson().contains("\"before\""), configure.getDetailsJson());
        assertTrue(events.stream().anyMatch(e -> "LEADERBOARD_REBUILD".equals(e.getAction())));
    }
}
