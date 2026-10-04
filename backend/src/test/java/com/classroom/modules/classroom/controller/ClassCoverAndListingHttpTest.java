package com.classroom.modules.classroom.controller;

import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.event.model.ClassEvent;
import com.classroom.modules.event.repository.ClassEventRepository;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-27: the uploaded class cover (media purposes CLASS_COVER / BLOG / EVENT, {@code coverMediaId} on PUT /classes/{id}, {@code coverUrl}),
 * the new ClassroomDto fields ({@code ownerAvatarUrl}, {@code upcomingEventCount}) and {@code q} / {@code sort} on GET /classes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClassCoverAndListingHttpTest extends ClassContentHttpTestBase {

    @Autowired private ClassEventRepository eventRepository;

    private User owner;
    private User member;
    private User blogStaff;
    private User eventStaff;
    private User plainStaff;
    private User stranger;
    private Classroom cls;
    private Classroom other;

    @BeforeAll
    void fixtures() {
        owner = user("owner");
        owner.setAvatarUrl("https://avatars.example/d27-owner.png");
        owner = userRepository.save(owner);
        member = user("member");
        blogStaff = user("blog-staff");
        eventStaff = user("event-staff");
        plainStaff = user("plain-staff");
        stranger = user("stranger");
        cls = newClass(owner, "Lop anh bia", "PUBLIC");
        other = newClass(owner, "Lop khac", "PUBLIC");
        member(cls, member, "ACTIVE");
        staff(cls, blogStaff, "BLOG:CREATE");
        staff(cls, eventStaff, "EVENT:EDIT");
        staff(cls, plainStaff, "FEED:VIEW");
    }

    private Map<String, Object> intent(String purpose, String mime, long size) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("filename", "anh.png");
        b.put("mimeType", mime);
        b.put("sizeBytes", size);
        b.put("purpose", purpose);
        return b;
    }

    private Answer uploadIntent(String purpose, String mime, long size, User u) throws Exception {
        return post("/api/v1/classes/" + cls.getId() + "/media/upload-intents", intent(purpose, mime, size), u);
    }

    @Test
    @DisplayName("upload intents: CLASS_COVER needs CLASS:EDIT, BLOG needs BLOG:CREATE/EDIT, EVENT needs EVENT:CREATE/EDIT; images only, <= 5 MB")
    void coverPurposes() throws Exception {
        Answer ok = uploadIntent("CLASS_COVER", "image/png", 1000, owner);
        assertEquals(200, ok.status(), ok.body());
        assertEquals(400, uploadIntent("CLASS_COVER", "application/pdf", 1000, owner).status());
        assertEquals(400, uploadIntent("CLASS_COVER", "image/png", 5L * 1024 * 1024 + 1, owner).status());
        assertEquals(403, uploadIntent("CLASS_COVER", "image/png", 1000, member).status());
        assertEquals(403, uploadIntent("CLASS_COVER", "image/png", 1000, blogStaff).status());

        assertEquals(200, uploadIntent("BLOG", "image/webp", 1000, blogStaff).status());
        assertEquals(400, uploadIntent("BLOG", "video/mp4", 1000, blogStaff).status());
        assertEquals(403, uploadIntent("BLOG", "image/png", 1000, plainStaff).status());
        assertEquals(403, uploadIntent("BLOG", "image/png", 1000, eventStaff).status());

        assertEquals(200, uploadIntent("EVENT", "image/jpeg", 1000, eventStaff).status());
        assertEquals(403, uploadIntent("EVENT", "image/jpeg", 1000, blogStaff).status());
        assertEquals(401, uploadIntent("EVENT", "image/jpeg", 1000, null).status());
    }

    @Test
    @DisplayName("PUT /classes/{id} coverMediaId: an UPLOADED CLASS_COVER image of the class is signed into coverUrl; other assets are 400; '' clears")
    void classCover() throws Exception {
        String good = uploadedImage(cls, owner, "CLASS_COVER").getId();
        String foreign = uploadedImage(other, owner, "CLASS_COVER").getId();
        String blogImage = uploadedImage(cls, owner, "BLOG").getId();

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("title", cls.getTitle());
        b.put("coverMediaId", foreign);
        assertEquals(400, put("/api/v1/classes/" + cls.getId(), b, owner).status());
        b.put("coverMediaId", blogImage);
        assertEquals(400, put("/api/v1/classes/" + cls.getId(), b, owner).status());
        b.put("coverMediaId", UUID.randomUUID().toString());
        assertEquals(400, put("/api/v1/classes/" + cls.getId(), b, owner).status());

        b.put("coverMediaId", good);
        Answer set = put("/api/v1/classes/" + cls.getId(), b, owner);
        assertEquals(200, set.status(), set.body());
        assertEquals(good, set.data().path("coverMediaId").asText());
        assertTrue(set.data().path("coverUrl").asText().contains("X-Amz-Signature"));

        Answer guestView = get("/api/v1/classes/" + cls.getId(), null);
        assertTrue(guestView.data().path("coverUrl").asText().contains("X-Amz-Signature"), "a guest of a public class sees the cover");
        JsonNode listed = find(get("/api/v1/classes?size=100&q=Lop%20ANH%20bia", null).data(), cls.getId());
        assertNotNull(listed);
        assertTrue(listed.path("coverUrl").asText().contains("X-Amz-Signature"));
        assertEquals("https://avatars.example/d27-owner.png", listed.path("ownerAvatarUrl").asText());

        Map<String, Object> keep = new LinkedHashMap<>();
        keep.put("title", cls.getTitle());
        assertEquals(good, put("/api/v1/classes/" + cls.getId(), keep, owner).data().path("coverMediaId").asText(),
                "an absent coverMediaId keeps the cover");
        keep.put("coverMediaId", "");
        Answer cleared = put("/api/v1/classes/" + cls.getId(), keep, owner);
        assertTrue(cleared.data().path("coverMediaId").isNull());
        assertTrue(cleared.data().path("coverUrl").isNull());
    }

    @Test
    @DisplayName("upcomingEventCount counts SCHEDULED events that have not ended - in the detail and in the list")
    void upcomingEventCount() throws Exception {
        Classroom c = newClass(owner, "Dem su kien " + UUID.randomUUID().toString().substring(0, 6), "PUBLIC");
        Instant inADay = Instant.now().plus(1, ChronoUnit.DAYS);
        saveEvent(c, inADay, ClassEvent.STATUS_SCHEDULED);
        saveEvent(c, inADay.plus(1, ChronoUnit.DAYS), ClassEvent.STATUS_SCHEDULED);
        saveEvent(c, inADay, ClassEvent.STATUS_CANCELLED);
        saveEvent(c, Instant.now().minus(3, ChronoUnit.DAYS), ClassEvent.STATUS_SCHEDULED);

        assertEquals(2, get("/api/v1/classes/" + c.getId(), null).data().path("upcomingEventCount").asLong());
        JsonNode listed = find(get("/api/v1/classes?size=100&q=" + c.getTitle().replace(" ", "%20"), stranger).data(), c.getId());
        assertNotNull(listed);
        assertEquals(2, listed.path("upcomingEventCount").asLong());
    }

    private void saveEvent(Classroom c, Instant startsAt, String status) {
        ClassEvent e = new ClassEvent();
        e.setClassId(c.getId());
        e.setCreatedBy(owner.getId());
        e.setHostUserId(owner.getId());
        e.setTitle("Su kien dem");
        e.setFormat(ClassEvent.FORMAT_ONLINE);
        e.setStartsAt(startsAt.truncatedTo(ChronoUnit.MICROS));
        e.setEndsAt(startsAt.plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS));
        e.setAudience(ClassEvent.AUDIENCE_PUBLIC);
        e.setStatus(status);
        eventRepository.save(e);
    }

    @Test
    @DisplayName("GET /classes?q=: case-insensitive on title and description, never reveals a class the caller cannot see; sort=popular orders by members")
    void searchAndSort() throws Exception {
        String token = "zq" + UUID.randomUUID().toString().substring(0, 8);
        Classroom small = newClass(owner, "Lop " + token + " nho", "PUBLIC");
        Classroom big = newClass(owner, "Lop lon", "PUBLIC");
        big.setDescription("Mo ta co tu khoa " + token.toUpperCase());
        classroomRepository.save(big);
        Classroom middle = newClass(owner, "LOP " + token.toUpperCase() + " VUA", "PUBLIC");
        Classroom hidden = newClass(owner, "Lop " + token + " an", "PRIVATE");
        for (int i = 0; i < 3; i++) member(big, user("big" + i), "ACTIVE");
        member(middle, user("mid"), "ACTIVE");
        member(hidden, member, "ACTIVE");

        Answer newest = get("/api/v1/classes?size=100&q=" + token, null);
        assertEquals(200, newest.status(), newest.body());
        assertEquals(List.of(middle.getId(), big.getId(), small.getId()), ids(newest.data()), "newest first (default)");

        Answer popular = get("/api/v1/classes?size=100&sort=popular&q=%20" + token + "%20", stranger);
        assertEquals(200, popular.status(), popular.body());
        assertEquals(List.of(big.getId(), middle.getId(), small.getId()), ids(popular.data()), "most active members first");

        assertFalse(ids(get("/api/v1/classes?size=100&q=" + token, stranger).data()).contains(hidden.getId()));
        assertFalse(ids(get("/api/v1/classes?size=100&sort=popular&q=" + token, null).data()).contains(hidden.getId()));
        assertTrue(ids(get("/api/v1/classes?size=100&q=" + token, member).data()).contains(hidden.getId()), "a member finds their private class");
        assertTrue(ids(get("/api/v1/classes?size=100&sort=popular&q=" + token, member).data()).contains(hidden.getId()));

        assertTrue(ids(get("/api/v1/classes?size=100&q=%25", null).data()).size() > 0, "a lone typed % is no search (never a SQL error)");
        assertEquals(0, ids(get("/api/v1/classes?size=100&q=" + token + "%25x", null).data()).size());
        assertEquals(400, get("/api/v1/classes?q=" + "a".repeat(101), null).status());
        assertEquals(400, get("/api/v1/classes?sort=oldest", null).status());
        Answer paged = get("/api/v1/classes?size=2&page=1&sort=popular&q=" + token, null);
        assertEquals(List.of(small.getId()), ids(paged.data()), "page / size still apply");
    }

    private static List<String> ids(JsonNode array) {
        List<String> ids = new ArrayList<>();
        array.forEach(n -> ids.add(n.path("id").asText()));
        return ids;
    }

    private static JsonNode find(JsonNode items, String id) {
        for (JsonNode n : items) {
            if (id.equals(n.path("id").asText())) return n;
        }
        return null;
    }
}
