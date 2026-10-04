package com.classroom.modules.ranking.controller;

import com.classroom.modules.classroom.controller.ClassContentHttpTestBase;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.ranking.model.RankTier;
import com.classroom.modules.ranking.repository.RankTierRepository;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** GET /classes/{classId}/leaderboard/tiers: same access rule as GET /leaderboard, tiers ordered by minPoints ascending. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderboardTiersHttpTest extends ClassContentHttpTestBase {

    @Autowired private RankTierRepository rankTierRepository;

    private User owner;
    private User member;
    private User staffUser;
    private User stranger;
    private User removed;
    private Classroom pub;
    private Classroom priv;

    @BeforeAll
    void fixtures() {
        owner = user("tier-owner");
        member = user("tier-member");
        staffUser = user("tier-staff");
        stranger = user("tier-stranger");
        removed = user("tier-removed");
        pub = newClass(owner, "Lop bac xep hang", "PUBLIC");
        priv = newClass(owner, "Lop bac rieng tu", "PRIVATE");
        for (Classroom c : List.of(pub, priv)) {
            member(c, member, "ACTIVE");
            member(c, removed, "REMOVED");
            staff(c, staffUser, "FEED:VIEW");
            // saved out of order on purpose
            rankTierRepository.save(new RankTier(c.getId(), "Vang", 500, "https://badges.example/gold.png", "Hang vang"));
            rankTierRepository.save(new RankTier(c.getId(), "Dong", 0, null, null));
            rankTierRepository.save(new RankTier(c.getId(), "Bac", 100, null, "Hang bac"));
        }
    }

    private String path(Classroom c) {
        return "/api/v1/classes/" + c.getId() + "/leaderboard/tiers";
    }

    @Test
    @DisplayName("member, owner and staff get the tiers ordered by minPoints asc, with the configuration endpoint's field names too")
    void insidersReadTiers() throws Exception {
        for (User u : new User[]{member, owner, staffUser}) {
            Answer a = get(path(pub), u);
            assertEquals(200, a.status(), u.getEmail() + " -> " + a.body());
            List<String> names = new ArrayList<>();
            List<Integer> points = new ArrayList<>();
            for (JsonNode t : a.data()) {
                names.add(t.path("name").asText());
                points.add(t.path("minPoints").asInt());
                assertEquals(t.path("name").asText(), t.path("tierName").asText());
            }
            assertEquals(List.of("Dong", "Bac", "Vang"), names);
            assertEquals(List.of(0, 100, 500), points);
            JsonNode gold = a.data().get(2);
            assertEquals("https://badges.example/gold.png", gold.path("badgeUrl").asText());
            assertEquals("Hang vang", gold.path("description").asText());
        }
        assertEquals(200, get(path(priv), member).status());
    }

    @Test
    @DisplayName("same answers as GET /leaderboard for outsiders: guest 401, non-member / REMOVED 403 on a public class, 404 on a private one")
    void outsidersAreRefusedLikeTheLeaderboard() throws Exception {
        assertEquals(401, get(path(pub), null).status());
        for (User u : new User[]{stranger, removed}) {
            Answer tiers = get(path(pub), u);
            Answer board = get("/api/v1/classes/" + pub.getId() + "/leaderboard", u);
            assertEquals(403, tiers.status(), tiers.body());
            assertEquals(board.status(), tiers.status());
            assertEquals(board.code(), tiers.code());
            assertFalse(tiers.body().contains("Vang"));
        }
        Answer hidden = get(path(priv), stranger);
        Answer missing = get("/api/v1/classes/" + UUID.randomUUID() + "/leaderboard/tiers", stranger);
        assertEquals(404, hidden.status(), hidden.body());
        assertEquals(missing.status(), hidden.status());
        assertEquals(missing.code(), hidden.code());
        assertFalse(hidden.body().contains("Vang"));
    }

    @Test
    @DisplayName("a class without tiers returns an empty list")
    void noTiers() throws Exception {
        Classroom bare = newClass(owner, "Lop chua co bac", "PUBLIC");
        Answer a = get(path(bare), owner);
        assertEquals(200, a.status());
        assertEquals(0, a.data().size());
    }
}
