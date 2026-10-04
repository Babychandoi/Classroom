package com.classroom.modules.event.controller;

import com.classroom.modules.classroom.controller.ClassContentHttpTestBase;
import com.classroom.modules.classroom.model.ClassMember;
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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-27 review fixes: a registrant who LOSES eligibility (EXPIRED / REMOVED / BLOCKED) no longer sees meetingUrl - neither on reads nor on a
 * repeated POST registration - and DELETE registrations/me always frees the seat but answers minimally once the class is hidden.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventEligibilityHttpTest extends ClassContentHttpTestBase {

    private static final String URL = "https://meet.example.com/secret-room";

    @Autowired private ClassEventRepository eventRepository;
    @Autowired private EventRegistrationRepository registrationRepository;

    private User owner;
    private Classroom pub;
    private Classroom paid;
    private Classroom priv;

    @BeforeAll
    void fixtures() {
        owner = user("elig-owner");
        pub = newClass(owner, "Elig cong khai", "PUBLIC");
        paid = newClass(owner, "Elig tra phi", "PUBLIC");
        paid.setAccessType(Classroom.ACCESS_PAID);
        classroomRepository.save(paid);
        priv = newClass(owner, "Elig rieng tu", "PRIVATE");
    }

    private String event(Classroom c, String audience, String title) throws Exception {
        Instant start = Instant.now().plus(2, ChronoUnit.DAYS);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("title", title);
        b.put("format", "ONLINE");
        b.put("meetingUrl", URL);
        b.put("startsAt", start.toString());
        b.put("endsAt", start.plus(1, ChronoUnit.HOURS).toString());
        b.put("audience", audience);
        Answer a = post("/api/v1/classes/" + c.getId() + "/events", b, owner);
        assertEquals(200, a.status(), a.body());
        return a.data().path("id").asText();
    }

    private void setState(Classroom c, User u, String state, Instant expiresAt) {
        ClassMember m = memberRepository.findByClassIdAndUserId(c.getId(), u.getId()).orElseGet(() -> new ClassMember(c.getId(), u.getId(), "STUDENT"));
        m.setState(state);
        m.setAccessExpiresAt(expiresAt);
        memberRepository.save(m);
    }

    private Answer register(String id, User u) throws Exception {
        return post("/api/v1/events/" + id + "/registrations", null, u);
    }

    private static JsonNode find(JsonNode items, String id) {
        for (JsonNode n : items) if (id.equals(n.path("id").asText())) return n;
        return null;
    }

    /** Registers while eligible (URL shown), then {@code lose} runs; afterwards no read and no repeat POST may return the URL. */
    private void assertUrlHiddenAfter(Classroom c, String eventId, User u, Runnable lose, int repeatStatus) throws Exception {
        Answer reg = register(eventId, u);
        assertEquals(200, reg.status(), reg.body());
        assertEquals(URL, reg.data().path("meetingUrl").asText());
        lose.run();

        Answer one = get("/api/v1/events/" + eventId, u);
        assertEquals(200, one.status(), one.body());
        assertTrue(one.data().path("meetingUrl").isNull(), "GET /events/{id} leaked: " + one.body());
        assertTrue(one.data().path("isRegistered").asBoolean(), "the registration itself still exists");
        JsonNode listed = find(get("/api/v1/classes/" + c.getId() + "/events", u).data(), eventId);
        assertTrue(listed.path("meetingUrl").isNull(), "GET /classes/{id}/events leaked");
        Answer again = register(eventId, u);
        assertEquals(repeatStatus, again.status(), again.body());
        assertFalse(again.body().contains(URL), "repeat POST leaked the URL");
        assertEquals(URL, get("/api/v1/events/" + eventId, owner).data().path("meetingUrl").asText(), "managers still see it");
    }

    @Test
    @DisplayName("PAID class: a member who registered and then EXPIRED gets no meetingUrl (reads, listing, repeat POST -> 403)")
    void expiredMemberOfPaidClass() throws Exception {
        User u = user("elig-expired");
        setState(paid, u, "ACTIVE", null);
        String id = event(paid, "PUBLIC", "Paid live");
        assertUrlHiddenAfter(paid, id, u, () -> setState(paid, u, "EXPIRED", Instant.now().minus(1, ChronoUnit.DAYS)), 403);
    }

    @Test
    @DisplayName("PAID class: an ACTIVE member whose paid access has lapsed (not yet swept) is treated as expired too")
    void lapsedMemberOfPaidClass() throws Exception {
        User u = user("elig-lapsed");
        setState(paid, u, "ACTIVE", Instant.now().plus(1, ChronoUnit.DAYS));
        String id = event(paid, "PUBLIC", "Paid lapse");
        assertUrlHiddenAfter(paid, id, u, () -> setState(paid, u, "ACTIVE", Instant.now().minus(1, ChronoUnit.MINUTES)), 403);
    }

    @Test
    @DisplayName("MEMBERS event of a public class: a member who registered and was then REMOVED gets no meetingUrl")
    void removedMemberOfMembersEvent() throws Exception {
        User u = user("elig-removed");
        setState(pub, u, "ACTIVE", null);
        String id = event(pub, "MEMBERS", "Members only");
        assertUrlHiddenAfter(pub, id, u, () -> setState(pub, u, "REMOVED", null), 403);
    }

    @Test
    @DisplayName("PUBLIC event of a public free class: an outsider who registered and was then BLOCKED in the class gets no meetingUrl")
    void blockedOutsiderOfOpenEvent() throws Exception {
        User u = user("elig-blocked");
        String id = event(pub, "PUBLIC", "Open to all");
        assertUrlHiddenAfter(pub, id, u, () -> setState(pub, u, "BLOCKED", null), 403);
    }

    @Test
    @DisplayName("a still-eligible registrant keeps the URL, and a repeat POST stays an idempotent 200")
    void eligibleRegistrantKeepsUrl() throws Exception {
        User u = user("elig-ok");
        setState(paid, u, "ACTIVE", null);
        String id = event(paid, "MEMBERS", "Still fine");
        assertEquals(200, register(id, u).status());
        Answer again = register(id, u);
        assertEquals(200, again.status());
        assertEquals(URL, again.data().path("meetingUrl").asText());
        assertEquals(1, again.data().path("registeredCount").asInt());
    }

    @Test
    @DisplayName("DELETE registrations/me with a registration in a class that is now hidden: the seat is freed, the answer is only {id, isRegistered:false}")
    void unregisterFromHiddenClass() throws Exception {
        User u = user("elig-hidden");
        setState(priv, u, "ACTIVE", null);
        String id = event(priv, "PUBLIC", "Phong kin bi mat");
        assertEquals(200, register(id, u).status());
        setState(priv, u, "REMOVED", null);
        assertEquals(404, get("/api/v1/events/" + id, u).status(), "the class is hidden from the caller now");

        Answer out = delete("/api/v1/events/" + id + "/registrations/me", u);
        assertEquals(200, out.status(), out.body());
        assertEquals(id, out.data().path("id").asText());
        assertFalse(out.data().path("isRegistered").asBoolean());
        assertEquals(2, out.data().size(), "minimal answer only: " + out.body());
        assertFalse(out.body().contains("Phong kin bi mat"));
        assertFalse(out.body().contains(URL));
        assertEquals(0, registrationRepository.countByEventId(id));
        assertEquals(0, eventRepository.findById(id).orElseThrow().getRegisteredCount());

        Answer again = delete("/api/v1/events/" + id + "/registrations/me", u);
        assertEquals(404, again.status(), "no registration left -> the normal visibility rule: hidden class is a 404");
    }

    @Test
    @DisplayName("DELETE registrations/me without a registration applies the visibility rule; on a visible class it answers the full event")
    void unregisterWithoutRegistration() throws Exception {
        String id = event(pub, "PUBLIC", "Khong dang ky");
        User u = user("elig-none");
        Answer visible = delete("/api/v1/events/" + id + "/registrations/me", u);
        assertEquals(200, visible.status(), visible.body());
        assertEquals("Khong dang ky", visible.data().path("title").asText());
        assertTrue(visible.data().path("meetingUrl").isNull());

        String hidden = event(priv, "PUBLIC", "Khong thay");
        Answer notFound = delete("/api/v1/events/" + hidden + "/registrations/me", u);
        assertEquals(404, notFound.status());
        assertFalse(notFound.body().contains("Khong thay"));

        Classroom archived = newClass(owner, "Elig luu tru", "PUBLIC");
        String archivedEvent = event(archived, "PUBLIC", "Lop da luu tru");
        archived.setStatus("ARCHIVED");
        classroomRepository.save(archived);
        assertEquals(403, delete("/api/v1/events/" + archivedEvent + "/registrations/me", u).status(),
                "an archived class is not visible to an outsider: same 403 as GET");
        assertEquals(get("/api/v1/events/" + archivedEvent, u).status(), 403);
    }
}
