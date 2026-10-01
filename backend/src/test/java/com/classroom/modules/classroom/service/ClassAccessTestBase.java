package com.classroom.modules.classroom.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.audit.model.AuditEvent;
import com.classroom.modules.audit.repository.AuditEventRepository;
import com.classroom.modules.classroom.dto.ClassroomDto;
import com.classroom.modules.classroom.dto.CreateClassroomRequest;
import com.classroom.modules.classroom.dto.StaffPermissionDto;
import com.classroom.modules.classroom.dto.UpdateClassAccessRequest;
import com.classroom.modules.classroom.dto.UpdateClassroomRequest;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.ClassInviteRepository;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.commerce.dto.CreateOrderRequest;
import com.classroom.modules.commerce.dto.OrderDto;
import com.classroom.modules.commerce.dto.WebhookPayload;
import com.classroom.modules.commerce.model.Entitlement;
import com.classroom.modules.commerce.model.Product;
import com.classroom.modules.commerce.payment.MockPaymentProvider;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import com.classroom.modules.commerce.repository.OrderRepository;
import com.classroom.modules.commerce.repository.ProductPriceRepository;
import com.classroom.modules.commerce.repository.ProductRepository;
import com.classroom.modules.commerce.service.CommerceService;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.outbox.model.OutboxEvent;
import com.classroom.modules.outbox.repository.OutboxEventRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * D-19 fixtures for the service tests of private / paid classes - on H2 (profile test) and on a real MySQL (profile integration): every test
 * builds its own classes and people through the REAL services (create class, update settings, update access, join, order, webhook), so what is
 * asserted is what the API would do. Nothing here touches the demo seed the other suites share.
 *
 * <p>Deliberately carries NO Spring test annotations: a subclass chooses its own {@code @SpringBootTest} / {@code @ActiveProfiles} (an inherited
 * profile would be merged with, not replaced by, the subclass's).</p>
 */
public abstract class ClassAccessTestBase {

    @Autowired protected UserRepository userRepository;
    @Autowired protected ClassroomRepository classroomRepository;
    @Autowired protected ClassMemberRepository memberRepository;
    @Autowired protected ClassInviteRepository inviteRepository;
    @Autowired protected ProductRepository productRepository;
    @Autowired protected ProductPriceRepository priceRepository;
    @Autowired protected OrderRepository orderRepository;
    @Autowired protected EntitlementRepository entitlementRepository;
    @Autowired protected AuditEventRepository auditEventRepository;
    @Autowired protected OutboxEventRepository outboxEventRepository;
    @Autowired protected ClassroomService classroomService;
    @Autowired protected ClassInviteService inviteService;
    @Autowired protected ClassAccessService accessService;
    @Autowired protected CommerceService commerceService;
    @Autowired protected MemberService memberService;
    @Autowired protected StaffService staffService;
    @Autowired protected MembershipExpiryService expiryService;
    @Autowired protected AccessPolicy accessPolicy;
    @Autowired protected MockPaymentProvider mockPaymentProvider;

    private static final AtomicInteger SEQ = new AtomicInteger();

    protected User newUser(String prefix) {
        String unique = prefix + "-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 6);
        return userRepository.save(new User(UUID.randomUUID().toString(), unique + "@d19.test", "hash", "Nguoi dung " + unique, "USER"));
    }

    /** A class created through the service (owner row, default About included), then configured. */
    protected Classroom newClass(User owner, String visibility) {
        CreateClassroomRequest req = new CreateClassroomRequest();
        req.setTitle("Lop D19 " + SEQ.incrementAndGet());
        req.setSlug("d19-" + UUID.randomUUID().toString().substring(0, 12));
        req.setDescription("Mo ta");
        req.setVisibility(visibility);
        ClassroomDto dto = classroomService.createClassroom(owner.getId(), req);
        return classroomRepository.findById(dto.getId()).orElseThrow();
    }

    protected Classroom newPaidClass(User owner, String visibility, String price, Integer durationDays) {
        Classroom c = newClass(owner, visibility);
        setAccess(c, owner, "PAID", price, durationDays);
        return classroomRepository.findById(c.getId()).orElseThrow();
    }

    protected ClassroomDto setAccess(Classroom c, User actor, String type, String price, Integer durationDays) {
        UpdateClassAccessRequest req = new UpdateClassAccessRequest();
        req.setAccessType(type);
        if (price != null) req.setPrice(new BigDecimal(price));
        req.setDurationDays(durationDays);
        return classroomService.updateClassAccess(c.getId(), req, actor.getId());
    }

    protected ClassroomDto setVisibility(Classroom c, User actor, String visibility) {
        UpdateClassroomRequest req = new UpdateClassroomRequest();
        req.setTitle(classroomRepository.findById(c.getId()).orElseThrow().getTitle());
        req.setVisibility(visibility);
        return classroomService.updateClassroom(c.getId(), req, actor.getId());
    }

    protected Classroom reload(Classroom c) {
        return classroomRepository.findById(c.getId()).orElseThrow();
    }

    protected ClassMember row(Classroom c, User u) {
        return memberRepository.findByClassIdAndUserId(c.getId(), u.getId()).orElse(null);
    }

    /** A roster row written directly (bypassing the services) - for states the services reach only through time or Studio actions. */
    protected ClassMember addRow(Classroom c, User u, String role, String state, Instant accessExpiresAt) {
        ClassMember m = new ClassMember(c.getId(), u.getId(), role);
        m.setState(state);
        m.setAccessExpiresAt(accessExpiresAt);
        return memberRepository.save(m);
    }

    protected ClassMember addActive(Classroom c, User u) {
        return addRow(c, u, "STUDENT", "ACTIVE", null);
    }

    protected Product accessProduct(Classroom c) {
        return productRepository.findById(reload(c).getAccessProductId()).orElseThrow();
    }

    protected OrderDto orderFor(User buyer, Classroom c, String inviteCode) {
        CreateOrderRequest req = new CreateOrderRequest();
        req.setClassId(c.getId());
        req.setProductId(reload(c).getAccessProductId());
        req.setIdempotencyKey("d19-" + UUID.randomUUID());
        req.setInviteCode(inviteCode);
        return commerceService.createOrder(buyer.getId(), req);
    }

    protected OrderDto webhook(OrderDto order, String providerRef, String eventType) {
        String body = String.format(Locale.ROOT,
                "{\"orderNumber\":\"%s\",\"providerRef\":\"%s\",\"eventType\":\"%s\",\"amount\":%s,\"currency\":\"%s\"}",
                order.getOrderNumber(), providerRef, eventType, order.getTotalAmount().toPlainString(), order.getCurrency());
        WebhookPayload payload = new WebhookPayload(order.getOrderNumber(), providerRef, eventType, order.getTotalAmount(), order.getCurrency());
        return commerceService.handlePaymentWebhook("MOCK", payload, body, mockPaymentProvider.generateSignature(body));
    }

    /** Creates an order and settles it. Returns the order with the provider reference used, so a refund can follow. */
    protected PaidOrder buy(User buyer, Classroom c, String inviteCode) {
        OrderDto order = orderFor(buyer, c, inviteCode);
        String ref = "txn-" + UUID.randomUUID();
        webhook(order, ref, "PAYMENT_SUCCESS");
        return new PaidOrder(order, ref);
    }

    protected void refund(PaidOrder paid) {
        webhook(paid.order(), paid.providerRef(), "PAYMENT_REFUNDED");
    }

    protected record PaidOrder(OrderDto order, String providerRef) {}

    protected List<Entitlement> chain(User buyer, Classroom c) {
        String productId = reload(c).getAccessProductId();
        return entitlementRepository.findByUserIdAndClassId(buyer.getId(), c.getId()).stream()
                .filter(e -> productId.equals(e.getProductId()))
                .sorted(Comparator.comparing(Entitlement::getStartsAt))
                .toList();
    }

    /** Makes a member's paid access lapse "now minus a little" without waiting. */
    protected void lapse(Classroom c, User u) {
        ClassMember m = row(c, u);
        m.setAccessExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        memberRepository.save(m);
    }

    protected ErrorCode codeOf(org.junit.jupiter.api.function.Executable action) {
        return assertThrows(AppException.class, action).getErrorCode();
    }

    protected void assertCode(ErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, codeOf(action));
    }

    protected void grantStaff(Classroom c, User owner, User staff, String... moduleActions) {
        if (row(c, staff) == null) addActive(c, staff);
        List<StaffPermissionDto> perms = java.util.Arrays.stream(moduleActions)
                .map(s -> new StaffPermissionDto(s.split(":")[0], s.split(":")[1], null)).toList();
        staffService.assignStaff(c.getId(), staff.getId(), perms, owner.getId());
    }

    protected List<OutboxEvent> events(Classroom c, String type) {
        return outboxEventRepository.findAll().stream()
                .filter(e -> type.equals(e.getEventType()) && c.getId().equals(e.getAggregateId()))
                .toList();
    }

    protected List<AuditEvent> audits(Classroom c, String action) {
        return auditEventRepository.findByClassIdOrderByCreatedAtDesc(c.getId()).stream()
                .filter(a -> action.equals(a.getAction())).toList();
    }
}
