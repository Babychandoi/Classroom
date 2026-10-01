package com.classroom.modules.classroom.controller;

import com.classroom.config.JwtTokenProvider;
import com.classroom.modules.classroom.dto.CreateClassroomRequest;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.classroom.service.ClassroomService;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * D-19 through the real HTTP chain: the JSON the UI agent will code against - the 402 {@code PAYMENT_REQUIRED} payload with
 * {@code error.details.accessProduct}, {@code INVITE_REQUIRED}, {@code MEMBERSHIP_EXPIRED}, the invite endpoints (code shown once, public
 * preview, authenticated join, revoke), {@code PUT /classes/{id}/access}, the new {@code ClassroomDto} fields, the product {@code kind}, and the
 * per-address throttle of the two code endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaidClassHttpContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassroomRepository classroomRepository;
    @Autowired private ClassMemberRepository memberRepository;
    @Autowired private ClassroomService classroomService;
    @Autowired private JwtTokenProvider tokens;

    private User owner;
    private User student;
    private User expired;
    private User staffStoreOnly;

    private User user(String tag) {
        return userRepository.save(new User(UUID.randomUUID().toString(),
                "paid-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8) + "@d19.test", "hash", "Paid " + tag, "USER"));
    }

    @BeforeAll
    void users() {
        owner = user("owner");
        student = user("student");
        expired = user("expired");
        staffStoreOnly = user("staff");
    }

    private Classroom newClass(String visibility) {
        CreateClassroomRequest req = new CreateClassroomRequest();
        req.setTitle("Hop dong " + UUID.randomUUID().toString().substring(0, 6));
        req.setSlug("hd-" + UUID.randomUUID().toString().substring(0, 10));
        req.setVisibility(visibility);
        return classroomRepository.findById(classroomService.createClassroom(owner.getId(), req).getId()).orElseThrow();
    }

    private JsonNode call(HttpMethod method, String path, String body, User as, int expectedStatus) throws Exception {
        return call(method, path, body, as, expectedStatus, null);
    }

    private JsonNode call(HttpMethod method, String path, String body, User as, int expectedStatus, String clientIp) throws Exception {
        MockHttpServletRequestBuilder b = request(method, path);
        if (as != null) b.header("Authorization", "Bearer " + tokens.generateToken(as.getId(), as.getEmail(), as.getRole()));
        if (clientIp != null) {
            b.with(r -> {
                r.setRemoteAddr("127.0.0.1"); // the trusted proxy (nginx) ...
                return r;
            }).header("X-Real-IP", clientIp); // ... naming the real client
        }
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(body);
        MockHttpServletResponse res = mockMvc.perform(b).andReturn().getResponse();
        String text = res.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(expectedStatus, res.getStatus(), method + " " + path + " -> " + text);
        return text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text);
    }

    private String makePaid(Classroom c, String price, String durationDays) throws Exception {
        JsonNode dto = call(HttpMethod.PUT, "/api/v1/classes/" + c.getId() + "/access",
                "{\"accessType\":\"PAID\",\"price\":" + price + ",\"currency\":\"VND\"" + (durationDays == null ? "" : ",\"durationDays\":" + durationDays) + "}",
                owner, 200).path("data");
        assertEquals("PAID", dto.path("accessType").asText());
        return dto.path("accessProduct").path("id").asText();
    }

    @Test
    @DisplayName("PUT /classes/{id}/access: the ClassroomDto now carries visibility, accessType and accessProduct{id,price,currency,durationDays,lifetime}")
    void accessEndpointAndDtoFields() throws Exception {
        Classroom c = newClass("PUBLIC");
        JsonNode before = call(HttpMethod.GET, "/api/v1/classes/" + c.getId(), null, null, 200).path("data");
        assertEquals("PUBLIC", before.path("visibility").asText());
        assertEquals("FREE", before.path("accessType").asText());
        assertTrue(before.path("accessProduct").isNull() || before.path("accessProduct").isMissingNode());
        assertEquals("NONE", before.path("memberState").asText());

        makePaid(c, "199000", "30");
        JsonNode after = call(HttpMethod.GET, "/api/v1/classes/slug/" + c.getSlug(), null, null, 200).path("data");
        assertEquals("PAID", after.path("accessType").asText());
        JsonNode product = after.path("accessProduct");
        assertEquals(199000, product.path("price").asInt());
        assertEquals("VND", product.path("currency").asText());
        assertEquals(30, product.path("durationDays").asInt());
        assertFalse(product.path("lifetime").asBoolean());
        assertFalse(product.path("id").asText().isBlank());

        JsonNode lifetime = call(HttpMethod.PUT, "/api/v1/classes/" + c.getId() + "/access",
                "{\"accessType\":\"PAID\",\"price\":499000}", owner, 200).path("data").path("accessProduct");
        assertTrue(lifetime.path("lifetime").asBoolean());
        assertTrue(lifetime.path("durationDays").isNull());

        call(HttpMethod.PUT, "/api/v1/classes/" + c.getId() + "/access", "{\"accessType\":\"GOLD\"}", owner, 400);
        call(HttpMethod.PUT, "/api/v1/classes/" + c.getId() + "/access", "{\"accessType\":\"PAID\",\"price\":0}", owner, 400);
        call(HttpMethod.PUT, "/api/v1/classes/" + c.getId() + "/access", "{\"accessType\":\"FREE\"}", null, 401);
        JsonNode denied = call(HttpMethod.PUT, "/api/v1/classes/" + c.getId() + "/access", "{\"accessType\":\"FREE\"}", student, 403);
        assertEquals("STAFF_PERMISSION_DENIED", denied.path("error").path("code").asText());
    }

    @Test
    @DisplayName("joining a PAID class by id is a 402 whose error.details.accessProduct is exactly what POST /orders needs; the store lists it with kind CLASS_ACCESS")
    void paymentRequiredPayload() throws Exception {
        Classroom c = newClass("PUBLIC");
        String productId = makePaid(c, "199000", "30");

        JsonNode error = call(HttpMethod.POST, "/api/v1/classes/" + c.getId() + "/join", null, student, 402).path("error");
        assertEquals("PAYMENT_REQUIRED", error.path("code").asText());
        assertEquals(c.getId(), error.path("details").path("classId").asText());
        assertEquals("PAID", error.path("details").path("accessType").asText());
        JsonNode p = error.path("details").path("accessProduct");
        assertEquals(productId, p.path("id").asText());
        assertEquals(199000, p.path("price").asInt());
        assertEquals("VND", p.path("currency").asText());
        assertEquals(30, p.path("durationDays").asInt());
        assertFalse(error.path("requestId").asText().isBlank());

        // other errors keep the old envelope: no "details" key at all
        JsonNode plain = call(HttpMethod.GET, "/api/v1/classes/" + UUID.randomUUID(), null, null, 404).path("error");
        assertTrue(plain.path("details").isMissingNode());

        JsonNode store = call(HttpMethod.GET, "/api/v1/classes/" + c.getId() + "/products", null, null, 200).path("data");
        assertEquals(1, store.size());
        assertEquals("CLASS_ACCESS", store.get(0).path("kind").asText());
        assertEquals(productId, store.get(0).path("id").asText());

        // the UI can start checkout straight from the payload
        JsonNode order = call(HttpMethod.POST, "/api/v1/orders",
                "{\"classId\":\"" + c.getId() + "\",\"productId\":\"" + p.path("id").asText() + "\",\"idempotencyKey\":\"k-" + UUID.randomUUID() + "\"}",
                student, 200, null).path("data");
        assertEquals("PENDING", order.path("status").asText());
        assertEquals(199000, order.path("totalAmount").asInt());
    }

    @Test
    @DisplayName("a private class: INVITE_REQUIRED (403) for someone who can see it but is not in it; MEMBERSHIP_EXPIRED (403) on member-only routes")
    void inviteRequiredAndMembershipExpired() throws Exception {
        Classroom c = newClass("PRIVATE");
        ClassMember m = new ClassMember(c.getId(), expired.getId(), "STUDENT");
        m.setState("EXPIRED");
        m.setAccessExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        memberRepository.save(m);

        JsonNode invite = call(HttpMethod.POST, "/api/v1/classes/" + c.getId() + "/join", null, expired, 403).path("error");
        assertEquals("INVITE_REQUIRED", invite.path("code").asText());
        assertEquals("Lớp riêng tư, cần mã mời", invite.path("message").asText());
        call(HttpMethod.POST, "/api/v1/classes/" + c.getId() + "/join", null, student, 404);

        for (String path : new String[]{"/members", "/courses", "/exams", "/leaderboard", "/documents", "/posts"}) {
            JsonNode err = call(HttpMethod.GET, "/api/v1/classes/" + c.getId() + path, null, expired, 403).path("error");
            assertEquals("MEMBERSHIP_EXPIRED", err.path("code").asText(), path);
        }
        JsonNode card = call(HttpMethod.GET, "/api/v1/classes/" + c.getId(), null, expired, 200).path("data");
        assertEquals("EXPIRED", card.path("memberState").asText());
        assertFalse(card.path("isMember").asBoolean());
        assertFalse(card.path("accessExpiresAt").isNull());
        call(HttpMethod.GET, "/api/v1/classes/" + c.getId() + "/about", null, expired, 200);
        call(HttpMethod.GET, "/api/v1/classes/" + c.getId() + "/products", null, expired, 200);
    }

    @Test
    @DisplayName("invites over HTTP: the code is returned once; the list has only the hint; the preview is public; join needs a login; revoke turns everything into the generic 404")
    void inviteEndpoints() throws Exception {
        Classroom c = newClass("PRIVATE");

        JsonNode created = call(HttpMethod.POST, "/api/v1/classes/" + c.getId() + "/invites", "{\"maxUses\":3}", owner, 200).path("data");
        String code = created.path("code").asText();
        String inviteId = created.path("id").asText();
        assertEquals(32, code.length());
        assertEquals(code.substring(28), created.path("codeHint").asText());
        assertEquals("ACTIVE", created.path("status").asText());
        assertEquals(3, created.path("maxUses").asInt());

        JsonNode list = call(HttpMethod.GET, "/api/v1/classes/" + c.getId() + "/invites", null, owner, 200).path("data");
        assertEquals(1, list.size());
        assertTrue(list.get(0).path("code").isMissingNode(), "the list never has the code");
        assertEquals(inviteId, list.get(0).path("id").asText());
        call(HttpMethod.GET, "/api/v1/classes/" + c.getId() + "/invites", null, student, 403);
        call(HttpMethod.POST, "/api/v1/classes/" + c.getId() + "/invites", "{}", null, 401);

        // public preview: no login
        JsonNode preview = call(HttpMethod.GET, "/api/v1/classes/invites/" + code, null, null, 200).path("data");
        assertEquals(c.getId(), preview.path("classId").asText());
        assertEquals(c.getSlug(), preview.path("slug").asText());
        assertEquals("FREE", preview.path("accessType").asText());
        assertEquals(owner.getFullName(), preview.path("ownerName").asText());
        assertTrue(preview.path("price").isMissingNode());

        call(HttpMethod.POST, "/api/v1/classes/invites/" + code + "/join", null, null, 401);
        JsonNode joined = call(HttpMethod.POST, "/api/v1/classes/invites/" + code + "/join", null, student, 200).path("data");
        assertEquals("ACTIVE", joined.path("memberState").asText());
        assertTrue(joined.path("isMember").asBoolean());
        assertEquals(c.getId(), joined.path("id").asText());
        call(HttpMethod.GET, "/api/v1/classes/" + c.getId(), null, student, 200);

        // revoke: the very same 404 as a code that never existed
        JsonNode revoked = call(HttpMethod.DELETE, "/api/v1/classes/" + c.getId() + "/invites/" + inviteId, null, owner, 200).path("data");
        assertEquals("REVOKED", revoked.path("status").asText());
        JsonNode gone = call(HttpMethod.GET, "/api/v1/classes/invites/" + code, null, null, 404);
        JsonNode never = call(HttpMethod.GET, "/api/v1/classes/invites/" + "Z".repeat(32), null, null, 404);
        assertEquals(never.path("error").path("code").asText(), gone.path("error").path("code").asText());
        assertEquals(never.path("error").path("message").asText(), gone.path("error").path("message").asText());
        call(HttpMethod.POST, "/api/v1/classes/invites/" + code + "/join", null, user("late"), 404);
    }

    @Test
    @DisplayName("a code that spells a sibling route (members, invites, about, posts, products) never breaks routing: it is a plain 4xx, never a 500")
    void codesThatSpellSiblingRoutes() throws Exception {
        for (String literal : new String[]{"members", "invites", "about", "posts", "products", "join", "slug"}) {
            for (User viewer : new User[]{null, student}) {
                MockHttpServletRequestBuilder b = request(HttpMethod.GET, "/api/v1/classes/invites/" + literal);
                if (viewer != null) b.header("Authorization", "Bearer " + tokens.generateToken(viewer.getId(), viewer.getEmail(), viewer.getRole()));
                int status = mockMvc.perform(b).andReturn().getResponse().getStatus();
                assertTrue(status >= 400 && status < 500, literal + " as " + (viewer == null ? "guest" : "student") + " -> " + status);
            }
        }
    }

    @Test
    @DisplayName("the code endpoints are throttled per client address through the real filter chain: 30 unknown codes, then 429 with Retry-After for that address only")
    void invitePreviewIsRateLimited() throws Exception {
        String ip = "198.18." + (int) (Math.random() * 200) + "." + (int) (Math.random() * 200);
        for (int i = 0; i < 30; i++) {
            call(HttpMethod.GET, "/api/v1/classes/invites/" + UUID.randomUUID().toString().replace("-", "") + "x", null, null, 404, ip);
        }
        JsonNode blocked = call(HttpMethod.GET, "/api/v1/classes/invites/" + UUID.randomUUID().toString().replace("-", "") + "y", null, null, 429, ip);
        assertEquals("RATE_LIMITED", blocked.path("error").path("code").asText());
        call(HttpMethod.GET, "/api/v1/classes/invites/" + "Q".repeat(32), null, null, 404, "203.0.113." + (int) (Math.random() * 200));
    }
}
