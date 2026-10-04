package com.classroom.modules.classroom.controller;

import com.classroom.config.JwtTokenProvider;
import com.classroom.modules.classroom.dto.CreateClassroomRequest;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.model.StaffAssignment;
import com.classroom.modules.classroom.model.StaffPermission;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.classroom.repository.StaffAssignmentRepository;
import com.classroom.modules.classroom.repository.StaffPermissionRepository;
import com.classroom.modules.classroom.service.ClassroomService;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.media.model.MediaAsset;
import com.classroom.modules.media.repository.MediaAssetRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * D-27: shared fixtures for the blog / events / class-cover HTTP tests - real servlet chain (Spring Security, filters, controllers, services,
 * H2). Every test class builds its own users and classes, so nothing depends on the demo seed or on another test class.
 */
public abstract class ClassContentHttpTestBase {

    protected static final ObjectMapper JSON = new ObjectMapper();

    @Autowired protected MockMvc mockMvc;
    @Autowired protected UserRepository userRepository;
    @Autowired protected ClassroomRepository classroomRepository;
    @Autowired protected ClassMemberRepository memberRepository;
    @Autowired protected StaffAssignmentRepository staffAssignmentRepository;
    @Autowired protected StaffPermissionRepository staffPermissionRepository;
    @Autowired protected MediaAssetRepository mediaAssetRepository;
    @Autowired protected ClassroomService classroomService;
    @Autowired protected JwtTokenProvider tokens;

    /** One request as one viewer. {@code json} is the parsed body (or an empty object). */
    protected record Answer(int status, String code, String message, JsonNode json, String body) {
        public JsonNode data() {
            return json.path("data");
        }
    }

    protected User user(String tag) {
        return userRepository.save(new User(UUID.randomUUID().toString(),
                "d27-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8) + "@d27.test", "hash", "D27 " + tag, "USER"));
    }

    protected Classroom newClass(User owner, String title, String visibility) {
        CreateClassroomRequest req = new CreateClassroomRequest();
        req.setTitle(title);
        req.setSlug("d27-" + UUID.randomUUID().toString().substring(0, 12));
        req.setDescription("Mo ta " + title);
        req.setVisibility(visibility);
        return classroomRepository.findById(classroomService.createClassroom(owner.getId(), req).getId()).orElseThrow();
    }

    protected ClassMember member(Classroom c, User u, String state) {
        ClassMember m = new ClassMember(c.getId(), u.getId(), "STUDENT");
        m.setState(state);
        return memberRepository.save(m);
    }

    /** An ACTIVE staff member (membership row + ACTIVE assignment) holding exactly {@code grants} ("MODULE:ACTION"). */
    protected void staff(Classroom c, User u, String... grants) {
        ClassMember m = new ClassMember(c.getId(), u.getId(), "STAFF");
        memberRepository.save(m);
        StaffAssignment a = staffAssignmentRepository.save(new StaffAssignment(c.getId(), u.getId()));
        for (String grant : grants) {
            String[] parts = grant.split(":");
            staffPermissionRepository.save(new StaffPermission(a.getId(), parts[0], parts[1], null));
        }
    }

    protected MediaAsset uploadedImage(Classroom c, User uploader, String purpose) {
        MediaAsset asset = new MediaAsset(c.getId(), uploader.getId(), "classes/" + c.getId() + "/media/" + UUID.randomUUID() + ".png",
                "anh-bia.png", "image/png", 2048);
        asset.setUploadPurpose(purpose);
        asset.setStatus("UPLOADED");
        return mediaAssetRepository.save(asset);
    }

    protected Answer call(HttpMethod method, String path, Object body, User as) throws Exception {
        // The path is used as an already-encoded URI (no second encoding), so "%20" in a query string really is a space.
        MockHttpServletRequestBuilder b = request(method, java.net.URI.create(path));
        if (as != null) {
            b.header("Authorization", "Bearer " + tokens.generateToken(as.getId(), as.getEmail(), as.getRole()));
        }
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : JSON.writeValueAsString(body));
        }
        MockHttpServletResponse res = mockMvc.perform(b).andReturn().getResponse();
        String text = res.getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = text.isBlank() || !text.trim().startsWith("{") ? JSON.createObjectNode() : JSON.readTree(text);
        return new Answer(res.getStatus(), root.path("error").path("code").asText(""), root.path("error").path("message").asText(""),
                root, text);
    }

    protected Answer get(String path, User as) throws Exception {
        return call(HttpMethod.GET, path, null, as);
    }

    protected Answer post(String path, Object body, User as) throws Exception {
        return call(HttpMethod.POST, path, body, as);
    }

    protected Answer put(String path, Object body, User as) throws Exception {
        return call(HttpMethod.PUT, path, body, as);
    }

    protected Answer delete(String path, User as) throws Exception {
        return call(HttpMethod.DELETE, path, null, as);
    }
}
