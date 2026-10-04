package com.classroom.modules.event.controller;

import com.classroom.modules.audit.repository.AuditEventRepository;
import com.classroom.modules.classroom.controller.ClassContentHttpTestBase;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.event.repository.ClassEventRepository;
import com.classroom.modules.event.repository.EventRegistrationRepository;
import com.classroom.modules.identity.model.User;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-27: class events through the real servlet chain - authorisation matrix, validation, meetingUrl hiding, capacity (409 when full,
 * idempotent register / unregister), who may register (MEMBERS events, PAID / PRIVATE classes), PRIVATE classes being a 404, the public
 * cross-class rail and archived classes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventHttpTest extends ClassContentHttpTestBase {

    private static final String MEETING_URL = "https://zoom.us/j/1112223334";

    @Autowired private ClassEventRepository eventRepository;
    @Autowired private EventRegistrationRepository registrationRepository;
    @Autowired private AuditEventRepository auditEventRepository;

    private User owner;
    private User member;
    private User member2;
    private User manager;     // staff with EVENT:VIEW/CREATE/EDIT/DELETE
    private User plainStaff;  // staff without EVENT grants
    private User stranger;
    private User stranger2;
    private Classroom pub;
    private Classroom priv;
    private Classroom paid;

    @BeforeAll
    void fixtures() {
        owner = user("owner");
        member = user("member");
        member2 = user("member2");
        manager = user("manager");
        plainStaff = user("plain-staff");
        stranger = user("stranger");
        stranger2 = user("stranger2");
        pub = newClass(owner, "Su kien cong khai", "PUBLIC");
        priv = newClass(owner, "Su kien rieng tu", "PRIVATE");
        paid = newClass(owner, "Su kien tra phi", "PUBLIC");
        paid.setAccessType(Classroom.ACCESS_PAID);
        classroomRepository.save(paid);
        for (Classroom c : List.of(pub, priv, paid)) {
            member(c, member, "ACTIVE");
            member(c, member2, "ACTIVE");
            staff(c, manager, "EVENT:VIEW", "EVENT:CREATE", "EVENT:EDIT", "EVENT:DELETE");
            staff(c, plainStaff, "BLOG:VIEW");
        }
    }

    private Map<String, Object> body(String title, Instant startsAt, Instant endsAt) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("title", title);
        b.put("description", "Mo ta su kien");
        b.put("forWhom", "Hoc vien lop 9");
        b.put("takeaways", List.of("Y mot", "Y hai"));
        b.put("format", "ONLINE");
        b.put("location", "Zoom");
        b.put("meetingUrl", MEETING_URL);
        b.put("startsAt", startsAt.toString());
        b.put("endsAt", endsAt.toString());
        b.put("audience", "PUBLIC");
        return b;
    }

    private Map<String, Object> upcomingBody(String title) {
        Instant start = Instant.now().plus(2, ChronoUnit.DAYS);
        return body(title, start, start.plus(2, ChronoUnit.HOURS));
    }

    private String create(Classroom c, Map<String, Object> b) throws Exception {
        Answer a = post("/api/v1/classes/" + c.getId() + "/events", b, owner);
        assertEquals(200, a.status(), a.body());
        return a.data().path("id").asText();
    }

    private Answer register(String eventId, User u) throws Exception {
        return post("/api/v1/events/" + eventId + "/registrations", null, u);
    }

    @Test
    @DisplayName("POST events: guest 401, stranger / member / staff without EVENT:CREATE 403, manager and owner 200 (host defaults to creator)")
    void createMatrix() throws Exception {
        String path = "/api/v1/classes/" + pub.getId() + "/events";
        assertEquals(401, post(path, upcomingBody("x"), null).status());
        for (User u : new User[]{stranger, member, plainStaff}) {
            Answer a = post(path, upcomingBody("x"), u);
            assertEquals(403, a.status(), u.getEmail());
            assertEquals("STAFF_PERMISSION_DENIED", a.code());
        }
        for (User u : new User[]{manager, owner}) {
            Answer a = post(path, upcomingBody("Su kien cua " + u.getFullName()), u);
            assertEquals(200, a.status(), a.body());
            JsonNode d = a.data();
            assertEquals(u.getId(), d.path("host").path("id").asText());
            assertEquals("SCHEDULED", d.path("status").asText());
            assertEquals(0, d.path("registeredCount").asInt());
            assertFalse(d.path("isRegistered").asBoolean());
            assertFalse(d.path("isFull").asBoolean());
            assertEquals(List.of("Y mot", "Y hai"), JSON.convertValue(d.path("takeaways"), List.class));
            assertEquals(MEETING_URL, d.path("meetingUrl").asText(), "the creator manages the event");
        }
    }

    @Test
    @DisplayName("validation: ends before start, longer than 7 days, non-http meetingUrl, 9 takeaways, capacity 0, a host who is not staff -> 400")
    void validation() throws Exception {
        String path = "/api/v1/classes/" + pub.getId() + "/events";
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        assertEquals(400, post(path, body("t", start, start), owner).status());
        assertEquals(400, post(path, body("t", start, start.plus(8, ChronoUnit.DAYS)), owner).status());
        Map<String, Object> ftp = upcomingBody("t");
        ftp.put("meetingUrl", "ftp://example.com/x");
        assertEquals(400, post(path, ftp, owner).status());
        Map<String, Object> js = upcomingBody("t");
        js.put("meetingUrl", "javascript:alert(1)");
        assertEquals(400, post(path, js, owner).status());
        Map<String, Object> many = upcomingBody("t");
        many.put("takeaways", List.of("1", "2", "3", "4", "5", "6", "7", "8", "9"));
        assertEquals(400, post(path, many, owner).status());
        Map<String, Object> zero = upcomingBody("t");
        zero.put("capacity", 0);
        assertEquals(400, post(path, zero, owner).status());
        Map<String, Object> badHost = upcomingBody("t");
        badHost.put("hostUserId", member.getId());
        assertEquals(400, post(path, badHost, owner).status());
        Map<String, Object> badFormat = upcomingBody("t");
        badFormat.put("format", "HYBRID");
        assertEquals(400, post(path, badFormat, owner).status());
        Map<String, Object> staffHost = upcomingBody("Host la tro giang");
        staffHost.put("hostUserId", manager.getId());
        Answer ok = post(path, staffHost, owner);
        assertEquals(200, ok.status(), ok.body());
        assertEquals(manager.getId(), ok.data().path("host").path("id").asText());
    }

    @Test
    @DisplayName("meetingUrl is null for guests and unregistered viewers, returned to managers and to a registered user")
    void meetingUrlHiding() throws Exception {
        String id = create(pub, upcomingBody("Link an"));
        for (User u : new User[]{null, stranger, member, plainStaff}) {
            Answer a = get("/api/v1/events/" + id, u);
            assertEquals(200, a.status(), a.body());
            assertTrue(a.data().path("meetingUrl").isNull(), "leaked to " + (u == null ? "guest" : u.getEmail()));
        }
        assertEquals(MEETING_URL, get("/api/v1/events/" + id, manager).data().path("meetingUrl").asText());
        assertEquals(MEETING_URL, get("/api/v1/events/" + id, owner).data().path("meetingUrl").asText());

        Answer reg = register(id, member);
        assertEquals(200, reg.status(), reg.body());
        assertTrue(reg.data().path("isRegistered").asBoolean());
        assertEquals(MEETING_URL, reg.data().path("meetingUrl").asText());
        assertEquals(MEETING_URL, get("/api/v1/events/" + id, member).data().path("meetingUrl").asText());
        JsonNode listed = find(get("/api/v1/classes/" + pub.getId() + "/events", member).data(), id);
        assertEquals(MEETING_URL, listed.path("meetingUrl").asText());
        assertTrue(find(get("/api/v1/classes/" + pub.getId() + "/events", member2).data(), id).path("meetingUrl").isNull());
    }

    @Test
    @DisplayName("capacity: full -> 409 'Sự kiện đã đủ chỗ'; registering twice is idempotent; unregister frees the seat and is idempotent too")
    void capacity() throws Exception {
        Map<String, Object> b = upcomingBody("Mot cho");
        b.put("capacity", 1);
        String id = create(pub, b);
        Answer first = register(id, member);
        assertEquals(200, first.status(), first.body());
        assertEquals(1, first.data().path("registeredCount").asInt());
        assertTrue(first.data().path("isFull").asBoolean());
        Answer again = register(id, member);
        assertEquals(200, again.status(), "already registered is a success");
        assertEquals(1, again.data().path("registeredCount").asInt());

        Answer full = register(id, member2);
        assertEquals(409, full.status(), full.body());
        assertEquals("CONFLICT", full.code());
        assertEquals("Sự kiện đã đủ chỗ", full.message());

        Answer out = delete("/api/v1/events/" + id + "/registrations/me", member);
        assertEquals(200, out.status(), out.body());
        assertEquals(0, out.data().path("registeredCount").asInt());
        assertFalse(out.data().path("isRegistered").asBoolean());
        assertEquals(200, delete("/api/v1/events/" + id + "/registrations/me", member).status(), "unregister is idempotent");
        assertEquals(0, eventRepository.findById(id).orElseThrow().getRegisteredCount());
        assertEquals(200, register(id, member2).status(), "the freed seat can be taken");
        assertEquals(1, registrationRepository.countByEventId(id));
    }

    @Test
    @DisplayName("PUT capacity below the registered count is 409; capacity:null makes it unlimited; absent fields are kept")
    void capacityChange() throws Exception {
        Map<String, Object> b = upcomingBody("Doi so cho");
        b.put("capacity", 5);
        String id = create(pub, b);
        register(id, member);
        register(id, member2);
        Answer shrink = put("/api/v1/events/" + id, Map.of("capacity", 1), owner);
        assertEquals(409, shrink.status(), shrink.body());
        assertEquals(200, put("/api/v1/events/" + id, Map.of("capacity", 2), manager).status());
        Answer unlimited = put("/api/v1/events/" + id, "{\"capacity\":null}", owner);
        assertEquals(200, unlimited.status(), unlimited.body());
        assertTrue(unlimited.data().path("capacity").isNull());
        assertEquals("Doi so cho", unlimited.data().path("title").asText());
        assertEquals("Hoc vien lop 9", unlimited.data().path("forWhom").asText());
        assertEquals(403, put("/api/v1/events/" + id, Map.of("title", "x"), plainStaff).status());
        Answer badDates = put("/api/v1/events/" + id, Map.of("endsAt", Instant.now().minus(30, ChronoUnit.DAYS).toString()), owner);
        assertEquals(400, badDates.status(), "endsAt before the stored startsAt");
    }

    @Test
    @DisplayName("who may register: MEMBERS events and PAID classes need an active member; a PUBLIC event of a PUBLIC FREE class takes any signed-in user")
    void registrationEligibility() throws Exception {
        String open = create(pub, upcomingBody("Mo cho tat ca"));
        assertEquals(401, register(open, null).status());
        Answer outsider = register(open, stranger);
        assertEquals(200, outsider.status(), outsider.body());

        Map<String, Object> membersOnly = upcomingBody("Chi thanh vien");
        membersOnly.put("audience", "MEMBERS");
        String closed = create(pub, membersOnly);
        Answer denied = register(closed, stranger);
        assertEquals(403, denied.status(), denied.body());
        assertTrue(get("/api/v1/events/" + closed, stranger).status() == 200, "the card is still visible");
        assertEquals(200, register(closed, member).status());

        String paidEvent = create(paid, upcomingBody("Lop tra phi"));
        assertEquals(403, register(paidEvent, stranger2).status(), "a PAID class needs membership even for a PUBLIC event");
        assertEquals(200, register(paidEvent, member).status());
    }

    @Test
    @DisplayName("a CANCELLED or ended event refuses registrations (409); cancel needs EVENT:EDIT and keeps registrations")
    void cancelledAndEnded() throws Exception {
        String id = create(pub, upcomingBody("Se huy"));
        register(id, member);
        assertEquals(403, post("/api/v1/events/" + id + "/cancel", null, plainStaff).status());
        Answer cancelled = post("/api/v1/events/" + id + "/cancel", null, manager);
        assertEquals(200, cancelled.status(), cancelled.body());
        assertEquals("CANCELLED", cancelled.data().path("status").asText());
        assertEquals(1, cancelled.data().path("registeredCount").asInt());
        assertEquals(409, register(id, member2).status());

        Instant start = Instant.now().minus(3, ChronoUnit.DAYS);
        String past = create(pub, body("Da qua", start, start.plus(1, ChronoUnit.HOURS)));
        assertEquals(409, register(past, member).status());
        assertNotNull(find(get("/api/v1/classes/" + pub.getId() + "/events?scope=past", null).data(), past));
        assertNull(find(get("/api/v1/classes/" + pub.getId() + "/events?scope=upcoming", null).data(), past));
        assertNotNull(find(get("/api/v1/classes/" + pub.getId() + "/events?scope=all", null).data(), past));
        assertNotNull(find(get("/api/v1/classes/" + pub.getId() + "/events", null).data(), id), "upcoming includes CANCELLED (badge)");
        assertEquals(400, get("/api/v1/classes/" + pub.getId() + "/events?scope=later", null).status());
    }

    @Test
    @DisplayName("a PRIVATE class: its events are 404 for guests and strangers (same answer as an unknown id), members see them")
    void privateClassIsNotFound() throws Exception {
        String id = create(priv, upcomingBody("Su kien bi mat"));
        Answer unknown = get("/api/v1/events/" + UUID.randomUUID(), stranger);
        for (User u : new User[]{null, stranger}) {
            assertEquals(404, get("/api/v1/classes/" + priv.getId() + "/events", u).status());
            Answer one = get("/api/v1/events/" + id, u);
            assertEquals(404, one.status());
            assertEquals(unknown.message(), one.message());
            assertFalse(one.body().contains("Su kien bi mat"));
        }
        assertEquals(404, register(id, stranger).status());
        assertEquals(404, delete("/api/v1/events/" + id + "/registrations/me", stranger).status());
        assertEquals(404, get("/api/v1/events/" + id + "/registrations", stranger).status());
        assertEquals(200, get("/api/v1/events/" + id, member).status());
        assertEquals(200, register(id, member).status());
    }

    @Test
    @DisplayName("GET /events/upcoming (public): only SCHEDULED upcoming events of PUBLIC ACTIVE classes, with class title / slug and never a meetingUrl")
    void upcomingRail() throws Exception {
        Instant soon = Instant.now().plus(10, ChronoUnit.MINUTES);
        Map<String, Object> pubBody = body("Rail cong khai", soon, soon.plus(1, ChronoUnit.HOURS));
        String pubEvent = create(pub, pubBody);
        String privEvent = create(priv, body("Rail rieng tu", soon, soon.plus(1, ChronoUnit.HOURS)));
        Classroom archived = newClass(owner, "Rail luu tru", "PUBLIC");
        String archivedEvent = create(archived, body("Rail lop luu tru", soon, soon.plus(1, ChronoUnit.HOURS)));
        archived.setStatus("ARCHIVED");
        classroomRepository.save(archived);
        String cancelledEvent = create(pub, body("Rail da huy", soon, soon.plus(1, ChronoUnit.HOURS)));
        post("/api/v1/events/" + cancelledEvent + "/cancel", null, owner);
        register(pubEvent, member);

        for (User u : new User[]{null, member, owner}) {
            Answer rail = get("/api/v1/events/upcoming?size=20", u);
            assertEquals(200, rail.status(), rail.body());
            JsonNode mine = find(rail.data(), pubEvent);
            assertNotNull(mine, "the public class's event is on the rail");
            assertEquals(pub.getTitle(), mine.path("classTitle").asText());
            assertEquals(pub.getSlug(), mine.path("classSlug").asText());
            assertTrue(mine.path("meetingUrl").isNull(), "never a meetingUrl on the rail");
            assertNull(find(rail.data(), privEvent), "a PRIVATE class's event never appears");
            assertNull(find(rail.data(), archivedEvent), "an ARCHIVED class's event never appears");
            assertNull(find(rail.data(), cancelledEvent));
            assertFalse(rail.body().contains("Rail rieng tu"));
        }
        assertTrue(find(get("/api/v1/events/upcoming?size=20", member).data(), pubEvent).path("isRegistered").asBoolean());
        assertEquals(200, get("/api/v1/events/upcoming?size=500", null).status());
        assertTrue(get("/api/v1/events/upcoming?size=500", null).data().size() <= 20);
    }

    @Test
    @DisplayName("registrants: EVENT:VIEW / EDIT and the owner see the list in registration order; members and plain staff get 403")
    void registrants() throws Exception {
        String id = create(pub, upcomingBody("Danh sach"));
        register(id, member);
        register(id, member2);
        for (User u : new User[]{owner, manager}) {
            Answer a = get("/api/v1/events/" + id + "/registrations", u);
            assertEquals(200, a.status(), a.body());
            List<String> ids = new ArrayList<>();
            a.data().forEach(n -> ids.add(n.path("user").path("id").asText()));
            assertEquals(List.of(member.getId(), member2.getId()), ids);
            assertFalse(a.data().get(0).path("registeredAt").asText().isBlank());
        }
        assertEquals(403, get("/api/v1/events/" + id + "/registrations", member).status());
        assertEquals(403, get("/api/v1/events/" + id + "/registrations", plainStaff).status());
        assertEquals(401, get("/api/v1/events/" + id + "/registrations", null).status());
    }

    @Test
    @DisplayName("DELETE needs EVENT:DELETE and removes the registrations; create / update / cancel / delete are audited")
    void deleteAndAudit() throws Exception {
        String id = create(pub, upcomingBody("Se xoa"));
        register(id, member);
        put("/api/v1/events/" + id, Map.of("title", "Se xoa (sua)"), owner);
        post("/api/v1/events/" + id + "/cancel", null, owner);
        assertEquals(403, delete("/api/v1/events/" + id, plainStaff).status());
        assertEquals(200, delete("/api/v1/events/" + id, manager).status());
        assertEquals(404, get("/api/v1/events/" + id, owner).status());
        assertEquals(0, registrationRepository.countByEventId(id));
        Set<String> actions = new HashSet<>();
        auditEventRepository.findByClassIdOrderByCreatedAtDesc(pub.getId()).stream()
                .filter(e -> id.equals(e.getTargetId()))
                .forEach(e -> actions.add(e.getAction()));
        assertEquals(Set.of("EVENT_CREATE", "EVENT_UPDATE", "EVENT_CANCEL", "EVENT_DELETE"), actions);
    }

    @Test
    @DisplayName("an ARCHIVED class: no new events and no registrations (409), existing events stay readable")
    void archivedClass() throws Exception {
        Classroom c = newClass(owner, "Su kien luu tru", "PUBLIC");
        member(c, member, "ACTIVE");
        String id = create(c, upcomingBody("Truoc khi luu tru"));
        c.setStatus("ARCHIVED");
        classroomRepository.save(c);
        assertEquals(409, post("/api/v1/classes/" + c.getId() + "/events", upcomingBody("Moi"), owner).status());
        assertEquals(409, register(id, member).status());
        assertEquals(200, get("/api/v1/events/" + id, member).status());
    }

    private static JsonNode find(JsonNode items, String id) {
        for (JsonNode n : items) {
            if (id.equals(n.path("id").asText())) return n;
        }
        return null;
    }
}
