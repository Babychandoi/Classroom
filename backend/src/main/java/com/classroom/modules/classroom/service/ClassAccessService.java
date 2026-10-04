package com.classroom.modules.classroom.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.dto.ClassAccessProductDto;
import com.classroom.modules.classroom.dto.UpdateClassAccessRequest;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.classroom.repository.StaffAssignmentRepository;
import com.classroom.modules.commerce.model.Entitlement;
import com.classroom.modules.commerce.model.Order;
import com.classroom.modules.commerce.model.Product;
import com.classroom.modules.commerce.model.ProductPrice;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import com.classroom.modules.commerce.repository.ProductPriceRepository;
import com.classroom.modules.commerce.repository.ProductRepository;
import com.classroom.modules.commerce.service.CommerceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * D-19: how a class is paid for - the class-access product, who may buy it, and what settling / refunding it does to the membership.
 *
 * <p>Design in one paragraph: a PAID class owns exactly one product of kind {@code CLASS_ACCESS} ({@link Classroom#getAccessProductId()}).
 * Buying it goes through the ordinary order / webhook / entitlement machinery (D-02, D-03: stacking renewals, snapshot prices, lock order
 * order -&gt; product -&gt; entitlements); this service only adds the two extra steps that make the entitlement mean "membership":
 * after the entitlement is granted (settle) or revoked (refund) the member row is recomputed from the entitlement chain, in the same
 * transaction, as the LAST lock (order -&gt; product -&gt; entitlements -&gt; member row). Lifetime access is an entitlement ending at
 * {@link #LIFETIME_END} and a member row with no expiry.</p>
 */
@Service
public class ClassAccessService {

    /** Entitlement end of a lifetime purchase (a sentinel, compared exactly): the member row then has no expiry. */
    public static final Instant LIFETIME_END = Instant.parse("9999-12-31T00:00:00Z");
    /** Only VND is sold today (the sandbox provider and the UI are VND-only). */
    public static final String CURRENCY = "VND";
    public static final String NOT_FOUND_MESSAGE = "Không tìm thấy lớp học";

    private final ClassroomRepository classroomRepository;
    private final ClassMemberRepository memberRepository;
    private final StaffAssignmentRepository staffAssignmentRepository;
    private final ProductRepository productRepository;
    private final ProductPriceRepository priceRepository;
    private final EntitlementRepository entitlementRepository;
    private final AccessPolicy accessPolicy;
    private final AuditService auditService;
    private final ClassMembershipService membershipService;
    private final ClassInviteLedger inviteLedger;

    public ClassAccessService(ClassroomRepository classroomRepository,
                              ClassMemberRepository memberRepository,
                              StaffAssignmentRepository staffAssignmentRepository,
                              ProductRepository productRepository,
                              ProductPriceRepository priceRepository,
                              EntitlementRepository entitlementRepository,
                              AccessPolicy accessPolicy,
                              AuditService auditService,
                              ClassMembershipService membershipService,
                              ClassInviteLedger inviteLedger) {
        this.classroomRepository = classroomRepository;
        this.memberRepository = memberRepository;
        this.staffAssignmentRepository = staffAssignmentRepository;
        this.productRepository = productRepository;
        this.priceRepository = priceRepository;
        this.entitlementRepository = entitlementRepository;
        this.accessPolicy = accessPolicy;
        this.auditService = auditService;
        this.membershipService = membershipService;
        this.inviteLedger = inviteLedger;
    }

    // ------------------------------------------------------------------------------------------------------------------------ read model

    /** The product a PAID class sells, for its DTO; {@code null} for a FREE class (or a PAID one whose product cannot be found). */
    @Transactional(readOnly = true)
    public ClassAccessProductDto accessProductOf(Classroom classroom) {
        if (classroom == null || !classroom.isPaid() || classroom.getAccessProductId() == null) {
            return null;
        }
        return priceRepository.findByProductId(classroom.getAccessProductId())
                .map(price -> toDto(classroom.getAccessProductId(), price))
                .orElse(null);
    }

    /** {@link #accessProductOf} for a page of classes with ONE price query (class id -&gt; product). FREE classes are absent. */
    @Transactional(readOnly = true)
    public Map<String, ClassAccessProductDto> accessProductsOf(Collection<Classroom> classrooms) {
        Map<String, ClassAccessProductDto> result = new HashMap<>();
        Map<String, String> productByClass = new HashMap<>();
        for (Classroom c : classrooms) {
            if (c.isPaid() && c.getAccessProductId() != null) {
                productByClass.put(c.getId(), c.getAccessProductId());
            }
        }
        if (productByClass.isEmpty()) {
            return result;
        }
        Map<String, ProductPrice> priceByProduct = new HashMap<>();
        for (ProductPrice price : priceRepository.findByProductIdIn(Set.copyOf(productByClass.values()))) {
            priceByProduct.put(price.getProductId(), price);
        }
        productByClass.forEach((classId, productId) -> {
            ProductPrice price = priceByProduct.get(productId);
            if (price != null) {
                result.put(classId, toDto(productId, price));
            }
        });
        return result;
    }

    private static ClassAccessProductDto toDto(String productId, ProductPrice price) {
        Integer days = price.getDurationDays() <= 0 ? null : price.getDurationDays();
        return new ClassAccessProductDto(productId, price.getPrice(), price.getCurrency(), days);
    }

    /** 402 PAYMENT_REQUIRED carrying the class-access product, so the UI can start checkout without another round trip. */
    public AppException paymentRequired(Classroom classroom) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("classId", classroom.getId());
        details.put("accessType", Classroom.ACCESS_PAID);
        ClassAccessProductDto product = accessProductOf(classroom);
        if (product != null) {
            details.put("accessProduct", product);
        }
        return new AppException(ErrorCode.PAYMENT_REQUIRED, ErrorCode.PAYMENT_REQUIRED.getDefaultMessage(), details);
    }

    // ---------------------------------------------------------------------------------------------------------- PUT /classes/{id}/access

    /** Who may change how a class is paid for: the owner, or staff holding BOTH STORE:EDIT (money) and CLASS:EDIT (settings). */
    public boolean canConfigureAccess(String userId, String classId) {
        return accessPolicy.isOwner(userId, classId)
                || (accessPolicy.canManage(userId, classId, "STORE", "EDIT", null)
                && accessPolicy.canManage(userId, classId, "CLASS", "EDIT", null));
    }

    /**
     * Switches a class between FREE and PAID and (re)prices it. Lock-first at READ_COMMITTED: the class row FOR UPDATE, then the
     * class-access product row FOR UPDATE, so two concurrent changes - and a change racing the conversion clean-up - serialise; nothing
     * else locks a product and then the class row.
     *
     * <ul>
     *   <li>FREE -&gt; PAID: a PUBLISHED {@code CLASS_ACCESS} product is created (or the archived one of an earlier paid period is
     *   restored and re-priced - one product per class for good). Everybody who is a member NOW keeps access for ever
     *   (<b>grandfathering</b>: their {@code access_expires_at} is NULL and stays NULL) - only newcomers pay.</li>
     *   <li>PAID -&gt; PAID: price / duration change. Affects only NEW orders (prices are snapshotted into the order item, D-03).</li>
     *   <li>PAID -&gt; FREE: the product is ARCHIVED; every ACTIVE member loses their expiry date (nobody loses anything); EXPIRED
     *   members become ACTIVE again only when they (re)join.</li>
     * </ul>
     * Audited as {@code CLASS_ACCESS_UPDATE} with the before / after values.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Classroom changeAccess(String classId, UpdateClassAccessRequest request, String actorId) {
        if (!canConfigureAccess(actorId, classId)) {
            throw new AppException(ErrorCode.STAFF_PERMISSION_DENIED,
                    "Chỉ chủ lớp hoặc nhân sự có đồng thời quyền STORE:EDIT và CLASS:EDIT mới được thay đổi hình thức thu phí của lớp");
        }
        accessPolicy.enforceNotSuspended(classId); // D-29: a suspended class is read-only, also for its owner
        Classroom classroom = classroomRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE));
        String target = request.getAccessType().trim().toUpperCase(Locale.ROOT);
        String before = describe(classroom);

        if (Classroom.ACCESS_PAID.equals(target)) {
            applyPaid(classroom, request);
        } else if (classroom.isPaid()) {
            applyFree(classroom);
        } else {
            return classroom; // FREE -> FREE: nothing to change, nothing to audit
        }
        Classroom saved = classroomRepository.save(classroom);
        auditService.record(classId, actorId, "CLASS_ACCESS_UPDATE", "CLASSROOM", classId,
                "{\"before\":" + before + ",\"after\":" + describe(saved) + "}");
        return saved;
    }

    private void applyPaid(Classroom classroom, UpdateClassAccessRequest request) {
        String currency = request.getCurrency() == null || request.getCurrency().isBlank()
                ? CURRENCY : request.getCurrency().trim().toUpperCase(Locale.ROOT);
        if (!CURRENCY.equals(currency)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Hiện chỉ hỗ trợ thanh toán bằng VND");
        }
        CommerceService.validatePrice(request.getPrice(), currency);
        Integer days = request.getDurationDays();
        int durationDays;
        if (days == null || days == 0) {
            durationDays = 0; // lifetime
        } else if (days < 0 || days > CommerceService.MAX_PRODUCT_DURATION_DAYS) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Thời hạn truy cập phải từ 1 đến "
                    + CommerceService.MAX_PRODUCT_DURATION_DAYS + " ngày, hoặc để trống nếu truy cập trọn đời");
        } else {
            durationDays = days;
        }

        String title = "Quyền truy cập lớp học: " + classroom.getTitle();
        if (title.length() > 255) title = title.substring(0, 255);

        Product product = classroom.getAccessProductId() == null ? null
                : productRepository.findByIdForUpdate(classroom.getAccessProductId()).orElse(null);
        if (product == null) {
            product = new Product(classroom.getId(), null, title, "Mua để trở thành thành viên của lớp học");
            product.setKind(Product.KIND_CLASS_ACCESS);
            product.setStatus("PUBLISHED");
            product = productRepository.save(product);
            priceRepository.save(new ProductPrice(product.getId(), request.getPrice(), currency, durationDays, Instant.EPOCH));
        } else {
            product.setTitle(title);
            product.setStatus("PUBLISHED");
            product.setUpdatedAt(Instant.now());
            productRepository.save(product);
            ProductPrice price = priceRepository.findByProductId(product.getId()).orElse(null);
            if (price == null) {
                price = new ProductPrice();
                price.setProductId(product.getId());
            }
            price.setPrice(request.getPrice());
            price.setCurrency(currency);
            price.setDurationDays(durationDays);
            price.setAccessStartsAt(Instant.EPOCH);
            priceRepository.save(price);
        }
        classroom.setAccessType(Classroom.ACCESS_PAID);
        classroom.setAccessProductId(product.getId());
    }

    private void applyFree(Classroom classroom) {
        if (classroom.getAccessProductId() != null) {
            productRepository.findByIdForUpdate(classroom.getAccessProductId()).ifPresent(product -> {
                product.setStatus("ARCHIVED");
                product.setUpdatedAt(Instant.now());
                productRepository.save(product);
            });
        }
        memberRepository.clearAccessExpiryOfActiveMembers(classroom.getId());
        classroom.setAccessType(Classroom.ACCESS_FREE);
    }

    /** {"accessType":..., "productId":..., "price":..., "currency":..., "durationDays":...} - numbers and enum-like strings only. */
    private String describe(Classroom classroom) {
        if (!classroom.isPaid()) {
            return "{\"accessType\":\"FREE\"}";
        }
        ClassAccessProductDto p = accessProductOf(classroom);
        if (p == null) {
            return "{\"accessType\":\"PAID\"}";
        }
        return String.format(Locale.ROOT, "{\"accessType\":\"PAID\",\"productId\":\"%s\",\"price\":%s,\"currency\":\"%s\",\"durationDays\":%s}",
                p.getId(), p.getPrice().toPlainString(), p.getCurrency(), p.getDurationDays() == null ? "null" : p.getDurationDays());
    }

    // -------------------------------------------------------------------------------------------------------------------- buying access

    /**
     * Order creation for a class-access product (called with the product row already locked). Refuses everything that must not buy:
     * a class that does not sell this product (FREE, or another product); the owner and staff (never pay); a BLOCKED person; a member
     * who already has access with no end (grandfathered / lifetime / a free-class member). Renewal by an ACTIVE member with a running
     * term, or by an EXPIRED member, is always allowed. A person who is NOT on the roster (never joined, or REMOVED) may only buy into a
     * PRIVATE class with a valid invite - the use is reserved here and given back if the order is cancelled or fails.
     *
     * @return the id of the reserved invite to store on the order, or {@code null}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String validateBuyerAndReserveInvite(String buyerId, String classId, Product product, String inviteCode, Instant now) {
        Classroom classroom = classroomRepository.findById(classId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE));
        if (!classroom.isPaid() || !product.getId().equals(classroom.getAccessProductId())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Gói truy cập lớp học này hiện không được bán");
        }
        if (accessPolicy.isOwner(buyerId, classId)
                || staffAssignmentRepository.findByClassIdAndUserId(classId, buyerId)
                .map(a -> "ACTIVE".equalsIgnoreCase(a.getStatus())).orElse(false)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Chủ lớp và nhân sự không cần mua quyền truy cập lớp học");
        }
        ClassMember member = memberRepository.findByClassIdAndUserId(classId, buyerId).orElse(null);
        if (member != null && ClassMembershipService.isBlockedState(member.getState())) {
            throw new AppException(ErrorCode.FORBIDDEN, ClassMembershipService.BLOCKED_MESSAGE);
        }
        if (member != null && member.isActiveAt(now) && member.getAccessExpiresAt() == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Bạn đã có quyền truy cập lớp học không giới hạn thời gian");
        }
        String effective = member == null ? null : member.effectiveState(now);
        boolean onRoster = "ACTIVE".equalsIgnoreCase(effective) || "EXPIRED".equalsIgnoreCase(effective);
        if (classroom.isPrivate() && !onRoster) {
            if (inviteCode == null || inviteCode.isBlank()) {
                throw new AppException(ErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE); // a private class does not exist for a stranger
            }
            return inviteLedger.reserveForPurchase(inviteCode.trim(), classId, now);
        }
        return null;
    }

    /**
     * A REMOVED (or otherwise lapsed-row) person of a PAID class who still holds a RUNNING class-access entitlement gets their access back
     * without paying twice: a purchase survives removal from the class (D-12), it just cannot be used meanwhile. Lock order as the settle path:
     * entitlement rows first, then the member row.
     *
     * @return true when the person is an ACTIVE member afterwards
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean restoreIfStillPaid(Classroom classroom, String userId, Instant now) {
        if (classroom == null || !classroom.isPaid() || classroom.getAccessProductId() == null) {
            return false;
        }
        List<Entitlement> chain = entitlementRepository.findLatestActiveByProductForUpdate(
                userId, classroom.getId(), classroom.getAccessProductId(), now);
        if (chain == null || chain.isEmpty() || chain.stream().noneMatch(e -> !e.getStartsAt().isAfter(now))) {
            return false;
        }
        return membershipService.grantPaidAccess(classroom.getId(), userId, normalizeChainEnd(chain.get(0).getExpiresAt()));
    }

    /** An order that reserved an invite use was cancelled / failed: give the use back. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseInviteUse(Order order) {
        if (order != null && order.getInviteId() != null) {
            inviteLedger.release(order.getInviteId());
        }
    }

    /**
     * The class-access entitlement of {@code order} was just granted (caller holds the order, product and entitlement locks): make the buyer
     * an ACTIVE member until the end of their entitlement chain. A class that turned FREE in the meantime still gets the buyer in (they
     * paid), with no expiry. A BLOCKED buyer is not let in - the purchase is flagged in the audit log for an operator (refund or unblock).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAccessPurchased(Order order, Product product, Instant now) {
        Classroom classroom = classroomRepository.findById(order.getClassId()).orElse(null);
        if (classroom == null) return;
        Instant accessExpiresAt = null;
        if (classroom.isPaid()) {
            accessExpiresAt = chainEnd(order.getBuyerId(), order.getClassId(), product.getId(), now);
        }
        boolean granted = membershipService.grantPaidAccess(order.getClassId(), order.getBuyerId(), accessExpiresAt);
        if (!granted) {
            auditService.record(order.getClassId(), order.getBuyerId(), "CLASS_ACCESS_PAID_WHILE_BLOCKED", "ORDER", order.getId(),
                    "{\"note\":\"Thanh toán quyền truy cập của thành viên đang bị chặn; cần hoàn tiền hoặc mở khóa thủ công\"}");
        }
    }

    /**
     * The class-access entitlement of a refunded order was revoked and the rest of the chain re-chained (caller holds all locks): recompute the
     * member row from what is left. Nothing left -&gt; EXPIRED at once. A FREE class is left alone (nobody loses anything there).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAccessRefunded(Order order, Product product, Instant now) {
        Classroom classroom = classroomRepository.findById(order.getClassId()).orElse(null);
        if (classroom == null || !classroom.isPaid()) return;
        List<Entitlement> chain = entitlementRepository.findLatestActiveByProductForUpdate(
                order.getBuyerId(), order.getClassId(), product.getId(), now);
        boolean hasAccess = chain != null && !chain.isEmpty();
        Instant end = hasAccess ? normalizeChainEnd(chain.get(0).getExpiresAt()) : null;
        boolean lostLifetime = entitlementRepository.findByOrderId(order.getId()).stream()
                .anyMatch(e -> product.getId().equals(e.getProductId()) && e.getExpiresAt() != null && !e.getExpiresAt().isBefore(LIFETIME_END));
        membershipService.reconcileAfterRefund(order.getClassId(), order.getBuyerId(), hasAccess, end, lostLifetime);
    }

    /** End of the buyer's running entitlement chain for the product; {@code null} = lifetime (or no chain). */
    private Instant chainEnd(String buyerId, String classId, String productId, Instant now) {
        List<Entitlement> chain = entitlementRepository.findLatestActiveByProductForUpdate(buyerId, classId, productId, now);
        if (chain == null || chain.isEmpty()) return null;
        return normalizeChainEnd(chain.get(0).getExpiresAt());
    }

    private static Instant normalizeChainEnd(Instant expiresAt) {
        return expiresAt == null || !expiresAt.isBefore(LIFETIME_END) ? null : expiresAt;
    }

    /** Ids of the class-access products among {@code productIds} (used by the webhook to decide which items need the membership step). */
    @Transactional(readOnly = true)
    public Set<String> classAccessProductIds(Collection<String> productIds) {
        return productRepository.findAllById(productIds).stream()
                .filter(Product::isClassAccess).map(Product::getId).collect(Collectors.toSet());
    }

}
