package com.classroom.modules.classroom.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.classroom.dto.ClassInviteDto;
import com.classroom.modules.classroom.dto.CreateInviteRequest;
import com.classroom.modules.classroom.dto.InvitePreviewDto;
import com.classroom.modules.classroom.model.ClassInvite;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.identity.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-19 invites, end to end through the real services on H2: who may manage them, what is (not) stored, what a holder of a code can do, and
 * that every way a code can be unusable is the SAME 404.
 */
@org.springframework.boot.test.context.SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class ClassInviteFlowTest extends ClassAccessTestBase {

    private CreateInviteRequest request(Instant expiresAt, Integer maxUses) {
        CreateInviteRequest r = new CreateInviteRequest();
        r.setExpiresAt(expiresAt);
        r.setMaxUses(maxUses);
        return r;
    }

    private AppException invalid(org.junit.jupiter.api.function.Executable action) {
        AppException ex = assertThrows(AppException.class, action);
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        assertEquals(ClassInviteLedger.NOT_FOUND_MESSAGE, ex.getMessage(), "one message for every invalid code");
        return ex;
    }

    // ------------------------------------------------------------------------------------------------ create / list / revoke

    @Test
    @DisplayName("create returns the full code once; storage keeps only its SHA-256 and a 4-character hint; the list never shows the code")
    void codeIsShownOnceAndOnlyItsHashIsStored() {
        User owner = newUser("owner");
        Classroom c = newClass(owner, "PRIVATE");

        ClassInviteDto created = inviteService.create(c.getId(), request(null, null), owner.getId());

        String code = created.getCode();
        assertNotNull(code);
        assertTrue(code.length() >= 32, "192 random bits, URL-safe Base64: 32 characters");
        assertTrue(code.matches("[A-Za-z0-9_-]+"));
        ClassInvite stored = inviteRepository.findById(created.getId()).orElseThrow();
        assertEquals(InviteCodes.hash(code), stored.getCodeHash());
        assertNotEquals(code, stored.getCodeHash());
        assertEquals(64, stored.getCodeHash().length());
        assertEquals(code.substring(code.length() - 4), stored.getCodeHint());
        assertEquals("ACTIVE", created.getStatus());

        List<ClassInviteDto> listed = inviteService.list(c.getId(), owner.getId());
        assertEquals(1, listed.size());
        assertNull(listed.get(0).getCode(), "the list never carries the code");
        assertEquals(stored.getCodeHint(), listed.get(0).getCodeHint());
        assertEquals(0, listed.get(0).getUsedCount());
    }

    @Test
    @DisplayName("two invites never get the same code; codes come from a CSPRNG (192 bits)")
    void codesAreUnique() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 500; i++) {
            String code = InviteCodes.generate();
            assertTrue(InviteCodes.isWellFormed(code));
            assertTrue(seen.add(code));
        }
    }

    @Test
    @DisplayName("only the owner or staff with MEMBER:EDIT manage invites - every other caller is refused, for create, list and revoke alike")
    void managementIsGatedOnMemberEdit() {
        User owner = newUser("owner");
        User delegate = newUser("delegate");
        User viewer = newUser("viewer");
        User member = newUser("member");
        User outsider = newUser("outsider");
        Classroom c = newClass(owner, "PRIVATE");
        addActive(c, member);
        grantStaff(c, owner, delegate, "MEMBER:EDIT");
        grantStaff(c, owner, viewer, "MEMBER:VIEW");

        ClassInviteDto made = inviteService.create(c.getId(), request(null, null), delegate.getId());
        assertNotNull(made.getCode(), "staff holding MEMBER:EDIT may create");
        assertEquals(1, inviteService.list(c.getId(), delegate.getId()).size());

        for (User denied : List.of(viewer, member, outsider)) {
            assertCode(ErrorCode.STAFF_PERMISSION_DENIED, () -> inviteService.create(c.getId(), request(null, null), denied.getId()));
            assertCode(ErrorCode.STAFF_PERMISSION_DENIED, () -> inviteService.list(c.getId(), denied.getId()));
            assertCode(ErrorCode.STAFF_PERMISSION_DENIED, () -> inviteService.revoke(c.getId(), made.getId(), denied.getId()));
        }
        // and a nonexistent class answers exactly like a class the caller is not allowed to manage
        assertCode(ErrorCode.STAFF_PERMISSION_DENIED, () -> inviteService.list("no-such-class", outsider.getId()));
        assertEquals("ACTIVE", inviteService.list(c.getId(), owner.getId()).get(0).getStatus(), "nothing was revoked by the refused calls");
    }

    @Test
    @DisplayName("create validates expiresAt (future, at most ten years) and maxUses (1..100000); an archived class refuses new invites")
    void createValidatesLimits() {
        User owner = newUser("owner");
        Classroom c = newClass(owner, "PRIVATE");

        assertCode(ErrorCode.BAD_REQUEST, () -> inviteService.create(c.getId(), request(Instant.now().minusSeconds(5), null), owner.getId()));
        assertCode(ErrorCode.BAD_REQUEST, () -> inviteService.create(c.getId(), request(Instant.now().plus(4000, ChronoUnit.DAYS), null), owner.getId()));
        assertCode(ErrorCode.BAD_REQUEST, () -> inviteService.create(c.getId(), request(null, 0), owner.getId()));
        assertCode(ErrorCode.BAD_REQUEST, () -> inviteService.create(c.getId(), request(null, 100_001), owner.getId()));
        assertNotNull(inviteService.create(c.getId(), request(Instant.now().plusSeconds(3600), 100_000), owner.getId()).getCode());

        Classroom archived = newClass(owner, "PRIVATE");
        UpdateStatus.archive(classroomService, archived, owner);
        assertCode(ErrorCode.BAD_REQUEST, () -> inviteService.create(archived.getId(), request(null, null), owner.getId()));
    }

    @Test
    @DisplayName("revoke marks the invite REVOKED, is idempotent (audited once) and unknown / foreign invite ids are 404")
    void revokeIsIdempotentAndAudited() {
        User owner = newUser("owner");
        Classroom c = newClass(owner, "PRIVATE");
        Classroom other = newClass(owner, "PRIVATE");
        ClassInviteDto made = inviteService.create(c.getId(), request(null, null), owner.getId());

        assertEquals("REVOKED", inviteService.revoke(c.getId(), made.getId(), owner.getId()).getStatus());
        assertEquals("REVOKED", inviteService.revoke(c.getId(), made.getId(), owner.getId()).getStatus());
        assertEquals(1, audits(c, "CLASS_INVITE_REVOKE").size(), "a repeated revoke is not audited twice");
        assertEquals(1, audits(c, "CLASS_INVITE_CREATE").size());
        assertCode(ErrorCode.NOT_FOUND, () -> inviteService.revoke(c.getId(), "no-such-invite", owner.getId()));
        assertCode(ErrorCode.NOT_FOUND, () -> inviteService.revoke(other.getId(), made.getId(), owner.getId()));

        String audit = audits(c, "CLASS_INVITE_CREATE").get(0).getDetailsJson();
        assertFalse(audit.contains(made.getCode()), "the audit trail never records the code");
    }

    @Test
    @DisplayName("status precedence: REVOKED, then EXPIRED, then EXHAUSTED, else ACTIVE")
    void statusPrecedence() {
        Instant now = Instant.now();
        ClassInvite i = new ClassInvite("c", "h", "abcd", "u", now.plusSeconds(60), 1);
        assertEquals("ACTIVE", i.statusAt(now));
        i.setUsedCount(1);
        assertEquals("EXHAUSTED", i.statusAt(now));
        assertEquals("EXPIRED", i.statusAt(now.plusSeconds(61)), "expired wins over exhausted");
        i.setRevokedAt(now);
        assertEquals("REVOKED", i.statusAt(now.plusSeconds(61)), "revoked wins over everything");
    }

    // ------------------------------------------------------------------------------------------------------------ preview

    @Test
    @DisplayName("preview of a valid code returns the class card (and the price of a paid class); nothing about members or content")
    void previewReturnsTheCard() {
        User owner = newUser("owner");
        Classroom free = newClass(owner, "PRIVATE");
        Classroom paid = newPaidClass(owner, "PRIVATE", "199000", 30);

        InvitePreviewDto freeCard = inviteService.preview(inviteService.create(free.getId(), request(null, null), owner.getId()).getCode());
        assertEquals(free.getId(), freeCard.getClassId());
        assertEquals(free.getSlug(), freeCard.getSlug());
        assertEquals(free.getTitle(), freeCard.getTitle());
        assertEquals("FREE", freeCard.getAccessType());
        assertNull(freeCard.getPrice());
        assertEquals(owner.getFullName(), freeCard.getOwnerName());

        InvitePreviewDto paidCard = inviteService.preview(inviteService.create(paid.getId(), request(null, null), owner.getId()).getCode());
        assertEquals("PAID", paidCard.getAccessType());
        assertEquals(0, new java.math.BigDecimal("199000").compareTo(paidCard.getPrice()));
        assertEquals("VND", paidCard.getCurrency());
        assertEquals(30, paidCard.getDurationDays());
        assertEquals(Boolean.FALSE, paidCard.getLifetime());
    }

    @Test
    @DisplayName("every way a code can be unusable is the SAME 404: unknown, malformed, revoked, expired, used up, archived class")
    void everyInvalidCodeIsTheSame404() {
        User owner = newUser("owner");
        User joiner = newUser("joiner");
        Classroom c = newClass(owner, "PRIVATE");

        String revoked = inviteService.create(c.getId(), request(null, null), owner.getId()).getCode();
        inviteService.revoke(c.getId(), inviteRepository.findByCodeHash(InviteCodes.hash(revoked)).orElseThrow().getId(), owner.getId());

        String expiredCode = inviteService.create(c.getId(), request(null, null), owner.getId()).getCode();
        ClassInvite expired = inviteRepository.findByCodeHash(InviteCodes.hash(expiredCode)).orElseThrow();
        expired.setExpiresAt(Instant.now().minusSeconds(1));
        inviteRepository.save(expired);

        String usedUpCode = inviteService.create(c.getId(), request(null, 1), owner.getId()).getCode();
        inviteService.join(usedUpCode, newUser("first").getId());

        Classroom archivedClass = newClass(owner, "PRIVATE");
        String archivedCode = inviteService.create(archivedClass.getId(), request(null, null), owner.getId()).getCode();
        UpdateStatus.archive(classroomService, archivedClass, owner);

        String[] bad = {"", "short", "not a code!", InviteCodes.generate(), revoked, expiredCode, usedUpCode, archivedCode,
                "x".repeat(129), "../../etc/passwd/................"};
        for (String code : bad) {
            invalid(() -> inviteService.preview(code));
            invalid(() -> inviteService.join(code, joiner.getId()));
        }
        assertNull(row(c, joiner), "no failed join left a roster row behind");
    }

    // -------------------------------------------------------------------------------------------------------------------- join

    @Test
    @DisplayName("joining a PRIVATE free class by invite makes an ACTIVE member, consumes one use and is audited; joining again consumes nothing")
    void joinFreePrivateClass() {
        User owner = newUser("owner");
        User student = newUser("student");
        Classroom c = newClass(owner, "PRIVATE");
        ClassInviteDto made = inviteService.create(c.getId(), request(null, 10), owner.getId());

        Classroom joined = inviteService.join(made.getCode(), student.getId());
        assertEquals(c.getId(), joined.getId());
        assertEquals("ACTIVE", row(c, student).getState());
        assertNull(row(c, student).getAccessExpiresAt());
        assertEquals(1, inviteRepository.findById(made.getId()).orElseThrow().getUsedCount());
        assertEquals(1, audits(c, "CLASS_INVITE_JOIN").size());
        assertEquals(1, events(c, "MEMBER_JOINED").stream().filter(e -> e.getPayloadJson().contains(student.getId())).count());

        inviteService.join(made.getCode(), student.getId());
        assertEquals(1, inviteRepository.findById(made.getId()).orElseThrow().getUsedCount(), "a member who is already in uses nothing up");
        assertTrue(accessPolicy.isMember(student.getId(), c.getId()));
    }

    @Test
    @DisplayName("owner and staff never need an invite and never consume one; a REMOVED person may rejoin; a BLOCKED person may not (403)")
    void ownerStaffRemovedAndBlocked() {
        User owner = newUser("owner");
        User staff = newUser("staff");
        User removed = newUser("removed");
        User blocked = newUser("blocked");
        Classroom c = newClass(owner, "PRIVATE");
        grantStaff(c, owner, staff, "COURSE:VIEW");
        addRow(c, removed, "STUDENT", "REMOVED", null);
        addRow(c, blocked, "STUDENT", "BLOCKED", null);
        ClassInviteDto made = inviteService.create(c.getId(), request(null, 5), owner.getId());

        inviteService.join(made.getCode(), owner.getId());
        inviteService.join(made.getCode(), staff.getId());
        assertEquals(0, inviteRepository.findById(made.getId()).orElseThrow().getUsedCount());

        inviteService.join(made.getCode(), removed.getId());
        assertEquals("ACTIVE", row(c, removed).getState(), "REMOVED may rejoin");
        assertEquals(1, inviteRepository.findById(made.getId()).orElseThrow().getUsedCount());

        assertCode(ErrorCode.FORBIDDEN, () -> inviteService.join(made.getCode(), blocked.getId()));
        assertEquals("BLOCKED", row(c, blocked).getState());
        assertEquals(1, inviteRepository.findById(made.getId()).orElseThrow().getUsedCount(), "a refused join consumes nothing");
    }

    @Test
    @DisplayName("maxUses is honoured one join at a time: the use after the last one is the generic 404")
    void maxUsesSequential() {
        User owner = newUser("owner");
        Classroom c = newClass(owner, "PRIVATE");
        ClassInviteDto made = inviteService.create(c.getId(), request(null, 2), owner.getId());
        inviteService.join(made.getCode(), newUser("a").getId());
        inviteService.join(made.getCode(), newUser("b").getId());
        User late = newUser("late");
        invalid(() -> inviteService.join(made.getCode(), late.getId()));
        assertEquals("EXHAUSTED", inviteService.list(c.getId(), owner.getId()).get(0).getStatus());
        assertNull(row(c, late));
    }

    @Test
    @DisplayName("a PAID class answers a non-member's invite join with PAYMENT_REQUIRED (402) carrying the class-access product; a BLOCKED person gets 403 instead")
    void joinPaidClassRequiresPayment() {
        User owner = newUser("owner");
        User student = newUser("student");
        User blocked = newUser("blocked");
        Classroom c = newPaidClass(owner, "PRIVATE", "199000", 30);
        addRow(c, blocked, "STUDENT", "BLOCKED", null);
        ClassInviteDto made = inviteService.create(c.getId(), request(null, 3), owner.getId());

        AppException ex = assertThrows(AppException.class, () -> inviteService.join(made.getCode(), student.getId()));
        assertEquals(ErrorCode.PAYMENT_REQUIRED, ex.getErrorCode());
        assertEquals(402, ex.getErrorCode().getHttpStatus().value());
        assertEquals(c.getId(), ex.getDetails().get("classId"));
        Object product = ex.getDetails().get("accessProduct");
        assertNotNull(product);
        assertEquals(reload(c).getAccessProductId(), ((com.classroom.modules.classroom.dto.ClassAccessProductDto) product).getId());
        assertNull(row(c, student), "no membership until the purchase settles");
        assertEquals(0, inviteRepository.findById(made.getId()).orElseThrow().getUsedCount());

        assertCode(ErrorCode.FORBIDDEN, () -> inviteService.join(made.getCode(), blocked.getId()));
    }

    @Test
    @DisplayName("an already-paid member joining by invite just gets the class back")
    void paidMemberJoinIsIdempotent() {
        User owner = newUser("owner");
        User buyer = newUser("buyer");
        Classroom c = newPaidClass(owner, "PRIVATE", "199000", 30);
        ClassInviteDto made = inviteService.create(c.getId(), request(null, null), owner.getId());
        buy(buyer, c, made.getCode());

        assertEquals(c.getId(), inviteService.join(made.getCode(), buyer.getId()).getId());
        assertNotNull(row(c, buyer).getAccessExpiresAt(), "the paid term is untouched");
    }

    /** ARCHIVE helper that goes through the real OWNER-only status endpoint logic. */
    static final class UpdateStatus {
        static void archive(ClassroomService service, Classroom c, User owner) {
            com.classroom.modules.classroom.dto.UpdateClassroomStatusRequest req = new com.classroom.modules.classroom.dto.UpdateClassroomStatusRequest();
            req.setStatus("ARCHIVED");
            service.updateClassroomStatus(c.getId(), req, owner.getId());
        }
    }

}
