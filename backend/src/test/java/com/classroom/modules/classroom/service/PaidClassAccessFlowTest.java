package com.classroom.modules.classroom.service;

import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.dto.ClassInviteDto;
import com.classroom.modules.classroom.dto.ClassroomDto;
import com.classroom.modules.classroom.dto.CreateInviteRequest;
import com.classroom.modules.classroom.dto.UpdateClassAccessRequest;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.commerce.dto.OrderDto;
import com.classroom.modules.commerce.dto.ProductDto;
import com.classroom.modules.commerce.model.Entitlement;
import com.classroom.modules.commerce.model.Product;
import com.classroom.modules.commerce.model.ProductPrice;
import com.classroom.modules.commerce.policy.ProPolicy;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import com.classroom.modules.identity.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-19 part B on H2 through the real services: configuring a PAID class, buying access, renewal stacking, refund, the guards around the
 * class-access product, lifetime access and independence from PRO. (Concurrency on a real MySQL is in ClassAccessConcurrencyIntegrationTest.)
 */
@org.springframework.boot.test.context.SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class PaidClassAccessFlowTest extends ClassAccessTestBase {

    @Autowired private ProPolicy proPolicy;
    @Autowired private EntitlementRepository entitlements;

    private static void assertAbout(Instant expected, Instant actual, long toleranceSeconds) {
        assertNotNull(actual, "expected an instant near " + expected);
        assertTrue(Math.abs(Duration.between(expected, actual).getSeconds()) <= toleranceSeconds,
                "expected ~" + expected + " but was " + actual);
    }

    // ------------------------------------------------------------------------------------------------- PUT /classes/{id}/access

    @Test
    @DisplayName("FREE -> PAID creates one PUBLISHED CLASS_ACCESS product (no course, VND, EPOCH start) and the class DTO carries it")
    void freeToPaidCreatesTheAccessProduct() {
        User owner = newUser("owner");
        Classroom c = newClass(owner, "PUBLIC");

        ClassroomDto dto = setAccess(c, owner, "PAID", "199000", 30);

        assertEquals("PAID", dto.getAccessType());
        assertNotNull(dto.getAccessProduct());
        assertEquals(0, new BigDecimal("199000").compareTo(dto.getAccessProduct().getPrice()));
        assertEquals("VND", dto.getAccessProduct().getCurrency());
        assertEquals(30, dto.getAccessProduct().getDurationDays());
        assertFalse(dto.getAccessProduct().isLifetime());

        Product product = productRepository.findById(dto.getAccessProduct().getId()).orElseThrow();
        assertEquals(Product.KIND_CLASS_ACCESS, product.getKind());
        assertEquals("PUBLISHED", product.getStatus());
        assertNull(product.getTargetCourseId(), "an explicit kind - target_course_id still means a PRO package / course product");
        assertEquals(product.getId(), reload(c).getAccessProductId());
        ProductPrice price = priceRepository.findByProductId(product.getId()).orElseThrow();
        assertEquals(Instant.EPOCH, price.getAccessStartsAt());
        assertEquals(30, price.getDurationDays());
    }

    @Test
    @DisplayName("the access change is audited as CLASS_ACCESS_UPDATE with before and after; FREE -> FREE is a silent no-op")
    void accessChangeIsAudited() {
        User owner = newUser("owner");
        Classroom c = newClass(owner, "PUBLIC");

        setAccess(c, owner, "FREE", null, null);
        assertTrue(audits(c, "CLASS_ACCESS_UPDATE").isEmpty(), "nothing changed, nothing audited");

        setAccess(c, owner, "PAID", "150000", 7);
        setAccess(c, owner, "PAID", "250000", 14);
        List<com.classroom.modules.audit.model.AuditEvent> events = audits(c, "CLASS_ACCESS_UPDATE");
        assertEquals(2, events.size());
        String latest = events.get(0).getDetailsJson();
        assertTrue(latest.contains("\"before\":{\"accessType\":\"PAID\""), latest);
        assertTrue(latest.contains("\"price\":150000"), latest);
        assertTrue(latest.contains("\"after\":{\"accessType\":\"PAID\""), latest);
        assertTrue(latest.contains("\"price\":250000"), latest);
        assertTrue(latest.contains("\"durationDays\":14"), latest);
        assertEquals(owner.getId(), events.get(0).getActorId());
    }

    @Test
    @DisplayName("who may change the access type: the owner, or staff holding BOTH STORE:EDIT and CLASS:EDIT - never one of the two, never a plain member")
    void accessChangeGating() {
        User owner = newUser("owner");
        User both = newUser("both");
        User storeOnly = newUser("storeOnly");
        User classOnly = newUser("classOnly");
        User member = newUser("member");
        Classroom c = newClass(owner, "PUBLIC");
        addActive(c, member);
        grantStaff(c, owner, both, "STORE:EDIT", "CLASS:EDIT");
        grantStaff(c, owner, storeOnly, "STORE:EDIT");
        grantStaff(c, owner, classOnly, "CLASS:EDIT");

        for (User denied : List.of(storeOnly, classOnly, member)) {
            assertCode(ErrorCode.STAFF_PERMISSION_DENIED, () -> setAccess(c, denied, "PAID", "199000", 30));
        }
        assertEquals("FREE", reload(c).getAccessType());

        assertEquals("PAID", setAccess(c, both, "PAID", "199000", 30).getAccessType());
        assertEquals(both.getId(), audits(c, "CLASS_ACCESS_UPDATE").get(0).getActorId());
        assertEquals("FREE", setAccess(c, owner, "FREE", null, null).getAccessType());
    }

    @Test
    @DisplayName("the price is validated like every product price (R19-09): positive whole dong, VND only, duration 1..3650 or lifetime")
    void accessChangeValidation() {
        User owner = newUser("owner");
        Classroom c = newClass(owner, "PUBLIC");

        assertCode(ErrorCode.BAD_REQUEST, () -> setAccess(c, owner, "PAID", null, 30));
        assertCode(ErrorCode.BAD_REQUEST, () -> setAccess(c, owner, "PAID", "0", 30));
        assertCode(ErrorCode.BAD_REQUEST, () -> setAccess(c, owner, "PAID", "-5", 30));
        assertCode(ErrorCode.BAD_REQUEST, () -> setAccess(c, owner, "PAID", "199000.50", 30));
        assertCode(ErrorCode.BAD_REQUEST, () -> setAccess(c, owner, "PAID", "199000", -1));
        assertCode(ErrorCode.BAD_REQUEST, () -> setAccess(c, owner, "PAID", "199000", 3651));
        UpdateClassAccessRequest usd = new UpdateClassAccessRequest();
        usd.setAccessType("PAID");
        usd.setPrice(new BigDecimal("10"));
        usd.setCurrency("USD");
        usd.setDurationDays(30);
        assertCode(ErrorCode.BAD_REQUEST, () -> classroomService.updateClassAccess(c.getId(), usd, owner.getId()));
        assertEquals("FREE", reload(c).getAccessType(), "a rejected change leaves the class as it was");
        assertNull(reload(c).getAccessProductId());

        assertEquals(3650, setAccess(c, owner, "PAID", "199000", 3650).getAccessProduct().getDurationDays());
        assertTrue(setAccess(c, owner, "PAID", "199000", null).getAccessProduct().isLifetime(), "no duration = lifetime access");
        assertTrue(setAccess(c, owner, "PAID", "199000", 0).getAccessProduct().isLifetime(), "0 days = lifetime access");
    }

    @Test
    @DisplayName("PAID -> PAID re-prices for NEW orders only; PAID -> FREE archives the product; FREE -> PAID again re-uses the same product")
    void repriceArchiveAndReuse() {
        User owner = newUser("owner");
        User early = newUser("early");
        Classroom c = newPaidClass(owner, "PUBLIC", "100000", 30);
        String productId = reload(c).getAccessProductId();
        PaidOrder earlyOrder = buy(early, c, null);

        setAccess(c, owner, "PAID", "300000", 60);
        assertEquals(productId, reload(c).getAccessProductId());
        assertEquals(0, new BigDecimal("100000").compareTo(orderRepository.findById(earlyOrder.order().getId()).orElseThrow().getTotalAmount()),
                "the order already placed keeps the price it was sold at");
        OrderDto late = orderFor(newUser("late"), c, null);
        assertEquals(0, new BigDecimal("300000").compareTo(late.getTotalAmount()));

        setAccess(c, owner, "FREE", null, null);
        assertEquals("ARCHIVED", productRepository.findById(productId).orElseThrow().getStatus());
        assertEquals("FREE", reload(c).getAccessType());
        assertEquals(productId, reload(c).getAccessProductId(), "kept (archived) so the history stays on one product");
        assertNull(classroomService.getById(c.getId(), owner.getId()).getAccessProduct());

        setAccess(c, owner, "PAID", "500000", 10);
        assertEquals(productId, reload(c).getAccessProductId(), "no second product for the same class");
        assertEquals("PUBLISHED", productRepository.findById(productId).orElseThrow().getStatus());
        assertEquals(0, new BigDecimal("500000").compareTo(priceRepository.findByProductId(productId).orElseThrow().getPrice()));
        assertEquals(1, productRepository.findByClassIdOrderByCreatedAtDesc(c.getId()).stream().filter(Product::isClassAccess).count());
    }

    // ------------------------------------------------------------------------------------------------------ grandfathering

    @Test
    @DisplayName("FREE -> PAID grandfathers everybody who is a member: no expiry, no purchase, still ACTIVE after a sweep; newcomers must pay")
    void grandfathering() {
        User owner = newUser("owner");
        User old = newUser("old");
        User newcomer = newUser("newcomer");
        Classroom c = newClass(owner, "PUBLIC");
        classroomService.joinClassroom(c.getId(), old.getId());
        assertEquals("ACTIVE", row(c, old).getState());

        setAccess(c, owner, "PAID", "199000", 30);

        assertNull(row(c, old).getAccessExpiresAt(), "grandfathered members never expire");
        assertTrue(accessPolicy.isMember(old.getId(), c.getId()));
        expiryService.sweepBatch(100, Instant.now());
        assertEquals("ACTIVE", row(c, old).getState());
        assertTrue(accessPolicy.isMember(old.getId(), c.getId()));

        assertCode(ErrorCode.PAYMENT_REQUIRED, () -> classroomService.joinClassroom(c.getId(), newcomer.getId()));
        assertNull(row(c, newcomer));
        // a grandfathered member has nothing to buy - the order is refused rather than taking their money
        assertCode(ErrorCode.BAD_REQUEST, () -> orderFor(old, c, null));
    }

    @Test
    @DisplayName("PAID -> FREE: nobody loses anything - ACTIVE members lose their expiry date; an EXPIRED member is ACTIVE again only by joining")
    void paidToFreeKeepsEveryone() {
        User owner = newUser("owner");
        User paying = newUser("paying");
        User expired = newUser("expired");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        buy(paying, c, null);
        addRow(c, expired, "STUDENT", "EXPIRED", Instant.now().minus(3, ChronoUnit.DAYS));
        assertNotNull(row(c, paying).getAccessExpiresAt());

        setAccess(c, owner, "FREE", null, null);

        assertNull(row(c, paying).getAccessExpiresAt());
        assertTrue(accessPolicy.isMember(paying.getId(), c.getId()));
        assertEquals("EXPIRED", row(c, expired).getState(), "not revived behind their back");
        assertFalse(accessPolicy.isMember(expired.getId(), c.getId()));

        ClassroomDto dto = classroomService.joinClassroom(c.getId(), expired.getId());
        assertEquals("ACTIVE", dto.getMemberState());
        assertNull(row(c, expired).getAccessExpiresAt());
        assertTrue(accessPolicy.isMember(expired.getId(), c.getId()));
    }

    // ------------------------------------------------------------------------------------------------------- buying access

    @Test
    @DisplayName("a non-member orders the class-access product, pays (webhook) and becomes an ACTIVE member until the end of the entitlement - and is NOT PRO")
    void buyingAccessCreatesTheMembership() {
        User owner = newUser("owner");
        User buyer = newUser("buyer");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        assertNull(row(c, buyer));
        assertFalse(accessPolicy.isMember(buyer.getId(), c.getId()));

        OrderDto order = orderFor(buyer, c, null);
        assertEquals("PENDING", order.getStatus());
        assertNull(row(c, buyer), "no membership before the payment settles");

        PaidOrder paid = buy(buyer, c, null);
        assertEquals("PAID", orderRepository.findById(paid.order().getId()).orElseThrow().getStatus());

        List<Entitlement> chain = chain(buyer, c);
        assertEquals(1, chain.size());
        ClassMember member = row(c, buyer);
        assertEquals("ACTIVE", member.getState());
        assertEquals("STUDENT", member.getRole());
        assertEquals(chain.get(0).getExpiresAt(), member.getAccessExpiresAt(), "access_expires_at = end of the entitlement chain");
        assertAbout(Instant.now().plus(30, ChronoUnit.DAYS), member.getAccessExpiresAt(), 120);
        assertTrue(accessPolicy.isMember(buyer.getId(), c.getId()));
        assertFalse(proPolicy.isPro(buyer.getId(), c.getId()), "the class-access product does NOT grant PRO");
        assertFalse(entitlements.hasActiveProEntitlement(buyer.getId(), c.getId(), Instant.now()));
        assertEquals(1, events(c, "MEMBER_JOINED").stream().filter(e -> e.getPayloadJson().contains(buyer.getId())).count());

        ClassroomDto dto = classroomService.getById(c.getId(), buyer.getId());
        assertTrue(dto.isMember());
        assertEquals("ACTIVE", dto.getMemberState());
        assertEquals(member.getAccessExpiresAt(), dto.getAccessExpiresAt());
        assertFalse(dto.isPro());
    }

    @Test
    @DisplayName("a repeated webhook changes nothing (one entitlement, one membership event)")
    void settleIsIdempotent() {
        User owner = newUser("owner");
        User buyer = newUser("buyer");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        PaidOrder paid = buy(buyer, c, null);
        Instant expiry = row(c, buyer).getAccessExpiresAt();

        webhook(paid.order(), paid.providerRef(), "PAYMENT_SUCCESS");

        assertEquals(1, chain(buyer, c).size());
        assertEquals(expiry, row(c, buyer).getAccessExpiresAt());
        assertEquals(1, events(c, "MEMBER_JOINED").stream().filter(e -> e.getPayloadJson().contains(buyer.getId())).count());
    }

    @Test
    @DisplayName("renewal by an active member stacks: the new term starts where the old one ends, and access_expires_at moves to the end of the chain")
    void renewalStacks() {
        User owner = newUser("owner");
        User buyer = newUser("buyer");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        buy(buyer, c, null);
        Instant firstEnd = row(c, buyer).getAccessExpiresAt();

        buy(buyer, c, null);

        List<Entitlement> chain = chain(buyer, c);
        assertEquals(2, chain.size());
        assertEquals(chain.get(0).getExpiresAt(), chain.get(1).getStartsAt(), "D-03 stacking");
        assertEquals(firstEnd.plus(30, ChronoUnit.DAYS), row(c, buyer).getAccessExpiresAt());
        assertEquals(1, events(c, "MEMBER_JOINED").stream().filter(e -> e.getPayloadJson().contains(buyer.getId())).count(),
                "an already-active member re-joins nothing");
    }

    @Test
    @DisplayName("an EXPIRED member renews: ACTIVE again from now for one term")
    void expiredMemberRenews() {
        User owner = newUser("owner");
        User buyer = newUser("buyer");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        buy(buyer, c, null);
        lapse(c, buyer);
        entitlementRepository.findByUserIdAndClassId(buyer.getId(), c.getId()).forEach(e -> {
            e.setStartsAt(Instant.now().minus(31, ChronoUnit.DAYS));
            e.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
            entitlementRepository.save(e);
        });
        assertFalse(accessPolicy.isMember(buyer.getId(), c.getId()));
        assertTrue(accessPolicy.isMembershipExpired(buyer.getId(), c.getId()));

        buy(buyer, c, null);

        assertTrue(accessPolicy.isMember(buyer.getId(), c.getId()));
        assertEquals("ACTIVE", row(c, buyer).getState());
        assertAbout(Instant.now().plus(30, ChronoUnit.DAYS), row(c, buyer).getAccessExpiresAt(), 120);
        assertEquals(2, events(c, "MEMBER_JOINED").stream().filter(e -> e.getPayloadJson().contains(buyer.getId())).count(),
                "the Neo4j edge comes back with the renewal");
    }

    @Test
    @DisplayName("a REMOVED member who still holds a running purchase rejoins WITHOUT paying again (the purchase survives removal)")
    void removedMemberWithRunningPurchaseRejoins() {
        User owner = newUser("owner");
        User buyer = newUser("buyer");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        buy(buyer, c, null);
        Instant end = row(c, buyer).getAccessExpiresAt();
        memberService.removeMember(c.getId(), buyer.getId(), owner.getId());
        assertEquals("REMOVED", row(c, buyer).getState());
        assertFalse(accessPolicy.isMember(buyer.getId(), c.getId()));

        ClassroomDto dto = classroomService.joinClassroom(c.getId(), buyer.getId());

        assertEquals("ACTIVE", dto.getMemberState());
        assertEquals(end, row(c, buyer).getAccessExpiresAt(), "the same paid term");
        assertEquals(1, chain(buyer, c).size(), "no second purchase was needed");
    }

    // ----------------------------------------------------------------------------------------------------------------- refund

    @Test
    @DisplayName("refunding the only purchase revokes access at once: EXPIRED, MEMBER_EXPIRED emitted; refunding one of two stacked purchases re-computes the end")
    void refundRecomputesMembership() {
        User owner = newUser("owner");
        User single = newUser("single");
        User stacked = newUser("stacked");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);

        PaidOrder only = buy(single, c, null);
        refund(only);
        assertEquals("EXPIRED", row(c, single).getState());
        assertFalse(accessPolicy.isMember(single.getId(), c.getId()), "access is gone the moment the refund lands");
        assertEquals(1, events(c, "MEMBER_EXPIRED").stream().filter(e -> e.getPayloadJson().contains(single.getId())).count());
        assertTrue(chain(single, c).stream().allMatch(e -> "REVOKED".equals(e.getState())));

        PaidOrder first = buy(stacked, c, null);
        PaidOrder second = buy(stacked, c, null);
        Instant bothEnd = row(c, stacked).getAccessExpiresAt();
        refund(second);
        assertEquals("ACTIVE", row(c, stacked).getState());
        assertEquals(bothEnd.minus(30, ChronoUnit.DAYS), row(c, stacked).getAccessExpiresAt(), "back to the end of the remaining chain");
        assertTrue(accessPolicy.isMember(stacked.getId(), c.getId()));
        refund(first);
        assertFalse(accessPolicy.isMember(stacked.getId(), c.getId()));
        assertEquals("EXPIRED", row(c, stacked).getState());
    }

    @Test
    @DisplayName("a refund never touches a grandfathered (perpetual) member or a staff member")
    void refundLeavesPerpetualMembersAlone() {
        User owner = newUser("owner");
        User buyer = newUser("buyer");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        PaidOrder paid = buy(buyer, c, null);
        ClassMember m = row(c, buyer);
        m.setAccessExpiresAt(null); // e.g. granted perpetual access by other means
        memberRepository.save(m);

        refund(paid);

        assertEquals("ACTIVE", row(c, buyer).getState());
        assertNull(row(c, buyer).getAccessExpiresAt());
    }

    // ------------------------------------------------------------------------------------------------------------------ guards

    @Test
    @DisplayName("who may NOT buy: blocked people, the owner, staff, members with no-expiry access; an archived class refuses new orders")
    void buyerGuards() {
        User owner = newUser("owner");
        User blocked = newUser("blocked");
        User staff = newUser("staff");
        User perpetual = newUser("perpetual");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        addRow(c, blocked, "STUDENT", "BLOCKED", null);
        addRow(c, perpetual, "STUDENT", "ACTIVE", null);
        grantStaff(c, owner, staff, "COURSE:VIEW");

        assertCode(ErrorCode.FORBIDDEN, () -> orderFor(blocked, c, null));
        assertCode(ErrorCode.BAD_REQUEST, () -> orderFor(owner, c, null));
        assertCode(ErrorCode.BAD_REQUEST, () -> orderFor(staff, c, null));
        assertCode(ErrorCode.BAD_REQUEST, () -> orderFor(perpetual, c, null));

        User late = newUser("late");
        ClassInviteFlowTest.UpdateStatus.archive(classroomService, c, owner);
        assertCode(ErrorCode.BAD_REQUEST, () -> orderFor(late, c, null));
    }

    @Test
    @DisplayName("a payment that settles for someone blocked in the meantime is recorded but does not let them in; the operator is told via the audit log")
    void blockedWhilePaying() {
        User owner = newUser("owner");
        User buyer = newUser("buyer");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        OrderDto order = orderFor(buyer, c, null);
        addRow(c, buyer, "STUDENT", "BLOCKED", null);

        webhook(order, "txn-" + java.util.UUID.randomUUID(), "PAYMENT_SUCCESS");

        assertEquals("BLOCKED", row(c, buyer).getState());
        assertFalse(accessPolicy.isMember(buyer.getId(), c.getId()));
        assertEquals(1, audits(c, "CLASS_ACCESS_PAID_WHILE_BLOCKED").size());
        assertEquals("PAID", orderRepository.findById(order.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("an order created before the class turned FREE still settles, and the buyer becomes a member with no expiry")
    void pendingOrderSettlesAfterConversionToFree() {
        User owner = newUser("owner");
        User buyer = newUser("buyer");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        OrderDto order = orderFor(buyer, c, null);
        setAccess(c, owner, "FREE", null, null);

        webhook(order, "txn-" + java.util.UUID.randomUUID(), "PAYMENT_SUCCESS");

        assertEquals("ACTIVE", row(c, buyer).getState());
        assertNull(row(c, buyer).getAccessExpiresAt());
    }

    @Test
    @DisplayName("no order for the class-access product of a FREE class (its product is archived), and a stranger cannot order a class-access product of another class")
    void productMustBeTheClassesOwnAccessProduct() {
        User owner = newUser("owner");
        User buyer = newUser("buyer");
        Classroom paid = newPaidClass(owner, "PUBLIC", "199000", 30);
        Classroom free = newClass(owner, "PUBLIC");
        String archivedId = reload(paid).getAccessProductId();
        setAccess(paid, owner, "FREE", null, null);

        com.classroom.modules.commerce.dto.CreateOrderRequest archived = new com.classroom.modules.commerce.dto.CreateOrderRequest();
        archived.setClassId(paid.getId());
        archived.setProductId(archivedId);
        archived.setIdempotencyKey("k-" + java.util.UUID.randomUUID());
        assertCode(ErrorCode.BAD_REQUEST, () -> commerceService.createOrder(buyer.getId(), archived));

        Classroom other = newPaidClass(owner, "PUBLIC", "99000", 30);
        com.classroom.modules.commerce.dto.CreateOrderRequest crossClass = new com.classroom.modules.commerce.dto.CreateOrderRequest();
        crossClass.setClassId(free.getId());
        crossClass.setProductId(reload(other).getAccessProductId());
        crossClass.setIdempotencyKey("k-" + java.util.UUID.randomUUID());
        assertCode(ErrorCode.BAD_REQUEST, () -> commerceService.createOrder(buyer.getId(), crossClass));
        assertNull(row(free, buyer));
    }

    @Test
    @DisplayName("the general product endpoints refuse the class-access product (publish / update / archive / restore); only PUT /access manages it")
    void generalProductEndpointsRefuseIt() {
        User owner = newUser("owner");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        String pid = reload(c).getAccessProductId();

        assertCode(ErrorCode.BAD_REQUEST, () -> commerceService.publishProduct(pid, owner.getId()));
        assertCode(ErrorCode.BAD_REQUEST, () -> commerceService.updateProduct(pid, "x", null, new BigDecimal("1000"), 5, owner.getId()));
        assertCode(ErrorCode.BAD_REQUEST, () -> commerceService.archiveProduct(pid, owner.getId()));
        assertCode(ErrorCode.BAD_REQUEST, () -> commerceService.restoreProduct(pid, owner.getId()));
        assertEquals("PUBLISHED", productRepository.findById(pid).orElseThrow().getStatus());
        assertEquals(0, new BigDecimal("199000").compareTo(priceRepository.findByProductId(pid).orElseThrow().getPrice()));
    }

    @Test
    @DisplayName("the store listing shows the class-access product with kind CLASS_ACCESS, to a guest and to an EXPIRED member (who may read the Store)")
    void storeListsTheAccessProduct() {
        User owner = newUser("owner");
        User expired = newUser("expired");
        Classroom c = newPaidClass(owner, "PUBLIC", "199000", 30);
        addRow(c, expired, "STUDENT", "EXPIRED", Instant.now().minus(1, ChronoUnit.DAYS));

        for (String viewer : new String[]{null, expired.getId()}) {
            List<ProductDto> products = commerceService.getProductsByClass(c.getId(), viewer);
            assertEquals(1, products.size());
            assertEquals("CLASS_ACCESS", products.get(0).getKind());
            assertEquals(reload(c).getAccessProductId(), products.get(0).getId());
        }
    }

    // ------------------------------------------------------------------------------------------------------------- lifetime

    @Test
    @DisplayName("lifetime access: one entitlement to the sentinel end, the member has NO expiry; refund revokes it; a lifetime member cannot buy again")
    void lifetimeAccess() {
        User owner = newUser("owner");
        User buyer = newUser("buyer");
        Classroom c = newPaidClass(owner, "PUBLIC", "999000", null);

        PaidOrder paid = buy(buyer, c, null);

        List<Entitlement> chain = chain(buyer, c);
        assertEquals(1, chain.size());
        assertEquals(ClassAccessService.LIFETIME_END, chain.get(0).getExpiresAt());
        assertNull(row(c, buyer).getAccessExpiresAt());
        assertTrue(accessPolicy.isMember(buyer.getId(), c.getId()));
        expiryService.sweepBatch(100, Instant.now());
        assertEquals("ACTIVE", row(c, buyer).getState(), "a lifetime member is never swept");
        assertCode(ErrorCode.BAD_REQUEST, () -> orderFor(buyer, c, null));

        refund(paid);
        assertEquals("EXPIRED", row(c, buyer).getState());
        assertFalse(accessPolicy.isMember(buyer.getId(), c.getId()));
    }

    // ------------------------------------------------------------------------------------------------ private + paid (invites)

    @Test
    @DisplayName("PRIVATE paid class: a stranger cannot even start checkout without a valid invite (404), with one the order is created and a use is reserved")
    void privatePaidClassNeedsAnInviteToCheckout() {
        User owner = newUser("owner");
        User stranger = newUser("stranger");
        Classroom c = newPaidClass(owner, "PRIVATE", "199000", 30);
        ClassInviteDto invite = inviteService.create(c.getId(), request(null, 2), owner.getId());
        String code = invite.getCode();

        assertCode(ErrorCode.NOT_FOUND, () -> orderFor(stranger, c, null));
        assertCode(ErrorCode.NOT_FOUND, () -> orderFor(stranger, c, "not-a-real-invite-code-xxxxxxxx"));
        Classroom elsewhere = newClass(owner, "PRIVATE");
        String foreign = inviteService.create(elsewhere.getId(), request(null, null), owner.getId()).getCode();
        assertCode(ErrorCode.NOT_FOUND, () -> orderFor(stranger, c, foreign));
        assertEquals(0, inviteRepository.findById(invite.getId()).orElseThrow().getUsedCount());

        OrderDto order = orderFor(stranger, c, code);
        assertEquals("PENDING", order.getStatus());
        assertEquals(1, inviteRepository.findById(invite.getId()).orElseThrow().getUsedCount(), "a use is reserved with the order");
        assertEquals(invite.getId(), orderRepository.findById(order.getId()).orElseThrow().getInviteId());

        commerceService.cancelOrder(order.getId(), stranger.getId());
        assertEquals(0, inviteRepository.findById(invite.getId()).orElseThrow().getUsedCount(), "cancelling gives the use back");

        OrderDto failing = orderFor(stranger, c, code);
        assertEquals(1, inviteRepository.findById(invite.getId()).orElseThrow().getUsedCount());
        webhook(failing, "txn-f", "PAYMENT_FAILED");
        assertEquals(0, inviteRepository.findById(invite.getId()).orElseThrow().getUsedCount(), "a failed payment gives the use back");

        webhook(orderFor(stranger, c, code), "txn-" + java.util.UUID.randomUUID(), "PAYMENT_SUCCESS");
        assertTrue(accessPolicy.isMember(stranger.getId(), c.getId()));
        assertEquals(1, inviteRepository.findById(invite.getId()).orElseThrow().getUsedCount(), "a paid join keeps its use");
    }

    @Test
    @DisplayName("PRIVATE paid class: maxUses caps checkouts, a revoked invite stops them, and an EXPIRED member renews without any invite")
    void privatePaidClassInviteLimitsAndRenewal() {
        User owner = newUser("owner");
        User a = newUser("a");
        User b = newUser("b");
        User expired = newUser("expired");
        Classroom c = newPaidClass(owner, "PRIVATE", "199000", 30);
        addRow(c, expired, "STUDENT", "EXPIRED", Instant.now().minus(2, ChronoUnit.DAYS));
        ClassInviteDto one = inviteService.create(c.getId(), request(null, 1), owner.getId());

        orderFor(a, c, one.getCode());
        assertCode(ErrorCode.NOT_FOUND, () -> orderFor(b, c, one.getCode()));

        ClassInviteDto other = inviteService.create(c.getId(), request(null, null), owner.getId());
        inviteService.revoke(c.getId(), other.getId(), owner.getId());
        assertCode(ErrorCode.NOT_FOUND, () -> orderFor(b, c, other.getCode()));

        assertNotNull(orderFor(expired, c, null), "an EXPIRED member is on the roster: renewing needs no invite");
    }

    @Test
    @DisplayName("a REMOVED person of a PRIVATE paid class cannot see it: they need an invite to buy back in")
    void removedNeedsInviteForPrivatePaid() {
        User owner = newUser("owner");
        User removed = newUser("removed");
        Classroom c = newPaidClass(owner, "PRIVATE", "199000", 30);
        addRow(c, removed, "STUDENT", "REMOVED", null);
        assertCode(ErrorCode.NOT_FOUND, () -> orderFor(removed, c, null));
        String code = inviteService.create(c.getId(), request(null, null), owner.getId()).getCode();
        assertNotNull(orderFor(removed, c, code));
    }

    private CreateInviteRequest request(Instant expiresAt, Integer maxUses) {
        CreateInviteRequest r = new CreateInviteRequest();
        r.setExpiresAt(expiresAt);
        r.setMaxUses(maxUses);
        return r;
    }

}
