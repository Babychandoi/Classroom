package com.classroom.modules.classroom.controller;

import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.identity.model.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D-33: GET /classes?discover=true drops every PRIVATE class in SQL, so paging is full; the default listing and guests are unchanged. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClassDiscoverHttpTest extends ClassContentHttpTestBase {

    private static List<String> ids(JsonNode items) {
        List<String> r = new ArrayList<>();
        for (JsonNode n : items) r.add(n.path("id").asText());
        return r;
    }

    @Test
    @DisplayName("owner with many PRIVATE and some PUBLIC classes: discover=true pages through public ones only, full pages, every variant")
    void discover() throws Exception {
        String tag = "dz" + UUID.randomUUID().toString().substring(0, 8);
        User owner = user("disc-owner");
        List<String> publicIds = new ArrayList<>();
        List<String> privateIds = new ArrayList<>();
        // newest first listing: private classes are created LAST so they crowd page 0 of the default listing
        for (int i = 0; i < 3; i++) publicIds.add(newClass(owner, tag + " cong khai " + i, "PUBLIC").getId());
        for (int i = 0; i < 6; i++) privateIds.add(newClass(owner, tag + " rieng tu " + i, "PRIVATE").getId());
        Classroom suspended = newClass(owner, tag + " tam khoa", "PUBLIC");
        suspended.setStatusBeforeSuspend("ACTIVE");
        suspended.setStatus("SUSPENDED");
        classroomRepository.save(suspended);

        for (String variant : new String[]{"", "&sort=popular", "&sort=newest"}) {
            String base = "/api/v1/classes?q=" + tag + variant;
            // default (unchanged): the owner's private classes are listed
            List<String> all = ids(get(base + "&size=50", owner).data());
            assertTrue(all.containsAll(privateIds), "default listing keeps private classes for their owner: " + variant);
            assertEquals(false, get(base + "&size=50&discover=false", owner).data().isEmpty());
            assertEquals(all, ids(get(base + "&size=50&discover=false", owner).data()));

            List<String> discovered = ids(get(base + "&size=50&discover=true", owner).data());
            for (String p : privateIds) assertTrue(!discovered.contains(p), "private class leaked into discover: " + variant);
            assertTrue(discovered.containsAll(publicIds), variant);
            // the owner still sees his own suspended class (visibility rule untouched), it is not PRIVATE
            assertTrue(discovered.contains(suspended.getId()), variant);
            assertEquals(4, discovered.size(), variant);

            // paging: size=2 pages are FULL until the last, and walking them gives exactly the discover set
            List<String> walked = new ArrayList<>();
            for (int page = 0; page < 2; page++) {
                List<String> chunk = ids(get(base + "&size=2&page=" + page + "&discover=true", owner).data());
                assertEquals(2, chunk.size(), "page " + page + " is full: " + variant);
                walked.addAll(chunk);
            }
            assertEquals(0, get(base + "&size=2&page=2&discover=true", owner).data().size());
            assertEquals(new java.util.HashSet<>(discovered), new java.util.HashSet<>(walked));
        }
    }

    @Test
    @DisplayName("members of a private class do not get it in discover either; category filter and the plain listing work; guests are unchanged")
    void memberGuestAndCategory() throws Exception {
        String tag = "dm" + UUID.randomUUID().toString().substring(0, 8);
        User owner = user("disc-owner2");
        User member = user("disc-member");
        Classroom pub = newClass(owner, tag + " pub", "PUBLIC");
        Classroom priv = newClass(owner, tag + " priv", "PRIVATE");
        member(pub, member, "ACTIVE");
        member(priv, member, "ACTIVE");

        assertTrue(ids(get("/api/v1/classes?q=" + tag, member).data()).contains(priv.getId()), "default keeps a member's private class");
        List<String> discovered = ids(get("/api/v1/classes?q=" + tag + "&discover=true", member).data());
        assertEquals(List.of(pub.getId()), discovered);
        assertEquals(List.of(pub.getId()), ids(get("/api/v1/classes?q=" + tag + "&discover=true&sort=popular", member).data()));

        // guests: identical with or without the flag, private never present
        List<String> guestDefault = ids(get("/api/v1/classes?q=" + tag, null).data());
        assertEquals(List.of(pub.getId()), guestDefault);
        assertEquals(guestDefault, ids(get("/api/v1/classes?q=" + tag + "&discover=true", null).data()));
        assertEquals(ids(get("/api/v1/classes?size=5", null).data()), ids(get("/api/v1/classes?size=5&discover=true", null).data()));
        // no q at all, signed in: plain newest listing with the flag also excludes private ones
        for (String id : ids(get("/api/v1/classes?size=100&discover=true", owner).data())) {
            assertTrue(!id.equals(priv.getId()));
        }
    }
}
