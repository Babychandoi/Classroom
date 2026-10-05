package com.classroom.modules.me;

import com.classroom.modules.classroom.controller.ClassContentHttpTestBase;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.commerce.model.Entitlement;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import com.classroom.modules.event.model.ClassEvent;
import com.classroom.modules.event.model.EventRegistration;
import com.classroom.modules.event.repository.ClassEventRepository;
import com.classroom.modules.event.repository.EventRegistrationRepository;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.model.Lesson;
import com.classroom.modules.learning.model.LessonProgress;
import com.classroom.modules.learning.model.Section;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.learning.repository.LessonProgressRepository;
import com.classroom.modules.learning.repository.LessonRepository;
import com.classroom.modules.learning.repository.SectionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-30: GET /me/classes, /me/courses, /me/events through the real servlet chain. Fixture: an owner with a PUBLIC, a PRIVATE and (later) a
 * SUSPENDED class; a member of both, a pending requester, a removed and a blocked person, and a stranger.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MeHttpTest extends ClassContentHttpTestBase {

    private static final String URL = "https://meet.example.com/room-d30";

    @Autowired private CourseRepository courseRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private LessonProgressRepository progressRepository;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private ClassEventRepository eventRepository;
    @Autowired private EventRegistrationRepository registrationRepository;

    private User owner;
    private User member;
    private User pending;
    private User removed;
    private User blocked;
    private User stranger;
    private Classroom pub;
    private Classroom priv;
    private Classroom suspended;

    @BeforeAll
    void fixtures() {
        owner = user("me-owner");
        member = user("me-member");
        pending = user("me-pending");
        removed = user("me-removed");
        blocked = user("me-blocked");
        stranger = user("me-stranger");
        pub = newClass(owner, "Me cong khai", "PUBLIC");
        priv = newClass(owner, "Me rieng tu", "PRIVATE");
        suspended = newClass(owner, "Me tam khoa", "PUBLIC");
        for (Classroom c : List.of(pub, priv, suspended)) {
            member(c, member, "ACTIVE");
        }
        member(pub, pending, "PENDING");
        member(pub, removed, "REMOVED");
        member(pub, blocked, "BLOCKED");
        member(priv, removed, "REMOVED");
        member(priv, blocked, "BLOCKED");
        suspended.setStatusBeforeSuspend("ACTIVE");
        suspended.setStatus("SUSPENDED");
        classroomRepository.save(suspended);
    }

    private static List<String> ids(JsonNode items) {
        List<String> r = new ArrayList<>();
        for (JsonNode n : items) r.add(n.path("id").asText());
        return r;
    }

    private static JsonNode find(JsonNode items, String id) {
        for (JsonNode n : items) if (id.equals(n.path("id").asText())) return n;
        return null;
    }

    // ------------------------------------------------------------------------------------------------------------------------- access

    @Test
    @DisplayName("every /me route: a guest gets 401, a signed-in user 200 with a plain list")
    void guestIs401() throws Exception {
        for (String path : new String[]{"/api/v1/me/classes", "/api/v1/me/courses", "/api/v1/me/events",
                "/api/v1/me/events?scope=all&page=0&size=5", "/api/v1/me/classes?page=3&size=500"}) {
            assertEquals(401, get(path, null).status(), path);
            Answer ok = get(path, stranger);
            assertEquals(200, ok.status(), path + " " + ok.body());
            assertTrue(ok.data().isArray(), path);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------ classes

    @Test
    @DisplayName("/me/classes: members get their PUBLIC and PRIVATE classes, not the SUSPENDED one; strangers, REMOVED and BLOCKED get nothing")
    void classesOfMember() throws Exception {
        JsonNode mine = get("/api/v1/me/classes", member).data();
        assertEquals(java.util.Set.of(pub.getId(), priv.getId()), new java.util.HashSet<>(ids(mine)), "suspended is the owner's alone");
        assertEquals("ACTIVE", find(mine, priv.getId()).path("memberState").asText());
        assertEquals("PRIVATE", find(mine, priv.getId()).path("visibility").asText());

        assertEquals(0, get("/api/v1/me/classes", stranger).data().size());
        assertTrue(ids(get("/api/v1/me/classes", removed).data()).isEmpty(), "REMOVED is not listed");
        assertTrue(ids(get("/api/v1/me/classes", blocked).data()).isEmpty(), "BLOCKED is not listed");
    }

    @Test
    @DisplayName("/me/classes: the owner sees all three (owned first, incl. SUSPENDED); a PENDING request lists the public class with memberState PENDING")
    void classesOfOwnerAndPending() throws Exception {
        JsonNode ownerList = get("/api/v1/me/classes", owner).data();
        assertTrue(ids(ownerList).containsAll(List.of(pub.getId(), priv.getId(), suspended.getId())));
        assertEquals("SUSPENDED", find(ownerList, suspended.getId()).path("status").asText());

        JsonNode p = get("/api/v1/me/classes", pending).data();
        assertEquals(List.of(pub.getId()), ids(p));
        assertEquals("PENDING", p.get(0).path("memberState").asText());

        // a pending request on a class that has since turned PRIVATE (or was archived) no longer lists it
        User late = user("me-late");
        Classroom hidden = newClass(owner, "Me cho duyet roi an", "PRIVATE");
        member(hidden, late, "PENDING");
        assertTrue(get("/api/v1/me/classes", late).data().isEmpty(), "a pending request never exposes a PRIVATE class");
    }

    @Test
    @DisplayName("/me/classes: owned classes come first, then the most recent membership; paging walks the list without overlap")
    void classesOrderAndPaging() throws Exception {
        User u = user("me-order");
        Classroom own = newClass(u, "Me cua toi", "PUBLIC");
        Classroom oldJoin = newClass(owner, "Me tham gia cu", "PUBLIC");
        Classroom newJoin = newClass(owner, "Me tham gia moi", "PUBLIC");
        var m1 = member(oldJoin, u, "ACTIVE");
        m1.setJoinedAt(Instant.now().minus(10, ChronoUnit.DAYS));
        memberRepository.save(m1);
        var m2 = member(newJoin, u, "ACTIVE");
        m2.setJoinedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        memberRepository.save(m2);

        assertEquals(List.of(own.getId(), newJoin.getId(), oldJoin.getId()), ids(get("/api/v1/me/classes", u).data()));
        List<String> walked = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            walked.addAll(ids(get("/api/v1/me/classes?page=" + page + "&size=1", u).data()));
        }
        assertEquals(List.of(own.getId(), newJoin.getId(), oldJoin.getId()), walked);
        assertEquals(0, get("/api/v1/me/classes?page=3&size=1", u).data().size());
    }

    // ------------------------------------------------------------------------------------------------------------------------ courses

    private Course course(Classroom c, String title, String mode, String status, int position, String productId) {
        Course course = new Course(c.getId(), title, mode);
        course.setStatus(status);
        course.setPosition(position);
        course.setProductId(productId);
        return courseRepository.save(course);
    }

    private Section section(Course c, int pos, boolean archived) {
        Section s = new Section(c.getId(), "Chuong " + pos, pos);
        s.setArchived(archived);
        return sectionRepository.save(s);
    }

    private Lesson lesson(Section s, Course c, String title, int pos, boolean archived) {
        Lesson l = new Lesson(s.getId(), c.getId(), title, "TEXT", pos);
        l.setArchived(archived);
        return lessonRepository.save(l);
    }

    private void complete(User u, Lesson l, Course c, Classroom k, Instant at) {
        LessonProgress p = new LessonProgress(u.getId(), l.getId(), c.getId(), k.getId());
        p.setCompletedAt(at);
        progressRepository.save(p);
    }

    @Test
    @DisplayName("/me/courses: progress ignores archived lessons/sections, resume lesson, started-first order, drafts / unpaid / foreign / pending / lapsed excluded")
    void courses() throws Exception {
        User u = user("me-learner");
        User lapsed = user("me-lapsed");
        Classroom k = newClass(owner, "Me khoa hoc", "PUBLIC");
        member(k, u, "ACTIVE");
        member(k, lapsed, "EXPIRED");
        Classroom other = newClass(owner, "Me khoa hoc khac", "PUBLIC"); // u is NOT a member here

        Course free = course(k, "Khoa mien phi", "FREE", "PUBLISHED", 1, null);
        Section s1 = section(free, 1, false);
        Section s2 = section(free, 2, false);
        Section sArchived = section(free, 3, true);
        Lesson l1 = lesson(s1, free, "Bai 1", 1, false);
        Lesson l2 = lesson(s1, free, "Bai 2", 2, false);
        Lesson l3 = lesson(s2, free, "Bai 3", 1, false);
        lesson(s2, free, "Bai luu tru", 2, true);
        Lesson inArchivedSection = lesson(sArchived, free, "Bai trong chuong luu tru", 1, false);
        complete(u, l1, free, k, Instant.now().minus(2, ChronoUnit.HOURS));
        complete(u, inArchivedSection, free, k, Instant.now().minus(1, ChronoUnit.HOURS)); // must not count

        Course untouched = course(k, "Khoa chua hoc", "FREE", "PUBLISHED", 0, null);
        Lesson u1 = lesson(section(untouched, 1, false), untouched, "Bai dau", 1, false);

        course(k, "Khoa nhap", "FREE", "DRAFT", 2, null);
        String productPaid = UUID.randomUUID().toString();
        Course unpaid = course(k, "Khoa tra phi chua mua", "PURCHASE_REQUIRED", "PUBLISHED", 3, productPaid);
        Course paid = course(k, "Khoa tra phi da mua", "PURCHASE_REQUIRED", "PUBLISHED", 4, productPaid);
        entitlementRepository.save(new Entitlement(u.getId(), k.getId(), productPaid, paid.getId(),
                Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(30, ChronoUnit.DAYS)));
        Course foreign = course(other, "Khoa lop khac", "FREE", "PUBLISHED", 0, null);

        Answer a = get("/api/v1/me/courses", u);
        assertEquals(200, a.status(), a.body());
        JsonNode items = a.data();
        // started first, then the rest by class title and course position
        assertEquals(List.of(free.getId(), untouched.getId(), paid.getId()), ids(items), a.body());

        JsonNode f = items.get(0);
        assertEquals(k.getId(), f.path("classId").asText());
        assertEquals("Me khoa hoc", f.path("classTitle").asText());
        assertEquals(k.getSlug(), f.path("classSlug").asText());
        assertEquals("FREE", f.path("accessMode").asText());
        assertEquals(3, f.path("totalLessons").asInt(), "archived lesson and archived-section lesson are not counted");
        assertEquals(1, f.path("completedLessons").asInt(), "progress on a lesson of an archived section is ignored");
        assertEquals(33, f.path("progressPercent").asInt());
        assertEquals(l2.getId(), f.path("nextLessonId").asText(), "first visible not-completed lesson in curriculum order");
        assertTrue(f.path("started").asBoolean());
        assertNotNull(f.path("lastActivityAt").asText(null));
        assertFalse(f.path("lastActivityAt").isNull());

        JsonNode n = items.get(1);
        assertFalse(n.path("started").asBoolean());
        assertEquals(0, n.path("completedLessons").asInt());
        assertEquals(0, n.path("progressPercent").asInt());
        assertTrue(n.path("lastActivityAt").isNull());
        assertEquals(u1.getId(), n.path("nextLessonId").asText());
        assertNull(find(items, unpaid.getId()));
        assertNull(find(items, foreign.getId()));
        assertEquals(l3.getId().length(), 36);

        // finishing every visible lesson: 100 %, no resume point
        complete(u, l2, free, k, Instant.now());
        complete(u, l3, free, k, Instant.now());
        JsonNode done = find(get("/api/v1/me/courses", u).data(), free.getId());
        assertEquals(100, done.path("progressPercent").asInt());
        assertTrue(done.path("nextLessonId").isNull());

        // paging
        assertEquals(List.of(free.getId()), ids(get("/api/v1/me/courses?size=1", u).data()));
        assertEquals(List.of(untouched.getId()), ids(get("/api/v1/me/courses?size=1&page=1", u).data()));

        // pending, lapsed (EXPIRED), removed and strangers have no courses; the owner sees all published (incl. the unpaid one)
        assertTrue(get("/api/v1/me/courses", pending).data().isEmpty());
        assertTrue(get("/api/v1/me/courses", lapsed).data().isEmpty(), "an EXPIRED member cannot learn");
        assertTrue(get("/api/v1/me/courses", removed).data().isEmpty());
        assertTrue(get("/api/v1/me/courses", stranger).data().isEmpty());
        List<String> ownerCourses = ids(get("/api/v1/me/courses", owner).data());
        assertTrue(ownerCourses.containsAll(List.of(free.getId(), untouched.getId(), unpaid.getId(), paid.getId(), foreign.getId())));
    }

    @Test
    @DisplayName("/me/courses: a SUSPENDED class' course is the owner's alone")
    void suspendedCourses() throws Exception {
        Course c = course(suspended, "Khoa lop tam khoa", "FREE", "PUBLISHED", 0, null);
        assertNull(find(get("/api/v1/me/courses", member).data(), c.getId()));
        assertNotNull(find(get("/api/v1/me/courses", owner).data(), c.getId()));
    }

    // ------------------------------------------------------------------------------------------------------------------------- events

    private ClassEvent event(Classroom c, String title, Instant startsAt, String status, String audience) {
        ClassEvent e = new ClassEvent();
        e.setClassId(c.getId());
        e.setCreatedBy(owner.getId());
        e.setHostUserId(owner.getId());
        e.setTitle(title);
        e.setFormat(ClassEvent.FORMAT_ONLINE);
        e.setMeetingUrl(URL);
        e.setStartsAt(startsAt.truncatedTo(ChronoUnit.MICROS));
        e.setEndsAt(startsAt.plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS));
        e.setAudience(audience);
        e.setStatus(status);
        return eventRepository.save(e);
    }

    private void register(ClassEvent e, User u) {
        registrationRepository.save(new EventRegistration(e.getId(), u.getId()));
    }

    @Test
    @DisplayName("/me/events: scopes and ordering, CANCELLED included, only my registrations, isRegistered + class fields + meetingUrl for an eligible registrant")
    void eventsScopesAndOrder() throws Exception {
        User u = user("me-events");
        Classroom k = newClass(owner, "Me su kien", "PUBLIC");
        member(k, u, "ACTIVE");
        Instant now = Instant.now();
        ClassEvent soon = event(k, "Sap toi", now.plus(1, ChronoUnit.DAYS), ClassEvent.STATUS_SCHEDULED, ClassEvent.AUDIENCE_MEMBERS);
        ClassEvent later = event(k, "Xa hon", now.plus(5, ChronoUnit.DAYS), ClassEvent.STATUS_SCHEDULED, ClassEvent.AUDIENCE_PUBLIC);
        ClassEvent cancelled = event(k, "Da huy", now.plus(3, ChronoUnit.DAYS), ClassEvent.STATUS_CANCELLED, ClassEvent.AUDIENCE_PUBLIC);
        ClassEvent past1 = event(k, "Qua 1", now.minus(2, ChronoUnit.DAYS), ClassEvent.STATUS_SCHEDULED, ClassEvent.AUDIENCE_PUBLIC);
        ClassEvent past2 = event(k, "Qua 2", now.minus(6, ChronoUnit.DAYS), ClassEvent.STATUS_SCHEDULED, ClassEvent.AUDIENCE_PUBLIC);
        ClassEvent notMine = event(k, "Khong dang ky", now.plus(2, ChronoUnit.DAYS), ClassEvent.STATUS_SCHEDULED, ClassEvent.AUDIENCE_PUBLIC);
        for (ClassEvent e : List.of(soon, later, cancelled, past1, past2)) register(e, u);
        register(notMine, owner);

        Answer def = get("/api/v1/me/events", u);
        assertEquals(List.of(soon.getId(), cancelled.getId(), later.getId()), ids(def.data()), def.body());
        assertEquals(ids(def.data()), ids(get("/api/v1/me/events?scope=upcoming", u).data()));
        assertEquals(List.of(past1.getId(), past2.getId()), ids(get("/api/v1/me/events?scope=past", u).data()), "latest first");
        assertEquals(List.of(soon.getId(), cancelled.getId(), later.getId(), past1.getId(), past2.getId()),
                ids(get("/api/v1/me/events?scope=ALL", u).data()), "upcoming first, then past");
        assertEquals(List.of(cancelled.getId()), ids(get("/api/v1/me/events?scope=all&size=1&page=1", u).data()));

        JsonNode first = def.data().get(0);
        assertTrue(first.path("isRegistered").asBoolean());
        assertEquals(k.getTitle(), first.path("classTitle").asText());
        assertEquals(k.getSlug(), first.path("classSlug").asText());
        assertEquals(URL, first.path("meetingUrl").asText(), "an eligible registrant keeps the link");
        assertEquals("CANCELLED", find(def.data(), cancelled.getId()).path("status").asText());

        assertEquals(List.of(notMine.getId()), ids(get("/api/v1/me/events", owner).data()), "only the caller's own registrations");
        assertEquals(400, get("/api/v1/me/events?scope=nonsense", u).status());
    }

    @Test
    @DisplayName("/me/events: meetingUrl follows eligibility (EXPIRED member of a PAID class loses it); a class that is no longer visible drops its events")
    void eventsEligibilityAndVisibility() throws Exception {
        User u = user("me-ev-elig");
        Instant at = Instant.now().plus(2, ChronoUnit.DAYS);
        Classroom paid = newClass(owner, "Me su kien tra phi", "PUBLIC");
        paid.setAccessType(Classroom.ACCESS_PAID);
        classroomRepository.save(paid);
        var m = member(paid, u, "EXPIRED");
        ClassEvent paidEvent = event(paid, "Su kien tra phi", at, ClassEvent.STATUS_SCHEDULED, ClassEvent.AUDIENCE_PUBLIC);
        register(paidEvent, u);
        JsonNode listed = find(get("/api/v1/me/events", u).data(), paidEvent.getId());
        assertNotNull(listed, "an EXPIRED member still sees the class");
        assertTrue(listed.path("meetingUrl").isNull(), "but not the link");
        m.setState("ACTIVE");
        memberRepository.save(m);
        assertEquals(URL, find(get("/api/v1/me/events", u).data(), paidEvent.getId()).path("meetingUrl").asText());

        // PRIVATE class: removed members no longer see it, so its events disappear from their list
        ClassEvent privEvent = event(priv, "Su kien rieng tu", at, ClassEvent.STATUS_SCHEDULED, ClassEvent.AUDIENCE_MEMBERS);
        register(privEvent, removed);
        register(privEvent, member);
        assertNull(find(get("/api/v1/me/events", removed).data(), privEvent.getId()));
        assertNotNull(find(get("/api/v1/me/events", member).data(), privEvent.getId()));

        // SUSPENDED class: owner only
        ClassEvent suspEvent = event(suspended, "Su kien tam khoa", at, ClassEvent.STATUS_SCHEDULED, ClassEvent.AUDIENCE_PUBLIC);
        register(suspEvent, member);
        register(suspEvent, owner);
        assertNull(find(get("/api/v1/me/events", member).data(), suspEvent.getId()));
        assertNotNull(find(get("/api/v1/me/events", owner).data(), suspEvent.getId()));
    }
}
