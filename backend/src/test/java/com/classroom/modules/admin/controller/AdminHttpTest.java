package com.classroom.modules.admin.controller;

import com.classroom.modules.audit.model.AuditEvent;
import com.classroom.modules.audit.repository.AuditEventRepository;
import com.classroom.modules.classroom.controller.ClassContentHttpTestBase;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.commerce.model.Order;
import com.classroom.modules.commerce.repository.OrderRepository;
import com.classroom.modules.exam.model.Exam;
import com.classroom.modules.exam.model.Question;
import com.classroom.modules.exam.repository.ExamAttemptRepository;
import com.classroom.modules.exam.service.ExamService;
import com.classroom.modules.identity.controller.AuthController;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.RefreshTokenRepository;
import com.classroom.modules.identity.service.RefreshTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-29: {@code /api/v1/admin/**} through the real servlet chain (Spring Security -> JWT filter -> controller -> services -> H2): access rules,
 * the ban (sessions revoked, next request 401), the safety rules, class suspension (hidden from everybody but the owner, frozen, restorable to
 * the previous status), the overview counters and the audit trail.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminHttpTest extends ClassContentHttpTestBase {

    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private AuditEventRepository auditRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ExamService examService;
    @Autowired private ExamAttemptRepository attemptRepository;

    private User admin;
    private User plain;

    private static final Map<String, String> REASON = Map.of("reason", "Kiểm thử quản trị");

    @BeforeAll
    void admins() {
        admin = user("admin");
        admin.setRole("PLATFORM_ADMIN");
        admin = userRepository.save(admin);
        plain = user("plain");
    }

    private User adminUser(String tag) {
        User u = user(tag);
        u.setRole("PLATFORM_ADMIN");
        return userRepository.save(u);
    }

    private List<AuditEvent> auditOf(String action, String targetId) {
        return auditRepository.findAll().stream()
                .filter(e -> action.equals(e.getAction()) && targetId.equals(e.getTargetId())).toList();
    }

    // -------------------------------------------------------------------------------------------------------------------------- access

    @Test
    @DisplayName("every admin route: guest 401, plain USER 403 (FORBIDDEN, nothing changes), PLATFORM_ADMIN 200")
    void everyRouteIsAdminOnly() throws Exception {
        User target = user("access-target");
        Classroom c = newClass(user("access-owner"), "Lop truy cap", "PRIVATE");
        record Route(HttpMethod method, String path, Object body) {}
        List<Route> reads = List.of(
                new Route(HttpMethod.GET, "/api/v1/admin/overview", null),
                new Route(HttpMethod.GET, "/api/v1/admin/users", null),
                new Route(HttpMethod.GET, "/api/v1/admin/users?q=access&status=ACTIVE&role=USER&sort=name&page=0&size=5", null),
                new Route(HttpMethod.GET, "/api/v1/admin/users/" + target.getId(), null),
                new Route(HttpMethod.GET, "/api/v1/admin/classes", null),
                new Route(HttpMethod.GET, "/api/v1/admin/classes?status=ACTIVE&visibility=PRIVATE&accessType=FREE&sort=members", null),
                new Route(HttpMethod.GET, "/api/v1/admin/classes/" + c.getId(), null),
                new Route(HttpMethod.GET, "/api/v1/admin/audit", null),
                new Route(HttpMethod.GET, "/api/v1/admin/audit?action=ADMIN_USER_BAN&from=2020-01-01&to=2100-01-01&size=500", null));
        List<Route> writes = List.of(
                new Route(HttpMethod.POST, "/api/v1/admin/users/" + target.getId() + "/ban", REASON),
                new Route(HttpMethod.POST, "/api/v1/admin/users/" + target.getId() + "/unban", REASON),
                new Route(HttpMethod.PUT, "/api/v1/admin/users/" + target.getId() + "/role", Map.of("role", "PLATFORM_ADMIN", "reason", "x")),
                new Route(HttpMethod.POST, "/api/v1/admin/classes/" + c.getId() + "/suspend", REASON),
                new Route(HttpMethod.POST, "/api/v1/admin/classes/" + c.getId() + "/restore", REASON));
        List<Route> all = new ArrayList<>(reads);
        all.addAll(writes);
        for (Route r : all) {
            Answer guest = call(r.method(), r.path(), r.body(), null);
            assertEquals(401, guest.status(), "guest " + r.path());
            Answer user = call(r.method(), r.path(), r.body(), plain);
            assertEquals(403, user.status(), "USER " + r.path());
            assertEquals("FORBIDDEN", user.code(), r.path());
            assertFalse(user.body().contains(target.getEmail()), "nothing leaks to a USER");
        }
        assertEquals("ACTIVE", userRepository.findById(target.getId()).orElseThrow().getStatus(), "the refused writes changed nothing");
        assertEquals("USER", userRepository.findById(target.getId()).orElseThrow().getRole());
        assertEquals("ACTIVE", classroomRepository.findById(c.getId()).orElseThrow().getStatus());
        for (Route r : reads) {
            Answer a = call(r.method(), r.path(), null, admin);
            assertEquals(200, a.status(), "admin " + r.path() + " -> " + a.body());
            assertFalse(a.body().contains("passwordHash") || a.body().contains("password_hash"), "no password hash: " + r.path());
        }
        // the writes, in an order where each one is valid
        for (Route r : writes) {
            Answer a = call(r.method(), r.path(), r.body(), admin);
            assertEquals(200, a.status(), "admin " + r.path() + " -> " + a.body());
        }
    }

    @Test
    @DisplayName("a demoted admin loses access on the very next request (the role is read from the database, not the token)")
    void roleIsLiveFromTheDatabase() throws Exception {
        User other = adminUser("live-role");
        assertEquals(200, get("/api/v1/admin/overview", other).status());
        assertEquals(200, put("/api/v1/admin/users/" + other.getId() + "/role", Map.of("role", "USER", "reason", "Hết nhiệm kỳ"), admin).status());
        assertEquals(403, get("/api/v1/admin/overview", other).status());
        assertEquals(1, auditOf("ADMIN_USER_ROLE", other.getId()).size());
    }

    // ----------------------------------------------------------------------------------------------------------------------------- users

    @Test
    @DisplayName("ban: ACTIVE -> BANNED, every refresh-token family revoked, the next request with the old access token is 401, refresh is refused; audit carries the reason; unban restores ACTIVE")
    void banRevokesSessions() throws Exception {
        User victim = user("ban-victim");
        String raw1 = refreshTokenService.issue(victim.getId()).rawToken();
        String raw2 = refreshTokenService.issue(victim.getId()).rawToken();
        assertEquals(200, get("/api/v1/me", victim).status());

        Answer banned = post("/api/v1/admin/users/" + victim.getId() + "/ban", Map.of("reason", "Spam quảng cáo"), admin);
        assertEquals(200, banned.status(), banned.body());
        assertEquals("BANNED", banned.data().path("status").asText());

        assertEquals("BANNED", userRepository.findById(victim.getId()).orElseThrow().getStatus());
        assertTrue(refreshTokenRepository.findAll().stream().filter(t -> t.getUserId().equals(victim.getId()))
                .allMatch(t -> t.getRevokedAt() != null), "both login families are revoked");
        assertEquals(401, get("/api/v1/me", victim).status(), "the old access token stops working at once");
        for (String raw : List.of(raw1, raw2)) {
            int status = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/refresh").cookie(new Cookie(AuthController.REFRESH_COOKIE_NAME, raw)))
                    .andReturn().getResponse().getStatus();
            assertEquals(401, status, "refresh refused");
        }

        List<AuditEvent> rows = auditOf("ADMIN_USER_BAN", victim.getId());
        assertEquals(1, rows.size());
        AuditEvent row = rows.get(0);
        assertEquals(admin.getId(), row.getActorId());
        assertEquals(null, row.getClassId(), "user actions have no class");
        JsonNode details = JSON.readTree(row.getDetailsJson());
        assertEquals("Spam quảng cáo", details.path("reason").asText());
        assertEquals(2, details.path("revokedSessions").asInt());

        assertEquals(409, post("/api/v1/admin/users/" + victim.getId() + "/ban", REASON, admin).status(), "already banned");
        Answer unbanned = post("/api/v1/admin/users/" + victim.getId() + "/unban", Map.of("reason", "Đã xác minh"), admin);
        assertEquals(200, unbanned.status());
        assertEquals("ACTIVE", unbanned.data().path("status").asText());
        assertEquals(200, get("/api/v1/me", victim).status());
        assertEquals(409, post("/api/v1/admin/users/" + victim.getId() + "/unban", REASON, admin).status(), "not banned any more");
        assertEquals(1, auditOf("ADMIN_USER_UNBAN", victim.getId()).size());
    }

    @Test
    @DisplayName("safety rules: self-ban and self-demotion 400, a DELETED account 409, the reason is mandatory (1..500), unknown user 404, bad role 400")
    void safetyRules() throws Exception {
        Answer selfBan = post("/api/v1/admin/users/" + admin.getId() + "/ban", REASON, admin);
        assertEquals(400, selfBan.status());
        assertEquals(400, put("/api/v1/admin/users/" + admin.getId() + "/role", Map.of("role", "USER", "reason", "x"), admin).status());
        assertEquals("ACTIVE", userRepository.findById(admin.getId()).orElseThrow().getStatus());
        assertEquals("PLATFORM_ADMIN", userRepository.findById(admin.getId()).orElseThrow().getRole());

        User deleted = user("deleted");
        deleted.setStatus("DELETED");
        userRepository.save(deleted);
        assertEquals(409, post("/api/v1/admin/users/" + deleted.getId() + "/ban", REASON, admin).status());
        assertEquals(409, post("/api/v1/admin/users/" + deleted.getId() + "/unban", REASON, admin).status());
        assertEquals(409, put("/api/v1/admin/users/" + deleted.getId() + "/role", Map.of("role", "PLATFORM_ADMIN", "reason", "x"), admin).status());

        User target = user("reason");
        assertEquals(400, post("/api/v1/admin/users/" + target.getId() + "/ban", Map.of(), admin).status());
        assertEquals(400, post("/api/v1/admin/users/" + target.getId() + "/ban", Map.of("reason", "   "), admin).status());
        assertEquals(400, post("/api/v1/admin/users/" + target.getId() + "/ban", Map.of("reason", "x".repeat(501)), admin).status());
        assertEquals(200, post("/api/v1/admin/users/" + target.getId() + "/ban", Map.of("reason", "x".repeat(500)), admin).status());
        assertEquals(400, put("/api/v1/admin/users/" + target.getId() + "/role", Map.of("role", "OWNER", "reason", "x"), admin).status());
        assertEquals(400, put("/api/v1/admin/users/" + target.getId() + "/role", Map.of("role", "USER"), admin).status());
        assertEquals(404, post("/api/v1/admin/users/" + UUID.randomUUID() + "/ban", REASON, admin).status());
        assertEquals(400, get("/api/v1/admin/users?status=GONE", admin).status());
        assertEquals(400, get("/api/v1/admin/users?q=" + "a".repeat(101), admin).status());
    }

    @Test
    @DisplayName("users list: search by e-mail / name, filters, batched counts; detail lists owned classes and the user's recent audit rows")
    void usersListAndDetail() throws Exception {
        String tag = "lst" + UUID.randomUUID().toString().substring(0, 6);
        User owner = user(tag + "-owner");
        Classroom c1 = newClass(owner, "Lop cua " + tag, "PUBLIC");
        newClass(owner, "Lop thu hai " + tag, "PRIVATE");
        User learner = user(tag + "-learner");
        member(c1, learner, "ACTIVE");

        Answer page = get("/api/v1/admin/users?q=" + tag + "&sort=oldest&size=10", admin);
        assertEquals(200, page.status());
        assertEquals(2, page.data().path("totalElements").asInt());
        assertEquals(1, page.data().path("totalPages").asInt());
        JsonNode first = page.data().path("content").get(0);
        assertEquals(owner.getId(), first.path("id").asText());
        assertEquals(2, first.path("ownedClassCount").asInt());
        assertEquals(0, first.path("membershipCount").asInt(), "owning is not a membership");
        JsonNode second = page.data().path("content").get(1);
        assertEquals(1, second.path("membershipCount").asInt());
        assertEquals("USER", second.path("role").asText());
        assertTrue(second.has("lastLoginAt"));

        Answer byName = get("/api/v1/admin/users?q=D27%20" + tag + "-LEARNER", admin);
        assertEquals(1, byName.data().path("totalElements").asInt(), "name match is case-insensitive");

        post("/api/v1/admin/users/" + learner.getId() + "/ban", Map.of("reason", "Vi phạm " + tag), admin);
        assertEquals(1, get("/api/v1/admin/users?q=" + tag + "&status=BANNED", admin).data().path("totalElements").asInt());

        Answer detail = get("/api/v1/admin/users/" + owner.getId(), admin);
        assertEquals(2, detail.data().path("ownedClasses").size());
        assertTrue(detail.data().path("ownedClasses").get(0).has("slug"));
        Answer learnerDetail = get("/api/v1/admin/users/" + learner.getId(), admin);
        JsonNode audit = learnerDetail.data().path("recentAudit");
        assertEquals("ADMIN_USER_BAN", audit.get(0).path("action").asText());
        assertEquals(admin.getId(), audit.get(0).path("actor").path("id").asText());
        assertEquals("Vi phạm " + tag, audit.get(0).path("details").path("reason").asText());
        assertEquals(404, get("/api/v1/admin/users/" + UUID.randomUUID(), admin).status());
    }

    // --------------------------------------------------------------------------------------------------------------------------- classes

    @Test
    @DisplayName("suspend: hidden (404, same as a missing class) from guests, strangers, members and staff; the owner reads it with the reason and cannot change anything; joins, orders, attempts, posts, events refused; restore returns the previous status")
    void suspendAndRestore() throws Exception {
        User owner = user("sus-owner");
        User learner = user("sus-member");
        User stranger = user("sus-stranger");
        User staffer = user("sus-staff");
        Classroom c = newClass(owner, "Lop bi tam khoa", "PUBLIC");
        member(c, learner, "ACTIVE");
        staff(c, staffer, "CLASS:EDIT", "FEED:CREATE", "EVENT:CREATE", "MEMBER:EDIT");
        Exam exam = examService.createExam(c.getId(), new Exam(c.getId(), "De tam khoa", "ALL", 30), owner.getId());
        examService.addQuestion(exam.getId(), new Question(exam.getId(), "Cau hoi", "ESSAY", 10, 1, "rubric"), List.of(), owner.getId());
        examService.publishExam(exam.getId(), owner.getId());
        String classPath = "/api/v1/classes/" + c.getId();
        assertEquals(200, get(classPath, learner).status());

        Answer suspended = post("/api/v1/admin/classes/" + c.getId() + "/suspend", Map.of("reason", "Nội dung vi phạm bản quyền"), admin);
        assertEquals(200, suspended.status(), suspended.body());
        assertEquals("SUSPENDED", suspended.data().path("status").asText());
        assertEquals("Nội dung vi phạm bản quyền", suspended.data().path("suspendedReason").asText());
        assertEquals(409, post("/api/v1/admin/classes/" + c.getId() + "/suspend", REASON, admin).status(), "already suspended");

        // hidden: the same answer as an id that does not exist
        Answer missing = get("/api/v1/classes/" + UUID.randomUUID(), learner);
        for (User viewer : new User[]{null, stranger, learner, staffer}) {
            Answer a = get(classPath, viewer);
            assertEquals(404, a.status(), "viewer " + (viewer == null ? "guest" : viewer.getEmail()));
            assertEquals(missing.code(), a.code());
            assertFalse(a.body().contains("Lop bi tam khoa"));
            assertFalse(get("/api/v1/classes?size=100", viewer).body().contains(c.getId()), "not listed");
            assertEquals(404, get(classPath + "/about", viewer).status());
            assertEquals(404, get(classPath + "/posts", viewer).status());
            assertEquals(404, get(classPath + "/events", viewer).status());
            assertEquals(404, get(classPath + "/blog-posts", viewer).status());
        }
        for (User viewer : new User[]{learner, staffer, stranger}) {
            assertEquals(404, get(classPath + "/members", viewer).status());
            assertEquals(404, post(classPath + "/join", null, viewer).status(), "no joins");
            assertEquals(404, post("/api/v1/orders", Map.of("classId", c.getId(), "productId", "p", "idempotencyKey", "k-" + UUID.randomUUID()),
                    viewer).status(), "no orders");
        }
        Answer staffWrite = put(classPath, Map.of("title", "Doi ten"), staffer);
        assertTrue(staffWrite.status() == 403 || staffWrite.status() == 404, staffWrite.body());
        long attemptsBefore = attemptRepository.count();
        Answer attempt = post("/api/v1/exams/" + exam.getId() + "/attempts", null, learner);
        assertTrue(attempt.status() >= 400, "no attempts: " + attempt.body());
        assertEquals(attemptsBefore, attemptRepository.count());

        // the owner: read-only, with the reason
        Answer own = get(classPath, owner);
        assertEquals(200, own.status());
        assertEquals("SUSPENDED", own.data().path("status").asText());
        assertEquals("Nội dung vi phạm bản quyền", own.data().path("suspendedReason").asText());
        assertNotNull(own.data().path("suspendedAt").asText(null));
        assertTrue(get("/api/v1/classes?size=100", owner).body().contains(c.getId()), "listed for the owner");
        assertEquals(200, get(classPath + "/members", owner).status());
        assertEquals(200, get("/api/v1/studio/classes/" + c.getId() + "/overview", owner).status());
        assertEquals(200, get(classPath + "/invites", owner).status(), "a read gated on MEMBER:EDIT stays readable");
        Answer reactivate = put(classPath + "/status", Map.of("status", "ACTIVE"), owner);
        assertEquals(409, reactivate.status(), "the owner cannot lift a suspension");
        assertEquals(409, put(classPath + "/status", Map.of("status", "ARCHIVED"), owner).status());
        assertEquals(409, put(classPath, Map.of("title", "Doi ten"), owner).status());
        assertEquals(409, post(classPath + "/posts", Map.of("title", "x", "contentMarkdown", "y", "visibility", "FREE"), owner).status());
        assertEquals(409, post(classPath + "/blog-posts", Map.of("title", "x", "contentMarkdown", "y", "audience", "PUBLIC"), owner).status());
        assertEquals(409, post(classPath + "/events", Map.of("title", "x", "format", "ONLINE", "startsAt", "2030-01-01T10:00:00Z",
                "endsAt", "2030-01-01T11:00:00Z", "audience", "PUBLIC"), owner).status());
        assertEquals(409, post(classPath + "/invites", Map.of(), owner).status());
        assertEquals(409, post(classPath + "/media/upload-intents", Map.of("filename", "a.pdf", "mimeType", "application/pdf",
                "sizeBytes", 10, "purpose", "DOCUMENT"), owner).status());
        assertEquals(409, post(classPath + "/courses", Map.of("title", "t", "accessMode", "FREE"), owner).status());
        assertEquals("SUSPENDED", classroomRepository.findById(c.getId()).orElseThrow().getStatus());

        // the admin list / detail see it, with counts and the audit
        Answer row = get("/api/v1/admin/classes?status=SUSPENDED&q=" + c.getSlug(), admin);
        assertEquals(1, row.data().path("totalElements").asInt());
        assertEquals(owner.getEmail(), row.data().path("content").get(0).path("owner").path("email").asText());
        Answer detail = get("/api/v1/admin/classes/" + c.getId(), admin);
        assertEquals(1, detail.data().path("counts").path("exams").asInt());
        assertEquals("ADMIN_CLASS_SUSPEND", detail.data().path("recentAudit").get(0).path("action").asText());
        assertEquals(c.getTitle(), detail.data().path("recentAudit").get(0).path("classTitle").asText());

        // restore: back to ACTIVE, everybody sees it again
        Answer restored = post("/api/v1/admin/classes/" + c.getId() + "/restore", Map.of("reason", "Đã gỡ nội dung"), admin);
        assertEquals(200, restored.status());
        assertEquals("ACTIVE", restored.data().path("status").asText());
        assertTrue(restored.data().path("suspendedReason").isNull());
        assertEquals(409, post("/api/v1/admin/classes/" + c.getId() + "/restore", REASON, admin).status(), "not suspended");
        assertEquals(200, get(classPath, learner).status());
        assertEquals(200, get(classPath, null).status());
        Classroom after = classroomRepository.findById(c.getId()).orElseThrow();
        assertEquals(null, after.getSuspendedReason());
        assertEquals(null, after.getStatusBeforeSuspend());

        List<AuditEvent> suspend = auditOf("ADMIN_CLASS_SUSPEND", c.getId());
        assertEquals(1, suspend.size());
        assertEquals(c.getId(), suspend.get(0).getClassId());
        assertEquals("Nội dung vi phạm bản quyền", JSON.readTree(suspend.get(0).getDetailsJson()).path("reason").asText());
        assertEquals("ACTIVE", JSON.readTree(suspend.get(0).getDetailsJson()).path("statusBefore").asText());
        assertEquals("Đã gỡ nội dung", JSON.readTree(auditOf("ADMIN_CLASS_RESTORE", c.getId()).get(0).getDetailsJson()).path("reason").asText());
    }

    @Test
    @DisplayName("an ARCHIVED class suspended and restored goes back to ARCHIVED (status_before_suspend)")
    void restoreReturnsThePreviousStatus() throws Exception {
        User owner = user("arch-owner");
        Classroom c = newClass(owner, "Lop luu tru", "PUBLIC");
        assertEquals(200, put("/api/v1/classes/" + c.getId() + "/status", Map.of("status", "ARCHIVED"), owner).status());
        assertEquals(200, post("/api/v1/admin/classes/" + c.getId() + "/suspend", REASON, admin).status());
        assertEquals("ARCHIVED", classroomRepository.findById(c.getId()).orElseThrow().getStatusBeforeSuspend());
        Answer restored = post("/api/v1/admin/classes/" + c.getId() + "/restore", REASON, admin);
        assertEquals("ARCHIVED", restored.data().path("status").asText());
        assertEquals(404, post("/api/v1/admin/classes/" + UUID.randomUUID() + "/suspend", REASON, admin).status());
    }

    // -------------------------------------------------------------------------------------------------------------------------- overview

    @Test
    @DisplayName("overview: the counters move exactly by a small fixture (users, classes, members, orders, revenue); 30 zero-filled signup days")
    void overviewCounts() throws Exception {
        JsonNode before = get("/api/v1/admin/overview", admin).data();

        User a = user("ov-a");
        User b = user("ov-b");
        b.setStatus("BANNED");
        userRepository.save(b);
        Classroom priv = newClass(a, "Lop tong quan", "PRIVATE");
        Classroom pub = newClass(a, "Lop tong quan 2", "PUBLIC");
        member(pub, b, "ACTIVE");
        ClassMember pending = member(pub, user("ov-pending"), "PENDING");
        assertNotNull(pending.getId());
        Order paid = new Order("OV-" + UUID.randomUUID(), a.getId(), pub.getId(), new BigDecimal("150000.00"), "VND", "MOCK");
        paid.setStatus("PAID");
        paid.setPaidAt(Instant.now().minus(2, ChronoUnit.DAYS));
        orderRepository.save(paid);
        Order old = new Order("OV-" + UUID.randomUUID(), a.getId(), pub.getId(), new BigDecimal("99000.00"), "VND", "MOCK");
        old.setStatus("PAID");
        old.setPaidAt(Instant.now().minus(40, ChronoUnit.DAYS));
        orderRepository.save(old);
        Order refunded = new Order("OV-" + UUID.randomUUID(), a.getId(), pub.getId(), new BigDecimal("50000.00"), "VND", "MOCK");
        refunded.setStatus("REFUNDED");
        refunded.setPaidAt(Instant.now().minus(1, ChronoUnit.DAYS));
        orderRepository.save(refunded);
        Order open = new Order("OV-" + UUID.randomUUID(), a.getId(), pub.getId(), new BigDecimal("10000.00"), "VND", "MOCK");
        orderRepository.save(open);
        post("/api/v1/admin/classes/" + priv.getId() + "/suspend", REASON, admin);

        Answer res = get("/api/v1/admin/overview", admin);
        assertEquals(200, res.status());
        JsonNode after = res.data();
        assertEquals(3, delta(before, after, "users", "total"));
        assertEquals(1, delta(before, after, "users", "banned"));
        assertEquals(3, delta(before, after, "users", "newLast7Days"));
        assertEquals(2, delta(before, after, "classes", "total"));
        assertEquals(1, delta(before, after, "classes", "suspended"));
        assertEquals(1, delta(before, after, "classes", "private"));
        assertEquals(1, delta(before, after, "classes", "public"));
        assertEquals(1, delta(before, after, "classes", "active"), "the suspended class is no longer ACTIVE");
        assertEquals(1, delta(before, after, "members", "activeMemberships"), "owner rows are not memberships");
        assertEquals(1, delta(before, after, "members", "pendingRequests"));
        assertEquals(1, delta(before, after, "commerce", "paidOrdersLast30Days"));
        assertEquals(1, delta(before, after, "commerce", "pendingOrders"));
        assertEquals(0, new BigDecimal("150000").compareTo(after.path("commerce").path("revenueLast30Days").decimalValue()
                .subtract(before.path("commerce").path("revenueLast30Days").decimalValue())), "refunds and old orders excluded");
        assertEquals("VND", after.path("commerce").path("currency").asText());
        JsonNode days = after.path("signupsByDay");
        assertEquals(30, days.size());
        assertEquals(java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString(), days.get(29).path("date").asText());
        assertEquals(3, days.get(29).path("count").asLong() - before.path("signupsByDay").get(29).path("count").asLong());
        assertTrue(after.path("privacy").has("openRequests"));
        assertTrue(after.path("outbox").has("deadLetter"));
        assertTrue(after.path("content").has("upcomingEvents"));
    }

    private static long delta(JsonNode before, JsonNode after, String block, String field) {
        return after.path(block).path(field).asLong() - before.path(block).path(field).asLong();
    }

    // ----------------------------------------------------------------------------------------------------------------------------- audit

    @Test
    @DisplayName("audit: platform-wide, newest first, filters by action / actor / class / time; page size capped at 200; bad dates 400")
    void auditPage() throws Exception {
        User owner = user("aud-owner");
        Classroom c = newClass(owner, "Lop nhat ky", "PUBLIC");
        post("/api/v1/admin/classes/" + c.getId() + "/suspend", Map.of("reason", "Nhật ký 1"), admin);
        post("/api/v1/admin/classes/" + c.getId() + "/restore", Map.of("reason", "Nhật ký 2"), admin);

        Answer page = get("/api/v1/admin/audit?classId=" + c.getId() + "&actorId=" + admin.getId(), admin);
        assertEquals(200, page.status());
        JsonNode rows = page.data().path("content");
        assertEquals(2, rows.size());
        assertEquals("ADMIN_CLASS_RESTORE", rows.get(0).path("action").asText(), "newest first");
        assertEquals("Nhật ký 2", rows.get(0).path("details").path("reason").asText());
        assertEquals("Lop nhat ky", rows.get(0).path("classTitle").asText());
        assertEquals(admin.getEmail(), rows.get(0).path("actor").path("email").asText());
        assertEquals(1, get("/api/v1/admin/audit?action=admin_class_suspend&classId=" + c.getId(), admin).data().path("totalElements").asInt());
        assertEquals(0, get("/api/v1/admin/audit?classId=" + c.getId() + "&to=2000-01-01", admin).data().path("totalElements").asInt());
        assertEquals(2, get("/api/v1/admin/audit?classId=" + c.getId() + "&from=" + Instant.now().minus(1, ChronoUnit.HOURS), admin)
                .data().path("totalElements").asInt());
        assertEquals(200, get("/api/v1/admin/audit?size=1000", admin).data().path("size").asInt());
        assertEquals(50, get("/api/v1/admin/audit", admin).data().path("size").asInt());
        assertEquals(400, get("/api/v1/admin/audit?from=yesterday", admin).status());
    }
}
