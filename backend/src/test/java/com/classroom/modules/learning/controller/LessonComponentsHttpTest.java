package com.classroom.modules.learning.controller;

import com.classroom.modules.classroom.controller.ClassContentHttpTestBase;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.model.Lesson;
import com.classroom.modules.learning.model.Section;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.learning.repository.LessonAttachmentRepository;
import com.classroom.modules.learning.repository.LessonRepository;
import com.classroom.modules.learning.repository.SectionRepository;
import com.classroom.modules.media.model.MediaAsset;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D-32: a lesson is a bundle of components - documents (CRUD, limits, authorization), assignment gating, derived type, mass assignment. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LessonComponentsHttpTest extends ClassContentHttpTestBase {

    @Autowired private CourseRepository courseRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private LessonRepository lessonRepository;
    @Autowired private LessonAttachmentRepository attachmentRepository;

    private User owner;
    private User staffNoEdit;
    private User member;
    private User outsider;
    private Classroom cls;
    private Course free;
    private Course paid;
    private Section freeSection;
    private Section paidSection;

    @BeforeAll
    void fixtures() {
        owner = user("lc-owner");
        staffNoEdit = user("lc-staff");
        member = user("lc-member");
        outsider = user("lc-outsider");
        cls = newClass(owner, "Lesson components", "PUBLIC");
        member(cls, member, "ACTIVE");
        staff(cls, staffNoEdit, "COURSE:VIEW");
        free = publish(new Course(cls.getId(), "Free", "FREE"));
        paid = publish(new Course(cls.getId(), "Paid", "PURCHASE_REQUIRED"));
        paid.setProductId(java.util.UUID.randomUUID().toString());
        courseRepository.save(paid);
        freeSection = sectionRepository.save(new Section(free.getId(), "S", 1));
        paidSection = sectionRepository.save(new Section(paid.getId(), "S", 1));
    }

    private Course publish(Course c) {
        c.setStatus("PUBLISHED");
        return courseRepository.save(c);
    }

    private MediaAsset asset(String mime, String name, User uploader) {
        MediaAsset a = new MediaAsset(cls.getId(), uploader.getId(), "classes/" + cls.getId() + "/media/" + java.util.UUID.randomUUID() + "-" + name,
                name, mime, 1234);
        a.setUploadPurpose("LESSON");
        a.setStatus("UPLOADED");
        return mediaAssetRepository.save(a);
    }

    private String newLesson(Section s, Map<String, Object> extra) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("title", "Bai " + java.util.UUID.randomUUID().toString().substring(0, 6));
        b.putAll(extra);
        Answer a = post("/api/v1/sections/" + s.getId() + "/lessons", b, owner);
        assertEquals(200, a.status(), a.body());
        return a.data().path("id").asText();
    }

    private Answer attach(String lessonId, MediaAsset asset, String title, User as) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("mediaAssetId", asset.getId());
        if (title != null) b.put("title", title);
        return post("/api/v1/lessons/" + lessonId + "/attachments", b, as);
    }

    private static JsonNode lessonIn(JsonNode course, String lessonId) {
        for (JsonNode section : course.path("sections"))
            for (JsonNode l : section.path("lessons")) if (lessonId.equals(l.path("id").asText())) return l;
        throw new AssertionError("lesson not in course: " + lessonId);
    }

    @Test
    @DisplayName("derived type: assignment > video (link or upload) > documents > text; a requested type is ignored")
    void derivedTypePriority() throws Exception {
        String id = newLesson(freeSection, Map.of("type", "ASSIGNMENT", "contentText", "Chu thich"));
        assertEquals("TEXT", lessonIn(get("/api/v1/courses/" + free.getId(), owner).data(), id).path("type").asText());

        Answer doc = attach(id, asset("application/pdf", "a.pdf", owner), null, owner);
        assertEquals(200, doc.status(), doc.body());
        assertEquals("a.pdf", doc.data().path("title").asText(), "the title defaults to the file name");
        assertEquals("DOCUMENT", lessonIn(get("/api/v1/courses/" + free.getId(), owner).data(), id).path("type").asText());

        Answer link = put("/api/v1/lessons/" + id, Map.of("videoUrl", "https://youtu.be/dQw4w9WgXcQ"), owner);
        assertEquals("VIDEO", link.data().path("type").asText());
        assertTrue(link.data().path("components").path("video").asBoolean());
        assertEquals("YOUTUBE", link.data().path("components").path("videoProvider").asText());
        assertEquals(1, link.data().path("components").path("attachments").asInt());

        Answer asg = put("/api/v1/lessons/" + id, Map.of("hasAssignment", true, "assignmentInstructions", "Lam bai 1"), owner);
        assertEquals(200, asg.status(), asg.body());
        assertEquals("ASSIGNMENT", asg.data().path("type").asText());
        assertTrue(asg.data().path("hasAssignment").asBoolean());
        assertTrue(asg.data().path("components").path("assignment").asBoolean());
        assertTrue(asg.data().path("components").path("content").asBoolean());

        Answer off = put("/api/v1/lessons/" + id, Map.of("hasAssignment", false), owner);
        assertEquals("VIDEO", off.data().path("type").asText());
        assertTrue(off.data().path("assignmentInstructions").isNull(), "instructions go with the component");

        put("/api/v1/lessons/" + id, Map.of("videoUrl", ""), owner);
        for (var a : attachmentRepository.findByLesson(id)) {
            assertEquals(200, delete("/api/v1/lessons/" + id + "/attachments/" + a.getId(), owner).status());
        }
        assertEquals("TEXT", lessonRepository.findById(id).orElseThrow().getType());
    }

    @Test
    @DisplayName("assignment: instructions required (400), submit form keyed on hasAssignment, not on type")
    void assignmentGating() throws Exception {
        assertEquals(400, post("/api/v1/sections/" + freeSection.getId() + "/lessons",
                Map.of("title", "Khong huong dan", "hasAssignment", true), owner).status());
        assertEquals(400, post("/api/v1/sections/" + freeSection.getId() + "/lessons",
                Map.of("title", "Trong", "hasAssignment", true, "assignmentInstructions", "   "), owner).status());
        assertEquals(400, post("/api/v1/sections/" + freeSection.getId() + "/lessons",
                Map.of("title", "Dai", "hasAssignment", true, "assignmentInstructions", "x".repeat(20_001)), owner).status());

        String plain = newLesson(freeSection, Map.of("contentText", "Chi co noi dung"));
        Answer no = post("/api/v1/lessons/" + plain + "/submissions", Map.of("submissionText", "bai lam"), member);
        assertEquals(400, no.status(), "no assignment component -> not an assignment");

        String withAsg = newLesson(freeSection, Map.of("hasAssignment", true, "assignmentInstructions", "Hay giai", "type", "TEXT"));
        Answer yes = post("/api/v1/lessons/" + withAsg + "/submissions", Map.of("submissionText", "bai lam"), member);
        assertEquals(200, yes.status(), yes.body());
        // a lesson can combine content + video link + documents + assignment at once
        Answer all = put("/api/v1/lessons/" + withAsg, Map.of("videoUrl", "https://youtu.be/dQw4w9WgXcQ", "contentText", "Noi dung"), owner);
        assertEquals("ASSIGNMENT", all.data().path("type").asText());
        assertEquals(200, post("/api/v1/lessons/" + withAsg + "/submissions", Map.of("submissionText", "lan 2"), member).status());
    }

    @Test
    @DisplayName("attachments: CRUD, reorder, limit 20 (409), validation, one lesson per file, video files refused as documents")
    void attachmentCrud() throws Exception {
        String id = newLesson(freeSection, Map.of());
        MediaAsset a1 = asset("application/pdf", "one.pdf", owner);
        MediaAsset a2 = asset("application/zip", "two.zip", owner);
        String att1 = attach(id, a1, "Phieu 1", owner).data().path("id").asText();
        String att2 = attach(id, a2, null, owner).data().path("id").asText();

        Answer dup = attach(newLesson(freeSection, Map.of()), a1, null, owner);
        assertEquals(400, dup.status(), "a file is attached once");
        assertEquals(400, attach(id, asset("video/mp4", "v.mp4", owner), null, owner).status(), "video belongs in the video slot");
        assertEquals(400, post("/api/v1/lessons/" + id + "/attachments", Map.of(), owner).status());
        MediaAsset pending = asset("application/pdf", "p.pdf", owner);
        pending.setStatus("PENDING");
        mediaAssetRepository.save(pending);
        assertEquals(400, attach(id, pending, null, owner).status());
        assertEquals(400, attach(id, asset("application/pdf", "x".repeat(10) + ".pdf", owner), "t".repeat(201), owner).status());

        Answer renamed = call(org.springframework.http.HttpMethod.PATCH, "/api/v1/lessons/" + id + "/attachments/" + att1, Map.of("title", "Doi ten"), owner);
        assertEquals(200, renamed.status(), renamed.body());
        assertEquals("Doi ten", renamed.data().path("title").asText());

        Answer re = put("/api/v1/lessons/" + id + "/attachments/reorder", Map.of("ids", List.of(att2, att1)), owner);
        assertEquals(200, re.status(), re.body());
        assertEquals(att2, re.data().get(0).path("id").asText());
        assertEquals(400, put("/api/v1/lessons/" + id + "/attachments/reorder", Map.of("ids", List.of(att2)), owner).status());
        assertEquals(400, put("/api/v1/lessons/" + id + "/attachments/reorder", Map.of("ids", List.of(att2, att2)), owner).status());

        JsonNode listed = lessonIn(get("/api/v1/courses/" + free.getId(), owner).data(), id).path("attachments");
        assertEquals(List.of(att2, att1), ids(listed));
        assertEquals("two.zip", listed.get(0).path("fileName").asText());
        assertEquals("application/zip", listed.get(0).path("mimeType").asText());
        assertEquals(1234, listed.get(0).path("sizeBytes").asLong());

        assertEquals(200, delete("/api/v1/lessons/" + id + "/attachments/" + att1, owner).status());
        assertEquals(404, delete("/api/v1/lessons/" + id + "/attachments/" + att1, owner).status());
        assertNotNull(mediaAssetRepository.findById(a1.getId()).orElse(null), "the media row stays; the file is only detached");
        assertFalse(attachmentRepository.existsByMediaAssetId(a1.getId()));
        // the detached file can be attached again (to the same or another lesson)
        assertEquals(200, attach(id, a1, null, owner).status());

        // limit
        String full = newLesson(freeSection, Map.of());
        for (int i = 0; i < 20; i++) assertEquals(200, attach(full, asset("application/pdf", "f" + i + ".pdf", owner), null, owner).status());
        Answer over = attach(full, asset("application/pdf", "f21.pdf", owner), null, owner);
        assertEquals(409, over.status(), over.body());
    }

    private static List<String> ids(JsonNode items) {
        List<String> r = new ArrayList<>();
        for (JsonNode n : items) r.add(n.path("id").asText());
        return r;
    }

    @Test
    @DisplayName("attachments: only COURSE:EDIT may write (member 403, staff without grant 403, outsider 403/404, guest 401); other classes' files refused")
    void attachmentAuthorization() throws Exception {
        String id = newLesson(freeSection, Map.of());
        MediaAsset a = asset("application/pdf", "auth.pdf", owner);
        for (User u : new User[]{member, staffNoEdit, outsider}) {
            Answer r = attach(id, a, null, u);
            assertTrue(r.status() == 403 || r.status() == 404, u.getEmail() + " -> " + r.status());
        }
        assertEquals(401, attach(id, a, null, null).status());
        assertFalse(attachmentRepository.existsByMediaAssetId(a.getId()));

        Classroom other = newClass(owner, "Lop khac", "PUBLIC");
        MediaAsset foreign = new MediaAsset(other.getId(), owner.getId(), "classes/" + other.getId() + "/media/f.pdf", "f.pdf", "application/pdf", 10);
        foreign.setStatus("UPLOADED");
        foreign = mediaAssetRepository.save(foreign);
        assertEquals(400, attach(id, foreign, null, owner).status(), "the file must belong to the lesson's class");
    }

    @Test
    @DisplayName("visibility: learners get documents + instructions; a non-buyer of a paid course gets component counts only; download-url follows lesson rules")
    void visibilityAndDownload() throws Exception {
        MediaAsset freeDoc = asset("application/pdf", "free.pdf", owner);
        MediaAsset paidDoc = asset("application/pdf", "paid.pdf", owner);
        String freeId = newLesson(freeSection, Map.of("hasAssignment", true, "assignmentInstructions", "Huong dan bi mat"));
        String paidId = newLesson(paidSection, Map.of("hasAssignment", true, "assignmentInstructions", "Huong dan tra phi"));
        attach(freeId, freeDoc, "Tai lieu mien phi", owner);
        attach(paidId, paidDoc, "Tai lieu tra phi", owner);

        JsonNode fl = lessonIn(get("/api/v1/courses/" + free.getId(), member).data(), freeId);
        assertEquals(freeDoc.getId(), fl.path("attachments").get(0).path("mediaAssetId").asText());
        assertEquals("Huong dan bi mat", fl.path("assignmentInstructions").asText());
        Answer single = get("/api/v1/lessons/" + freeId, member);
        assertEquals(200, single.status(), single.body());
        assertEquals(1, single.data().path("attachments").size());
        Answer dl = get("/api/v1/media/" + freeDoc.getId() + "/download-url", member);
        assertEquals(200, dl.status(), dl.body());

        Answer paidView = get("/api/v1/courses/" + paid.getId(), member);
        JsonNode pl = lessonIn(paidView.data(), paidId);
        assertEquals(0, pl.path("attachments").size(), "no media ids for a non-buyer");
        assertEquals(1, pl.path("components").path("attachments").asInt(), "but the count is public metadata");
        assertTrue(pl.path("hasAssignment").asBoolean());
        assertTrue(pl.path("assignmentInstructions").isNull());
        assertFalse(paidView.body().contains(paidDoc.getId()), "the media id never reaches a non-buyer");
        assertFalse(paidView.body().contains("Huong dan tra phi"));
        assertEquals(403, get("/api/v1/lessons/" + paidId, member).status());
        assertEquals(403, get("/api/v1/media/" + paidDoc.getId() + "/download-url", member).status());
        assertEquals(401, get("/api/v1/media/" + paidDoc.getId() + "/download-url", null).status());
        assertEquals(200, get("/api/v1/media/" + paidDoc.getId() + "/download-url", owner).status());
        assertEquals(401, get("/api/v1/lessons/" + freeId, null).status());
        assertEquals(1, get("/api/v1/lessons/" + paidId, owner).data().path("attachments").size());
    }

    @Test
    @DisplayName("video slot takes video/audio files only; mass assignment of derived fields is ignored")
    void videoMimeAndMassAssignment() throws Exception {
        MediaAsset pdf = asset("application/pdf", "notvideo.pdf", owner);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("title", "Sai nguon");
        b.put("mediaAssetId", pdf.getId());
        assertEquals(400, post("/api/v1/sections/" + freeSection.getId() + "/lessons", b, owner).status());
        String id = newLesson(freeSection, Map.of());
        assertEquals(400, put("/api/v1/lessons/" + id, Map.of("mediaAssetId", pdf.getId()), owner).status());
        MediaAsset video = asset("video/mp4", "ok.mp4", owner);
        Answer ok = put("/api/v1/lessons/" + id, Map.of("mediaAssetId", video.getId()), owner);
        assertEquals(200, ok.status(), ok.body());
        assertEquals("VIDEO", ok.data().path("type").asText());
        assertEquals("UPLOAD", ok.data().path("components").path("videoProvider").asText());

        Map<String, Object> evil = new LinkedHashMap<>();
        evil.put("components", Map.of("video", true, "attachments", 99, "assignment", true));
        evil.put("attachments", List.of(Map.of("mediaAssetId", pdf.getId(), "title", "x")));
        evil.put("type", "ASSIGNMENT");
        evil.put("videoProvider", "YOUTUBE");
        Answer u = put("/api/v1/lessons/" + id, evil, owner);
        assertEquals(200, u.status(), u.body());
        assertEquals("VIDEO", u.data().path("type").asText());
        assertFalse(u.data().path("hasAssignment").asBoolean());
        assertEquals(0, u.data().path("components").path("attachments").asInt());
        assertFalse(attachmentRepository.existsByMediaAssetId(pdf.getId()));
        Lesson row = lessonRepository.findById(id).orElseThrow();
        assertFalse(row.isAssignmentEnabled());
    }
}
