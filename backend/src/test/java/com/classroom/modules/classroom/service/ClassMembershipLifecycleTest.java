package com.classroom.modules.classroom.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.dto.ClassMemberDto;
import com.classroom.modules.classroom.dto.ClassMemberPageDto;
import com.classroom.modules.classroom.dto.ClassroomDto;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.repository.StaffAssignmentRepository;
import com.classroom.modules.community.dto.FeedPageDto;
import com.classroom.modules.community.service.FeedService;
import com.classroom.modules.exam.service.ExamService;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.learning.service.LearningService;
import com.classroom.modules.ranking.service.LeaderboardService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-19 membership state machine on H2: EXPIRED members and what they may still read, the expiry sweeper, the "active member" headcounts, Studio
 * actions on expired members, staff assignment, and the join matrix across PUBLIC / PRIVATE x FREE / PAID x ACTIVE / ARCHIVED.
 */
@org.springframework.boot.test.context.SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class ClassMembershipLifecycleTest extends ClassAccessTestBase {

    @Autowired private FeedService feedService;
    @Autowired private LearningService learningService;
    @Autowired private ExamService examService;
    @Autowired private LeaderboardService leaderboardService;
    @Autowired private ClassAboutService aboutService;
    @Autowired private StaffAssignmentRepository staffAssignmentRepository;

    private Instant ago(long days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }

    // ------------------------------------------------------------------------------------------------- the EXPIRED experience

    @Test
    @DisplayName("an ACTIVE row whose paid access has lapsed is already EXPIRED everywhere - before the sweeper runs")
    void lapsedIsExpiredBeforeTheSweep() {
        User owner = newUser("owner");
        User lapsed = newUser("lapsed");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        addRow(c, lapsed, "STUDENT", "ACTIVE", Instant.now().minusSeconds(5));

        assertFalse(accessPolicy.isMember(lapsed.getId(), c.getId()));
        assertTrue(accessPolicy.isMembershipExpired(lapsed.getId(), c.getId()));
        assertEquals("ACTIVE", row(c, lapsed).getState(), "the stored state has not been flipped yet");

        ClassroomDto dto = classroomService.getById(c.getId(), lapsed.getId());
        assertEquals("EXPIRED", dto.getMemberState());
        assertFalse(dto.isMember());
        assertEquals("GUEST", dto.getUserRole());
        assertNotNull(dto.getAccessExpiresAt(), "when the access lapsed, for the UI");
        assertEquals("PAID", dto.getAccessType());
        assertNotNull(dto.getAccessProduct(), "the paywall data");
        assertEquals(1, dto.getMemberCount(), "a lapsed member is not counted: only the owner is");
    }

    @Test
    @DisplayName("contract for the UI: an EXPIRED member reads About, Store and the class card - everything else is 403 MEMBERSHIP_EXPIRED")
    void expiredMemberContract() {
        User owner = newUser("owner");
        User expired = newUser("expired");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        addRow(c, expired, "STUDENT", "EXPIRED", ago(1));
        String id = c.getId();
        String uid = expired.getId();

        // readable: the paywall
        assertNotNull(aboutService.getAbout(id, uid));
        assertFalse(commerceService.getProductsByClass(id, uid).isEmpty());
        assertEquals("EXPIRED", classroomService.getById(id, uid).getMemberState());
        assertEquals("EXPIRED", classroomService.getBySlug(c.getSlug(), uid).getMemberState());

        // everything else: MEMBERSHIP_EXPIRED (403), not the generic FORBIDDEN
        assertCode(ErrorCode.MEMBERSHIP_EXPIRED, () -> classroomService.getClassMembers(id, uid));
        assertCode(ErrorCode.MEMBERSHIP_EXPIRED, () -> learningService.getCoursesByClass(id, uid));
        assertCode(ErrorCode.MEMBERSHIP_EXPIRED, () -> examService.getExamsByClass(id, uid));
        assertCode(ErrorCode.MEMBERSHIP_EXPIRED, () -> leaderboardService.getLeaderboard(id, uid));
        assertCode(ErrorCode.MEMBERSHIP_EXPIRED, () -> feedService.getFeedPage(id, uid, null, 10));
        assertCode(ErrorCode.MEMBERSHIP_EXPIRED, () -> accessPolicy.enforceMember(uid, id));
        assertEquals(403, ErrorCode.MEMBERSHIP_EXPIRED.getHttpStatus().value());
    }

    @Test
    @DisplayName("an EXPIRED member cannot buy other products or post: they are not a member until they renew")
    void expiredMemberIsNotAMemberForOtherThings() {
        User owner = newUser("owner");
        User expired = newUser("expired");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        addRow(c, expired, "STUDENT", "EXPIRED", ago(1));
        com.classroom.modules.community.dto.CreatePostRequest post =
                new com.classroom.modules.community.dto.CreatePostRequest("t", "b", "FREE", null, null, false);
        assertCode(ErrorCode.MEMBERSHIP_EXPIRED, () -> feedService.createPost(c.getId(), expired.getId(), post));
    }

    @Test
    @DisplayName("a guest or a stranger asking for a class's feed gets the public part exactly as before (public class) - the EXPIRED rule is about known members only")
    void guestsAreUnaffected() {
        User owner = newUser("owner");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        FeedPageDto page = feedService.getFeedPage(c.getId(), null, null, 10);
        assertNotNull(page);
        assertNotNull(feedService.getFeedPage(c.getId(), newUser("stranger").getId(), null, 10));
    }

    // -------------------------------------------------------------------------------------------------------------- the sweeper

    @Test
    @DisplayName("the sweeper flips exactly the lapsed ACTIVE members, once each, emits MEMBER_EXPIRED, and a second sweep changes nothing")
    void sweeperFlipsExactlyTheLapsed() {
        User owner = newUser("owner");
        User lapsed1 = newUser("lapsed1");
        User lapsed2 = newUser("lapsed2");
        User running = newUser("running");
        User forever = newUser("forever");
        User removed = newUser("removed");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        addRow(c, lapsed1, "STUDENT", "ACTIVE", Instant.now().minusSeconds(60));
        addRow(c, lapsed2, "STUDENT", "ACTIVE", ago(3));
        addRow(c, running, "STUDENT", "ACTIVE", Instant.now().plus(5, ChronoUnit.DAYS));
        addRow(c, forever, "STUDENT", "ACTIVE", null);
        addRow(c, removed, "STUDENT", "REMOVED", ago(2));

        MembershipExpiryService.BatchResult first = expiryService.sweepBatch(1000, Instant.now());

        assertTrue(first.expired() >= 2);
        assertEquals("EXPIRED", row(c, lapsed1).getState());
        assertEquals("EXPIRED", row(c, lapsed2).getState());
        assertEquals("ACTIVE", row(c, running).getState());
        assertEquals("ACTIVE", row(c, forever).getState());
        assertEquals("REMOVED", row(c, removed).getState(), "REMOVED / BLOCKED semantics are unchanged");
        assertEquals("ACTIVE", row(c, owner).getState());
        for (User u : List.of(lapsed1, lapsed2)) {
            assertEquals(1, events(c, "MEMBER_EXPIRED").stream().filter(e -> e.getPayloadJson().contains(u.getId())).count());
        }
        assertTrue(events(c, "MEMBER_EXPIRED").stream().noneMatch(e -> e.getPayloadJson().contains(running.getId())
                || e.getPayloadJson().contains(forever.getId()) || e.getPayloadJson().contains(removed.getId())));

        // idempotent: nothing left to do for this class, no new events
        int eventsBefore = events(c, "MEMBER_EXPIRED").size();
        expiryService.sweepBatch(1000, Instant.now());
        assertEquals(eventsBefore, events(c, "MEMBER_EXPIRED").size());
        assertEquals("EXPIRED", row(c, lapsed1).getState());
    }

    @Test
    @DisplayName("the sweeper works through a backlog in batches and never flips a member whose access was just extended")
    void sweeperBatchesAndRespectsRenewal() {
        User owner = newUser("owner");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        List<User> lapsed = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            User u = newUser("l" + i);
            lapsed.add(u);
            addRow(c, u, "STUDENT", "ACTIVE", Instant.now().minusSeconds(100 + i));
        }
        User renewed = newUser("renewed");
        ClassMember r = addRow(c, renewed, "STUDENT", "ACTIVE", Instant.now().minusSeconds(10));
        r.setAccessExpiresAt(Instant.now().plus(10, ChronoUnit.DAYS)); // extended by a purchase before the sweep got to it
        memberRepository.save(r);

        long totalFlipped = 0;
        for (int guard = 0; guard < 50; guard++) {
            MembershipExpiryService.BatchResult batch = expiryService.sweepBatch(2, Instant.now());
            totalFlipped += batch.expired();
            assertTrue(batch.examined() <= 2, "a batch never exceeds its size");
            if (batch.examined() == 0) break;
        }
        assertTrue(totalFlipped >= 5);
        lapsed.forEach(u -> assertEquals("EXPIRED", row(c, u).getState()));
        assertEquals("ACTIVE", row(c, renewed).getState());
    }

    @Test
    @DisplayName("a staff or owner row that somehow carries an expiry date is repaired (date cleared), never expired")
    void sweeperNeverExpiresStaffOrOwner() {
        User owner = newUser("owner");
        User staff = newUser("staff");
        Classroom c = newClass(owner, "PUBLIC");
        addRow(c, staff, "STAFF", "ACTIVE", Instant.now().minusSeconds(30));

        expiryService.sweepBatch(1000, Instant.now());

        assertEquals("ACTIVE", row(c, staff).getState());
        assertNull(row(c, staff).getAccessExpiresAt());
        assertTrue(events(c, "MEMBER_EXPIRED").isEmpty());
    }

    @Test
    @DisplayName("the real timer is wired: a sweeper started with a 1 s interval expires a lapsed member on its own thread, and stops cleanly")
    void theTimerExpiresMembersInTheBackground() throws Exception {
        User owner = newUser("owner");
        User lapsed = newUser("lapsed");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        addRow(c, lapsed, "STUDENT", "ACTIVE", Instant.now().minusSeconds(10));

        MembershipExpirySweeper sweeper = new MembershipExpirySweeper(expiryService, true, 1, 50);
        sweeper.start();
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            while (!"EXPIRED".equals(row(c, lapsed).getState()) && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertEquals("EXPIRED", row(c, lapsed).getState());
            Set<String> threads = Thread.getAllStackTraces().keySet().stream().map(Thread::getName).collect(Collectors.toSet());
            assertTrue(threads.stream().anyMatch(n -> n.startsWith("membership-expiry-")), "its own thread, not classroom-sched-N");
        } finally {
            sweeper.stop();
        }
    }

    @Test
    @DisplayName("a disabled sweeper starts no thread")
    void disabledSweeperStartsNothing() {
        MembershipExpirySweeper sweeper = new MembershipExpirySweeper(expiryService, false, 1, 50);
        sweeper.start();
        sweeper.stop();
    }

    // ---------------------------------------------------------------------------------------- "active member" everywhere

    @Test
    @DisplayName("headcounts, boards and batch lookups agree on who is an active member: a lapsed member is out of all of them")
    void activeMemberDefinitionIsShared() {
        User owner = newUser("owner");
        User active = newUser("active");
        User lapsed = newUser("lapsed");
        User expired = newUser("expired");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        addRow(c, active, "STUDENT", "ACTIVE", Instant.now().plus(3, ChronoUnit.DAYS));
        addRow(c, lapsed, "STUDENT", "ACTIVE", Instant.now().minusSeconds(3));
        addRow(c, expired, "STUDENT", "EXPIRED", ago(1));

        assertEquals(2, memberRepository.countByClassIdAndState(c.getId(), "ACTIVE"), "owner + active");
        assertEquals(1, memberRepository.countByClassIdAndState(c.getId(), "EXPIRED"), "the stored-EXPIRED one (exact state)");
        assertEquals(Set.of(owner.getId(), active.getId()), Set.copyOf(memberRepository.findActiveUserIdsByClassId(c.getId())));
        long batch = memberRepository.countActiveByClassIds(List.of(c.getId())).stream()
                .filter(r -> c.getId().equals(r[0])).mapToLong(r -> ((Number) r[1]).longValue()).sum();
        assertEquals(2, batch);
        assertEquals(2, classroomService.getById(c.getId(), null).getMemberCount());
        assertEquals(2, classroomService.getAllClassrooms(null, 0, 100).stream().filter(d -> c.getId().equals(d.getId()))
                .findFirst().orElseThrow().getMemberCount());

        // the peer-visible member list shows only members who can still act
        List<ClassMemberDto> peers = classroomService.getClassMembers(c.getId(), active.getId());
        assertEquals(2, peers.size(), "owner + the viewer; the lapsed and the expired member are not peers any more");
        assertTrue(peers.stream().allMatch(p -> "ACTIVE".equals(p.getState())));
        Set<String> visibleIds = peers.stream().map(ClassMemberDto::getUserId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        assertFalse(visibleIds.contains(lapsed.getId()));
        assertFalse(visibleIds.contains(expired.getId()));
        assertTrue(peers.stream().allMatch(p -> p.getAccessExpiresAt() == null), "a peer never learns when somebody's access ends");
    }

    @Test
    @DisplayName("Studio roster: states are effective (a lapsed ACTIVE shows and filters as EXPIRED) and carry accessExpiresAt")
    void studioRosterShowsEffectiveState() {
        User owner = newUser("owner");
        User running = newUser("running");
        User lapsed = newUser("lapsed");
        User expired = newUser("expired");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        addRow(c, running, "STUDENT", "ACTIVE", Instant.now().plus(9, ChronoUnit.DAYS));
        addRow(c, lapsed, "STUDENT", "ACTIVE", Instant.now().minusSeconds(40));
        addRow(c, expired, "STUDENT", "EXPIRED", ago(2));

        ClassMemberPageDto all = memberService.getStudioMembers(c.getId(), owner.getId());
        assertEquals("EXPIRED", all.getMembers().stream().filter(m -> lapsed.getId().equals(m.getUserId())).findFirst().orElseThrow().getState());
        assertNotNull(all.getMembers().stream().filter(m -> running.getId().equals(m.getUserId())).findFirst().orElseThrow().getAccessExpiresAt());

        Set<String> expiredIds = memberService.getStudioMembers(c.getId(), owner.getId(), 0, 50, null, "EXPIRED", null)
                .getMembers().stream().map(ClassMemberDto::getUserId).collect(Collectors.toSet());
        assertEquals(Set.of(lapsed.getId(), expired.getId()), expiredIds);
        Set<String> activeIds = memberService.getStudioMembers(c.getId(), owner.getId(), 0, 50, null, "ACTIVE", null)
                .getMembers().stream().map(ClassMemberDto::getUserId).collect(Collectors.toSet());
        assertEquals(Set.of(owner.getId(), running.getId()), activeIds);
    }

    // ---------------------------------------------------------------------------------------------- Studio actions

    @Test
    @DisplayName("Studio can remove or block an EXPIRED member (a blocked person must be refused the purchase); unblocking a lapsed paid member brings them back EXPIRED, not ACTIVE")
    void studioActionsOnExpiredMembers() {
        User owner = newUser("owner");
        User toBlock = newUser("toBlock");
        User toRemove = newUser("toRemove");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        addRow(c, toBlock, "STUDENT", "EXPIRED", ago(1));
        addRow(c, toRemove, "STUDENT", "EXPIRED", ago(1));

        assertEquals("BLOCKED", memberService.blockMember(c.getId(), toBlock.getId(), owner.getId()).getState());
        assertEquals("REMOVED", memberService.removeMember(c.getId(), toRemove.getId(), owner.getId()).getState());
        assertCode(ErrorCode.FORBIDDEN, () -> orderFor(toBlock, c, null));

        assertEquals("EXPIRED", memberService.unblockMember(c.getId(), toBlock.getId(), owner.getId()).getState(),
                "their paid access lapsed while blocked - it is not revived by the unblock");
        assertFalse(accessPolicy.isMember(toBlock.getId(), c.getId()));
        assertNotNull(orderFor(toBlock, c, null), "and once unblocked they can renew");
    }

    @Test
    @DisplayName("unblocking a member whose paid access is still running brings them back ACTIVE with the same end date")
    void unblockKeepsRunningAccess() {
        User owner = newUser("owner");
        User buyer = newUser("buyer");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        buy(buyer, c, null);
        Instant end = row(c, buyer).getAccessExpiresAt();
        memberService.blockMember(c.getId(), buyer.getId(), owner.getId());

        assertEquals("ACTIVE", memberService.unblockMember(c.getId(), buyer.getId(), owner.getId()).getState());
        assertEquals(end, row(c, buyer).getAccessExpiresAt());
        assertCode(ErrorCode.BAD_REQUEST, () -> memberService.unblockMember(c.getId(), buyer.getId(), owner.getId()));
    }

    @Test
    @DisplayName("assigning staff ends the paid term (no expiry, never swept); an EXPIRED member cannot be made staff")
    void staffNeverExpire() {
        User owner = newUser("owner");
        User student = newUser("student");
        User expired = newUser("expired");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        buy(student, c, null);
        assertNotNull(row(c, student).getAccessExpiresAt());
        addRow(c, expired, "STUDENT", "EXPIRED", ago(1));

        staffService.assignStaff(c.getId(), student.getId(), List.of(), owner.getId());
        assertEquals("STAFF", row(c, student).getRole());
        assertNull(row(c, student).getAccessExpiresAt());
        assertCode(ErrorCode.BAD_REQUEST, () -> staffService.assignStaff(c.getId(), expired.getId(), List.of(), owner.getId()));

        expiryService.sweepBatch(100, Instant.now());
        assertTrue(accessPolicy.isMember(student.getId(), c.getId()));
        assertTrue(staffAssignmentRepository.findByClassIdAndUserId(c.getId(), student.getId()).isPresent());
    }

    // ------------------------------------------------------------------------------------------------ the join matrix

    @Test
    @DisplayName("POST /classes/{id}/join: FREE public joins; PAID answers 402 with the product; PRIVATE is 404 for an outsider and INVITE_REQUIRED for someone who can see it")
    void joinMatrix() {
        User owner = newUser("owner");
        User student = newUser("student");
        User lapsed = newUser("lapsed");
        User blocked = newUser("blocked");

        Classroom free = newClass(owner, "PUBLIC");
        assertEquals("ACTIVE", classroomService.joinClassroom(free.getId(), student.getId()).getMemberState());
        assertEquals("ACTIVE", classroomService.joinClassroom(free.getId(), student.getId()).getMemberState(), "idempotent");

        Classroom paid = newPaidClass(owner, "PUBLIC", "199000", 30);
        AppException payment = assertThrows(AppException.class, () -> classroomService.joinClassroom(paid.getId(), newUser("x").getId()));
        assertEquals(ErrorCode.PAYMENT_REQUIRED, payment.getErrorCode());
        assertNotNull(payment.getDetails().get("accessProduct"));

        Classroom priv = newClass(owner, "PRIVATE");
        assertCode(ErrorCode.NOT_FOUND, () -> classroomService.joinClassroom(priv.getId(), student.getId()));
        assertCode(ErrorCode.NOT_FOUND, () -> classroomService.joinClassroom("no-such-class", student.getId()));
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(AppException.class, () -> classroomService.joinClassroom(priv.getId(), student.getId())).getErrorCode());
        assertNull(row(priv, student));

        // someone who can SEE a private class but is not in it (a lapsed paid member of a now-FREE private class) needs an invite
        addRow(priv, lapsed, "STUDENT", "EXPIRED", ago(1));
        AppException invite = assertThrows(AppException.class, () -> classroomService.joinClassroom(priv.getId(), lapsed.getId()));
        assertEquals(ErrorCode.INVITE_REQUIRED, invite.getErrorCode());
        assertEquals("Lớp riêng tư, cần mã mời", invite.getMessage());
        assertEquals(403, invite.getErrorCode().getHttpStatus().value());

        // members / the owner simply get the class back
        assertEquals("OWNER", classroomService.joinClassroom(priv.getId(), owner.getId()).getUserRole());

        // a blocked person is refused on a public class
        addRow(free, blocked, "STUDENT", "BLOCKED", null);
        assertCode(ErrorCode.FORBIDDEN, () -> classroomService.joinClassroom(free.getId(), blocked.getId()));
    }

    @Test
    @DisplayName("an ARCHIVED class takes no joins (D-11), for public and private alike")
    void archivedClassTakesNoJoins() {
        User owner = newUser("owner");
        User student = newUser("student");
        Classroom c = newClass(owner, "PUBLIC");
        ClassInviteFlowTest.UpdateStatus.archive(classroomService, c, owner);
        assertCode(ErrorCode.FORBIDDEN, () -> classroomService.joinClassroom(c.getId(), student.getId()));
    }

    // ------------------------------------------------------------------------------------------------------ conversions

    @Test
    @DisplayName("PUBLIC -> PRIVATE keeps every member and hides the class from outsiders; PRIVATE -> PUBLIC lists it and makes it joinable by anyone")
    void visibilityConversions() {
        User owner = newUser("owner");
        User member = newUser("member");
        User outsider = newUser("outsider");
        Classroom c = newClass(owner, "PUBLIC");
        classroomService.joinClassroom(c.getId(), member.getId());
        assertTrue(listedFor(c, null));
        assertTrue(listedFor(c, outsider));

        ClassroomDto dto = setVisibility(c, owner, "PRIVATE");
        assertEquals("PRIVATE", dto.getVisibility());
        assertTrue(accessPolicy.isMember(member.getId(), c.getId()), "existing members are kept");
        assertFalse(listedFor(c, null));
        assertFalse(listedFor(c, outsider));
        assertTrue(listedFor(c, member));
        assertTrue(listedFor(c, owner));
        assertCode(ErrorCode.NOT_FOUND, () -> classroomService.getById(c.getId(), outsider.getId()));
        assertCode(ErrorCode.NOT_FOUND, () -> classroomService.getBySlug(c.getSlug(), null));
        assertEquals(c.getId(), classroomService.getById(c.getId(), member.getId()).getId());
        assertEquals("PRIVATE", classroomService.getBySlug(c.getSlug(), member.getId()).getVisibility());

        setVisibility(c, owner, "PUBLIC");
        assertTrue(listedFor(c, null));
        assertTrue(listedFor(c, outsider));
        assertEquals(c.getId(), classroomService.getById(c.getId(), null).getId());
        assertEquals("ACTIVE", classroomService.joinClassroom(c.getId(), outsider.getId()).getMemberState());
        assertEquals(2, audits(c, "CLASS_SETTINGS_UPDATE").size());
        assertTrue(audits(c, "CLASS_SETTINGS_UPDATE").get(1).getDetailsJson().contains("\"visibilityBefore\":\"PUBLIC\",\"visibilityAfter\":\"PRIVATE\""));
    }

    @Test
    @DisplayName("changing the visibility needs CLASS:EDIT (staff without it are refused) and a value other than PUBLIC/PRIVATE is rejected by validation")
    void visibilityEditGating() {
        User owner = newUser("owner");
        User staff = newUser("staff");
        User editor = newUser("editor");
        Classroom c = newClass(owner, "PUBLIC");
        grantStaff(c, owner, staff, "COURSE:VIEW");
        grantStaff(c, owner, editor, "CLASS:EDIT");

        assertCode(ErrorCode.STAFF_PERMISSION_DENIED, () -> setVisibility(c, staff, "PRIVATE"));
        assertEquals("PUBLIC", reload(c).getVisibility());
        assertEquals("PRIVATE", setVisibility(c, editor, "PRIVATE").getVisibility());
    }

    @Test
    @DisplayName("the class list never contains a PRIVATE class for a guest or an outsider, for its members, EXPIRED members and staff it does; pages stay full")
    void listingHonoursPrivate() {
        User owner = newUser("owner");
        User member = newUser("member");
        User expired = newUser("expired");
        User staff = newUser("staff");
        User outsider = newUser("outsider");
        User removed = newUser("removed");
        Classroom priv = newClass(owner, "PRIVATE");
        addActive(priv, member);
        addRow(priv, expired, "STUDENT", "EXPIRED", ago(1));
        addRow(priv, removed, "STUDENT", "REMOVED", null);
        grantStaff(priv, owner, staff, "COURSE:VIEW");

        assertFalse(listedFor(priv, null));
        assertFalse(listedFor(priv, outsider));
        assertFalse(listedFor(priv, removed));
        assertTrue(listedFor(priv, owner));
        assertTrue(listedFor(priv, member));
        assertTrue(listedFor(priv, expired));
        assertTrue(listedFor(priv, staff));
    }

    private boolean listedFor(Classroom c, User viewer) {
        String viewerId = viewer == null ? null : viewer.getId();
        for (int page = 0; page < 200; page++) {
            List<ClassroomDto> dtos = classroomService.getAllClassrooms(viewerId, page, 100);
            if (dtos.stream().anyMatch(d -> c.getId().equals(d.getId()))) return true;
            if (dtos.size() < 100) return false;
        }
        return false;
    }
}
