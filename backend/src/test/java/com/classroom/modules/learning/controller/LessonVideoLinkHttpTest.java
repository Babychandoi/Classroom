package com.classroom.modules.learning.controller;

import com.classroom.modules.classroom.controller.ClassContentHttpTestBase;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.model.Lesson;
import com.classroom.modules.learning.model.Section;
import com.classroom.modules.learning.repository.CourseRepository;
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

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** D-31: external lesson video through the real servlet chain: create / update / clear, one source, VIDEO only, visibility, mass assignment. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LessonVideoLinkHttpTest extends ClassContentHttpTestBase {

    private static final String YT = "https://youtu.be/dQw4w9WgXcQ?t=5";
    private static final String DRIVE = "https://drive.google.com/file/d/1AbC_dEf-GhIjKlMnOp/view?usp=sharing";

    @Autowired private CourseRepository courseRepository;
    @Autowired private SectionRepository sectionRepository;
    @Autowired private LessonRepository lessonRepository;

    private User owner;
    private User member;
    private User outsider;
    private Classroom cls;
    private Course free;
    private Section freeSection;
    private Course paid;
    private Section paidSection;

    @BeforeAll
    void fixtures() {
        owner = user("vl-owner");
        member = user("vl-member");
        outsider = user("vl-outsider");
        cls = newClass(owner, "Video link class", "PUBLIC");
        member(cls, member, "ACTIVE");
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

    private Map<String, Object> body(String type, String videoUrl) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("title", "Bai video");
        b.put("type", type);
        if (videoUrl != null) b.put("videoUrl", videoUrl);
        return b;
    }

    private Answer create(Section s, Map<String, Object> b) throws Exception {
        return post("/api/v1/sections/" + s.getId() + "/lessons", b, owner);
    }

    @Test
    @DisplayName("create with a YouTube link: provider + canonical + embed URLs derived by the server; update to Drive; blank clears")
    void createUpdateClear() throws Exception {
        Answer a = create(freeSection, body("VIDEO", YT));
        assertEquals(200, a.status(), a.body());
        JsonNode d = a.data();
        assertEquals("YOUTUBE", d.path("videoProvider").asText());
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", d.path("videoUrl").asText());
        assertEquals("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ", d.path("embedUrl").asText());
        assertFalse(d.has("videoRef") || d.has("storedVideoRef") || d.has("storedVideoProvider"), "internals are not exposed");
        String id = d.path("id").asText();
        Lesson row = lessonRepository.findById(id).orElseThrow();
        assertEquals("YOUTUBE", row.getStoredVideoProvider());
        assertEquals("dQw4w9WgXcQ", row.getStoredVideoRef());

        Answer toDrive = put("/api/v1/lessons/" + id, Map.of("videoUrl", DRIVE), owner);
        assertEquals(200, toDrive.status(), toDrive.body());
        assertEquals("GOOGLE_DRIVE", toDrive.data().path("videoProvider").asText());
        assertEquals("https://drive.google.com/file/d/1AbC_dEf-GhIjKlMnOp/preview", toDrive.data().path("embedUrl").asText());

        Answer keep = put("/api/v1/lessons/" + id, Map.of("title", "Doi ten"), owner);
        assertEquals("GOOGLE_DRIVE", keep.data().path("videoProvider").asText(), "absent videoUrl keeps the link");

        Answer cleared = put("/api/v1/lessons/" + id, Map.of("videoUrl", ""), owner);
        assertEquals(200, cleared.status(), cleared.body());
        assertTrue(cleared.data().path("videoProvider").isNull());
        assertTrue(cleared.data().path("embedUrl").isNull());
        assertNull(lessonRepository.findById(id).orElseThrow().getStoredVideoRef());
    }

    @Test
    @DisplayName("invalid / look-alike / http links -> 400 BAD_REQUEST with the Vietnamese message; nothing is stored")
    void invalidLinks() throws Exception {
        for (String bad : new String[]{"http://youtu.be/dQw4w9WgXcQ", "https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ",
                "javascript:alert(1)", "https://drive.google.com/drive/folders/1AbC_dEf-GhIjKlMnOp"}) {
            Answer a = create(freeSection, body("VIDEO", bad));
            assertEquals(400, a.status(), bad);
            assertEquals("BAD_REQUEST", a.code());
            assertTrue(a.message().startsWith("Chỉ hỗ trợ liên kết video YouTube hoặc Google Drive"), a.message());
        }
        String id = create(freeSection, body("VIDEO", null)).data().path("id").asText();
        assertEquals(400, put("/api/v1/lessons/" + id, Map.of("videoUrl", "https://evilyoutube.com/watch?v=dQw4w9WgXcQ"), owner).status());
        assertNull(lessonRepository.findById(id).orElseThrow().getStoredVideoRef());
    }

    @Test
    @DisplayName("one source only: link + uploaded file -> 400 both ways; non-VIDEO lessons refuse a link")
    void singleSourceAndType() throws Exception {
        MediaAsset asset = uploadedImage(cls, owner, "LESSON_MEDIA");
        Map<String, Object> both = body("VIDEO", YT);
        both.put("mediaAssetId", asset.getId());
        Answer a = create(freeSection, both);
        assertEquals(400, a.status(), a.body());
        assertTrue(a.message().startsWith("Mỗi bài chỉ dùng một nguồn video"), a.message());

        assertEquals(400, create(freeSection, body("TEXT", YT)).status());

        String id = create(freeSection, body("VIDEO", YT)).data().path("id").asText();
        Map<String, Object> attach = new LinkedHashMap<>();
        attach.put("mediaAssetId", asset.getId());
        Answer attached = put("/api/v1/lessons/" + id, attach, owner);
        assertEquals(400, attached.status(), "attaching a file while a link is set must be explicit");
        assertEquals("YOUTUBE", lessonRepository.findById(id).orElseThrow().getStoredVideoProvider());

        // switching source in ONE request (clear the link, attach the file) is fine
        attach.put("videoUrl", "");
        assertEquals(200, put("/api/v1/lessons/" + id, attach, owner).status());
        Lesson row = lessonRepository.findById(id).orElseThrow();
        assertEquals(asset.getId(), row.getMediaAssetId());
        assertNull(row.getStoredVideoRef());
        assertEquals("UPLOAD", row.getVideoProvider());

        // and turning a link lesson into a TEXT lesson is refused
        String id2 = create(freeSection, body("VIDEO", YT)).data().path("id").asText();
        assertEquals(400, put("/api/v1/lessons/" + id2, Map.of("type", "TEXT"), owner).status());
    }

    @Test
    @DisplayName("mass assignment: videoProvider / videoRef / embedUrl / stored* in the body are ignored")
    void massAssignment() throws Exception {
        Map<String, Object> b = body("VIDEO", null);
        b.put("videoProvider", "YOUTUBE");
        b.put("videoRef", "aaaaaaaaaaa");
        b.put("embedUrl", "https://evil.example/x");
        b.put("storedVideoProvider", "YOUTUBE");
        b.put("storedVideoRef", "bbbbbbbbbbb");
        Answer a = create(freeSection, b);
        assertEquals(200, a.status(), a.body());
        assertTrue(a.data().path("videoProvider").isNull());
        assertTrue(a.data().path("embedUrl").isNull());
        Lesson row = lessonRepository.findById(a.data().path("id").asText()).orElseThrow();
        assertNull(row.getStoredVideoRef());
        assertNull(row.getStoredVideoProvider());

        String id = create(freeSection, body("VIDEO", YT)).data().path("id").asText();
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("videoProvider", "GOOGLE_DRIVE");
        patch.put("videoRef", "cccccccccc");
        patch.put("embedUrl", "https://evil.example/y");
        patch.put("storedVideoRef", "dddddddddd");
        Answer u = put("/api/v1/lessons/" + id, patch, owner);
        assertEquals(200, u.status(), u.body());
        assertEquals("YOUTUBE", u.data().path("videoProvider").asText());
        assertEquals("dQw4w9WgXcQ", lessonRepository.findById(id).orElseThrow().getStoredVideoRef());
    }

    private static JsonNode lessonIn(JsonNode course, String lessonId) {
        for (JsonNode section : course.path("sections")) {
            for (JsonNode l : section.path("lessons")) if (lessonId.equals(l.path("id").asText())) return l;
        }
        throw new AssertionError("lesson not in course detail: " + lessonId);
    }

    @Test
    @DisplayName("visibility: members of a free course get the URLs; a non-buyer of a paid course gets provider only; the lesson itself is 403; guests 401")
    void visibility() throws Exception {
        String freeId = create(freeSection, body("VIDEO", YT)).data().path("id").asText();
        String paidId = create(paidSection, body("VIDEO", DRIVE)).data().path("id").asText();

        JsonNode freeDetail = lessonIn(get("/api/v1/courses/" + free.getId(), member).data(), freeId);
        assertEquals("YOUTUBE", freeDetail.path("videoProvider").asText());
        assertEquals("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ", freeDetail.path("embedUrl").asText());
        Answer lesson = get("/api/v1/lessons/" + freeId, member);
        assertEquals(200, lesson.status(), lesson.body());
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", lesson.data().path("videoUrl").asText());
        assertEquals("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ", lesson.data().path("embedUrl").asText());

        Answer paidDetail = get("/api/v1/courses/" + paid.getId(), member);
        assertEquals(200, paidDetail.status(), paidDetail.body());
        JsonNode pl = lessonIn(paidDetail.data(), paidId);
        assertEquals("GOOGLE_DRIVE", pl.path("videoProvider").asText(), "provider is public metadata");
        assertTrue(pl.path("videoUrl").isNull(), paidDetail.body());
        assertTrue(pl.path("embedUrl").isNull());
        assertFalse(paidDetail.body().contains("1AbC_dEf-GhIjKlMnOp"), "the Drive id never reaches a non-buyer");
        Answer paidLesson = get("/api/v1/lessons/" + paidId, member);
        assertEquals(403, paidLesson.status());
        assertFalse(paidLesson.body().contains("1AbC_dEf-GhIjKlMnOp"));

        assertEquals(401, get("/api/v1/lessons/" + freeId, null).status());
        assertEquals(401, get("/api/v1/courses/" + paid.getId(), null).status());
        assertFalse(get("/api/v1/lessons/" + freeId, outsider).body().contains("dQw4w9WgXcQ"), "an outsider learns nothing");
        // the course owner (managers) do see the paid lesson's link
        assertEquals("https://drive.google.com/file/d/1AbC_dEf-GhIjKlMnOp/preview",
                get("/api/v1/lessons/" + paidId, owner).data().path("embedUrl").asText());
    }
}
