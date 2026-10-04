package com.classroom.modules.blog.controller;

import com.classroom.modules.audit.repository.AuditEventRepository;
import com.classroom.modules.blog.service.BlogService;
import com.classroom.modules.classroom.controller.ClassContentHttpTestBase;
import com.classroom.modules.classroom.model.Classroom;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-27: the class blog through the real servlet chain - authorisation matrix (guest / stranger / member / staff with and without a BLOG
 * grant / owner), drafts, MEMBERS posts locked for non-members, a PRIVATE class being a 404, keyset paging, categories, archived classes and
 * the audit trail.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BlogHttpTest extends ClassContentHttpTestBase {

    @Autowired private AuditEventRepository auditEventRepository;

    private User owner;
    private User member;
    private User editor;      // staff with every BLOG action
    private User plainStaff;  // staff without any BLOG grant
    private User stranger;
    private Classroom pub;
    private Classroom priv;

    @BeforeAll
    void fixtures() {
        owner = user("owner");
        member = user("member");
        editor = user("editor");
        plainStaff = user("plain-staff");
        stranger = user("stranger");
        pub = newClass(owner, "Blog cong khai", "PUBLIC");
        priv = newClass(owner, "Blog rieng tu", "PRIVATE");
        for (Classroom c : List.of(pub, priv)) {
            member(c, member, "ACTIVE");
            staff(c, editor, "BLOG:VIEW", "BLOG:CREATE", "BLOG:EDIT", "BLOG:PUBLISH", "BLOG:DELETE");
            staff(c, plainStaff, "FEED:VIEW");
        }
    }

    private Map<String, Object> body(String title, String content, String audience) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("title", title);
        b.put("contentMarkdown", content);
        b.put("audience", audience);
        return b;
    }

    /** Creates and publishes a post as the owner; returns its id. */
    private String published(Classroom c, String title, String audience, String category) throws Exception {
        Map<String, Object> b = body(title, "Noi dung bai viet " + title + " voi vai chu.", audience);
        b.put("category", category);
        b.put("excerpt", "Tom tat " + title);
        Answer created = post("/api/v1/classes/" + c.getId() + "/blog-posts", b, owner);
        assertEquals(200, created.status(), created.body());
        String id = created.data().path("id").asText();
        Answer pubd = post("/api/v1/blog-posts/" + id + "/publish", null, owner);
        assertEquals(200, pubd.status(), pubd.body());
        return id;
    }

    @Test
    @DisplayName("POST blog-posts: guest 401, stranger / member / staff without BLOG:CREATE 403, staff with the grant and the owner create a DRAFT")
    void createMatrix() throws Exception {
        String path = "/api/v1/classes/" + pub.getId() + "/blog-posts";
        Map<String, Object> b = body("Bai moi", "Noi dung", "PUBLIC");
        assertEquals(401, post(path, b, null).status());
        for (User u : new User[]{stranger, member, plainStaff}) {
            Answer a = post(path, b, u);
            assertEquals(403, a.status(), u.getEmail() + " -> " + a.body());
            assertEquals("STAFF_PERMISSION_DENIED", a.code());
        }
        for (User u : new User[]{editor, owner}) {
            Answer a = post(path, b, u);
            assertEquals(200, a.status(), a.body());
            assertEquals("DRAFT", a.data().path("status").asText());
            assertEquals(u.getId(), a.data().path("author").path("id").asText());
            assertEquals(u.getFullName(), a.data().path("author").path("fullName").asText());
            assertNull(a.data().path("publishedAt").textValue());
            assertFalse(a.data().path("locked").asBoolean());
        }
    }

    @Test
    @DisplayName("request validation: title > 200, unknown audience and a missing audience are 400")
    void validation() throws Exception {
        String path = "/api/v1/classes/" + pub.getId() + "/blog-posts";
        assertEquals(400, post(path, body("x".repeat(201), "c", "PUBLIC"), owner).status());
        assertEquals(400, post(path, body("ok", "c", "EVERYONE"), owner).status());
        assertEquals(400, post(path, body("ok", "c", null), owner).status());
        Map<String, Object> longExcerpt = body("ok", "c", "PUBLIC");
        longExcerpt.put("excerpt", "e".repeat(301));
        assertEquals(400, post(path, longExcerpt, owner).status());
        Map<String, Object> longCategory = body("ok", "c", "PUBLIC");
        longCategory.put("category", "c".repeat(61));
        assertEquals(400, post(path, longCategory, owner).status());
    }

    @Test
    @DisplayName("a DRAFT is a 404 for guests, members and staff without a BLOG grant; editors see it and can list drafts with status=DRAFT|ALL")
    void draftVisibility() throws Exception {
        Answer created = post("/api/v1/classes/" + pub.getId() + "/blog-posts", body("Ban nhap bi mat", "noi dung", "PUBLIC"), owner);
        String id = created.data().path("id").asText();
        for (User u : new User[]{null, stranger, member, plainStaff}) {
            Answer a = get("/api/v1/blog-posts/" + id, u);
            assertEquals(404, a.status(), "draft visible to " + (u == null ? "guest" : u.getEmail()));
            assertEquals("NOT_FOUND", a.code());
        }
        assertEquals(200, get("/api/v1/blog-posts/" + id, editor).status());
        assertEquals(200, get("/api/v1/blog-posts/" + id, owner).status());

        assertFalse(get("/api/v1/classes/" + pub.getId() + "/blog-posts?size=50", owner).body().contains(id),
                "the default list is PUBLISHED only, even for the owner");
        Answer memberDrafts = get("/api/v1/classes/" + pub.getId() + "/blog-posts?status=DRAFT", member);
        assertEquals(403, memberDrafts.status());
        assertEquals(403, get("/api/v1/classes/" + pub.getId() + "/blog-posts?status=ALL", null).status());
        assertTrue(get("/api/v1/classes/" + pub.getId() + "/blog-posts?status=DRAFT&size=50", editor).body().contains(id));
        assertTrue(get("/api/v1/classes/" + pub.getId() + "/blog-posts?status=ALL&size=50", owner).body().contains(id));
        assertEquals(400, get("/api/v1/classes/" + pub.getId() + "/blog-posts?status=BOGUS", owner).status());
    }

    @Test
    @DisplayName("a MEMBERS post is listed for everybody who can see the class but locked (no content) for guests and non-members")
    void membersPostsAreLocked() throws Exception {
        String id = published(pub, "Chi cho thanh vien", "MEMBERS", "Kinh nghiem");
        for (User u : new User[]{null, stranger}) {
            Answer one = get("/api/v1/blog-posts/" + id, u);
            assertEquals(200, one.status(), one.body());
            assertTrue(one.data().path("locked").asBoolean());
            assertTrue(one.data().path("contentMarkdown").isNull());
            assertEquals("Chi cho thanh vien", one.data().path("title").asText());

            JsonNode item = find(get("/api/v1/classes/" + pub.getId() + "/blog-posts?size=50", u).data().path("items"), id);
            assertNotNull(item, "the locked card is still listed");
            assertTrue(item.path("locked").asBoolean());
        }
        for (User u : new User[]{member, plainStaff, editor, owner}) {
            Answer one = get("/api/v1/blog-posts/" + id, u);
            assertEquals(200, one.status());
            assertFalse(one.data().path("locked").asBoolean(), u.getEmail());
            assertTrue(one.data().path("contentMarkdown").asText().startsWith("Noi dung bai viet"));
        }
        // lists never carry the content
        JsonNode item = find(get("/api/v1/classes/" + pub.getId() + "/blog-posts?size=50", member).data().path("items"), id);
        assertFalse(item.path("locked").asBoolean());
        assertTrue(item.path("contentMarkdown").isNull());
        assertTrue(item.path("readingMinutes").asInt() >= 1);
    }

    @Test
    @DisplayName("publish needs BLOG:PUBLISH and non-blank content; first publish sets publishedAt, unpublish / republish keeps it")
    void publishLifecycle() throws Exception {
        Map<String, Object> noContent = new LinkedHashMap<>();
        noContent.put("title", "Chua co noi dung");
        noContent.put("audience", "PUBLIC");
        Answer created = post("/api/v1/classes/" + pub.getId() + "/blog-posts", noContent, owner);
        assertEquals(200, created.status(), created.body());
        String id = created.data().path("id").asText();
        assertEquals(400, post("/api/v1/blog-posts/" + id + "/publish", null, owner).status());
        assertEquals(403, post("/api/v1/blog-posts/" + id + "/publish", null, plainStaff).status());

        assertEquals(200, put("/api/v1/blog-posts/" + id, Map.of("contentMarkdown", "Bay gio da co noi dung"), editor).status());
        Answer first = post("/api/v1/blog-posts/" + id + "/publish", null, editor);
        assertEquals(200, first.status(), first.body());
        String publishedAt = first.data().path("publishedAt").asText();
        assertFalse(publishedAt.isBlank());
        assertEquals("PUBLISHED", first.data().path("status").asText());

        Answer un = post("/api/v1/blog-posts/" + id + "/unpublish", null, editor);
        assertEquals("DRAFT", un.data().path("status").asText());
        assertEquals(404, get("/api/v1/blog-posts/" + id, member).status());
        Answer again = post("/api/v1/blog-posts/" + id + "/publish", null, owner);
        assertEquals(publishedAt, again.data().path("publishedAt").asText(), "republishing keeps the original publishedAt");

        assertEquals(400, put("/api/v1/blog-posts/" + id, Map.of("contentMarkdown", "   "), owner).status(),
                "a published post cannot be emptied");
    }

    @Test
    @DisplayName("PUT: absent fields are kept, an explicit null / empty string clears excerpt and category; staff without BLOG:EDIT get 403")
    void updateSemantics() throws Exception {
        String id = published(pub, "Bai de sua", "PUBLIC", "Chuyen muc A");
        Answer kept = put("/api/v1/blog-posts/" + id, Map.of("title", "Tieu de moi"), owner);
        assertEquals(200, kept.status(), kept.body());
        assertEquals("Tieu de moi", kept.data().path("title").asText());
        assertEquals("Tom tat Bai de sua", kept.data().path("excerpt").asText());
        assertEquals("Chuyen muc A", kept.data().path("category").asText());

        Answer cleared = put("/api/v1/blog-posts/" + id, "{\"excerpt\":null,\"category\":\"\"}", owner);
        assertEquals(200, cleared.status(), cleared.body());
        assertTrue(cleared.data().path("excerpt").isNull());
        assertTrue(cleared.data().path("category").isNull());
        assertEquals("Tieu de moi", cleared.data().path("title").asText());

        assertEquals(403, put("/api/v1/blog-posts/" + id, Map.of("title", "x"), plainStaff).status());
        assertEquals(403, put("/api/v1/blog-posts/" + id, Map.of("title", "x"), member).status());
        assertEquals(401, put("/api/v1/blog-posts/" + id, Map.of("title", "x"), null).status());
    }

    @Test
    @DisplayName("a PRIVATE class: list, categories and the post are 404 for guests and strangers - identical to an unknown id; members read it")
    void privateClassIsNotFound() throws Exception {
        String id = published(priv, "Bai lop rieng", "PUBLIC", "Rieng");
        Answer unknown = get("/api/v1/blog-posts/" + java.util.UUID.randomUUID(), stranger);
        for (User u : new User[]{null, stranger}) {
            Answer list = get("/api/v1/classes/" + priv.getId() + "/blog-posts", u);
            assertEquals(404, list.status());
            assertEquals("NOT_FOUND", list.code());
            assertEquals(404, get("/api/v1/classes/" + priv.getId() + "/blog-categories", u).status());
            Answer one = get("/api/v1/blog-posts/" + id, u);
            assertEquals(404, one.status());
            assertEquals(unknown.message(), one.message());
            assertFalse(one.body().contains("Bai lop rieng"));
        }
        Answer update = put("/api/v1/blog-posts/" + id, Map.of("title", "x"), stranger);
        assertEquals(404, update.status(), "a write on a hidden class's post is a 404 too, not a 403");
        assertEquals(200, get("/api/v1/blog-posts/" + id, member).status());
        assertEquals(200, get("/api/v1/classes/" + priv.getId() + "/blog-posts", member).status());
    }

    @Test
    @DisplayName("keyset paging walks every published post exactly once, newest publishedAt first; a forged cursor is a 400")
    void keysetPaging() throws Exception {
        Classroom c = newClass(owner, "Blog phan trang", "PUBLIC");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(published(c, "Trang " + i, "PUBLIC", i % 2 == 0 ? "Chan" : "Le"));
        }
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            Answer page = get("/api/v1/classes/" + c.getId() + "/blog-posts?size=2" + (cursor == null ? "" : "&cursor=" + cursor), null);
            assertEquals(200, page.status(), page.body());
            page.data().path("items").forEach(n -> seen.add(n.path("id").asText()));
            cursor = page.data().path("nextCursor").isNull() ? null : page.data().path("nextCursor").asText(null);
            pages++;
        } while (cursor != null && pages < 10);
        assertEquals(3, pages);
        List<String> expected = new ArrayList<>(ids);
        java.util.Collections.reverse(expected);
        assertEquals(expected, seen, "newest first, no gaps, no repeats");

        Answer even = get("/api/v1/classes/" + c.getId() + "/blog-posts?category=Chan", null);
        assertEquals(3, even.data().path("items").size());
        Answer cats = get("/api/v1/classes/" + c.getId() + "/blog-categories", null);
        assertEquals(List.of("Chan", "Le"), JSON.convertValue(cats.data(), List.class));

        assertEquals(400, get("/api/v1/classes/" + c.getId() + "/blog-posts?cursor=not-a-cursor", null).status());
        Answer oversized = get("/api/v1/classes/" + c.getId() + "/blog-posts?size=500", null);
        assertEquals(200, oversized.status(), "size is clamped to 50, not rejected");
        assertEquals(5, oversized.data().path("items").size());
    }

    @Test
    @DisplayName("DELETE needs BLOG:DELETE, removes the post and every write is audited")
    void deleteAndAudit() throws Exception {
        String id = published(pub, "Bai se xoa", "PUBLIC", null);
        assertEquals(403, delete("/api/v1/blog-posts/" + id, plainStaff).status());
        assertEquals(200, delete("/api/v1/blog-posts/" + id, editor).status());
        assertEquals(404, get("/api/v1/blog-posts/" + id, owner).status());
        Set<String> actions = new HashSet<>();
        auditEventRepository.findByClassIdOrderByCreatedAtDesc(pub.getId()).stream()
                .filter(e -> id.equals(e.getTargetId()))
                .forEach(e -> actions.add(e.getAction()));
        assertEquals(Set.of("BLOG_POST_CREATE", "BLOG_POST_PUBLISH", "BLOG_POST_DELETE"), actions);
    }

    @Test
    @DisplayName("an ARCHIVED class takes no new posts (409) but stays readable")
    void archivedClassIsReadOnly() throws Exception {
        Classroom c = newClass(owner, "Blog luu tru", "PUBLIC");
        String id = published(c, "Bai cu", "PUBLIC", null);
        c.setStatus("ARCHIVED");
        classroomRepository.save(c);
        Answer a = post("/api/v1/classes/" + c.getId() + "/blog-posts", body("Moi", "c", "PUBLIC"), owner);
        assertEquals(409, a.status(), a.body());
        assertEquals(200, get("/api/v1/blog-posts/" + id, owner).status());
    }

    @Test
    @DisplayName("a cover must be an UPLOADED BLOG image of the same class; its presigned URL is returned with the post")
    void coverImage() throws Exception {
        String goodId = uploadedImage(pub, owner, "BLOG").getId();
        String otherClass = uploadedImage(priv, owner, "BLOG").getId();
        String wrongPurpose = uploadedImage(pub, owner, "ABOUT").getId();
        Map<String, Object> b = body("Co anh bia", "noi dung", "PUBLIC");
        b.put("coverMediaId", otherClass);
        assertEquals(400, post("/api/v1/classes/" + pub.getId() + "/blog-posts", b, owner).status());
        b.put("coverMediaId", wrongPurpose);
        assertEquals(400, post("/api/v1/classes/" + pub.getId() + "/blog-posts", b, owner).status());
        b.put("coverMediaId", goodId);
        Answer ok = post("/api/v1/classes/" + pub.getId() + "/blog-posts", b, owner);
        assertEquals(200, ok.status(), ok.body());
        assertEquals(goodId, ok.data().path("coverMediaId").asText());
        assertTrue(ok.data().path("coverUrl").asText().contains("X-Amz-Signature"), ok.body());
    }

    @Test
    @DisplayName("reading minutes = ceil(words / 200), at least 1")
    void readingMinutes() {
        assertEquals(1, BlogService.readingMinutes(null));
        assertEquals(1, BlogService.readingMinutes("  "));
        assertEquals(1, BlogService.readingMinutes("một hai ba"));
        assertEquals(1, BlogService.readingMinutes("chu ".repeat(200)));
        assertEquals(2, BlogService.readingMinutes("chu ".repeat(201)));
        assertEquals(3, BlogService.readingMinutes("## Tiêu đề\n\n" + "từ ".repeat(450) + " --- ***"));
    }

    private static JsonNode find(JsonNode items, String id) {
        for (JsonNode n : items) {
            if (id.equals(n.path("id").asText())) return n;
        }
        return null;
    }
}
