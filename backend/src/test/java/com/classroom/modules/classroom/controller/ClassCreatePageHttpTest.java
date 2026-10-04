package com.classroom.modules.classroom.controller;

import com.classroom.modules.audit.repository.AuditEventRepository;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.service.ClassCategories;
import com.classroom.modules.classroom.service.ClassMembershipService;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-28 ("Tạo lớp học"): category (list, validation, filter), server-generated slugs, the class avatar (CLASS_AVATAR, presigned only after the
 * visibility check), cover / avatar positions, and join approval (PENDING requests, approve / reject / withdraw, invite and purchase bypass,
 * private classes still 404, blocked 403, pendingRequestCount only for MEMBER:VIEW).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClassCreatePageHttpTest extends ClassContentHttpTestBase {

    @Autowired private AuditEventRepository auditEventRepository;
    @Autowired private ClassMembershipService membershipService;
    @Autowired private TransactionTemplate transactionTemplate;

    private User owner;
    private User member;
    private User stranger;
    private User viewStaff;   // MEMBER:VIEW only
    private User editStaff;   // MEMBER:VIEW + MEMBER:EDIT
    private User plainStaff;  // no MEMBER grant

    @BeforeAll
    void fixtures() {
        owner = user("cc-owner");
        member = user("cc-member");
        stranger = user("cc-stranger");
        viewStaff = user("cc-view");
        editStaff = user("cc-edit");
        plainStaff = user("cc-plain");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private Answer create(Map<String, Object> body) throws Exception {
        return post("/api/v1/classes", body, owner);
    }

    private Map<String, Object> body(String title) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("title", title);
        return b;
    }

    private Classroom approvalClass(String title) throws Exception {
        Map<String, Object> b = body(title);
        b.put("requireApproval", true);
        Answer a = create(b);
        assertEquals(200, a.status(), a.body());
        assertTrue(a.data().path("requireApproval").asBoolean());
        return classroomRepository.findById(a.data().path("id").asText()).orElseThrow();
    }

    private Answer join(Classroom c, User u) throws Exception {
        return post("/api/v1/classes/" + c.getId() + "/join", null, u);
    }

    private String stateOf(Classroom c, User u) throws Exception {
        return get("/api/v1/classes/" + c.getId(), u).data().path("memberState").asText();
    }

    // ---------------------------------------------------------------------------------------------------------------------------- category

    @Test
    @DisplayName("GET /classes/categories is public and returns the fixed list in order")
    void categoriesEndpoint() throws Exception {
        Answer a = get("/api/v1/classes/categories", null);
        assertEquals(200, a.status(), a.body());
        assertEquals(ClassCategories.ALL, JSON.convertValue(a.data(), List.class));
        assertEquals(12, a.data().size());
        assertEquals("Nấu ăn", a.data().get(0).asText());
    }

    @Test
    @DisplayName("category: exact strings only (400 otherwise) on create / update / filter; '' clears it on update; the filter narrows GET /classes")
    void categoryValidationAndFilter() throws Exception {
        String token = "cat" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> b = body("Lop nau an " + token);
        b.put("category", "Nấu ăn");
        Answer ok = create(b);
        assertEquals(200, ok.status(), ok.body());
        assertEquals("Nấu ăn", ok.data().path("category").asText());
        String cookingId = ok.data().path("id").asText();

        Map<String, Object> vegan = body("Lop an chay " + token);
        vegan.put("category", "Ăn chay");
        String veganId = create(vegan).data().path("id").asText();
        String noneId = create(body("Lop khong danh muc " + token)).data().path("id").asText();

        Map<String, Object> bad = body("Lop sai " + token);
        bad.put("category", "Bơi lội");
        assertEquals(400, create(bad).status());
        Map<String, Object> wrongCase = body("Lop sai hoa " + token);
        wrongCase.put("category", "nấu ăn");
        assertEquals(400, create(wrongCase).status(), "exact string match");

        Answer filtered = get("/api/v1/classes?size=100&q=" + token + "&category=" + enc("Ăn chay"), null);
        assertEquals(200, filtered.status(), filtered.body());
        assertEquals(List.of(veganId), ids(filtered.data()));
        Answer popular = get("/api/v1/classes?size=100&sort=popular&category=" + enc("Nấu ăn") + "&q=" + token, stranger);
        assertEquals(List.of(cookingId), ids(popular.data()));
        assertTrue(ids(get("/api/v1/classes?size=100&category=" + enc("Nấu ăn"), null).data()).contains(cookingId), "category without q");
        assertEquals(3, ids(get("/api/v1/classes?size=100&q=" + token, null).data()).size());
        assertEquals(400, get("/api/v1/classes?category=" + enc("Bơi lội"), null).status());

        Map<String, Object> put = new LinkedHashMap<>();
        put.put("title", "Lop khong danh muc " + token);
        put.put("category", "Bơi lội");
        assertEquals(400, put("/api/v1/classes/" + noneId, put, owner).status());
        put.put("category", "AI");
        assertEquals("AI", put("/api/v1/classes/" + noneId, put, owner).data().path("category").asText());
        put.remove("category");
        assertEquals("AI", put("/api/v1/classes/" + noneId, put, owner).data().path("category").asText(), "absent keeps");
        put.put("category", "");
        assertTrue(put("/api/v1/classes/" + noneId, put, owner).data().path("category").isNull(), "'' clears");
    }

    // -------------------------------------------------------------------------------------------------------------------------------- slug

    @Test
    @DisplayName("slug: absent / blank -> derived from the Vietnamese title (diacritics, đ), collisions get -2, -3; explicit slugs keep 400 / 409")
    void slugGeneration() throws Exception {
        String token = UUID.randomUUID().toString().substring(0, 6);
        String title = "Lớp Học Đàn Ghi-ta Cơ Bản " + token + " !!";
        String expected = "lop-hoc-dan-ghi-ta-co-ban-" + token;
        Answer first = create(body(title));
        assertEquals(200, first.status(), first.body());
        assertEquals(expected, first.data().path("slug").asText());
        Map<String, Object> blank = body(title);
        blank.put("slug", "  ");
        assertEquals(expected + "-2", create(blank).data().path("slug").asText());
        Map<String, Object> empty = body(title);
        empty.put("slug", "");
        assertEquals(expected + "-3", create(empty).data().path("slug").asText());

        Map<String, Object> explicit = body("Lop slug rieng");
        explicit.put("slug", "cc-" + token);
        assertEquals(200, create(explicit).status());
        assertEquals(409, create(explicit).status(), "an explicit slug that is taken is still a 409");
        explicit.put("slug", "ab");
        assertEquals(400, create(explicit).status());
        explicit.put("slug", "Co Dau");
        assertEquals(400, create(explicit).status());
        assertEquals(200, get("/api/v1/classes/slug/" + expected, null).status());
    }

    // ------------------------------------------------------------------------------------------------------------------- avatar / position

    @Test
    @DisplayName("CLASS_AVATAR: CLASS:EDIT, images <= 5 MB; avatarMediaId must be an UPLOADED CLASS_AVATAR image of the class; '' clears")
    void avatarPurposeAndAttach() throws Exception {
        Classroom c = newClass(owner, "Lop anh dai dien", "PUBLIC");
        member(c, member, "ACTIVE");
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("filename", "avatar.png");
        intent.put("mimeType", "image/png");
        intent.put("sizeBytes", 1000);
        intent.put("purpose", "CLASS_AVATAR");
        String path = "/api/v1/classes/" + c.getId() + "/media/upload-intents";
        assertEquals(200, post(path, intent, owner).status());
        assertEquals(403, post(path, intent, member).status());
        intent.put("mimeType", "application/pdf");
        assertEquals(400, post(path, intent, owner).status());
        intent.put("mimeType", "image/png");
        intent.put("sizeBytes", 5L * 1024 * 1024 + 1);
        assertEquals(400, post(path, intent, owner).status());

        Classroom other = newClass(owner, "Lop khac anh", "PUBLIC");
        String good = uploadedImage(c, owner, "CLASS_AVATAR").getId();
        String coverPurpose = uploadedImage(c, owner, "CLASS_COVER").getId();
        String foreign = uploadedImage(other, owner, "CLASS_AVATAR").getId();
        Map<String, Object> put = new LinkedHashMap<>();
        put.put("title", c.getTitle());
        put.put("avatarMediaId", coverPurpose);
        assertEquals(400, put("/api/v1/classes/" + c.getId(), put, owner).status());
        put.put("avatarMediaId", foreign);
        assertEquals(400, put("/api/v1/classes/" + c.getId(), put, owner).status());
        put.put("avatarMediaId", good);
        Answer set = put("/api/v1/classes/" + c.getId(), put, owner);
        assertEquals(200, set.status(), set.body());
        assertEquals(good, set.data().path("avatarMediaId").asText());
        assertTrue(set.data().path("avatarUrl").asText().contains("X-Amz-Signature"));
        assertTrue(get("/api/v1/classes/" + c.getId(), null).data().path("avatarUrl").asText().contains("X-Amz-Signature"));
        JsonNode listed = find(get("/api/v1/classes?size=100&q=" + enc("Lop anh dai dien"), null).data(), c.getId());
        assertTrue(listed.path("avatarUrl").asText().contains("X-Amz-Signature"), "batched in the listing");

        put.put("avatarMediaId", "");
        Answer cleared = put("/api/v1/classes/" + c.getId(), put, owner);
        assertTrue(cleared.data().path("avatarMediaId").isNull());
        assertTrue(cleared.data().path("avatarUrl").isNull());
    }

    @Test
    @DisplayName("the avatar of a PRIVATE class is never signed for an outsider (404, no URL anywhere) - only after the visibility check")
    void avatarOnlyAfterVisibility() throws Exception {
        Classroom c = newClass(owner, "Lop kin anh " + UUID.randomUUID().toString().substring(0, 6), "PRIVATE");
        member(c, member, "ACTIVE");
        c.setAvatarMediaId(uploadedImage(c, owner, "CLASS_AVATAR").getId());
        classroomRepository.save(c);
        for (User u : new User[]{null, stranger}) {
            Answer a = get("/api/v1/classes/" + c.getId(), u);
            assertEquals(404, a.status());
            assertFalse(a.body().contains("X-Amz"), "signed URL leaked");
            assertFalse(get("/api/v1/classes?size=100", u).body().contains(c.getAvatarMediaId()));
        }
        assertTrue(get("/api/v1/classes/" + c.getId(), member).data().path("avatarUrl").asText().contains("X-Amz-Signature"));
    }

    @Test
    @DisplayName("coverPosition / avatarPosition: 'X% Y%' with 0..100 only; '' clears; absent keeps")
    void positions() throws Exception {
        Map<String, Object> b = body("Lop vi tri anh");
        b.put("coverPosition", "50% 30%");
        b.put("avatarPosition", "0% 100%");
        Answer ok = create(b);
        assertEquals(200, ok.status(), ok.body());
        assertEquals("50% 30%", ok.data().path("coverPosition").asText());
        assertEquals("0% 100%", ok.data().path("avatarPosition").asText());
        String id = ok.data().path("id").asText();
        for (String bad : new String[]{"101% 0%", "50%30%", "center", "50% 30", "-1% 5%", "1000% 1%", "50.5% 1%"}) {
            Map<String, Object> wrong = body("Lop vi tri sai");
            wrong.put("coverPosition", bad);
            assertEquals(400, create(wrong).status(), bad);
            Map<String, Object> put = new LinkedHashMap<>();
            put.put("title", "Lop vi tri anh");
            put.put("avatarPosition", bad);
            assertEquals(400, put("/api/v1/classes/" + id, put, owner).status(), bad);
        }
        Map<String, Object> put = new LinkedHashMap<>();
        put.put("title", "Lop vi tri anh");
        put.put("coverPosition", "");
        Answer cleared = put("/api/v1/classes/" + id, put, owner);
        assertTrue(cleared.data().path("coverPosition").isNull());
        assertEquals("0% 100%", cleared.data().path("avatarPosition").asText(), "absent keeps");
    }

    // ----------------------------------------------------------------------------------------------------------------------------- approval

    @Test
    @DisplayName("approval: join -> PENDING (not a member, idempotent) -> listed with requestedAt -> approve -> ACTIVE member, MEMBER_APPROVE audited")
    void requestAndApprove() throws Exception {
        Classroom c = approvalClass("Lop duyet " + UUID.randomUUID().toString().substring(0, 6));
        User u = user("cc-req");
        Answer req = join(c, u);
        assertEquals(200, req.status(), req.body());
        assertEquals("PENDING", req.data().path("memberState").asText());
        assertFalse(req.data().path("isMember").asBoolean());
        assertEquals("GUEST", req.data().path("userRole").asText());
        assertEquals("PENDING", join(c, u).data().path("memberState").asText(), "idempotent while pending");
        assertEquals(403, get("/api/v1/classes/" + c.getId() + "/members", u).status(), "a pending person is not a member");
        assertEquals(0, get("/api/v1/classes/" + c.getId(), null).data().path("memberCount").asLong() - 1, "only the owner counts");

        Answer queue = get("/api/v1/classes/" + c.getId() + "/studio/members?state=PENDING", owner);
        assertEquals(200, queue.status(), queue.body());
        JsonNode items = queue.data().path("items").isMissingNode() ? queue.data().path("members") : queue.data().path("items");
        assertEquals(1, items.size(), queue.body());
        assertEquals(u.getId(), items.get(0).path("userId").asText());
        assertEquals("PENDING", items.get(0).path("state").asText());
        assertFalse(items.get(0).path("requestedAt").asText().isBlank());
        assertEquals(1, get("/api/v1/classes/" + c.getId(), owner).data().path("pendingRequestCount").asLong());

        Answer approved = post("/api/v1/classes/" + c.getId() + "/studio/members/" + u.getId() + "/approve", null, owner);
        assertEquals(200, approved.status(), approved.body());
        assertEquals("ACTIVE", approved.data().path("state").asText());
        JsonNode after = get("/api/v1/classes/" + c.getId(), u).data();
        assertEquals("ACTIVE", after.path("memberState").asText());
        assertTrue(after.path("isMember").asBoolean());
        assertEquals(200, get("/api/v1/classes/" + c.getId() + "/members", u).status());
        assertEquals(0, get("/api/v1/classes/" + c.getId(), owner).data().path("pendingRequestCount").asLong());
        assertTrue(auditEventRepository.findByClassIdOrderByCreatedAtDesc(c.getId()).stream().anyMatch(e -> "MEMBER_APPROVE".equals(e.getAction())));
        assertEquals(400, post("/api/v1/classes/" + c.getId() + "/studio/members/" + u.getId() + "/approve", null, owner).status(),
                "nothing pending any more");
    }

    @Test
    @DisplayName("reject deletes the request (memberState NONE, may ask again); withdraw is idempotent; REMOVED may re-request; BLOCKED 403")
    void rejectWithdrawRemovedBlocked() throws Exception {
        Classroom c = approvalClass("Lop tu choi " + UUID.randomUUID().toString().substring(0, 6));
        User r = user("cc-rej");
        join(c, r);
        Answer rejected = post("/api/v1/classes/" + c.getId() + "/studio/members/" + r.getId() + "/reject", null, owner);
        assertEquals(200, rejected.status(), rejected.body());
        assertEquals("NONE", stateOf(c, r));
        assertTrue(auditEventRepository.findByClassIdOrderByCreatedAtDesc(c.getId()).stream().anyMatch(e -> "MEMBER_REJECT".equals(e.getAction())));
        assertEquals("PENDING", join(c, r).data().path("memberState").asText(), "may request again");

        User w = user("cc-wd");
        join(c, w);
        Answer withdrawn = delete("/api/v1/classes/" + c.getId() + "/join-request", w);
        assertEquals(200, withdrawn.status(), withdrawn.body());
        assertEquals("NONE", withdrawn.data().path("memberState").asText());
        assertEquals(200, delete("/api/v1/classes/" + c.getId() + "/join-request", w).status(), "idempotent");
        assertEquals(401, delete("/api/v1/classes/" + c.getId() + "/join-request", null).status());

        User removed = user("cc-removed");
        member(c, removed, "REMOVED");
        assertEquals("PENDING", join(c, removed).data().path("memberState").asText());
        User blocked = user("cc-blocked");
        member(c, blocked, "BLOCKED");
        assertEquals(403, join(c, blocked).status());
        assertEquals("BLOCKED", stateOf(c, blocked));

        assertEquals(400, post("/api/v1/classes/" + c.getId() + "/studio/members/" + removed.getId() + "/unblock", null, owner).status(),
                "unblock is not a back door around approval");
        assertEquals(404, post("/api/v1/classes/" + c.getId() + "/studio/members/" + stranger.getId() + "/reject", null, owner).status());
    }

    @Test
    @DisplayName("bypasses: a valid invite code and a settled purchase make a PENDING person ACTIVE; a PAID class answers 402, never PENDING")
    void inviteAndPaidBypass() throws Exception {
        Classroom c = approvalClass("Lop bo qua " + UUID.randomUUID().toString().substring(0, 6));
        User u = user("cc-invite");
        join(c, u);
        Answer invite = post("/api/v1/classes/" + c.getId() + "/invites", "{}", owner);
        assertEquals(200, invite.status(), invite.body());
        Answer viaInvite = post("/api/v1/classes/invites/" + invite.data().path("code").asText() + "/join", null, u);
        assertEquals(200, viaInvite.status(), viaInvite.body());
        assertEquals("ACTIVE", stateOf(c, u));
        User fresh = user("cc-invite-fresh");
        post("/api/v1/classes/invites/" + invite.data().path("code").asText() + "/join", null, fresh);
        assertEquals("ACTIVE", stateOf(c, fresh), "an invite never creates a request");

        User buyer = user("cc-buyer");
        join(c, buyer);
        assertEquals("PENDING", stateOf(c, buyer));
        transactionTemplate.executeWithoutResult(s -> assertTrue(membershipService.grantPaidAccess(c.getId(), buyer.getId(), null)));
        assertEquals("ACTIVE", stateOf(c, buyer), "a settled purchase bypasses approval");

        c.setAccessType(Classroom.ACCESS_PAID);
        classroomRepository.save(c);
        User payer = user("cc-payer");
        Answer paid = join(c, payer);
        assertEquals(402, paid.status(), paid.body());
        assertEquals("NONE", stateOf(c, payer));
    }

    @Test
    @DisplayName("a PRIVATE class with requireApproval is still 404 for outsiders (join and read) - even for someone holding a PENDING row")
    void privateStillNotFound() throws Exception {
        Map<String, Object> b = body("Lop kin duyet " + UUID.randomUUID().toString().substring(0, 6));
        b.put("requireApproval", true);
        b.put("visibility", "PRIVATE");
        Classroom c = classroomRepository.findById(create(b).data().path("id").asText()).orElseThrow();
        assertEquals(404, join(c, stranger).status());
        assertEquals(404, get("/api/v1/classes/" + c.getId(), stranger).status());
        User pending = user("cc-priv-pending");
        ClassMember row = new ClassMember(c.getId(), pending.getId(), "STUDENT");
        row.setState("PENDING");
        memberRepository.save(row);
        assertEquals(404, get("/api/v1/classes/" + c.getId(), pending).status(), "a pending request never makes a private class visible");
        assertEquals(404, join(c, pending).status());
        assertFalse(ids(get("/api/v1/classes?size=100", pending).data()).contains(c.getId()));
        Answer withdrawn = delete("/api/v1/classes/" + c.getId() + "/join-request", pending);
        assertEquals(200, withdrawn.status(), "the holder of a request may always withdraw it");
        assertTrue(withdrawn.data().isMissingNode() || withdrawn.data().isNull(), "but learns nothing about the class: " + withdrawn.body());
        assertFalse(withdrawn.body().contains(c.getTitle()));
        assertEquals(404, delete("/api/v1/classes/" + c.getId() + "/join-request", pending).status(), "nothing left -> 404 like any outsider");
    }

    @Test
    @DisplayName("pendingRequestCount only for MEMBER:VIEW (owner, staff with the grant) - detail and list; approve needs MEMBER:EDIT")
    void pendingCountVisibility() throws Exception {
        String token = "pc" + UUID.randomUUID().toString().substring(0, 8);
        Classroom c = approvalClass("Lop dem yeu cau " + token);
        member(c, member, "ACTIVE");
        staff(c, viewStaff, "MEMBER:VIEW");
        staff(c, editStaff, "MEMBER:VIEW", "MEMBER:EDIT");
        staff(c, plainStaff, "FEED:VIEW");
        User a = user("cc-pa");
        User b = user("cc-pb");
        join(c, a);
        join(c, b);
        for (User u : new User[]{owner, viewStaff, editStaff}) {
            assertEquals(2, get("/api/v1/classes/" + c.getId(), u).data().path("pendingRequestCount").asLong(), u.getEmail());
            JsonNode listed = find(get("/api/v1/classes?size=100&q=" + token, u).data(), c.getId());
            assertEquals(2, listed.path("pendingRequestCount").asLong(), "list, " + u.getEmail());
        }
        for (User u : new User[]{null, stranger, member, plainStaff, a}) {
            assertEquals(0, get("/api/v1/classes/" + c.getId(), u).data().path("pendingRequestCount").asLong());
            assertEquals(0, find(get("/api/v1/classes?size=100&q=" + token, u).data(), c.getId()).path("pendingRequestCount").asLong());
        }
        assertEquals(200, get("/api/v1/classes/" + c.getId() + "/studio/members?state=PENDING", viewStaff).status());
        assertEquals(403, post("/api/v1/classes/" + c.getId() + "/studio/members/" + a.getId() + "/approve", null, viewStaff).status());
        assertEquals(403, post("/api/v1/classes/" + c.getId() + "/studio/members/" + a.getId() + "/reject", null, member).status());
        assertEquals(200, post("/api/v1/classes/" + c.getId() + "/studio/members/" + a.getId() + "/approve", null, editStaff).status());
        assertEquals(1, get("/api/v1/classes/" + c.getId(), owner).data().path("pendingRequestCount").asLong());
    }

    @Test
    @DisplayName("turning approval OFF is audited and does not approve anybody; with it off, a join is immediate again")
    void approvalOff() throws Exception {
        Classroom c = approvalClass("Lop tat duyet " + UUID.randomUUID().toString().substring(0, 6));
        User waiting = user("cc-waiting");
        join(c, waiting);
        Map<String, Object> put = new LinkedHashMap<>();
        put.put("title", c.getTitle());
        put.put("requireApproval", false);
        Answer off = put("/api/v1/classes/" + c.getId(), put, owner);
        assertEquals(200, off.status(), off.body());
        assertFalse(off.data().path("requireApproval").asBoolean());
        assertEquals("PENDING", stateOf(c, waiting), "still pending until handled");
        assertEquals(1, off.data().path("pendingRequestCount").asLong());
        assertTrue(auditEventRepository.findByClassIdOrderByCreatedAtDesc(c.getId()).stream()
                .anyMatch(e -> "CLASS_APPROVAL_SETTING".equals(e.getAction())));
        User later = user("cc-later");
        assertEquals("ACTIVE", join(c, later).data().path("memberState").asText());
        assertEquals("ACTIVE", join(c, waiting).data().path("memberState").asText(), "a pending person joining again once approval is off");
    }

    private static List<String> ids(JsonNode array) {
        List<String> ids = new ArrayList<>();
        array.forEach(n -> ids.add(n.path("id").asText()));
        return ids;
    }

    private static JsonNode find(JsonNode items, String id) {
        for (JsonNode n : items) if (id.equals(n.path("id").asText())) return n;
        return null;
    }
}
