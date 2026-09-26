package com.classroom.modules.commerce.service;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.audit.service.AuditService;
import com.classroom.modules.classroom.policy.AccessPolicy;
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
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class CommerceService {
    private static final Logger log = LoggerFactory.getLogger(CommerceService.class);

    public static final int MAX_PRODUCT_DURATION_DAYS = 3650;

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

    @Transactional(readOnly = true)
    public List<ProductDto> getProductsByClass(String classId, String userId) {
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

            // Check if user has access right now, and how far that access is already paid for.
            //
            // These are two different questions. "Has access now" is the currently-running
            // entitlement (startsAt <= now < expiresAt). A renewal, however, is stacked with a
            // future startsAt (see fulfilOrder), so it is deliberately excluded from
            // findActiveEntitlements — reading the expiry off that row would tell a buyer who just
            // renewed that their access ends at the *old* expiry. The displayed expiry must be the
            // end of the whole non-expired chain instead.
            if (userId != null) {
                boolean hasAccessNow = entitlementRepository.findActiveEntitlements(userId, classId, now).stream()
                        .anyMatch(e -> p.getId().equals(e.getProductId()));
                if (hasAccessNow) {
                    dto.setUserHasActiveEntitlement(true);
                    // Ordered by expiresAt DESC and filtered to expiresAt > now, so the first row is
                    // the end of the valid chain (current purchase plus any stacked renewals).
                    entitlementRepository.findLatestActiveByProduct(userId, classId, p.getId(), now).stream()
                            .findFirst()
                            .ifPresent(e -> dto.setEntitlementExpiresAt(e.getExpiresAt()));
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

    @Transactional
    public OrderDto createOrderTransactional(String buyerId, CreateOrderRequest request) {
        if (paymentProvider == null || ("MOCK".equalsIgnoreCase(paymentProvider.getProviderCode()) && !mockCheckoutEnabled)) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Thanh toán hiện chưa được cấu hình; chưa thể tạo đơn hàng");
        }
        // Enforce buyer is an active member of the classroom (Finding 9)
        accessPolicy.enforceMember(buyerId, request.getClassId());

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

    @Transactional
    public OrderDto handlePaymentWebhook(WebhookPayload payload, String rawBody, String signature) {
        return handlePaymentWebhook("MOCK", payload, rawBody, signature);
    }

    @Transactional
    public OrderDto handlePaymentWebhook(String providerCode, WebhookPayload payload, String rawBody, String signature) {
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

            // Legal state transition guard: PAYMENT_SUCCESS only legal from PENDING
            if (!"PENDING".equalsIgnoreCase(order.getStatus())) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Không thể chuyển sang trạng thái PAID cho đơn hàng đang ở: " + order.getStatus());
            }

            if (payload.getProviderRef() == null || payload.getProviderRef().isBlank()) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Thiếu mã giao dịch nhà cung cấp (providerRef)");
            }

            String providerRef = payload.getProviderRef().trim();

            // Verify providerRef uniqueness across distinct orders
            Optional<Order> existingWithRef = orderRepository.findByProviderRef(providerRef);
            if (existingWithRef.isPresent() && !existingWithRef.get().getId().equals(order.getId())) {
                throw new AppException(ErrorCode.BAD_REQUEST, "Mã giao dịch nhà cung cấp đã được sử dụng: " + providerRef);
            }

            order.setStatus("PAID");
            order.setPaidAt(now);
            order.setProviderRef(providerRef);
            orderRepository.save(order);

            // Grant Entitlements with D-03 Renewal/Stacking modeling (Finding 3)
            List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
            for (OrderItem item : items) {
                // A product row is the common serialization point for every buyer renewal of
                // this product, including first purchases where no entitlement row exists yet.
                Product product = productRepository.findByIdForUpdate(item.getProductId())
                        .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy sản phẩm"));
                String targetCourseId = item.getTargetCourseIdSnapshot();

                // Check for existing active entitlement to stack upon
                List<Entitlement> active = entitlementRepository.findLatestActiveByProduct(order.getBuyerId(), order.getClassId(), item.getProductId(), now);

                Instant configuredStart = item.getAccessStartsAtSnapshot() == null ? now : item.getAccessStartsAtSnapshot();
                Instant startsAt = (active != null && !active.isEmpty()) ? active.get(0).getExpiresAt() : configuredStart;
                if (startsAt.isBefore(configuredStart)) startsAt = configuredStart;
                if (startsAt.isBefore(now)) startsAt = now;
                int duration = item.getDurationDaysSnapshot();
                if (duration <= 0 || duration > MAX_PRODUCT_DURATION_DAYS) {
                    duration = Math.min(Math.max(1, duration), MAX_PRODUCT_DURATION_DAYS);
                }
                Instant expiresAt;
                try {
                    expiresAt = startsAt.plus(duration, ChronoUnit.DAYS);
                } catch (Exception ex) {
                    expiresAt = startsAt.plus(MAX_PRODUCT_DURATION_DAYS, ChronoUnit.DAYS);
                }

                Entitlement entitlement = new Entitlement(
                        order.getBuyerId(),
                        order.getClassId(),
                        item.getProductId(),
                        targetCourseId,
                        startsAt,
                        expiresAt
                );
                // Explicitly associate renewal order ID with entitlement record for deterministic refund tracking
                entitlement.setOrderId(order.getId());
                entitlementRepository.save(entitlement);
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
            orderRepository.save(order);

            // Use the same product-row serialization point as successful renewals. Lock every
            // affected product in stable order before revoking/re-chaining entitlements, so a
            // concurrent renewal can neither miss this revocation nor remain behind its expiry.
            List<Entitlement> entitlements = entitlementRepository.findByOrderId(order.getId());
            Set<String> affectedProductIds = new HashSet<>();
            for (Entitlement e : entitlements) {
                if (e.getProductId() != null) {
                    affectedProductIds.add(e.getProductId());
                }
            }

            for (String prodId : affectedProductIds.stream().sorted().toList()) {
                productRepository.findByIdForUpdate(prodId)
                        .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Không tìm thấy sản phẩm"));
            }
            for (Entitlement entitlement : entitlements) {
                entitlement.setState("REVOKED");
                entitlementRepository.save(entitlement);
            }

            // Reconcile downstream renewal entitlements without integer day truncation (Finding 8)
            for (String prodId : affectedProductIds.stream().sorted().toList()) {
                reconcileProductEntitlements(order.getBuyerId(), order.getClassId(), prodId, now);
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
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Giá bán sản phẩm phải lớn hơn 0");
        }
        if (durationDays <= 0 || durationDays > MAX_PRODUCT_DURATION_DAYS || accessStartsAt == null) {
            throw new AppException(ErrorCode.BAD_REQUEST, "Thời hạn sử dụng sản phẩm phải từ 1 đến " + MAX_PRODUCT_DURATION_DAYS + " ngày");
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

        Product product = new Product(classId, targetCourseId, title, description);
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
        product.setStatus("PUBLISHED");
        product.setUpdatedAt(Instant.now());
        return productRepository.save(product);
    }

    private ProductDto toProductDto(Product p) {
        ProductDto dto = new ProductDto();
        dto.setId(p.getId());
        dto.setClassId(p.getClassId());
        dto.setTargetCourseId(p.getTargetCourseId());
        dto.setTitle(p.getTitle());
        dto.setDescription(p.getDescription());
        dto.setStatus(p.getStatus());
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
        dto.setCreatedAt(order.getCreatedAt());

        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        List<OrderDto.OrderItemDto> iDtos = items.stream()
                .map(i -> new OrderDto.OrderItemDto(i.getProductId(), i.getProductNameSnapshot(), i.getPriceSnapshot(), i.getDurationDaysSnapshot()))
                .toList();
        dto.setItems(iDtos);

        return dto;
    }

    private void reconcileProductEntitlements(String userId, String classId, String productId, Instant now) {
        List<Entitlement> remaining = entitlementRepository.findFutureActiveByProductAsc(userId, classId, productId, now);
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
