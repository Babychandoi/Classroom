package com.classroom.integration;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.dto.ClassInviteDto;
import com.classroom.modules.classroom.dto.ClassMemberPageDto;
import com.classroom.modules.classroom.dto.ClassroomDto;
import com.classroom.modules.classroom.dto.CreateInviteRequest;
import com.classroom.modules.classroom.model.ClassInvite;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.service.ClassAccessService;
import com.classroom.modules.classroom.service.ClassAccessTestBase;
import com.classroom.modules.classroom.service.MembershipExpiryService;
import com.classroom.modules.commerce.dto.OrderDto;
import com.classroom.modules.commerce.model.Entitlement;
import com.classroom.modules.identity.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-19 against a real MySQL 8.4 (Flyway V1..V37 applied, Hibernate validating the mapping): the concurrency the feature promises - an invite's
 * {@code maxUses} under contention, purchases settling while refunds land, the expiry sweeper racing a renewal - and the SQL that H2 cannot prove
 * (the visibility twins, the roster's effective-state filter, the PRO exclusion, DATETIME(6), foreign keys, defaults of pre-existing rows).
 *
 * <p>Every test builds its own classes, so nothing depends on, or disturbs, the demo seed.</p>
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("integration")
class ClassAccessConcurrencyIntegrationTest extends ClassAccessTestBase {

    @org.springframework.beans.factory.annotation.Autowired private JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired private MembershipExpiryService sweeper;
    @org.springframework.beans.factory.annotation.Autowired private javax.sql.DataSource dataSource;

    private CreateInviteRequest invite(Integer maxUses) {
        CreateInviteRequest r = new CreateInviteRequest();
        r.setMaxUses(maxUses);
        return r;
    }

    /** Runs the jobs at the same instant on separate connections and returns each outcome (value or thrown exception). */
    private List<Object> runTogether(List<? extends Callable<?>> jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        try {
            CountDownLatch ready = new CountDownLatch(jobs.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> job : jobs) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return job.call();
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            assertTrue(ready.await(20, TimeUnit.SECONDS));
            go.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) results.add(f.get(60, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------------------------------------------ the migration

    @Test
    @DisplayName("V37 on MySQL: new columns have the documented types and defaults, a row inserted the OLD way is PUBLIC / FREE / STANDARD, the access-product FK restricts")
    void migrationShape() {
        assertEquals("PUBLIC", jdbc.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'classrooms' AND column_name = 'visibility'", String.class));
        assertEquals("FREE", jdbc.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'classrooms' AND column_name = 'access_type'", String.class));
        assertEquals("STANDARD", jdbc.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'products' AND column_name = 'kind'", String.class));
        assertEquals("datetime", jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'class_members' AND column_name = 'access_expires_at'", String.class).toLowerCase());
        assertEquals(6, jdbc.queryForObject("SELECT datetime_precision FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'class_members' AND column_name = 'access_expires_at'", Integer.class));
        assertEquals(Integer.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'class_invites' AND index_name = 'uk_class_invites_code_hash' AND non_unique = 0", Integer.class));
        assertEquals(Integer.valueOf(2), jdbc.queryForObject("SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'class_members' AND index_name IN ('ix_class_members_access_expiry', 'ix_class_members_expiry_sweep') AND column_name = 'access_expires_at'", Integer.class));
        assertEquals("RESTRICT", jdbc.queryForObject("SELECT delete_rule FROM information_schema.referential_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'fk_class_access_product'", String.class));
        assertEquals("CASCADE", jdbc.queryForObject("SELECT delete_rule FROM information_schema.referential_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'fk_invite_class'", String.class));

        // a class created by an "old" writer that knows nothing about the new columns
        User owner = newUser("legacy");
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO classrooms (id, owner_id, slug, title, status, created_at, updated_at) VALUES (?, ?, ?, 'Legacy', 'ACTIVE', NOW(6), NOW(6))",
                id, owner.getId(), "legacy-" + id.substring(0, 8));
        assertEquals("PUBLIC", jdbc.queryForObject("SELECT visibility FROM classrooms WHERE id = ?", String.class, id));
        assertEquals("FREE", jdbc.queryForObject("SELECT access_type FROM classrooms WHERE id = ?", String.class, id));
        assertNull(jdbc.queryForObject("SELECT access_product_id FROM classrooms WHERE id = ?", String.class, id));
        Classroom loaded = classroomRepository.findById(id).orElseThrow();
        assertFalse(loaded.isPrivate());
        assertFalse(loaded.isPaid());
        assertTrue(accessPolicy.isClassVisibleToUser(loaded, null), "existing classes behave exactly as before");

        // the product of a paid class cannot be deleted from under it
        Classroom paid = newPaidClass(owner, "PUBLIC", "199000", 30);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM products WHERE id = ?", reload(paid).getAccessProductId()));
    }

    @Test
    @DisplayName("V37 is replay-safe: running the script again (as after a half-applied run) changes nothing - no error, no lost data, same schema")
    void migrationCanBeReplayed() throws Exception {
        User owner = newUser("replay");
        Classroom c = newPaidClass(owner, "PRIVATE", "199000", 30);
        User buyer = newUser("replay-buyer");
        ClassInviteDto invite = inviteService.create(c.getId(), invite(5), owner.getId());
        buy(buyer, c, invite.getCode());
        int tablesBefore = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name IN ('classrooms', 'class_members', 'products', 'orders', 'class_invites')", Integer.class);
        String hashBefore = inviteRepository.findById(invite.getId()).orElseThrow().getCodeHash();

        try (java.sql.Connection conn = dataSource.getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(conn,
                    new org.springframework.core.io.ClassPathResource("db/migration/V37__class_visibility_access_invites.sql"));
        }

        assertEquals(tablesBefore, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name IN ('classrooms', 'class_members', 'products', 'orders', 'class_invites')", Integer.class));
        assertEquals(hashBefore, inviteRepository.findById(invite.getId()).orElseThrow().getCodeHash());
        assertEquals("PRIVATE", reload(c).getVisibility());
        assertEquals("PAID", reload(c).getAccessType());
        assertNotNull(row(c, buyer).getAccessExpiresAt());
        assertTrue(accessPolicy.isMember(buyer.getId(), c.getId()));
    }

    // ---------------------------------------------------------------------------------------------------- invite maxUses race

    @Test
    @DisplayName("20 threads join a private class with an invite of maxUses 5: exactly 5 become members, used_count is exactly 5, the other 15 get the generic 404")
    void maxUsesUnderContention() throws Exception {
        User owner = newUser("owner");
        Classroom c = newClass(owner, "PRIVATE");
        ClassInviteDto made = inviteService.create(c.getId(), invite(5), owner.getId());
        List<User> joiners = new ArrayList<>();
        for (int i = 0; i < 20; i++) joiners.add(newUser("j" + i));

        List<Callable<Classroom>> jobs = joiners.stream()
                .<Callable<Classroom>>map(u -> () -> inviteService.join(made.getCode(), u.getId())).toList();
        List<Object> results = runTogether(jobs);

        long successes = results.stream().filter(r -> r instanceof Classroom).count();
        long rejected = results.stream().filter(r -> r instanceof AppException e && e.getErrorCode() == ErrorCode.NOT_FOUND).count();
        assertEquals(5, successes, "results: " + results);
        assertEquals(15, rejected);
        assertEquals(5, inviteRepository.findById(made.getId()).orElseThrow().getUsedCount());
        long members = joiners.stream().filter(u -> row(c, u) != null && "ACTIVE".equals(row(c, u).getState())).count();
        assertEquals(5, members, "a rolled-back join leaves no member behind");
        assertEquals("EXHAUSTED", inviteService.list(c.getId(), owner.getId()).get(0).getStatus());
    }

    @Test
    @DisplayName("the SAME person joining with an invite 20 times at once consumes exactly one use and is a member once")
    void samePersonJoiningConcurrently() throws Exception {
        User owner = newUser("owner");
        User student = newUser("student");
        Classroom c = newClass(owner, "PRIVATE");
        ClassInviteDto made = inviteService.create(c.getId(), invite(5), owner.getId());

        List<Callable<Classroom>> jobs = new ArrayList<>();
        for (int i = 0; i < 20; i++) jobs.add(() -> inviteService.join(made.getCode(), student.getId()));
        List<Object> results = runTogether(jobs);

        assertTrue(results.stream().allMatch(r -> r instanceof Classroom), "every call succeeded: " + results);
        assertEquals(1, inviteRepository.findById(made.getId()).orElseThrow().getUsedCount());
        assertEquals(1, memberRepository.findByClassId(c.getId()).stream().filter(m -> m.getUserId().equals(student.getId())).count());
    }

    @Test
    @DisplayName("revoking an invite while people are joining: every join after the revoke is 404, none is lost or double counted")
    void revokeRacesJoins() throws Exception {
        User owner = newUser("owner");
        Classroom c = newClass(owner, "PRIVATE");
        ClassInviteDto made = inviteService.create(c.getId(), invite(null), owner.getId());
        List<User> joiners = new ArrayList<>();
        for (int i = 0; i < 10; i++) joiners.add(newUser("j" + i));

        List<Callable<Object>> jobs = new ArrayList<>();
        for (User u : joiners) jobs.add(() -> inviteService.join(made.getCode(), u.getId()));
        jobs.add(() -> inviteService.revoke(c.getId(), made.getId(), owner.getId()));
        runTogether(jobs);

        ClassInvite after = inviteRepository.findById(made.getId()).orElseThrow();
        assertNotNull(after.getRevokedAt());
        long members = joiners.stream().filter(u -> row(c, u) != null).count();
        assertEquals(members, after.getUsedCount(), "used_count equals the members actually created");
        assertCode(ErrorCode.NOT_FOUND, () -> inviteService.join(made.getCode(), newUser("late").getId()));
    }

    // ------------------------------------------------------------------------------------ purchases vs refunds vs renewals

    /** The invariant tying the member row to the entitlement chain (the one the whole feature exists to keep). */
    private void assertMembershipMatchesChain(Classroom c, User u) {
        Instant now = Instant.now();
        List<Entitlement> live = chain(u, c).stream()
                .filter(e -> "ACTIVE".equals(e.getState()) && e.getExpiresAt().isAfter(now)).toList();
        ClassMember m = row(c, u);
        assertNotNull(m);
        if (live.isEmpty()) {
            assertFalse(m.isActiveAt(now), "no paid time left, yet the member is active: " + m.getState() + " " + m.getAccessExpiresAt());
        } else {
            Instant end = live.stream().map(Entitlement::getExpiresAt).max(Comparator.naturalOrder()).orElseThrow();
            assertEquals("ACTIVE", m.getState());
            assertEquals(end, m.getAccessExpiresAt(), "access_expires_at is the end of the live chain");
            assertTrue(accessPolicy.isMember(u.getId(), c.getId()));
        }
    }

    @Test
    @DisplayName("two pending purchases of one buyer settling at the same instant stack (contiguous chain) and access_expires_at is the end of the chain")
    void concurrentSettlesStack() throws Exception {
        User owner = newUser("owner");
        User buyer = newUser("buyer");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        OrderDto o1 = orderFor(buyer, c, null);
        OrderDto o2 = orderFor(buyer, c, null);

        runTogether(List.of(
                () -> webhook(o1, "txn-" + UUID.randomUUID(), "PAYMENT_SUCCESS"),
                () -> webhook(o2, "txn-" + UUID.randomUUID(), "PAYMENT_SUCCESS")));

        List<Entitlement> chain = chain(buyer, c);
        assertEquals(2, chain.size());
        assertEquals(chain.get(0).getExpiresAt(), chain.get(1).getStartsAt(), "no overlap, no gap");
        assertMembershipMatchesChain(c, buyer);
        assertEquals(1, events(c, "MEMBER_JOINED").stream().filter(e -> e.getPayloadJson().contains(buyer.getId())).count(),
                "one person joined once, however their two payments interleaved");
    }

    @Test
    @DisplayName("settle racing a refund, 12 rounds: whatever the interleaving, the member row always matches the surviving entitlement chain")
    void settleAndRefundRace() throws Exception {
        User owner = newUser("owner");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        for (int round = 0; round < 12; round++) {
            User buyer = newUser("r" + round);
            PaidOrder first = buy(buyer, c, null);
            PaidOrder second = buy(buyer, c, null);
            OrderDto third = orderFor(buyer, c, null);

            List<Object> results = runTogether(List.of(
                    () -> { refund(first); return "refund-1"; },
                    () -> webhook(third, "txn-" + UUID.randomUUID(), "PAYMENT_SUCCESS"),
                    () -> { refund(second); return "refund-2"; }));

            for (Object outcome : results) {
                assertFalse(outcome instanceof Throwable, "round " + round + ": " + outcome);
            }
            assertMembershipMatchesChain(c, buyer);
            List<Entitlement> chain = chain(buyer, c);
            assertEquals(3, chain.size());
            List<Entitlement> live = chain.stream().filter(e -> "ACTIVE".equals(e.getState())).toList();
            assertEquals(1, live.size(), "only the third purchase survives, round " + round);
            assertEquals("PAID", orderRepository.findById(third.getId()).orElseThrow().getStatus());
        }
    }

    @Test
    @DisplayName("refund of the only purchase racing a renewal: the buyer ends with exactly the renewal's paid time (never a stale expiry, never locked out with time left)")
    void refundVersusRenewal() throws Exception {
        User owner = newUser("owner");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        for (int round = 0; round < 8; round++) {
            User buyer = newUser("v" + round);
            PaidOrder only = buy(buyer, c, null);
            OrderDto renewal = orderFor(buyer, c, null);

            runTogether(List.of(
                    () -> { refund(only); return "refund"; },
                    () -> webhook(renewal, "txn-" + UUID.randomUUID(), "PAYMENT_SUCCESS")));

            assertMembershipMatchesChain(c, buyer);
            assertTrue(accessPolicy.isMember(buyer.getId(), c.getId()), "round " + round + ": the renewal is paid, so the buyer must be in");
        }
    }

    // ------------------------------------------------------------------------------------------------------------- the sweeper

    @Test
    @DisplayName("the sweeper flips exactly the lapsed members on MySQL, once; a second run is a no-op; staff and lifetime members are never touched")
    void sweeperOnMySql() {
        User owner = newUser("owner");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        List<User> lapsed = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            User u = newUser("l" + i);
            lapsed.add(u);
            addRow(c, u, "STUDENT", "ACTIVE", Instant.now().minusSeconds(30 + i));
        }
        User running = newUser("running");
        addRow(c, running, "STUDENT", "ACTIVE", Instant.now().plus(2, ChronoUnit.DAYS));
        User forever = newUser("forever");
        addRow(c, forever, "STUDENT", "ACTIVE", null);

        for (int i = 0; i < 500; i++) {
            MembershipExpiryService.BatchResult r = sweeper.sweepBatch(3, Instant.now());
            if (r.examined() == 0) break;
        }
        lapsed.forEach(u -> assertEquals("EXPIRED", row(c, u).getState()));
        assertEquals("ACTIVE", row(c, running).getState());
        assertEquals("ACTIVE", row(c, forever).getState());
        long events = events(c, "MEMBER_EXPIRED").size();
        assertEquals(7, events, "exactly the seven lapsed members of this class, once each");
        sweeper.sweepBatch(100, Instant.now());
        assertEquals(events, events(c, "MEMBER_EXPIRED").size(), "idempotent: nothing more is emitted");
    }

    @Test
    @DisplayName("the sweeper racing a renewal: the renewed member is never left EXPIRED with paid time, and an EXPIRED event is followed by the JOINED that undoes it")
    void sweeperVersusRenewal() throws Exception {
        User owner = newUser("owner");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        for (int round = 0; round < 8; round++) {
            User buyer = newUser("s" + round);
            PaidOrder paid = buy(buyer, c, null);
            // the term has just run out ...
            entitlementRepository.findByUserIdAndClassId(buyer.getId(), c.getId()).forEach(e -> {
                e.setStartsAt(Instant.now().minus(31, ChronoUnit.DAYS));
                e.setExpiresAt(Instant.now().minusSeconds(5));
                entitlementRepository.save(e);
            });
            ClassMember m = row(c, buyer);
            m.setAccessExpiresAt(Instant.now().minusSeconds(5));
            memberRepository.save(m);
            OrderDto renewal = orderFor(buyer, c, null);

            // ... and the sweep and the renewal's payment land together
            runTogether(List.of(
                    () -> sweeper.sweepBatch(500, Instant.now()),
                    () -> webhook(renewal, "txn-" + UUID.randomUUID(), "PAYMENT_SUCCESS")));

            assertEquals("ACTIVE", row(c, buyer).getState(), "round " + round);
            assertTrue(accessPolicy.isMember(buyer.getId(), c.getId()), "round " + round);
            assertMembershipMatchesChain(c, buyer);
            long expired = events(c, "MEMBER_EXPIRED").stream().filter(e -> e.getPayloadJson().contains(buyer.getId())).count();
            long joined = events(c, "MEMBER_JOINED").stream().filter(e -> e.getPayloadJson().contains(buyer.getId())).count();
            assertTrue(joined >= 1 + expired, "every expiry must be followed by a join: expired=" + expired + " joined=" + joined);
        }
    }

    // ------------------------------------------------------------------------------------- SQL that H2 cannot vouch for

    @Test
    @DisplayName("visibility on MySQL: findVisibleToUser / findPubliclyVisible agree with AccessPolicy for every shape and viewer; pages stay full")
    void visibilityParityAndPagingOnMySql() {
        User owner = newUser("owner");
        User member = newUser("member");
        User expired = newUser("expired");
        User removed = newUser("removed");
        User stranger = newUser("stranger");
        List<Classroom> mine = new ArrayList<>();
        for (String vis : List.of("PUBLIC", "PRIVATE")) {
            for (int i = 0; i < 6; i++) {
                Classroom c = newClass(owner, vis);
                addActive(c, member);
                addRow(c, expired, "STUDENT", "EXPIRED", Instant.now().minus(1, ChronoUnit.DAYS));
                addRow(c, removed, "STUDENT", "REMOVED", null);
                if (i % 3 == 0) {
                    com.classroom.modules.classroom.dto.UpdateClassroomStatusRequest st = new com.classroom.modules.classroom.dto.UpdateClassroomStatusRequest();
                    st.setStatus("ARCHIVED");
                    classroomService.updateClassroomStatus(c.getId(), st, owner.getId());
                }
                mine.add(reload(c));
            }
        }
        Set<String> mineIds = mine.stream().map(Classroom::getId).collect(Collectors.toSet());
        for (User viewer : new User[]{owner, member, expired, removed, stranger}) {
            Set<String> sql = classroomRepository.findVisibleToUser(viewer.getId(), PageRequest.of(0, 5000)).stream()
                    .map(Classroom::getId).filter(mineIds::contains).collect(Collectors.toSet());
            for (Classroom c : mine) {
                assertEquals(accessPolicy.isClassVisibleToUser(c, viewer.getId()), sql.contains(c.getId()),
                        viewer.getEmail() + " / " + c.getVisibility() + " " + c.getStatus());
            }
        }
        Set<String> guest = classroomRepository.findPubliclyVisible(PageRequest.of(0, 5000)).stream()
                .map(Classroom::getId).filter(mineIds::contains).collect(Collectors.toSet());
        for (Classroom c : mine) {
            assertEquals(accessPolicy.isClassVisibleToUser(c, null), guest.contains(c.getId()));
        }

        // paging: every page but the last is exactly full, no class is repeated, and the private ones never appear for a stranger
        Set<String> seen = new java.util.HashSet<>();
        int page = 0;
        List<ClassroomDto> dtos;
        do {
            dtos = classroomService.getAllClassrooms(stranger.getId(), page++, 7);
            for (ClassroomDto d : dtos) assertTrue(seen.add(d.getId()), "class repeated across pages");
            if (dtos.size() == 7) assertTrue(page < 500);
        } while (dtos.size() == 7);
        mine.stream().filter(Classroom::isPrivate).forEach(c -> assertFalse(seen.contains(c.getId())));
        mine.stream().filter(c -> !c.isPrivate() && "ACTIVE".equals(c.getStatus())).forEach(c -> assertTrue(seen.contains(c.getId())));
    }

    @Test
    @DisplayName("the roster's effective-state filter and the 'active member' counts work on MySQL (CASE in JPQL, DATETIME(6) comparison)")
    void rosterAndCountsOnMySql() {
        User owner = newUser("owner");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        User running = newUser("running");
        User lapsed = newUser("lapsed");
        User expired = newUser("expired");
        addRow(c, running, "STUDENT", "ACTIVE", Instant.now().plus(3, ChronoUnit.DAYS));
        addRow(c, lapsed, "STUDENT", "ACTIVE", Instant.now().minusSeconds(20));
        addRow(c, expired, "STUDENT", "EXPIRED", Instant.now().minus(2, ChronoUnit.DAYS));

        ClassMemberPageDto expiredPage = memberService.getStudioMembers(c.getId(), owner.getId(), 0, 50, null, "EXPIRED", null);
        assertEquals(Set.of(lapsed.getId(), expired.getId()), expiredPage.getMembers().stream().map(m -> m.getUserId()).collect(Collectors.toSet()));
        assertEquals(2, expiredPage.getTotal());
        ClassMemberPageDto activePage = memberService.getStudioMembers(c.getId(), owner.getId(), 0, 50, null, "ACTIVE", null);
        assertEquals(Set.of(owner.getId(), running.getId()), activePage.getMembers().stream().map(m -> m.getUserId()).collect(Collectors.toSet()));
        assertEquals(2, memberRepository.countByClassIdAndState(c.getId(), "ACTIVE"));
        assertEquals(Set.of(owner.getId(), running.getId()), Set.copyOf(memberRepository.findActiveUserIdsByClassId(c.getId())));
        assertEquals(2L, memberRepository.countActiveByClassIds(List.of(c.getId())).stream().mapToLong(r -> ((Number) r[1]).longValue()).sum());
    }

    @Test
    @DisplayName("PRO on MySQL: a class-access entitlement is never PRO, while a normal product's entitlement still is (the NOT EXISTS subquery)")
    void proExclusionOnMySql() {
        User owner = newUser("owner");
        User buyer = newUser("buyer");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        buy(buyer, c, null);
        Instant now = Instant.now();
        assertFalse(entitlementRepository.hasActiveProEntitlement(buyer.getId(), c.getId(), now));
        assertTrue(entitlementRepository.findClassIdsWithActiveEntitlement(buyer.getId(), List.of(c.getId()), now).isEmpty());
        assertTrue(entitlementRepository.findUserIdsWithActiveEntitlement(c.getId(), List.of(buyer.getId()), now).isEmpty());

        // a STANDARD product's entitlement in the same class does count
        com.classroom.modules.commerce.model.Product pro = new com.classroom.modules.commerce.model.Product(c.getId(), null, "PRO", "d");
        pro.setStatus("PUBLISHED");
        pro = productRepository.save(pro);
        entitlementRepository.save(new Entitlement(buyer.getId(), c.getId(), pro.getId(), null, now.minusSeconds(60), now.plus(5, ChronoUnit.DAYS)));
        assertTrue(entitlementRepository.hasActiveProEntitlement(buyer.getId(), c.getId(), now));
        assertEquals(List.of(c.getId()), entitlementRepository.findClassIdsWithActiveEntitlement(buyer.getId(), List.of(c.getId()), now));
    }

    @Test
    @DisplayName("lifetime access round-trips through DATETIME(6) on MySQL (entitlement end 9999-12-31, member row with NO expiry) and refund revokes it")
    void lifetimeOnMySql() {
        User owner = newUser("owner");
        User buyer = newUser("buyer");
        Classroom c = newPaidClass(owner, "PUBLIC", "999000", null);

        PaidOrder paid = buy(buyer, c, null);

        assertEquals(ClassAccessService.LIFETIME_END, chain(buyer, c).get(0).getExpiresAt());
        assertNull(row(c, buyer).getAccessExpiresAt());
        assertTrue(accessPolicy.isMember(buyer.getId(), c.getId()));
        refund(paid);
        assertFalse(accessPolicy.isMember(buyer.getId(), c.getId()));
        assertEquals("EXPIRED", row(c, buyer).getState());
    }

    @Test
    @DisplayName("converting a class with many members FREE -> PAID -> FREE on MySQL: grandfathering leaves every expiry NULL; the bulk clean-up clears only ACTIVE rows")
    void conversionsOnMySql() {
        User owner = newUser("owner");
        Classroom c = newClass(owner, "PUBLIC");
        List<User> members = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            User u = newUser("m" + i);
            members.add(u);
            classroomService.joinClassroom(c.getId(), u.getId());
        }
        setAccess(c, owner, "PAID", "199000", 30);
        members.forEach(u -> assertNull(row(c, u).getAccessExpiresAt()));

        User payer = newUser("payer");
        buy(payer, c, null);
        User lapsed = newUser("lapsed");
        addRow(c, lapsed, "STUDENT", "EXPIRED", Instant.now().minus(1, ChronoUnit.DAYS));
        assertNotNull(row(c, payer).getAccessExpiresAt());

        setAccess(c, owner, "FREE", null, null);

        assertNull(row(c, payer).getAccessExpiresAt());
        assertEquals("EXPIRED", row(c, lapsed).getState());
        members.forEach(u -> assertEquals("ACTIVE", row(c, u).getState()));
        Map<String, Object> product = jdbc.queryForMap("SELECT status, kind FROM products WHERE id = ?", reload(c).getAccessProductId());
        assertEquals("ARCHIVED", product.get("status"));
        assertEquals("CLASS_ACCESS", product.get("kind"));
    }
}
