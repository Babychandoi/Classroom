package com.classroom.modules.commerce.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.policy.AccessPolicy;
import com.classroom.modules.classroom.service.ClassAccessService;
import com.classroom.modules.commerce.dto.*;
import com.classroom.modules.commerce.model.*;
import com.classroom.modules.commerce.payment.PaymentProvider;
import com.classroom.modules.commerce.repository.*;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.outbox.service.OutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class CommerceService {
    private static final Logger log = LoggerFactory.getLogger(CommerceService.class);

    public static final int MAX_PRODUCT_DURATION_DAYS = 3650;

    /**
     * R19-03: the furthest into the future an entitlement (or a product's access start) may reach.
     * Stacked renewals of a long product used to be limited only by the old TIMESTAMP column range
     * (year 2038); the columns are DATETIME(6) since V30, and this business ceiling keeps a runaway
     * chain (or a typo'd access start) from reaching an absurd date.
     */
    public static final int MAX_ENTITLEMENT_HORIZON_YEARS = 50;

    /** R19-01: a deadlock victim / duplicate-key loser is retried this many times in total before the webhook answers 5xx. */
    static final int MAX_WEBHOOK_ATTEMPTS = 3;

    /** Largest DECIMAL(12,2) price: 10 integer digits. */
    static final BigDecimal MAX_PRICE = new BigDecimal("9999999999.99");

    private final ProductRepository productRepository;
    private final ProductPriceRepository priceRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final EntitlementRepository entitlementRepository;
    private final CourseRepository courseRepository;
    private final AccessPolicy accessPolicy;
    private final PaymentProvider paymentProvider;
    private final List<PaymentProvider> paymentProviders;
    private final OutboxService outboxService;
    private final AuditService auditService;

    @Value("${classroom.payment.mock.checkout.enabled:false}")
    private boolean mockCheckoutEnabled;

    /**
     * Self-reference through the Spring proxy, so the transactional boundaries of
     * {@code createOrderTransactional} and {@code findReplayableOrder} are honoured when
     * {@code createOrder} calls them. Absent outside a Spring context (plain unit tests), where
     * {@link #self()} falls back to {@code this} and the calls are simply direct.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private CommerceService selfProxy;

    /**
     * D-19: the membership side of the class-access product (who may buy it, what settling / refunding it does to the member row). Field
     * injection and optional, like {@link #selfProxy}: a plain unit test that builds the service by hand simply has no class-access behaviour.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private ClassAccessService classAccessService;

    private CommerceService self() {
        return selfProxy != null ? selfProxy : this;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CommerceService(ProductRepository productRepository,
                           ProductPriceRepository priceRepository,
                           OrderRepository orderRepository,
                           OrderItemRepository orderItemRepository,
                           EntitlementRepository entitlementRepository,
                           CourseRepository courseRepository,
                           AccessPolicy accessPolicy,
                           PaymentProvider paymentProvider,
                           @org.springframework.beans.factory.annotation.Autowired(required = false) List<PaymentProvider> paymentProviders,
                           OutboxService outboxService,
                           AuditService auditService) {
        this.productRepository = productRepository;
        this.priceRepository = priceRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.entitlementRepository = entitlementRepository;
        this.courseRepository = courseRepository;
        this.accessPolicy = accessPolicy;
        this.paymentProvider = paymentProvider;
        if (paymentProviders != null && !paymentProviders.isEmpty()) {
            this.paymentProviders = paymentProviders;
        } else if (paymentProvider != null) {
            this.paymentProviders = List.of(paymentProvider);
        } else {
            this.paymentProviders = List.of();
        }
        this.outboxService = outboxService;
        this.auditService = auditService;
    }

    public CommerceService(ProductRepository productRepository,
                           ProductPriceRepository priceRepository,
                           OrderRepository orderRepository,
                           OrderItemRepository orderItemRepository,
                           EntitlementRepository entitlementRepository,
                           CourseRepository courseRepository,
                           AccessPolicy accessPolicy,
                           PaymentProvider paymentProvider,
                           OutboxService outboxService,
                           AuditService auditService) {
        this(productRepository, priceRepository, orderRepository, orderItemRepository,
                entitlementRepository, courseRepository, accessPolicy,
                paymentProvider, (paymentProvider != null ? List.of(paymentProvider) : null),
                outboxService, auditService);
    }

    /**
     * R4-06: a non-ACTIVE (e.g. draft/archived) class's product listing must be hidden from
     * viewers who cannot see the class itself, anonymous callers included — previously this
     * skipped the visibility check entirely for anonymous requests.
     */
    @Transactional(readOnly = true)
    public List<ProductDto> getProductsByClass(String classId, String userId) {
        if (!accessPolicy.isClassVisibleToUser(classId, userId)) {
            if (accessPolicy.isMissingOrHiddenPrivateClass(classId, userId)) {
                // D-19: an unknown class and a PRIVATE class this viewer has no relation to are the same 404.
                throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy lớp học");
            }
            if (userId == null) {
                throw new AppException(ErrorCode.UNAUTHORIZED, "Yêu cầu đăng nhập để xem sản phẩm của lớp học này");
            }
            throw new AppException(ErrorCode.FORBIDDEN, "Bạn không có quyền truy cập sản phẩm của lớp học không công khai này");
        }
        return listProducts(classId, userId, false);
    }

    @Transactional(readOnly = true)
    public List<ProductDto> getStudioProducts(String classId, String userId) {
        if (!accessPolicy.isOwner(userId, classId)
                && !accessPolicy.canManage(userId, classId, "STORE", "VIEW", null)
                && !accessPolicy.canManage(userId, classId, "STORE", "CREATE", null)
                && !accessPolicy.canManage(userId, classId, "STORE", "EDIT", null)
                && !accessPolicy.canManage(userId, classId, "STORE", "PUBLISH", null)) {
            throw new AppException(ErrorCode.FORBIDDEN, "Không có quyền xem sản phẩm Studio");
        }
        return listProducts(classId, userId, true);
    }

    private List<ProductDto> listProducts(String classId, String userId, boolean includeDrafts) {
        List<Product> products = includeDrafts ? productRepository.findByClassIdOrderByCreatedAtDesc(classId)
                : productRepository.findByClassIdAndStatusOrderByCreatedAtDesc(classId, "PUBLISHED");
        List<ProductDto> dtos = new ArrayList<>();
        Instant now = Instant.now();

        // R19-06: the viewer's paid, not-yet-lapsed entitlements for the whole class, grouped by product
        // (one query instead of one per product). "Not lapsed" deliberately includes an entitlement whose
        // startsAt is still in the future: a pre-sale purchase is owned even though it is not running yet.
        Map<String, List<Entitlement>> ownedByProduct = new HashMap<>();
        if (userId != null) {
            for (Entitlement e : entitlementRepository.findByUserIdAndClassId(userId, classId)) {
                if ("ACTIVE".equalsIgnoreCase(e.getState()) && e.getExpiresAt().isAfter(now)) {
                    ownedByProduct.computeIfAbsent(e.getProductId(), k -> new ArrayList<>()).add(e);
                }
            }
        }

        for (Product p : products) {
            ProductDto dto = toProductDto(p);

            // Fetch pricing
            priceRepository.findByProductId(p.getId()).ifPresent(pr -> {
                dto.setPrice(pr.getPrice());
                dto.setCurrency(pr.getCurrency());
                dto.setDurationDays(pr.getDurationDays());
                dto.setAccessStartsAt(pr.getAccessStartsAt());
            });

            // Fetch target course info if present
            if (p.getTargetCourseId() != null) {
                courseRepository.findById(p.getTargetCourseId()).ifPresent(c -> dto.setTargetCourseTitle(c.getTitle()));
            }

            // How far the viewer's access is already paid for, and whether it is running yet.
            //
            // "Has access now" is the currently-running entitlement (startsAt <= now < expiresAt). A
            // renewal, however, is stacked with a future startsAt (see grantEntitlements), so reading the
            // expiry off the running row would tell a buyer who just renewed that their access ends at the
            // *old* expiry: the displayed expiry is the end of the whole non-lapsed chain instead.
            //
            // R19-06: a buyer whose ONLY entitlement starts in the future (pre-sale purchase, product
            // accessStartsAt ahead of now) owns the product but has no access yet. That used to look exactly
            // like "not owned", so the store kept offering "Mua ngay" and a second purchase; it is now
            // reported as userOwnsUpcoming + entitlementStartsAt so the UI can say "Đã mua - bắt đầu từ ...".
            List<Entitlement> owned = ownedByProduct.getOrDefault(p.getId(), List.of());
            if (!owned.isEmpty()) {
                boolean runningNow = owned.stream().anyMatch(e -> !e.getStartsAt().isAfter(now));
                owned.stream().map(Entitlement::getExpiresAt).max(Comparator.naturalOrder())
                        .ifPresent(dto::setEntitlementExpiresAt);
                if (runningNow) {
                    dto.setUserHasActiveEntitlement(true);
                } else {
                    dto.setUserOwnsUpcoming(true);
                    owned.stream().map(Entitlement::getStartsAt).min(Comparator.naturalOrder())
                            .ifPresent(dto::setEntitlementStartsAt);
                }
            }

            dtos.add(dto);
        }

        return dtos;
    }

    /**
     * Creates (or replays) an order for a buyer.
     *
     * <p>Not transactional itself: the insert runs in {@link #createOrderTransactional}, so the
     * unique-key violation raised when a concurrent request with the same idempotency key wins the
     * race is observed <em>after</em> that transaction has already rolled back. The recovery lookup
     * then runs in a fresh transaction instead of being issued on a connection already marked
     * rollback-only.</p>
     */
    public OrderDto createOrder(String buyerId, CreateOrderRequest request) {
        try {
            return self().createOrderTransactional(buyerId, request);
        } catch (DataIntegrityViolationException e) {
            String key = normalizeIdempotencyKey(request == null ? null : request.getIdempotencyKey());
            if (key != null) {
                Optional<OrderDto> raceWinner = self().findReplayableOrder(key, buyerId, request);
                if (raceWinner.isPresent()) return raceWinner.get();
            }
            throw new AppException(ErrorCode.BAD_REQUEST, "Đơn hàng bị xung đột khi tạo đồng thời, vui lòng thử lại");
        }
    }

    /** Normalizes an idempotency key exactly once; a blank key means "no idempotency". */
    private static String normalizeIdempotencyKey(String rawKey) {
        if (rawKey == null) return null;
        String trimmed = rawKey.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Returns the stored order for a normalized idempotency key when it replays the same purchase.
     * Runs in its own transaction so it is usable as concurrent-race recovery.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<OrderDto> findReplayableOrder(String normalizedKey, String buyerId, CreateOrderRequest request) {
        Optional<Order> existingOrder = orderRepository.findByIdempotencyKey(normalizedKey);
        if (existingOrder.isEmpty()) return Optional.empty();
        Order order = existingOrder.get();
        if (!order.getBuyerId().equals(buyerId)) {
            throw new AppException(ErrorCode.FORBIDDEN, "Khóa idempotency không thuộc về tài khoản này");
        }
        boolean sameRequest = order.getClassId().equals(request.getClassId())
                && orderItemRepository.findByOrderId(order.getId()).stream()
                .anyMatch(item -> item.getProductId().equals(request.getProductId()));
        if (!sameRequest) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Khóa idempotency đã được dùng cho yêu cầu mua khác");
        }
        return Optional.of(toOrderDto(order));
    }

    /**
     * R19-01: READ_COMMITTED so the product-row lock below is followed by reads of the LATEST
     * committed product price / entitlement chain. At MySQL's default REPEATABLE READ the idempotency
     * lookup above the lock fixes the transaction's snapshot first, so an order that waited for a
     * concurrent price edit (or renewal) to commit would still be priced from the pre-edit snapshot.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderDto createOrderTransactional(String buyerId, CreateOrderRequest request) {
        if (paymentProvider == null || ("MOCK".equalsIgnoreCase(paymentProvider.getProviderCode()) && !mockCheckoutEnabled)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Thanh toán hiện chưa được cấu hình; chưa thể tạo đơn hàng");
        }
        // Enforce buyer is an active member of the classroom (Finding 9).
        // D-19: the ONE exception is the class-access product - it is what turns a non-member (or an EXPIRED member) into a member, so its
        // buyer is validated by ClassAccessService after the product row is locked. The kind is read here without a lock; it never changes.
        boolean classAccessOrder = isClassAccessProduct(request.getProductId());
        if (!classAccessOrder) {
            accessPolicy.enforceMember(buyerId, request.getClassId());
        }
        // D-29: a SUSPENDED class is hidden from everybody but its owner - the class-access path (which skips enforceMember) answers the
        // same 404 as for a class that does not exist.
        if (classAccessOrder && accessPolicy.isClassSuspended(request.getClassId()) && !accessPolicy.isOwner(buyerId, request.getClassId())) {
            throw new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy lớp học");
        }

        // Idempotency check scoped to buyer (Finding 6 & 7)
        // The key is normalized exactly once here and that same normalized value is both looked up
        // and stored, so a retry differing only in surrounding whitespace resolves to its original
        // order instead of creating a second one.
        String idempotencyKey = normalizeIdempotencyKey(request.getIdempotencyKey());
        if (idempotencyKey == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Yêu cầu tạo đơn hàng bắt buộc phải có Idempotency-Key");
        }

        Optional<Order> existingOrder = orderRepository.findByIdempotencyKey(idempotencyKey);
        if (existingOrder.isPresent()) {
            Order order = existingOrder.get();
            if (!order.getBuyerId().equals(buyerId)) {
                throw new AppException(ErrorCode.FORBIDDEN, "Khóa idempotency không thuộc về tài khoản này");
            }
            boolean sameRequest = order.getClassId().equals(request.getClassId())
                    && orderItemRepository.findByOrderId(order.getId()).stream()
                    .anyMatch(item -> item.getProductId().equals(request.getProductId()));
            if (!sameRequest) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Khóa idempotency đã được dùng cho yêu cầu mua khác");
            }
            return toOrderDto(order);
        }

        // R14-13 (D-11): an ARCHIVED class is frozen for new activity. A replay of an order that
        // already exists was returned above; only creating a NEW order is refused here.
        // D-29: the same freeze for a class SUSPENDED by a platform admin (not ACTIVE = frozen).
        if (accessPolicy.isClassFrozen(request.getClassId())) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    (accessPolicy.isClassSuspended(request.getClassId()) ? AccessPolicy.SUSPENDED_MESSAGE : "Lớp học đã được lưu trữ; không thể tạo đơn hàng mới"));
        }

        // Serialize purchase creation against course association changes.
        Product product = productRepository.findByIdForUpdate(request.getProductId())
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy sản phẩm"));

        if (!product.getClassId().equals(request.getClassId())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Sản phẩm không thuộc lớp học được chỉ định");
        }

        // Enforce product is PUBLISHED before allowing purchase (Finding 9)
        if (!"PUBLISHED".equalsIgnoreCase(product.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Sản phẩm chưa được mở bán");
        }

        ProductPrice price = priceRepository.findByProductId(product.getId())
                .orElseThrow(() -> new AppException(ErrorCode.BAD_REQUEST, "Sản phẩm chưa được thiết lập giá bán"));

        // R19-03: refuse an order whose access could not be granted once paid (the buyer's stacked chain plus
        // this purchase would run past the horizon). Rejecting it here is the friendly path; the settle step
        // re-checks against the chain as it is at payment time.
        Instant nowForHorizon = Instant.now();
        String reservedInviteId = null;
        if (product.isClassAccess()) {
            // D-19: who may buy the class-access product (never owner/staff/blocked/perpetual members; strangers need an invite for a
            // PRIVATE class, and a use of it is reserved here). A lifetime product has no end to check against the horizon.
            if (classAccessService == null) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Thanh toán hiện chưa được cấu hình; chưa thể tạo đơn hàng");
            }
            reservedInviteId = classAccessService.validateBuyerAndReserveInvite(
                    buyerId, request.getClassId(), product, request.getInviteCode(), nowForHorizon);
        }
        if (!product.isClassAccess() || price.getDurationDays() > 0) {
            List<Entitlement> currentChain = entitlementRepository.findLatestActiveByProduct(
                    buyerId, request.getClassId(), product.getId(), nowForHorizon);
            Instant chainEnd = (currentChain == null || currentChain.isEmpty()) ? null : currentChain.get(0).getExpiresAt();
            computeGrant(nowForHorizon, chainEnd, price.getAccessStartsAt(), price.getDurationDays()); // 400 when past the horizon
        }

        String orderNumber = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        Order order = new Order(
                orderNumber,
                buyerId,
                request.getClassId(),
                price.getPrice(),
                price.getCurrency(),
                paymentProvider.getProviderCode()
        );
        order.setIdempotencyKey(idempotencyKey);
        order.setInviteId(reservedInviteId);
        // Flush now so a unique-key clash surfaces here rather than at commit. A concurrent request
        // with the same key that raced through the pre-check rolls this transaction back and is
        // recovered by createOrder, outside the poisoned transaction.
        Order savedOrder = orderRepository.saveAndFlush(order);

        OrderItem item = new OrderItem(
                savedOrder.getId(),
                product.getId(),
                product.getTitle(),
                price.getPrice(),
                price.getDurationDays(),
                price.getAccessStartsAt(),
                product.getTargetCourseId()
        );
        orderItemRepository.save(item);

        return toOrderDto(savedOrder);
    }

    /**
     * D-19: the class-access product is owned by the class's access settings (PUT /classes/{id}/access creates, re-prices, publishes and
     * archives it together with the class's access type). Editing it through the general product endpoints would let a class say PAID while
     * its only product is archived (nobody could ever join) - so those endpoints refuse it.
     */
    private static void rejectClassAccessProductEdit(Product product) {
        if (product.isClassAccess()) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "Sản phẩm truy cập lớp học được quản lý trong cài đặt thu phí của lớp (PUT /classes/{id}/access)");
        }
    }

    /** D-19: whether the product is the class-access product of a paid class (plain read, the kind never changes after creation). */
    private boolean isClassAccessProduct(String productId) {
        if (productId == null || productId.isBlank()) return false;
        Optional<Product> product = productRepository.findById(productId);
        return product != null && product.map(Product::isClassAccess).orElse(false);
    }

    private PaymentProvider resolveProvider(String providerCode) {
        if (providerCode == null || providerCode.isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Thiếu mã nhà cung cấp thanh toán");
        }
        String normalized = providerCode.trim();
        if (paymentProviders != null) {
            for (PaymentProvider p : paymentProviders) {
                if (p != null && p.getProviderCode().equalsIgnoreCase(normalized)) {
                    return p;
                }
            }
        }
        if (paymentProvider != null && paymentProvider.getProviderCode().equalsIgnoreCase(normalized)) {
            return paymentProvider;
        }
        throw new AppException(ErrorCode.BAD_REQUEST, "Nhà cung cấp thanh toán không được hỗ trợ: " + providerCode);
    }

    public OrderDto handlePaymentWebhook(WebhookPayload payload, String rawBody, String signature) {
        return handlePaymentWebhook("MOCK", payload, rawBody, signature);
    }

    /**
     * Applies a payment-provider webhook (PAYMENT_SUCCESS / PAYMENT_REFUNDED / PAYMENT_FAILED).
     *
     * <p>Not transactional itself: the work runs in {@link #processPaymentWebhookTransactional}, so what
     * happens when that transaction FAILS is decided outside it (R19-01 / R19-03):</p>
     * <ul>
     *   <li>a deadlock victim, lock-wait timeout or duplicate-key loser is rolled back and simply
     *   re-run (bounded by {@link #MAX_WEBHOOK_ATTEMPTS}) - every attempt starts by re-locking and
     *   re-reading, so it is safe to repeat;</li>
     *   <li>any other persistence failure (for instance a value the schema rejects) used to surface as a
     *   generic 400, which a real provider treats as "never retry" while the order stayed PENDING for a
     *   payment that had actually been taken. It now answers <b>500 PAYMENT_SETTLE_FAILED</b> (the provider
     *   retries) and leaves an audit row plus a {@code PAYMENT_SETTLE_FAILED} outbox event in a separate
     *   transaction, so an operator can see and reconcile it;</li>
     *   <li>a purchase that can never be fulfilled (entitlement would run past the horizon) is answered
     *   400 with a clear message, and is recorded the same way.</li>
     * </ul>
     */
    public OrderDto handlePaymentWebhook(String providerCode, WebhookPayload payload, String rawBody, String signature) {
        for (int attempt = 1; ; attempt++) {
            try {
                return self().processPaymentWebhookTransactional(providerCode, payload, rawBody, signature);
            } catch (UnfulfillableOrderException ex) {
                recordWebhookFailure(providerCode, payload, "UNFULFILLABLE", ex.getMessage());
                throw ex;
            } catch (AppException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                if (attempt < MAX_WEBHOOK_ATTEMPTS && isRetryableWebhookFailure(ex)) {
                    log.warn("Payment webhook for order {} hit a retryable conflict (attempt {}/{}): {}",
                            payload != null ? payload.getOrderNumber() : "null", attempt, MAX_WEBHOOK_ATTEMPTS, ex.toString());
                    pauseBeforeWebhookRetry(attempt);
                    continue;
                }
                log.error("Payment webhook for order {} failed and was rolled back; the provider should retry",
                        payload != null ? payload.getOrderNumber() : "null", ex);
                recordWebhookFailure(providerCode, payload, "PERSISTENCE_FAILURE", ex.getClass().getSimpleName());
                throw new AppException(ErrorCode.PAYMENT_SETTLE_FAILED);
            }
        }
    }

    /** A rollback caused by lock contention, or by losing a unique-key race, is worth re-running verbatim. */
    private static boolean isRetryableWebhookFailure(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof PessimisticLockingFailureException || t instanceof org.springframework.dao.DuplicateKeyException) {
                return true;
            }
            if (t instanceof java.sql.SQLException sql && (sql.getErrorCode() == 1213 || sql.getErrorCode() == 1205
                    || sql.getErrorCode() == 1062)) { // deadlock, lock wait timeout, duplicate entry
                return true;
            }
            if (t.getCause() == t) break;
        }
        return false;
    }

    private static void pauseBeforeWebhookRetry(int attempt) {
        try {
            Thread.sleep(25L * attempt + (long) (Math.random() * 25));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Records that a webhook could not be applied. Runs in its own transaction (the webhook's own one has
     * already rolled back) and never throws: a failure to leave the breadcrumb must not mask the real error.
     */
    private void recordWebhookFailure(String providerCode, WebhookPayload payload, String reason, String detail) {
        try {
            self().recordWebhookFailureTransactional(providerCode, payload, reason, detail);
        } catch (RuntimeException ex) {
            log.error("Could not record failed payment webhook for order {}", payload != null ? payload.getOrderNumber() : "null", ex);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordWebhookFailureTransactional(String providerCode, WebhookPayload payload, String reason, String detail) {
        if (payload == null || payload.getOrderNumber() == null || payload.getOrderNumber().isBlank()) return;
        Optional<Order> orderOpt = orderRepository.findByOrderNumber(payload.getOrderNumber().trim());
        if (orderOpt == null || orderOpt.isEmpty()) return;
        Order order = orderOpt.get();
        String eventType = (payload.getEventType() != null && !payload.getEventType().isBlank())
                ? payload.getEventType().toUpperCase().trim() : "PAYMENT_SUCCESS";
        outboxService.recordEventIfNotExists("COMMERCE", order.getId(), "PAYMENT_SETTLE_FAILED", Map.of(
                "orderNumber", order.getOrderNumber(),
                "buyerId", order.getBuyerId(),
                "classId", order.getClassId(),
                "webhookEvent", eventType,
                "reason", reason
        ));
        auditService.record(order.getClassId(), order.getBuyerId(), "PAYMENT_SETTLE_FAILED", "ORDER", order.getId(),
                String.format("{\"orderNumber\":\"%s\",\"webhookEvent\":\"%s\",\"provider\":\"%s\",\"reason\":\"%s\",\"detail\":\"%s\"}",
                        jsonSafe(order.getOrderNumber()), jsonSafe(eventType), jsonSafe(providerCode), jsonSafe(reason), jsonSafe(detail)));
    }

    private static String jsonSafe(String value) {
        if (value == null) return "";
        String cleaned = value.replace('"', '\'').replace('\\', '/').replaceAll("[\\p{Cntrl}]", " ");
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }

    /**
     * The whole webhook, in ONE transaction at READ_COMMITTED.
     *
     * <p><b>Lock order (R19-01)</b> - identical for settle, refund and the renewal re-chain, and for any
     * other path that changes a buyer's entitlement chain: <i>order row</i> (FOR UPDATE, the first
     * statement of the transaction) -> <i>product rows</i> (FOR UPDATE, ascending id) -> <i>entitlement
     * rows</i> (FOR UPDATE, current read). Two webhooks for the same buyer+product therefore queue on
     * the product row, and - because every read after that point is either a locking read or runs at
     * READ_COMMITTED - the one that waited sees the chain the other one committed. At MySQL's default
     * REPEATABLE READ the plain reads that used to sit before the product lock ({@code findByProviderRef},
     * {@code findByOrderId}) froze the snapshot first, so the waiter stacked its entitlement on a stale
     * chain: two parallel purchases of a 30-day product granted the same 30 days twice, and a refund
     * racing a renewal left the renewal starting at the revoked period's old expiry.</p>
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderDto processPaymentWebhookTransactional(String providerCode, WebhookPayload payload, String rawBody, String signature) {
        PaymentProvider provider = resolveProvider(providerCode);

        // 1. Signature Verification using matched provider
        if (!provider.verifyWebhookSignature(rawBody, signature)) {
            log.warn("Invalid webhook signature for order: {} on provider: {}", payload != null ? payload.getOrderNumber() : "null", providerCode);
            throw new AppException(ErrorCode.INVALID_WEBHOOK_SIGNATURE);
        }

        if (payload == null || payload.getOrderNumber() == null || payload.getOrderNumber().isBlank()) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Thiếu mã đơn hàng trong thông tin webhook");
        }

        String orderNumber = payload.getOrderNumber().trim();

        // Concurrency & Serialized locking (Finding 3): lock the order row
        Optional<Order> orderOpt = orderRepository.findByOrderNumberForUpdate(orderNumber);
        if (orderOpt.isEmpty()) {
            orderOpt = orderRepository.findByOrderNumber(orderNumber);
        }
        Order order = orderOpt.orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn hàng: " + orderNumber));

        // Validate provider match against order's configured provider (Review 19 Finding 3)
        if (!order.getProvider().equalsIgnoreCase(providerCode.trim())) {
            log.warn("Provider mismatch for order {}: order expects {} but webhook received for {}", orderNumber, order.getProvider(), providerCode);
            throw new AppException(ErrorCode.BAD_REQUEST, "Nhà cung cấp thanh toán không khớp với đơn hàng");
        }

        String eventType = (payload.getEventType() != null && !payload.getEventType().isBlank())
                ? payload.getEventType().toUpperCase().trim()
                : "PAYMENT_SUCCESS";

        // A verified signature authenticates the sender, but does not prove this event settles
        // the persisted order's exact amount/currency. Require both before any successful-payment
        // transition (including duplicate replay handling).
        if ("PAYMENT_SUCCESS".equals(eventType)
                && (payload.getAmount() == null || payload.getCurrency() == null || payload.getCurrency().isBlank())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Webhook thanh toán thành công phải có số tiền và đơn vị tiền tệ");
        }

        // Provider transaction validation (Finding 3): amount & currency checks
        if (payload.getAmount() != null && order.getTotalAmount().compareTo(payload.getAmount()) != 0) {
            log.warn("Webhook amount mismatch for order {}: expected {} but received {}", orderNumber, order.getTotalAmount(), payload.getAmount());
            throw new AppException(ErrorCode.BAD_REQUEST, "Số tiền thanh toán không khớp với đơn hàng");
        }
        if (payload.getCurrency() != null && !order.getCurrency().equalsIgnoreCase(payload.getCurrency().trim())) {
            log.warn("Webhook currency mismatch for order {}: expected {} but received {}", orderNumber, order.getCurrency(), payload.getCurrency());
            throw new AppException(ErrorCode.BAD_REQUEST, "Đơn vị tiền tệ thanh toán không khớp");
        }

        Instant now = Instant.now();

        if ("PAYMENT_SUCCESS".equalsIgnoreCase(eventType)) {
            // Idempotency: duplicate webhook replay for already PAID order
            if ("PAID".equalsIgnoreCase(order.getStatus())) {
                String replayedProviderRef = payload.getProviderRef() == null
                        ? null : payload.getProviderRef().trim();
                if (replayedProviderRef == null || replayedProviderRef.isBlank()
                        || order.getProviderRef() == null
                        || !order.getProviderRef().equals(replayedProviderRef)) {
                    throw new AppException(ErrorCode.BAD_REQUEST,
                            "Mã giao dịch webhook không khớp với giao dịch đã ghi nhận");
                }
                log.info("Duplicate webhook for already paid order {}. Ignoring without duplicate entitlement.", order.getOrderNumber());
                return toOrderDto(order);
            }

            // R13-03 (SRS §5 edge case: "webhook trùng chỉ xử lý một lần" + buyer-cancel):
            // a PAYMENT_SUCCESS arriving late for an order the buyer already cancelled must not
            // silently grant an entitlement for a purchase the buyer no longer wants, but it also
            // must not look like a transient failure the provider should retry forever. Flag it via
            // audit + outbox for manual reconciliation (money may have moved provider-side despite
            // the cancellation) and acknowledge idempotently instead of granting access.
            if ("CANCELLED".equalsIgnoreCase(order.getStatus())) {
                log.warn("PAYMENT_SUCCESS webhook for cancelled order {}; flagging for manual refund reconciliation instead of granting access.", orderNumber);
                outboxService.recordEventIfNotExists("COMMERCE", order.getId(), "ORDER_PAID_AFTER_CANCEL", Map.of(
                        "orderNumber", order.getOrderNumber(),
                        "buyerId", order.getBuyerId(),
                        "classId", order.getClassId(),
                        "providerRef", payload.getProviderRef() == null ? "" : payload.getProviderRef()
                ));
                auditService.record(order.getClassId(), order.getBuyerId(), "ORDER_PAID_AFTER_CANCEL", "ORDER", order.getId(),
                        "{\"note\":\"Thanh toán đến sau khi đơn đã hủy; cần đối soát hoàn tiền thủ công\"}");
                return toOrderDto(order);
            }

            // Legal state transition guard: PAYMENT_SUCCESS only legal from PENDING
            if (!"PENDING".equalsIgnoreCase(order.getStatus())) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Không thể chuyển sang trạng thái PAID cho đơn hàng đang ở: " + order.getStatus());
            }

            if (payload.getProviderRef() == null || payload.getProviderRef().isBlank()) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Thiếu mã giao dịch nhà cung cấp (providerRef)");
            }

            String providerRef = payload.getProviderRef().trim();

            // Lock order step 2 (the order row above was step 1): every product the order sells, ascending
            // id, BEFORE any other read - the product row is what serializes all renewals of a product,
            // including a first purchase where the buyer has no entitlement row to lock yet.
            List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
            lockProductsInOrder(items.stream().map(OrderItem::getProductId).toList());

            // Verify providerRef uniqueness across distinct orders (after the locks, so it reads current data)
            Optional<Order> existingWithRef = orderRepository.findByProviderRef(providerRef);
            if (existingWithRef.isPresent() && !existingWithRef.get().getId().equals(order.getId())) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Mã giao dịch nhà cung cấp đã được sử dụng: " + providerRef);
            }

            order.setStatus("PAID");
            order.setPaidAt(now);
            order.setProviderRef(providerRef);
            orderRepository.save(order);

            // Grant Entitlements with D-03 Renewal/Stacking modeling (Finding 3)
            Map<String, Product> productsById = loadProducts(items.stream().map(OrderItem::getProductId).toList());
            grantEntitlements(order, items, productsById, now);

            // D-19: the class-access product turns the entitlement into MEMBERSHIP, in this same transaction and as the LAST lock
            // (order -> products -> entitlements -> member row): the buyer becomes (or stays) an ACTIVE member until the end of the chain.
            if (classAccessService != null) {
                for (OrderItem item : items) {
                    Product purchased = productsById.get(item.getProductId());
                    if (purchased != null && purchased.isClassAccess()) {
                        classAccessService.onAccessPurchased(order, purchased, now);
                    }
                }
            }

            // Commit Outbox Event in same MySQL transaction
            outboxService.recordEvent("COMMERCE", order.getId(), "ORDER_PAID", Map.of(
                    "orderNumber", order.getOrderNumber(),
                    "buyerId", order.getBuyerId(),
                    "classId", order.getClassId(),
                    "totalAmount", order.getTotalAmount(),
                    "paidAt", now.toString()
            ));

            // Finding 7: Record transactional audit event for payment success
            auditService.record(
                    order.getClassId(),
                    order.getBuyerId(),
                    "ORDER_PAID",
                    "ORDER",
                    order.getId(),
                    String.format("{\"orderNumber\":\"%s\",\"totalAmount\":%s,\"currency\":\"%s\",\"providerRef\":\"%s\"}",
                            order.getOrderNumber(), order.getTotalAmount(), order.getCurrency(), providerRef)
            );

        } else if ("PAYMENT_REFUNDED".equalsIgnoreCase(eventType)) {
            // Idempotency: duplicate refund replay
            if ("REFUNDED".equalsIgnoreCase(order.getStatus())) {
                log.info("Duplicate refund webhook for already refunded order {}.", order.getOrderNumber());
                return toOrderDto(order);
            }

            // Legal state transition guard: REFUNDED only legal from PAID (Finding 3)
            if (!"PAID".equalsIgnoreCase(order.getStatus())) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Chỉ có thể hoàn tiền cho đơn hàng đã thanh toán thành công (PAID). Trạng thái hiện tại: " + order.getStatus());
            }

            if (payload.getProviderRef() == null || payload.getProviderRef().isBlank()
                    || order.getProviderRef() == null
                    || !order.getProviderRef().equals(payload.getProviderRef().trim())) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Mã giao dịch hoàn tiền không khớp với giao dịch đã thanh toán");
            }
            if (payload.getAmount() == null || order.getTotalAmount().compareTo(payload.getAmount()) != 0) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Số tiền hoàn tiền không khớp với đơn hàng");
            }
            if (payload.getCurrency() == null || !order.getCurrency().equalsIgnoreCase(payload.getCurrency().trim())) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Đơn vị tiền tệ hoàn tiền không khớp");
            }

            order.setStatus("REFUNDED");
            order.setRefundedAt(now);
            orderRepository.save(order);

            // Same lock order as a successful renewal (order -> products ascending -> entitlements), so a
            // concurrent renewal can neither miss this revocation nor remain behind its expiry. The products
            // come from the order's items; the entitlements are only read (FOR UPDATE) once those locks
            // are held.
            Set<String> affectedProductIds = new TreeSet<>();
            for (OrderItem item : orderItemRepository.findByOrderId(order.getId())) {
                if (item.getProductId() != null) {
                    affectedProductIds.add(item.getProductId());
                }
            }
            lockProductsInOrder(affectedProductIds);

            List<Entitlement> entitlements = entitlementRepository.findByOrderIdForUpdate(order.getId());
            // Defensive: an entitlement for a product the order's items do not list (legacy data) is
            // locked too - late, but before anything is revoked or re-chained.
            Set<String> lateProducts = new TreeSet<>();
            for (Entitlement e : entitlements) {
                if (e.getProductId() != null && affectedProductIds.add(e.getProductId())) {
                    lateProducts.add(e.getProductId());
                }
            }
            lockProductsInOrder(lateProducts);

            for (Entitlement entitlement : entitlements) {
                entitlement.setState("REVOKED");
                entitlementRepository.save(entitlement);
            }

            // Reconcile downstream renewal entitlements without integer day truncation (Finding 8)
            for (String prodId : affectedProductIds) {
                reconcileProductEntitlements(order.getBuyerId(), order.getClassId(), prodId, now);
            }

            // D-19: access is revoked immediately - the member row is recomputed from the chain that is left (none -> EXPIRED at once).
            if (classAccessService != null) {
                Map<String, Product> refundedProducts = loadProducts(affectedProductIds);
                for (String prodId : affectedProductIds) {
                    Product refunded = refundedProducts.get(prodId);
                    if (refunded != null && refunded.isClassAccess()) {
                        classAccessService.onAccessRefunded(order, refunded, now);
                    }
                }
            }

            // Commit Outbox Event
            outboxService.recordEvent("COMMERCE", order.getId(), "ORDER_REFUNDED", Map.of(
                    "orderNumber", order.getOrderNumber(),
                    "buyerId", order.getBuyerId(),
                    "classId", order.getClassId(),
                    "refundedAt", now.toString()
            ));

            // Finding 7: Record transactional audit event for refund
            auditService.record(
                    order.getClassId(),
                    order.getBuyerId(),
                    "ORDER_REFUNDED",
                    "ORDER",
                    order.getId(),
                    String.format("{\"orderNumber\":\"%s\",\"revokedEntitlements\":%d}",
                            order.getOrderNumber(), entitlements.size())
            );

        } else if ("PAYMENT_FAILED".equalsIgnoreCase(eventType)) {
            if ("FAILED".equalsIgnoreCase(order.getStatus())) {
                return toOrderDto(order);
            }
            if (!"PENDING".equalsIgnoreCase(order.getStatus())) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Không thể chuyển trạng thái thất bại cho đơn hàng đang ở: " + order.getStatus());
            }
            order.setStatus("FAILED");
            orderRepository.save(order);
            if (classAccessService != null) {
                classAccessService.releaseInviteUse(order); // D-19: a failed checkout gives its reserved invite use back
            }

            // Finding 7: Record transactional audit event for payment failure
            auditService.record(
                    order.getClassId(),
                    order.getBuyerId(),
                    "ORDER_FAILED",
                    "ORDER",
                    order.getId(),
                    String.format("{\"orderNumber\":\"%s\"}", order.getOrderNumber())
            );
        } else {
            throw new AppException(ErrorCode.BAD_REQUEST, "Loại sự kiện webhook không hỗ trợ: " + eventType);
        }

        return toOrderDto(order);
    }

    @Transactional(readOnly = true)
    public OrderDto getOrder(String orderId, String userId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn hàng"));

        boolean isBuyer = order.getBuyerId().equals(userId);
        boolean canManage = accessPolicy.canManage(userId, order.getClassId(), "STORE", "VIEW", null);

        if (!isBuyer && !canManage) {
            throw new AppException(ErrorCode.FORBIDDEN, "Bạn không có quyền truy cập đơn hàng này");
        }

        return toOrderDto(order);
    }

    @Transactional(readOnly = true)
    public List<OrderDto> getOrdersByClass(String classId, String currentUserId) {
        accessPolicy.enforceManage(currentUserId, classId, "STORE", "VIEW", null);

        return orderRepository.findByClassIdOrderByCreatedAtDesc(classId).stream()
                .map(this::toOrderDto)
                .toList();
    }

    @Transactional
    public Product createProduct(String classId, String targetCourseId, String title, String description, BigDecimal price, int durationDays, String currentUserId) {
        return createProduct(classId, targetCourseId, title, description, price, durationDays, Instant.now(), currentUserId);
    }

    @Transactional
    public Product createProduct(String classId, String targetCourseId, String title, String description,
                                 BigDecimal price, int durationDays, Instant accessStartsAt, String currentUserId) {
        accessPolicy.enforceManage(currentUserId, classId, "STORE", "CREATE", null);

        // Validation (Finding 9)
        validatePrice(price, "VND");
        if (durationDays <= 0 || durationDays > MAX_PRODUCT_DURATION_DAYS) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Thời hạn sử dụng sản phẩm phải từ 1 đến " + MAX_PRODUCT_DURATION_DAYS + " ngày");
        }
        if (accessStartsAt == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Thiếu ngày bắt đầu hiệu lực của sản phẩm");
        }
        // R19-03: the start date is stored as DATETIME(6) and later stacked with the duration; keep it (and the
        // end of its first period) inside the horizon so a typo like year 9999 cannot make every purchase unfulfillable.
        Instant horizon = Instant.now().atZone(ZoneOffset.UTC).plusYears(MAX_ENTITLEMENT_HORIZON_YEARS).toInstant();
        if (accessStartsAt.isBefore(Instant.EPOCH) || accessStartsAt.plus(durationDays, ChronoUnit.DAYS).isAfter(horizon)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Ngày bắt đầu hiệu lực phải nằm trong khoảng từ 1970 đến "
                    + MAX_ENTITLEMENT_HORIZON_YEARS + " năm kể từ hôm nay (tính cả thời hạn sử dụng)");
        }
        Course targetCourse = null;
        if (targetCourseId != null && !targetCourseId.isBlank()) {
            accessPolicy.enforceManage(currentUserId, classId, "COURSE", "EDIT", targetCourseId.trim());
            targetCourse = courseRepository.findByIdForUpdate(targetCourseId.trim())
                    .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy khóa học"));
            if (!targetCourse.getClassId().equals(classId)) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Khóa học không thuộc lớp học này");
            }
            if (targetCourse.getProductId() != null && !targetCourse.getProductId().isBlank()) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Khóa học đã được liên kết với một sản phẩm khác");
            }
        }

        // R16-03: persist the resolved course id (trimmed), or NULL when the product targets no
        // course - a blank string would now violate fk_prod_target_course (V29).
        Product product = new Product(classId, targetCourse != null ? targetCourseId.trim() : null, title, description);
        product.setStatus("DRAFT"); // Ignore client status; new products start in DRAFT
        Product savedProduct = productRepository.save(product);

        ProductPrice pp = new ProductPrice(savedProduct.getId(), price, "VND", durationDays, accessStartsAt);
        priceRepository.save(pp);

        // Associate product and restrict course access in the same transaction.
        if (targetCourse != null) {
            targetCourse.setProductId(savedProduct.getId());
            targetCourse.setAccessMode("PURCHASE_REQUIRED");
            targetCourse.setUpdatedAt(Instant.now());
            courseRepository.save(targetCourse);
        }

        return savedProduct;
    }

    @Transactional
    public Product publishProduct(String productId, String currentUserId) {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy sản phẩm"));
        accessPolicy.enforceManage(currentUserId, product.getClassId(), "STORE", "PUBLISH", null);
        rejectClassAccessProductEdit(product);
        // R16-02: publish is a DRAFT -> PUBLISHED transition only. An ARCHIVED product must go
        // through restoreProduct (STORE:EDIT) first; publishing straight from ARCHIVED used to skip
        // that gate and left no audit trail. Re-publishing a PUBLISHED product is an idempotent no-op.
        if ("PUBLISHED".equalsIgnoreCase(product.getStatus())) {
            return product;
        }
        if (!"DRAFT".equalsIgnoreCase(product.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST,
                    "Chỉ có thể công bố sản phẩm ở trạng thái DRAFT; sản phẩm đã gỡ bán cần được khôi phục trước");
        }
        product.setStatus("PUBLISHED");
        product.setUpdatedAt(Instant.now());
        Product saved = productRepository.save(product);
        auditService.record(product.getClassId(), currentUserId, "PRODUCT_PUBLISH", "PRODUCT", productId, "{}");
        return saved;
    }

    /**
     * R13-03 (FR-07/LLD §5): title/description/duration may be updated at any time. Price is
     * captured into order_items.price_snapshot at purchase time (see createOrderTransactional),
     * so changing product_prices here only ever affects FUTURE orders — every existing order and
     * its already-granted entitlement keep the price/duration they were sold at, untouched.
     */
    @Transactional
    public Product updateProduct(String productId, String title, String description, BigDecimal price,
                                  Integer durationDays, String currentUserId) {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy sản phẩm"));
        accessPolicy.enforceManage(currentUserId, product.getClassId(), "STORE", "EDIT", null);
        rejectClassAccessProductEdit(product);
        if ("ARCHIVED".equalsIgnoreCase(product.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Không thể sửa sản phẩm đã gỡ bán; hãy khôi phục trước");
        }
        if (title != null) {
            if (title.isBlank()) throw new AppException(ErrorCode.BAD_REQUEST, "Tên sản phẩm không được để trống");
            product.setTitle(title);
        }
        if (description != null) product.setDescription(description);
        product.setUpdatedAt(Instant.now());
        Product saved = productRepository.save(product);

        if (price != null || durationDays != null) {
            ProductPrice pp = priceRepository.findByProductId(productId)
                    .orElseThrow(() -> new AppException(ErrorCode.BAD_REQUEST, "Sản phẩm chưa được thiết lập giá bán"));
            if (price != null) {
                validatePrice(price, pp.getCurrency());
                pp.setPrice(price);
            }
            if (durationDays != null) {
                if (durationDays <= 0 || durationDays > MAX_PRODUCT_DURATION_DAYS) {
                    throw new AppException(ErrorCode.BAD_REQUEST, "Thời hạn sử dụng sản phẩm phải từ 1 đến " + MAX_PRODUCT_DURATION_DAYS + " ngày");
                }
                pp.setDurationDays(durationDays);
            }
            priceRepository.save(pp);
        }
        auditService.record(product.getClassId(), currentUserId, "PRODUCT_UPDATE", "PRODUCT", productId,
                String.format("{\"title\":\"%s\"}", saved.getTitle()));
        return saved;
    }

    /**
     * R13-03 (SRS §5 "khóa học bị gỡ bán nhưng người mua còn hạn"): archiving ("gỡ bán") a product
     * only stops it from being listed/purchased by new buyers (see listProducts filtering by
     * PUBLISHED status, and createOrderTransactional's PUBLISHED-only purchase gate below).
     * Existing entitlements are untouched — they were already granted rows in the entitlements
     * table with their own expiresAt, independent of the product's current status, so buyers keep
     * access until those rows naturally expire.
     */
    @Transactional
    public Product archiveProduct(String productId, String currentUserId) {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy sản phẩm"));
        accessPolicy.enforceManage(currentUserId, product.getClassId(), "STORE", "EDIT", null);
        rejectClassAccessProductEdit(product);
        if ("ARCHIVED".equalsIgnoreCase(product.getStatus())) {
            return product;
        }
        product.setStatus("ARCHIVED");
        product.setUpdatedAt(Instant.now());
        Product saved = productRepository.save(product);
        auditService.record(product.getClassId(), currentUserId, "PRODUCT_ARCHIVE", "PRODUCT", productId, "{}");
        return saved;
    }

    /** R13-03: unarchive back to DRAFT so OWNER/STAFF can review before re-publishing. */
    @Transactional
    public Product restoreProduct(String productId, String currentUserId) {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy sản phẩm"));
        accessPolicy.enforceManage(currentUserId, product.getClassId(), "STORE", "EDIT", null);
        rejectClassAccessProductEdit(product);
        if (!"ARCHIVED".equalsIgnoreCase(product.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Chỉ có thể khôi phục sản phẩm đã gỡ bán");
        }
        product.setStatus("DRAFT");
        product.setUpdatedAt(Instant.now());
        Product saved = productRepository.save(product);
        auditService.record(product.getClassId(), currentUserId, "PRODUCT_RESTORE", "PRODUCT", productId, "{}");
        return saved;
    }

    /**
     * R13-03 (SRS §5 order state machine + FR-07): buyer-initiated cancellation of their own
     * PENDING order. Only the buyer may cancel (not staff — spec does not grant staff an order
     * cancellation action, only refund via the payment webhook), and only from PENDING: a PAID
     * order must go through REFUNDED via the payment webhook so entitlements are correctly revoked,
     * never silently CANCELLED here without touching the (nonexistent, since it's still PENDING)
     * entitlement.
     */
    @Transactional
    public OrderDto cancelOrder(String orderId, String currentUserId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy đơn hàng"));
        if (!order.getBuyerId().equals(currentUserId)) {
            throw new AppException(ErrorCode.FORBIDDEN, "Bạn chỉ có thể hủy đơn hàng của chính mình");
        }
        if (!"PENDING".equalsIgnoreCase(order.getStatus())) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Chỉ có thể hủy đơn hàng đang chờ thanh toán (PENDING). Trạng thái hiện tại: " + order.getStatus());
        }
        order.setStatus("CANCELLED");
        order.setUpdatedAt(Instant.now());
        Order saved = orderRepository.save(order);
        if (classAccessService != null) {
            classAccessService.releaseInviteUse(order); // D-19: a cancelled checkout gives its reserved invite use back
        }
        outboxService.recordEventIfNotExists("COMMERCE", saved.getId(), "ORDER_CANCELLED", Map.of(
                "orderNumber", saved.getOrderNumber(),
                "buyerId", saved.getBuyerId(),
                "classId", saved.getClassId()
        ));
        auditService.record(order.getClassId(), currentUserId, "ORDER_CANCEL", "ORDER", orderId, "{}");
        return toOrderDto(saved);
    }

    private ProductDto toProductDto(Product p) {
        ProductDto dto = new ProductDto();
        dto.setId(p.getId());
        dto.setClassId(p.getClassId());
        dto.setTargetCourseId(p.getTargetCourseId());
        dto.setTitle(p.getTitle());
        dto.setDescription(p.getDescription());
        dto.setStatus(p.getStatus());
        dto.setKind(p.getKind() == null ? Product.KIND_STANDARD : p.getKind());
        dto.setCreatedAt(p.getCreatedAt());
        return dto;
    }

    private OrderDto toOrderDto(Order order) {
        OrderDto dto = new OrderDto();
        dto.setId(order.getId());
        dto.setOrderNumber(order.getOrderNumber());
        dto.setBuyerId(order.getBuyerId());
        dto.setClassId(order.getClassId());
        dto.setStatus(order.getStatus());
        dto.setTotalAmount(order.getTotalAmount());
        dto.setCurrency(order.getCurrency());
        dto.setProvider(order.getProvider());
        dto.setCheckoutUrl(paymentProvider.createPaymentSession(order));
        dto.setPaidAt(order.getPaidAt());
        dto.setRefundedAt(order.getRefundedAt());
        dto.setCreatedAt(order.getCreatedAt());

        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        List<OrderDto.OrderItemDto> iDtos = items.stream()
                .map(i -> new OrderDto.OrderItemDto(i.getProductId(), i.getProductNameSnapshot(), i.getPriceSnapshot(), i.getDurationDaysSnapshot()))
                .toList();
        dto.setItems(iDtos);

        return dto;
    }

    /**
     * R13-06 (FR-07/TC-17): the buyer's own orders, optionally scoped to one class. Batch-loads
     * order items (one query for every order instead of one per order) — the only per-order call
     * left is the mock payment provider's checkoutUrl, which does no I/O.
     */
    @Transactional(readOnly = true)
    public List<OrderDto> getMyOrders(String buyerId, String classId) {
        List<Order> orders = orderRepository.findByBuyerIdOrderByCreatedAtDesc(buyerId);
        if (classId != null && !classId.isBlank()) {
            orders = orders.stream().filter(o -> classId.equals(o.getClassId())).toList();
        }
        if (orders.isEmpty()) {
            return List.of();
        }
        List<String> orderIds = orders.stream().map(Order::getId).toList();
        Map<String, List<OrderItem>> itemsByOrder = new HashMap<>();
        for (OrderItem item : orderItemRepository.findByOrderIdIn(orderIds)) {
            itemsByOrder.computeIfAbsent(item.getOrderId(), k -> new ArrayList<>()).add(item);
        }
        return orders.stream().map(order -> {
            OrderDto dto = new OrderDto();
            dto.setId(order.getId());
            dto.setOrderNumber(order.getOrderNumber());
            dto.setBuyerId(order.getBuyerId());
            dto.setClassId(order.getClassId());
            dto.setStatus(order.getStatus());
            dto.setTotalAmount(order.getTotalAmount());
            dto.setCurrency(order.getCurrency());
            dto.setProvider(order.getProvider());
            dto.setCheckoutUrl(paymentProvider.createPaymentSession(order));
            dto.setPaidAt(order.getPaidAt());
            dto.setRefundedAt(order.getRefundedAt());
            dto.setCreatedAt(order.getCreatedAt());
            List<OrderDto.OrderItemDto> iDtos = itemsByOrder.getOrDefault(order.getId(), List.of()).stream()
                    .map(i -> new OrderDto.OrderItemDto(i.getProductId(), i.getProductNameSnapshot(), i.getPriceSnapshot(), i.getDurationDaysSnapshot()))
                    .toList();
            dto.setItems(iDtos);
            return dto;
        }).toList();
    }

    /**
     * R19-09: product_prices.price is DECIMAL(12,2), so a value the column cannot represent used to be
     * rounded silently on write - 0.001 became 0.00 (a FREE product) and 12345678901 either overflowed or
     * was truncated. VND has no minor unit, so a VND price must be a whole number of dong; any other
     * currency keeps at most 2 decimals. The value that will actually be stored (rounded to the allowed
     * scale) must still be positive, and must fit 10 integer digits.
     */
    public static void validatePrice(BigDecimal price, String currency) {
        if (price == null || price.signum() <= 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Giá bán sản phẩm phải lớn hơn 0");
        }
        boolean vnd = currency == null || "VND".equalsIgnoreCase(currency.trim());
        int allowedScale = vnd ? 0 : 2;
        if (price.stripTrailingZeros().scale() > allowedScale) {
            throw new AppException(ErrorCode.BAD_REQUEST, vnd
                    ? "Giá bán bằng VND phải là số nguyên (không có phần thập phân)"
                    : "Giá bán chỉ được có tối đa 2 chữ số thập phân");
        }
        BigDecimal stored = price.setScale(allowedScale, RoundingMode.HALF_UP);
        if (stored.signum() <= 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Giá bán sản phẩm phải lớn hơn 0");
        }
        if (stored.compareTo(MAX_PRICE) > 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Giá bán vượt quá giới hạn tối đa (9.999.999.999)");
        }
    }

    /**
     * Lock-order step 2 (see {@link #processPaymentWebhookTransactional}): takes the product rows FOR
     * UPDATE in ascending id order. Every path that changes a buyer's entitlement chain must call this
     * before it reads or writes an entitlement, and must already hold the order row it came from.
     */
    private void lockProductsInOrder(Collection<String> productIds) {
        if (productIds == null || productIds.isEmpty()) return;
        for (String productId : productIds.stream().filter(Objects::nonNull).distinct().sorted().toList()) {
            productRepository.findByIdForUpdate(productId)
                    .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy sản phẩm"));
        }
    }

    /** The (already locked) product rows of an order, by id: one query, used to tell the class-access product from the rest. */
    private Map<String, Product> loadProducts(Collection<String> productIds) {
        Map<String, Product> byId = new HashMap<>();
        if (productIds == null || productIds.isEmpty()) return byId;
        List<Product> found = productRepository.findAllById(productIds.stream().filter(Objects::nonNull).distinct().toList());
        if (found != null) {
            for (Product product : found) {
                byId.put(product.getId(), product);
            }
        }
        return byId;
    }

    /** A paid order whose access cannot be granted (the entitlement would run past the horizon). */
    static final class UnfulfillableOrderException extends AppException {
        UnfulfillableOrderException(String message) {
            super(ErrorCode.BAD_REQUEST, message);
        }
    }

    /** {@code [startsAt, expiresAt]} of one entitlement to be granted. */
    record Grant(Instant startsAt, Instant expiresAt) {}

    /**
     * D-03 stacking: a new purchase starts when the buyer's current paid chain ends (or at the product's
     * configured start / now, whichever is latest) and runs for the product's duration.
     *
     * @throws UnfulfillableOrderException when the result would end after {@code now + MAX_ENTITLEMENT_HORIZON_YEARS}
     */
    static Grant computeGrant(Instant now, Instant chainEnd, Instant configuredStartOrNull, int durationDaysSnapshot) {
        Instant configuredStart = configuredStartOrNull == null ? now : configuredStartOrNull;
        Instant startsAt = chainEnd != null ? chainEnd : configuredStart;
        if (startsAt.isBefore(configuredStart)) startsAt = configuredStart;
        if (startsAt.isBefore(now)) startsAt = now;
        int duration = durationDaysSnapshot;
        if (duration <= 0 || duration > MAX_PRODUCT_DURATION_DAYS) {
            duration = Math.min(Math.max(1, duration), MAX_PRODUCT_DURATION_DAYS);
        }
        Instant horizon = now.atZone(ZoneOffset.UTC).plusYears(MAX_ENTITLEMENT_HORIZON_YEARS).toInstant();
        Instant expiresAt;
        try {
            expiresAt = startsAt.plus(duration, ChronoUnit.DAYS);
        } catch (DateTimeException | ArithmeticException ex) {
            throw new UnfulfillableOrderException(horizonMessage());
        }
        if (startsAt.isAfter(horizon) || expiresAt.isAfter(horizon)) {
            throw new UnfulfillableOrderException(horizonMessage());
        }
        return new Grant(startsAt, expiresAt);
    }

    private static String horizonMessage() {
        return "Thời hạn sử dụng cộng dồn vượt quá giới hạn cho phép (" + MAX_ENTITLEMENT_HORIZON_YEARS
                + " năm kể từ hôm nay); không thể cấp quyền truy cập cho đơn hàng này";
    }

    /**
     * Grants one entitlement per order item, stacked on the buyer's current chain. Preconditions: the
     * order row and every item's product row are locked by this transaction (see
     * {@link #processPaymentWebhookTransactional}); the chain is read FOR UPDATE, i.e. as the latest
     * committed data, never from a stale snapshot.
     */
    private void grantEntitlements(Order order, List<OrderItem> items, Map<String, Product> productsById, Instant now) {
        for (OrderItem item : items) {
            // Check for existing active entitlement to stack upon
            List<Entitlement> active = entitlementRepository.findLatestActiveByProductForUpdate(
                    order.getBuyerId(), order.getClassId(), item.getProductId(), now);
            Instant chainEnd = (active != null && !active.isEmpty()) ? active.get(0).getExpiresAt() : null;

            Product soldProduct = productsById.get(item.getProductId());
            boolean classAccess = soldProduct != null && soldProduct.isClassAccess();
            Grant grant;
            if (classAccess && item.getDurationDaysSnapshot() <= 0) {
                // D-19: LIFETIME access - one entitlement from now to the sentinel end; it never stacks and is never re-checked against the horizon.
                grant = new Grant(now, ClassAccessService.LIFETIME_END);
            } else {
                if (classAccess && chainEnd != null && !chainEnd.isBefore(ClassAccessService.LIFETIME_END)) {
                    chainEnd = null; // a finite top-up bought on top of a lifetime grant starts now instead of past the horizon
                }
                grant = computeGrant(now, chainEnd, item.getAccessStartsAtSnapshot(), item.getDurationDaysSnapshot());
            }

            Entitlement entitlement = new Entitlement(
                    order.getBuyerId(),
                    order.getClassId(),
                    item.getProductId(),
                    item.getTargetCourseIdSnapshot(),
                    grant.startsAt(),
                    grant.expiresAt()
            );
            // Explicitly associate renewal order ID with entitlement record for deterministic refund tracking
            entitlement.setOrderId(order.getId());
            entitlementRepository.save(entitlement);
        }
    }

    private void reconcileProductEntitlements(String userId, String classId, String productId, Instant now) {
        List<Entitlement> remaining = entitlementRepository.findFutureActiveByProductAscForUpdate(userId, classId, productId, now);
        if (remaining == null || remaining.isEmpty()) {
            return;
        }

        Instant chainCursor = null;
        for (Entitlement rem : remaining) {
            Duration duration = Duration.between(rem.getStartsAt(), rem.getExpiresAt());
            if (duration.isNegative() || duration.isZero()) {
                continue;
            }

            Instant contractualStart = orderItemRepository.findByOrderId(rem.getOrderId()).stream()
                    .filter(item -> productId.equals(item.getProductId()))
                    .map(OrderItem::getAccessStartsAtSnapshot)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(Instant.EPOCH);
            // EPOCH is the legacy/default sentinel for an unrestricted start date.
            if (contractualStart.equals(Instant.EPOCH)) {
                contractualStart = now;
            }

            // A surviving period that has already started is a real consumed entitlement:
            // preserve its original boundaries instead of restarting its full duration at refund time.
            // Only periods not yet started are re-chained after the surviving active period.
            if (!rem.getStartsAt().isAfter(now)) {
                chainCursor = rem.getExpiresAt();
                continue;
            }

            Instant reconciledStart = contractualStart.isAfter(now) ? contractualStart : now;
            if (chainCursor != null && reconciledStart.isBefore(chainCursor)) {
                reconciledStart = chainCursor;
            }
            if (!reconciledStart.equals(rem.getStartsAt())) {
                rem.setStartsAt(reconciledStart);
                rem.setExpiresAt(reconciledStart.plus(duration));
            }
            chainCursor = rem.getExpiresAt();
            entitlementRepository.save(rem);
        }
    }
}
