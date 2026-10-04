package com.classroom.modules.classroom.policy;

import com.classroom.modules.classroom.dto.ClassroomDto;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.model.StaffAssignment;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.classroom.repository.StaffAssignmentRepository;
import com.classroom.modules.classroom.service.ClassroomService;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-19: the class-visibility rule exists twice - {@code AccessPolicy.isClassVisibleToUser} (one class at a time) and its SQL twins
 * {@code ClassroomRepository.findPubliclyVisible} / {@code findVisibleToUser} (a page of the listing). They must never disagree, or the list would
 * show a class the detail page refuses (or hide one it allows).
 *
 * <p>The fixture is every combination of PUBLIC/PRIVATE x FREE/PAID x ACTIVE/ARCHIVED (8 classes). One parameterised case per kind of viewer - guest,
 * stranger, owner, ACTIVE staff, revoked staff, ACTIVE member, ACTIVE-but-lapsed member, EXPIRED member, REMOVED, BLOCKED and the legacy BANNED -
 * compares, for all 8 classes, the policy answer with (a) the SQL listing and (b) the paged {@code GET /classes} service: 11 x 8 = 88 comparisons.
 * It runs on H2; the same JPQL is executed on a real MySQL by ClassAccessConcurrencyIntegrationTest.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessPolicySqlParityTest {

    @Autowired private ClassroomRepository classroomRepository;
    @Autowired private ClassMemberRepository memberRepository;
    @Autowired private StaffAssignmentRepository staffAssignmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AccessPolicy accessPolicy;
    @Autowired private ClassroomService classroomService;

    private User owner;
    private final Map<String, User> viewers = new LinkedHashMap<>();
    private final List<Classroom> classes = new ArrayList<>();
    private Set<String> fixtureIds;

    private User user(String tag) {
        return userRepository.save(new User(UUID.randomUUID().toString(),
                "parity-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8) + "@d19.test", "hash", "Parity " + tag, "USER"));
    }

    @BeforeAll
    void buildEightClassesAndElevenViewers() {
        owner = user("owner");
        for (String kind : List.of("stranger", "staffActive", "staffRevoked", "memberActive", "memberLapsed", "memberExpired",
                "removed", "blocked", "banned", "pending")) {
            viewers.put(kind, user(kind));
        }
        for (String visibility : List.of("PUBLIC", "PRIVATE")) {
            for (String access : List.of("FREE", "PAID")) {
                for (String status : List.of("ACTIVE", "ARCHIVED")) {
                    Classroom c = new Classroom();
                    c.setOwnerId(owner.getId());
                    c.setSlug("parity-" + visibility + "-" + access + "-" + status + "-" + UUID.randomUUID().toString().substring(0, 6));
                    c.setTitle("Parity " + visibility + " " + access + " " + status);
                    c.setVisibility(visibility);
                    c.setAccessType(access);
                    c.setStatus(status);
                    c = classroomRepository.save(c);
                    classes.add(c);
                    memberRepository.save(new ClassMember(c.getId(), owner.getId(), "OWNER"));

                    StaffAssignment staffActive = new StaffAssignment(c.getId(), viewers.get("staffActive").getId());
                    staffActive.setStatus("ACTIVE");
                    staffAssignmentRepository.save(staffActive);
                    StaffAssignment staffRevoked = new StaffAssignment(c.getId(), viewers.get("staffRevoked").getId());
                    staffRevoked.setStatus("REVOKED");
                    staffAssignmentRepository.save(staffRevoked);

                    row(c, viewers.get("memberActive"), "ACTIVE", null);
                    row(c, viewers.get("memberLapsed"), "ACTIVE", Instant.now().minus(1, ChronoUnit.HOURS));
                    row(c, viewers.get("memberExpired"), "EXPIRED", Instant.now().minus(1, ChronoUnit.DAYS));
                    row(c, viewers.get("removed"), "REMOVED", null);
                    row(c, viewers.get("blocked"), "BLOCKED", null);
                    row(c, viewers.get("banned"), "BANNED", null);
                    // D-28: a pending join request is not a relation to the class
                    row(c, viewers.get("pending"), "PENDING", null);
                }
            }
        }
        fixtureIds = classes.stream().map(Classroom::getId).collect(Collectors.toSet());
    }

    private void row(Classroom c, User u, String state, Instant expiresAt) {
        ClassMember m = new ClassMember(c.getId(), u.getId(), "STUDENT");
        m.setState(state);
        m.setAccessExpiresAt(expiresAt);
        memberRepository.save(m);
    }

    private static PageRequest everything() {
        return PageRequest.of(0, 5000, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.ASC, "id")));
    }

    private Set<String> mine(List<Classroom> listed) {
        return listed.stream().map(Classroom::getId).filter(fixtureIds::contains).collect(Collectors.toSet());
    }

    /** Every page of GET /classes for this viewer (size 7, so the 8 fixture classes straddle pages), as ids of the fixture classes. */
    private Set<String> pagedListing(String viewerId) {
        Set<String> seen = new HashSet<>();
        List<ClassroomDto> page;
        int index = 0;
        do {
            page = classroomService.getAllClassrooms(viewerId, index++, 7);
            for (ClassroomDto d : page) {
                assertTrue(seen.add(d.getId()), "class repeated across pages: " + d.getId());
            }
            if (page.size() == 7) {
                assertTrue(index < 1000, "paging does not terminate");
            }
        } while (page.size() == 7);
        seen.retainAll(fixtureIds);
        return seen;
    }

    @ParameterizedTest(name = "viewer = {0}")
    @ValueSource(strings = {"guest", "stranger", "owner", "staffActive", "staffRevoked", "memberActive", "memberLapsed", "memberExpired",
            "removed", "blocked", "banned", "pending"})
    @DisplayName("SQL listing == AccessPolicy.isClassVisibleToUser for all 8 class shapes, per kind of viewer - and so is the paged GET /classes")
    void sqlAndPolicyAgree(String kind) {
        String viewerId = switch (kind) {
            case "guest" -> null;
            case "owner" -> owner.getId();
            default -> viewers.get(kind).getId();
        };
        Set<String> sql = viewerId == null
                ? mine(classroomRepository.findPubliclyVisible(everything()))
                : mine(classroomRepository.findVisibleToUser(viewerId, everything()));
        Set<String> paged = pagedListing(viewerId);
        // D-27: the search / popularity twins of the listing must apply the very same visibility rule.
        Set<String> searched = viewerId == null
                ? mine(classroomRepository.searchPubliclyVisible("%parity%", "", everything()))
                : mine(classroomRepository.searchVisibleToUser(viewerId, "%parity%", "", everything()));
        Set<String> popular = viewerId == null
                ? mine(classroomRepository.searchPubliclyVisibleByPopularity("%", "", Instant.now(), PageRequest.of(0, 5000)))
                : mine(classroomRepository.searchVisibleToUserByPopularity(viewerId, "%", "", Instant.now(), PageRequest.of(0, 5000)));
        for (Classroom c : classes) {
            boolean policy = accessPolicy.isClassVisibleToUser(c, viewerId);
            assertEquals(policy, searched.contains(c.getId()), kind + " / " + c.getTitle() + ": policy=" + policy + " search=" + searched.contains(c.getId()));
            assertEquals(policy, popular.contains(c.getId()), kind + " / " + c.getTitle() + ": policy=" + policy + " popular=" + popular.contains(c.getId()));
            assertEquals(policy, sql.contains(c.getId()), kind + " / " + c.getTitle() + ": policy=" + policy + " sql=" + sql.contains(c.getId()));
            assertEquals(policy, paged.contains(c.getId()), kind + " / " + c.getTitle() + ": policy=" + policy + " GET /classes=" + paged.contains(c.getId()));
            if (viewerId != null) {
                assertEquals(policy, accessPolicy.isClassVisibleToUser(c.getId(), viewerId));
            }
        }
    }

    @Test
    @DisplayName("the headline facts, spelled out, so a wrong rule cannot hide behind two equally wrong implementations")
    void headlineFacts() {
        Map<String, Classroom> byName = classes.stream().collect(Collectors.toMap(Classroom::getTitle, c -> c));
        Classroom publicActive = byName.get("Parity PUBLIC FREE ACTIVE");
        Classroom publicPaid = byName.get("Parity PUBLIC PAID ACTIVE");
        Classroom privateActive = byName.get("Parity PRIVATE FREE ACTIVE");
        Classroom privatePaid = byName.get("Parity PRIVATE PAID ACTIVE");
        Classroom publicArchived = byName.get("Parity PUBLIC FREE ARCHIVED");
        assertTrue(accessPolicy.isClassVisibleToUser(publicActive, null));
        assertTrue(accessPolicy.isClassVisibleToUser(publicPaid, null), "a paid class is listed: people must find the paywall");
        assertFalse(accessPolicy.isClassVisibleToUser(privateActive, null));
        assertFalse(accessPolicy.isClassVisibleToUser(privatePaid, viewers.get("stranger").getId()));
        assertFalse(accessPolicy.isClassVisibleToUser(privateActive, viewers.get("removed").getId()));
        assertFalse(accessPolicy.isClassVisibleToUser(privateActive, viewers.get("blocked").getId()));
        assertFalse(accessPolicy.isClassVisibleToUser(privateActive, viewers.get("banned").getId()));
        assertFalse(accessPolicy.isClassVisibleToUser(privateActive, viewers.get("pending").getId()), "D-28: a pending request never reveals a private class");
        assertFalse(accessPolicy.isMember(viewers.get("pending").getId(), publicActive.getId()), "D-28: PENDING is not a member");
        assertTrue(accessPolicy.isClassVisibleToUser(privateActive, viewers.get("memberActive").getId()));
        assertTrue(accessPolicy.isClassVisibleToUser(privateActive, viewers.get("memberExpired").getId()), "a lapsed paid member still sees the class (to renew)");
        assertTrue(accessPolicy.isClassVisibleToUser(privateActive, viewers.get("memberLapsed").getId()));
        assertTrue(accessPolicy.isClassVisibleToUser(privateActive, viewers.get("staffActive").getId()));
        assertFalse(accessPolicy.isClassVisibleToUser(privateActive, viewers.get("staffRevoked").getId()));
        assertTrue(accessPolicy.isClassVisibleToUser(privateActive, owner.getId()));
        assertFalse(accessPolicy.isClassVisibleToUser(publicArchived, viewers.get("stranger").getId()), "archived classes stay hidden from strangers (D-11)");
        assertTrue(accessPolicy.isClassVisibleToUser(publicArchived, viewers.get("memberActive").getId()));
    }

    @Test
    @DisplayName("paging is exact: for a stranger the public ACTIVE classes of the fixture are listed once each over pages that are full until the last")
    void pagesStayFull() {
        Set<String> expected = classes.stream()
                .filter(c -> !c.isPrivate() && "ACTIVE".equals(c.getStatus()))
                .map(Classroom::getId).collect(Collectors.toSet());
        assertEquals(expected, pagedListing(viewers.get("stranger").getId()));
        assertEquals(expected, pagedListing(null));
    }
}
