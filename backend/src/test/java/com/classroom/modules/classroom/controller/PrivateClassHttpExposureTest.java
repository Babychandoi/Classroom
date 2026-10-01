package com.classroom.modules.classroom.controller;

import com.classroom.config.JwtTokenProvider;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.classroom.service.ClassroomService;
import com.classroom.modules.classroom.dto.CreateClassroomRequest;
import com.classroom.modules.community.dto.CreatePostRequest;
import com.classroom.modules.community.service.FeedService;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.media.model.MediaAsset;
import com.classroom.modules.media.repository.MediaAssetRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * D-19 / security checklist E, through the REAL servlet chain (Spring Security -> filters -> controllers -> services -> H2): a PRIVATE class does
 * not exist for anybody who is not its owner, staff or (ACTIVE / EXPIRED) member.
 *
 * <p>For every class-scoped route and for each kind of outsider - an anonymous visitor, a logged-in stranger, a REMOVED and a BLOCKED person -
 * the answer for the private class is compared with the answer for a class id (or slug) that really does not exist: same HTTP status, same
 * error code, same message (the class id / slug itself normalised). Reads, writes, studio routes, the anonymous {@code permitAll} routes and the
 * media URL of a file inside the class are all in the table. None of the responses may contain the class's id, slug or title.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PrivateClassHttpExposureTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassroomRepository classroomRepository;
    @Autowired private ClassMemberRepository memberRepository;
    @Autowired private MediaAssetRepository mediaAssetRepository;
    @Autowired private ClassroomService classroomService;
    @Autowired private FeedService feedService;
    @Autowired private JwtTokenProvider tokens;

    private User owner;
    private User member;
    private User expired;
    private User stranger;
    private User removed;
    private User blocked;
    private Classroom hidden;
    private String hiddenTitle;
    private String mediaId;

    private User user(String tag) {
        return userRepository.save(new User(UUID.randomUUID().toString(),
                "http-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8) + "@d19.test", "hash", "Http " + tag, "USER"));
    }

    @BeforeAll
    void fixtures() {
        owner = user("owner");
        member = user("member");
        expired = user("expired");
        stranger = user("stranger");
        removed = user("removed");
        blocked = user("blocked");

        CreateClassroomRequest req = new CreateClassroomRequest();
        hiddenTitle = "Lop Kin Bi Mat " + UUID.randomUUID().toString().substring(0, 8);
        req.setTitle(hiddenTitle);
        req.setSlug("kin-" + UUID.randomUUID().toString().substring(0, 10));
        req.setDescription("Noi dung kin");
        req.setVisibility("PRIVATE");
        hidden = classroomRepository.findById(classroomService.createClassroom(owner.getId(), req).getId()).orElseThrow();

        addRow(member, "ACTIVE", null);
        addRow(expired, "EXPIRED", Instant.now().minus(1, ChronoUnit.DAYS));
        addRow(removed, "REMOVED", null);
        addRow(blocked, "BLOCKED", null);
        // a PUBLIC post (what a guest could read in a public class) and a file in the class
        feedService.createPost(hidden.getId(), owner.getId(), new CreatePostRequest("Bai cong khai", "Noi dung bai viet", "PUBLIC", null, null, false));
        MediaAsset asset = new MediaAsset(hidden.getId(), owner.getId(), "obj/" + UUID.randomUUID(), "tai-lieu.pdf", "application/pdf", 10);
        asset.setStatus("UPLOADED");
        mediaId = mediaAssetRepository.save(asset).getId();
    }

    private void addRow(User u, String state, Instant expiresAt) {
        ClassMember m = new ClassMember(hidden.getId(), u.getId(), "STUDENT");
        m.setState(state);
        m.setAccessExpiresAt(expiresAt);
        memberRepository.save(m);
    }

    /** One request as one viewer, reduced to what an observer can compare. */
    private record Answer(int status, String code, String message, String body) {}

    private Answer call(HttpMethod method, String path, String body, User as) throws Exception {
        MockHttpServletRequestBuilder b = request(method, path);
        if (as != null) {
            b.header("Authorization", "Bearer " + tokens.generateToken(as.getId(), as.getEmail(), as.getRole()));
        }
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        MockHttpServletResponse res = mockMvc.perform(b).andReturn().getResponse();
        String text = res.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String code = "";
        String message = "";
        if (!text.isBlank() && text.trim().startsWith("{")) {
            JsonNode root = JSON.readTree(text);
            code = root.path("error").path("code").asText("");
            message = root.path("error").path("message").asText("");
        }
        return new Answer(res.getStatus(), code, message, text);
    }

    /** The table of routes (method, path with {id}/{slug}, optional body). {@code classKeyed} routes must match in message too. */
    private record Route(HttpMethod method, String path, String body, boolean anonymousAllowed) {}

    private List<Route> routes() {
        List<Route> r = new ArrayList<>();
        // the anonymous (permitAll) routes
        r.add(new Route(HttpMethod.GET, "/api/v1/classes/{id}", null, true));
        r.add(new Route(HttpMethod.GET, "/api/v1/classes/slug/{slug}", null, true));
        r.add(new Route(HttpMethod.GET, "/api/v1/classes/{id}/about", null, true));
        r.add(new Route(HttpMethod.GET, "/api/v1/classes/{id}/products", null, true));
        r.add(new Route(HttpMethod.GET, "/api/v1/classes/{id}/posts", null, true));
        r.add(new Route(HttpMethod.GET, "/api/v1/classes/{id}/posts?size=5&cursor=abc", null, true));
        // member-only reads
        r.add(new Route(HttpMethod.GET, "/api/v1/classes/{id}/members", null, false));
        r.add(new Route(HttpMethod.GET, "/api/v1/classes/{id}/leaderboard", null, false));
        r.add(new Route(HttpMethod.GET, "/api/v1/classes/{id}/documents", null, false));
        r.add(new Route(HttpMethod.GET, "/api/v1/classes/{id}/courses", null, false));
        r.add(new Route(HttpMethod.GET, "/api/v1/classes/{id}/exams", null, false));
        // writes
        r.add(new Route(HttpMethod.POST, "/api/v1/classes/{id}/join", null, false));
        r.add(new Route(HttpMethod.POST, "/api/v1/classes/{id}/posts", "{\"title\":\"x\",\"contentMarkdown\":\"y\",\"visibility\":\"FREE\"}", false));
        r.add(new Route(HttpMethod.PUT, "/api/v1/classes/{id}", "{\"title\":\"hijack\"}", false));
        r.add(new Route(HttpMethod.PUT, "/api/v1/classes/{id}/access", "{\"accessType\":\"PAID\",\"price\":1000,\"durationDays\":1}", false));
        r.add(new Route(HttpMethod.PUT, "/api/v1/classes/{id}/status", "{\"status\":\"ARCHIVED\"}", false));
        // invites (management)
        r.add(new Route(HttpMethod.GET, "/api/v1/classes/{id}/invites", null, false));
        r.add(new Route(HttpMethod.POST, "/api/v1/classes/{id}/invites", "{}", false));
        // studio
        r.add(new Route(HttpMethod.GET, "/api/v1/studio/classes/{id}/overview", null, false));
        r.add(new Route(HttpMethod.GET, "/api/v1/studio/classes/{id}/outbox/status", null, false));
        r.add(new Route(HttpMethod.POST, "/api/v1/studio/classes/{id}/outbox/replay", null, false));
        r.add(new Route(HttpMethod.GET, "/api/v1/classes/{id}/studio/members", null, false));
        r.add(new Route(HttpMethod.GET, "/api/v1/classes/{id}/studio/products", null, false));
        r.add(new Route(HttpMethod.GET, "/api/v1/classes/{id}/staff", null, false));
        r.add(new Route(HttpMethod.GET, "/api/v1/classes/{id}/audit", null, false));
        r.add(new Route(HttpMethod.GET, "/api/v1/classes/{id}/orders", null, false));
        r.add(new Route(HttpMethod.GET, "/api/v1/classes/{id}/segments", null, false));
        r.add(new Route(HttpMethod.POST, "/api/v1/classes/{id}/media/upload-intents",
                "{\"filename\":\"a.pdf\",\"mimeType\":\"application/pdf\",\"sizeBytes\":10,\"purpose\":\"DOCUMENT\"}", false));
        // the rest of the controllers that take a class id (writes, queues, configuration, per-member studio actions)
        r.add(new Route(HttpMethod.GET, "/api/v1/classes/{id}/assignment-queue", null, false));
        r.add(new Route(HttpMethod.GET, "/api/v1/classes/{id}/grading-queue", null, false));
        r.add(new Route(HttpMethod.POST, "/api/v1/classes/{id}/exams", "{\"title\":\"t\",\"audienceScope\":\"ALL\",\"durationMinutes\":30}", false));
        r.add(new Route(HttpMethod.POST, "/api/v1/classes/{id}/courses", "{\"title\":\"t\",\"accessMode\":\"FREE\"}", false));
        r.add(new Route(HttpMethod.PUT, "/api/v1/classes/{id}/courses/reorder", "[\"a\",\"b\"]", false));
        r.add(new Route(HttpMethod.POST, "/api/v1/classes/{id}/documents", "{\"title\":\"t\",\"mediaAssetId\":\"m\"}", false));
        r.add(new Route(HttpMethod.POST, "/api/v1/classes/{id}/products", "{\"title\":\"t\",\"price\":1000,\"durationDays\":5}", false));
        r.add(new Route(HttpMethod.POST, "/api/v1/classes/{id}/segments", "{\"name\":\"s\",\"rules\":[]}", false));
        r.add(new Route(HttpMethod.POST, "/api/v1/classes/{id}/leaderboard/rebuild", null, false));
        r.add(new Route(HttpMethod.GET, "/api/v1/classes/{id}/leaderboard/configuration", null, false));
        r.add(new Route(HttpMethod.PUT, "/api/v1/classes/{id}/leaderboard/configuration", "{}", false));
        r.add(new Route(HttpMethod.PUT, "/api/v1/classes/{id}/staff/some-user/permissions", "[]", false));
        r.add(new Route(HttpMethod.DELETE, "/api/v1/classes/{id}/staff/some-user", null, false));
        r.add(new Route(HttpMethod.POST, "/api/v1/classes/{id}/studio/members/some-user/remove", null, false));
        r.add(new Route(HttpMethod.POST, "/api/v1/classes/{id}/studio/members/some-user/block", null, false));
        r.add(new Route(HttpMethod.POST, "/api/v1/classes/{id}/studio/members/some-user/unblock", null, false));
        r.add(new Route(HttpMethod.GET, "/api/v1/users/some-user?classId={id}", null, false));
        r.add(new Route(HttpMethod.GET, "/api/v1/users/some-user/journey?classId={id}", null, false));
        r.add(new Route(HttpMethod.POST, "/api/v1/orders", "{\"classId\":\"{id}\",\"productId\":\"p\",\"idempotencyKey\":\"k-1234\"}", false));
        return r;
    }

    private static String fill(String path, String id, String slug) {
        return path.replace("{id}", id).replace("{slug}", slug);
    }

    /** Message with the class id / slug replaced by a placeholder, so "Không tìm thấy lớp học với id: X" compares equal for two different ids. */
    private static String normalised(String message, String id, String slug) {
        return message.replace(id, "<ID>").replace(slug, "<SLUG>");
    }

    @Test
    @DisplayName("every class-scoped route answers a PRIVATE class exactly like a class that does not exist - for a guest, a stranger, a REMOVED and a BLOCKED person")
    void privateIsIndistinguishableFromMissing() throws Exception {
        String missingId = UUID.randomUUID().toString();
        String missingSlug = "khong-ton-tai-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, User> viewers = new LinkedHashMap<>();
        viewers.put("guest", null);
        viewers.put("stranger", stranger);
        viewers.put("removed", removed);
        viewers.put("blocked", blocked);

        int compared = 0;
        for (Map.Entry<String, User> viewer : viewers.entrySet()) {
            for (Route route : routes()) {
                Answer real = call(route.method(), fill(route.path(), hidden.getId(), hidden.getSlug()), route.body() == null ? null : fill(route.body(), hidden.getId(), hidden.getSlug()), viewer.getValue());
                Answer none = call(route.method(), fill(route.path(), missingId, missingSlug), route.body() == null ? null : fill(route.body(), missingId, missingSlug), viewer.getValue());
                String label = viewer.getKey() + " " + route.method() + " " + route.path();
                assertEquals(none.status(), real.status(), label + " status");
                assertEquals(none.code(), real.code(), label + " error code");
                assertEquals(normalised(none.message(), missingId, missingSlug), normalised(real.message(), hidden.getId(), hidden.getSlug()), label + " message");
                // the only place the id / slug may appear is the caller's own input echoed back ("...với id: X"), exactly as for a missing class
                String withoutEcho = real.body().replace("id: " + hidden.getId(), "").replace("slug: " + hidden.getSlug(), "");
                assertFalse(withoutEcho.contains(hidden.getId()), label + " leaks the class id: " + real.body());
                assertFalse(withoutEcho.contains(hidden.getSlug()), label + " leaks the slug");
                assertFalse(real.body().contains(hiddenTitle), label + " leaks the title");
                assertFalse(real.body().contains("Bai cong khai"), label + " leaks a post");
                assertTrue(real.status() >= 400, label + " must be an error, was " + real.status());
                compared++;
            }
        }
        assertTrue(compared >= 4 * 45, "compared " + compared + " route x viewer pairs");
    }

    @Test
    @DisplayName("GET /me/orders?classId= is the caller's OWN orders: an empty 200 for a private class and for a missing one alike - nothing to learn")
    void myOrdersFilterRevealsNothing() throws Exception {
        Answer real = call(HttpMethod.GET, "/api/v1/me/orders?classId=" + hidden.getId(), null, stranger);
        Answer none = call(HttpMethod.GET, "/api/v1/me/orders?classId=" + UUID.randomUUID(), null, stranger);
        assertEquals(200, real.status());
        assertEquals(none.status(), real.status());
        assertEquals(0, JSON.readTree(real.body()).path("data").size());
        assertEquals(0, JSON.readTree(none.body()).path("data").size());
    }

    @Test
    @DisplayName("the anonymous routes are plain 404 for a private class: guests and strangers get NOT_FOUND (never 401 / 403, which would reveal it exists)")
    void anonymousRoutesAre404() throws Exception {
        for (Route route : routes().stream().filter(Route::anonymousAllowed).toList()) {
            for (User viewer : new User[]{null, stranger}) {
                Answer a = call(route.method(), fill(route.path(), hidden.getId(), hidden.getSlug()), route.body(), viewer);
                assertEquals(404, a.status(), route.path() + " as " + (viewer == null ? "guest" : "stranger") + " -> " + a.body());
                assertEquals("NOT_FOUND", a.code());
            }
        }
    }

    @Test
    @DisplayName("the class list, a search-style query string and sitemap-like URLs never reveal a private class to a guest or a stranger")
    void listingsAndDiscoveryUrls() throws Exception {
        String[] urls = {
                "/api/v1/classes", "/api/v1/classes?page=0&size=100",
                "/api/v1/classes?q=" + hiddenTitle.replace(' ', '+') + "&search=" + hidden.getSlug() + "&title=" + hidden.getSlug()
                        + "&slug=" + hidden.getSlug() + "&ownerId=" + owner.getId() + "&visibility=PRIVATE",
                "/api/v1/classes/sitemap.xml", "/sitemap.xml", "/api/v1/sitemap", "/robots.txt"};
        for (User viewer : new User[]{null, stranger, removed, blocked}) {
            for (String url : urls) {
                Answer a = call(HttpMethod.GET, url, null, viewer);
                assertFalse(a.body().contains(hidden.getId()), url + " leaks the id");
                assertFalse(a.body().contains(hidden.getSlug()), url + " leaks the slug");
                assertFalse(a.body().contains(hiddenTitle), url + " leaks the title");
            }
        }
    }

    @Test
    @DisplayName("a file inside a private class: its download URL is 404 for an outsider (nothing is signed), and a BLOCKED / REMOVED person cannot use it either")
    void mediaUrlsAreClosed() throws Exception {
        for (User viewer : new User[]{stranger, removed, blocked}) {
            for (String path : new String[]{"/api/v1/media/" + mediaId + "/download-url", "/api/v1/media/" + mediaId + "/download"}) {
                Answer a = call(HttpMethod.GET, path, null, viewer);
                Answer none = call(HttpMethod.GET, path.replace(mediaId, UUID.randomUUID().toString()), null, viewer);
                assertEquals(404, a.status(), path);
                assertEquals(none.status(), a.status());
                assertEquals(none.code(), a.code());
                assertFalse(a.body().contains("http"), "no URL was produced: " + a.body());
            }
        }
        assertEquals(401, call(HttpMethod.GET, "/api/v1/media/" + mediaId + "/download-url", null, null).status());
    }

    @Test
    @DisplayName("people the class does know - owner, member, EXPIRED member - can read the private class card, About, products and the owner/member the feed")
    void insidersCanRead() throws Exception {
        for (User viewer : new User[]{owner, member, expired}) {
            for (String path : new String[]{"/api/v1/classes/" + hidden.getId(), "/api/v1/classes/slug/" + hidden.getSlug(),
                    "/api/v1/classes/" + hidden.getId() + "/about", "/api/v1/classes/" + hidden.getId() + "/products"}) {
                assertEquals(200, call(HttpMethod.GET, path, null, viewer).status(), path + " as " + viewer.getEmail());
            }
        }
        assertEquals(200, call(HttpMethod.GET, "/api/v1/classes/" + hidden.getId() + "/posts", null, member).status());
        assertEquals(200, call(HttpMethod.GET, "/api/v1/classes/" + hidden.getId() + "/posts", null, owner).status());
        Answer expiredFeed = call(HttpMethod.GET, "/api/v1/classes/" + hidden.getId() + "/posts", null, expired);
        assertEquals(403, expiredFeed.status());
        assertEquals("MEMBERSHIP_EXPIRED", expiredFeed.code());
        Answer expiredMembers = call(HttpMethod.GET, "/api/v1/classes/" + hidden.getId() + "/members", null, expired);
        assertEquals("MEMBERSHIP_EXPIRED", expiredMembers.code());
        assertEquals(200, call(HttpMethod.GET, "/api/v1/classes/" + hidden.getId() + "/members", null, member).status());
    }

    @Test
    @DisplayName("the class list includes the private class for its members, EXPIRED members and owner (and only them)")
    void listIncludesPrivateForInsiders() throws Exception {
        for (User viewer : new User[]{owner, member, expired}) {
            assertTrue(call(HttpMethod.GET, "/api/v1/classes?size=100", null, viewer).body().contains(hidden.getId()),
                    "visible to " + viewer.getEmail());
        }
        for (User viewer : new User[]{null, stranger, removed, blocked}) {
            assertFalse(call(HttpMethod.GET, "/api/v1/classes?size=100", null, viewer).body().contains(hidden.getId()));
        }
    }

    @Test
    @DisplayName("a PUBLIC-profile author of a private class's posts stays invisible to guests and strangers (no name, no avatar); opening the class makes them visible, closing it hides them again")
    void publicProfileAuthorsDoNotLeak() throws Exception {
        User author = user("author");
        author.setProfileVisibility("PUBLIC");
        author.setAvatarUrl("https://avatars.example/pub-author.png");
        author = userRepository.save(author);
        CreateClassroomRequest req = new CreateClassroomRequest();
        req.setTitle("Tac gia cong khai " + UUID.randomUUID().toString().substring(0, 6));
        req.setSlug("tg-" + UUID.randomUUID().toString().substring(0, 10));
        req.setVisibility("PRIVATE");
        Classroom c = classroomRepository.findById(classroomService.createClassroom(author.getId(), req).getId()).orElseThrow();
        feedService.createPost(c.getId(), author.getId(), new CreatePostRequest("Bai cua tac gia", "Noi dung", "PUBLIC", null, null, false));
        String path = "/api/v1/classes/" + c.getId() + "/posts";

        for (User viewer : new User[]{null, stranger, removed}) {
            Answer a = call(HttpMethod.GET, path, null, viewer);
            assertEquals(404, a.status());
            assertFalse(a.body().contains(author.getFullName()), "the PUBLIC-profile author's name leaked");
            assertFalse(a.body().contains("pub-author.png"), "the author's avatar leaked");
            assertFalse(a.body().contains(author.getId()), "the author's id leaked");
        }

        String put = "{\"title\":\"" + c.getTitle() + "\",\"visibility\":\"";
        assertEquals(200, call(HttpMethod.PUT, "/api/v1/classes/" + c.getId(), put + "PUBLIC\"}", author).status());
        Answer open = call(HttpMethod.GET, path, null, null);
        assertEquals(200, open.status());
        assertTrue(open.body().contains(author.getFullName()), "in a public class a PUBLIC profile is shown to guests, as before");

        assertEquals(200, call(HttpMethod.PUT, "/api/v1/classes/" + c.getId(), put + "PRIVATE\"}", author).status());
        Answer closed = call(HttpMethod.GET, path, null, null);
        assertEquals(404, closed.status());
        assertFalse(closed.body().contains(author.getFullName()));
    }

    @Test
    @DisplayName("PRIVATE -> PUBLIC through the HTTP API makes the class appear for everybody; PUBLIC -> PRIVATE hides it again (existing members stay)")
    void visibilityConversionOverHttp() throws Exception {
        CreateClassroomRequest req = new CreateClassroomRequest();
        req.setTitle("Chuyen doi " + UUID.randomUUID().toString().substring(0, 8));
        req.setSlug("cd-" + UUID.randomUUID().toString().substring(0, 10));
        Classroom c = classroomRepository.findById(classroomService.createClassroom(owner.getId(), req).getId()).orElseThrow();
        ClassMember m = new ClassMember(c.getId(), member.getId(), "STUDENT");
        memberRepository.save(m);

        assertEquals(200, call(HttpMethod.GET, "/api/v1/classes/" + c.getId(), null, null).status());
        Answer toPrivate = call(HttpMethod.PUT, "/api/v1/classes/" + c.getId(), "{\"title\":\"" + c.getTitle() + "\",\"visibility\":\"private\"}", owner);
        assertEquals(200, toPrivate.status(), toPrivate.body());
        assertEquals("PRIVATE", JSON.readTree(toPrivate.body()).path("data").path("visibility").asText());
        assertEquals(404, call(HttpMethod.GET, "/api/v1/classes/" + c.getId(), null, null).status());
        assertEquals(404, call(HttpMethod.GET, "/api/v1/classes/" + c.getId(), null, stranger).status());
        assertEquals(200, call(HttpMethod.GET, "/api/v1/classes/" + c.getId(), null, member).status());

        Answer toPublic = call(HttpMethod.PUT, "/api/v1/classes/" + c.getId(), "{\"title\":\"" + c.getTitle() + "\",\"visibility\":\"PUBLIC\"}", owner);
        assertEquals(200, toPublic.status(), toPublic.body());
        assertEquals(200, call(HttpMethod.GET, "/api/v1/classes/" + c.getId(), null, null).status());

        Answer bad = call(HttpMethod.PUT, "/api/v1/classes/" + c.getId(), "{\"title\":\"x\",\"visibility\":\"SECRET\"}", owner);
        assertEquals(400, bad.status());
    }
}
