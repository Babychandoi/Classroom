package com.classroom.seed;

import com.classroom.modules.classroom.dto.InvitePreviewDto;
import com.classroom.modules.classroom.model.ClassInvite;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.ClassInviteRepository;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.classroom.service.ClassInviteService;
import com.classroom.modules.classroom.service.InviteCodes;
import com.classroom.modules.commerce.model.Product;
import com.classroom.modules.commerce.model.ProductPrice;
import com.classroom.modules.commerce.repository.ProductPriceRepository;
import com.classroom.modules.commerce.repository.ProductRepository;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-19 demo data: a PRIVATE free class joined with a fixed, DEMO-ONLY invite code, and a PUBLIC paid class (199.000 VND / 30 days). The seed is
 * idempotent, leaves student.free without access to either, and cannot exist in a production deployment because the runner that creates the
 * fixed code only exists under the explicit demo opt-in.
 */
@SpringBootTest
@ActiveProfiles("test")
class DataSeedPrivateAndPaidClassesTest {

    @Autowired private DataSeedRunner seedRunner;
    @Autowired private ClassroomRepository classroomRepository;
    @Autowired private ClassMemberRepository memberRepository;
    @Autowired private ClassInviteRepository inviteRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductPriceRepository priceRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ClassInviteService inviteService;
    @Autowired private AccessPolicy accessPolicy;

    private User user(String email) {
        return userRepository.findByEmail(email).orElseThrow();
    }

    @Test
    @DisplayName("the private class exists, is PRIVATE + FREE, owned by owner@classroom.local, hidden from guests and from student.free")
    void privateClass() {
        Classroom c = classroomRepository.findBySlug(DataSeedRunner.DEMO_PRIVATE_CLASS_SLUG).orElseThrow();
        assertEquals("Lớp Riêng Tư (mã mời)", c.getTitle());
        assertEquals("PRIVATE", c.getVisibility());
        assertEquals("FREE", c.getAccessType());
        assertEquals("ACTIVE", c.getStatus());
        assertEquals(user("owner@classroom.local").getId(), c.getOwnerId());
        assertFalse(accessPolicy.isClassVisibleToUser(c, null));
        assertFalse(accessPolicy.isClassVisibleToUser(c, user("student.free@classroom.local").getId()));
        assertFalse(accessPolicy.isClassVisibleToUser(c, user("student.pro@classroom.local").getId()));
        assertTrue(accessPolicy.isClassVisibleToUser(c, user("owner@classroom.local").getId()));
        assertTrue(memberRepository.findByClassIdAndUserId(c.getId(), user("student.free@classroom.local").getId()).isEmpty());
    }

    @Test
    @DisplayName("exactly one active invite with the documented demo-only code; only its hash is stored; it previews and joins like any real code")
    void demoInvite() {
        Classroom c = classroomRepository.findBySlug(DataSeedRunner.DEMO_PRIVATE_CLASS_SLUG).orElseThrow();
        List<ClassInvite> invites = inviteRepository.findByClassIdOrderByCreatedAtDesc(c.getId());
        assertEquals(1, invites.size());
        ClassInvite invite = invites.get(0);
        assertEquals("demo-invite-lop-rieng-tu-2026", DataSeedRunner.DEMO_PRIVATE_INVITE_CODE);
        assertEquals(InviteCodes.hash(DataSeedRunner.DEMO_PRIVATE_INVITE_CODE), invite.getCodeHash());
        assertNull(invite.getExpiresAt());
        assertNull(invite.getMaxUses());
        assertTrue(InviteCodes.isWellFormed(DataSeedRunner.DEMO_PRIVATE_INVITE_CODE));

        InvitePreviewDto card = inviteService.preview(DataSeedRunner.DEMO_PRIVATE_INVITE_CODE);
        assertEquals(c.getId(), card.getClassId());
        assertEquals("FREE", card.getAccessType());
    }

    @Test
    @DisplayName("the paid class exists: PUBLIC, PAID, with a PUBLISHED CLASS_ACCESS product of 199.000 VND for 30 days; student.free is not a member")
    void paidClass() {
        Classroom c = classroomRepository.findBySlug(DataSeedRunner.DEMO_PAID_CLASS_SLUG).orElseThrow();
        assertEquals("Lớp Trả Phí", c.getTitle());
        assertEquals("PUBLIC", c.getVisibility());
        assertEquals("PAID", c.getAccessType());
        assertEquals(user("owner@classroom.local").getId(), c.getOwnerId());
        assertTrue(accessPolicy.isClassVisibleToUser(c, null), "a paid class is listed - people must be able to find the paywall");

        Product product = productRepository.findById(c.getAccessProductId()).orElseThrow();
        assertEquals(Product.KIND_CLASS_ACCESS, product.getKind());
        assertEquals("PUBLISHED", product.getStatus());
        ProductPrice price = priceRepository.findByProductId(product.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("199000").compareTo(price.getPrice()));
        assertEquals("VND", price.getCurrency());
        assertEquals(30, price.getDurationDays());
        assertTrue(memberRepository.findByClassIdAndUserId(c.getId(), user("student.free@classroom.local").getId()).isEmpty());
        assertTrue(memberRepository.findByClassIdAndUserId(c.getId(), user("student.pro@classroom.local").getId()).isEmpty());
    }

    @Test
    @DisplayName("the seed is idempotent: running it again creates no second class, invite or product, and student.pro keeps the classes it had")
    void idempotent() {
        long classes = classroomRepository.count();
        long invites = inviteRepository.count();
        long products = productRepository.count();
        seedRunner.run();
        seedRunner.run();
        assertEquals(classes, classroomRepository.count());
        assertEquals(invites, inviteRepository.count());
        assertEquals(products, productRepository.count());
        Classroom original = classroomRepository.findBySlug("lop-toan-nang-cao").orElseThrow();
        assertTrue(memberRepository.findByClassIdAndUserId(original.getId(), user("student.pro@classroom.local").getId()).isPresent());
    }

    @Test
    @DisplayName("production never seeds the fixed code: the runner exists only under an explicit demo opt-in (property AND a dev/test/docker/integration profile)")
    void productionNeverSeeds() {
        ConditionalOnProperty optIn = DataSeedRunner.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(optIn);
        assertEquals("classroom.seed.demo.enabled", optIn.name()[0]);
        assertEquals("true", optIn.havingValue());
        assertFalse(optIn.matchIfMissing(), "no opt-in, no seed");
        Profile profile = DataSeedRunner.class.getAnnotation(Profile.class);
        assertNotNull(profile);
        assertEquals(java.util.Set.of("dev", "test", "docker", "integration"), java.util.Set.copyOf(List.of(profile.value())));
        // and the code is not derivable from anything a production class has: a real code is 192 random bits
        assertNotEquals(DataSeedRunner.DEMO_PRIVATE_INVITE_CODE, InviteCodes.generate());
        assertEquals(32, InviteCodes.generate().length());
    }
}
