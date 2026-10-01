package com.classroom.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.modules.audit.model.AuditEvent;
import com.classroom.modules.audit.repository.AuditEventRepository;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.commerce.dto.CreateOrderRequest;
import com.classroom.modules.commerce.dto.OrderDto;
import com.classroom.modules.commerce.dto.ProductDto;
import com.classroom.modules.commerce.dto.WebhookPayload;
import com.classroom.modules.commerce.model.Entitlement;
import com.classroom.modules.commerce.model.Order;
import com.classroom.modules.commerce.model.Product;
import com.classroom.modules.commerce.model.ProductPrice;
import com.classroom.modules.commerce.payment.MockPaymentProvider;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import com.classroom.modules.commerce.repository.OrderRepository;
import com.classroom.modules.commerce.repository.ProductPriceRepository;
import com.classroom.modules.commerce.repository.ProductRepository;
import com.classroom.modules.commerce.service.CommerceService;
import com.classroom.modules.community.model.DocumentAsset;
import com.classroom.modules.community.repository.DocumentAssetRepository;
import com.classroom.modules.community.service.DocumentService;
import com.classroom.modules.exam.model.Exam;
import com.classroom.modules.exam.model.Question;
import com.classroom.modules.exam.repository.QuestionRepository;
import com.classroom.modules.exam.service.ExamService;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.learning.dto.CourseDto;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.learning.service.LearningService;
import com.classroom.modules.media.model.MediaAsset;
import com.classroom.modules.media.repository.MediaAssetRepository;
import com.classroom.modules.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round 19 against a real MySQL (REPEATABLE READ by default - the isolation level the bugs live in).
 *
 * <p>R19-01: concurrent settles / refunds of the same buyer+product must produce a strictly sequential
 * entitlement chain. R19-03: entitlement dates beyond 2038. R19-06/R19-12: owned-upcoming and
 * not-purchasable read models. R19-01(c): the other "plain read, then lock" call sites. Every test builds
 * its own classroom so it cannot disturb the shared demo seed other suites use.</p>
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("integration")
class Round19CommerceIntegrationTest {

    private static final String V30 = "db/migration/V30__timestamp_to_datetime_beyond_2038.sql";

    @Autowired private UserRepository userRepository;
    @Autowired private ClassroomRepository classroomRepository;
    @Autowired private ClassMemberRepository memberRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductPriceRepository priceRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private AuditEventRepository auditEventRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private MediaAssetRepository mediaAssetRepository;
    @Autowired private DocumentAssetRepository documentAssetRepository;
    @Autowired private QuestionRepository questionRepository;
    @Autowired private CommerceService commerceService;
    @Autowired private LearningService learningService;
    @Autowired private DocumentService documentService;
    @Autowired private ExamService examService;
    @Autowired private com.classroom.modules.classroom.service.MemberService memberService;
    @Autowired private com.classroom.modules.exam.repository.ExamAttemptRepository attemptRepository;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired private MockPaymentProvider mockPaymentProvider;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;

    private ListAppender<ILoggingEvent> commerceLog;
    private ch.qos.logback.classic.Logger commerceLogger;

    @BeforeEach
    void captureCommerceLog() {
        commerceLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(CommerceService.class);
        commerceLog = new ListAppender<>();
        commerceLog.start();
        commerceLogger.addAppender(commerceLog);
    }

    @AfterEach
    void releaseCommerceLog() {
        commerceLogger.detachAppender(commerceLog);
    }

    // ----- fixtures -----

    private User newUser(String prefix) {
        String unique = prefix + "-" + System.nanoTime();
        return userRepository.save(new User(UUID.randomUUID().toString(), unique + "@r19.test", "hash", unique, "USER"));
    }

    private Classroom newClass(User owner) {
        Classroom c = new Classroom();
        c.setOwnerId(owner.getId());
        c.setSlug("r19-" + System.nanoTime());
        c.setTitle("Round 19");
        c.setStatus("ACTIVE");
        c = classroomRepository.save(c);
        memberRepository.save(new ClassMember(c.getId(), owner.getId(), "OWNER"));
        return c;
    }

    private void join(Classroom c, User u) {
        memberRepository.save(new ClassMember(c.getId(), u.getId(), "STUDENT"));
    }

    private Product publishedProduct(Classroom c, String title, int days, Instant accessStartsAt) {
        Product p = new Product(c.getId(), null, title, "d");
        p.setStatus("PUBLISHED");
        p = productRepository.save(p);
        priceRepository.save(new ProductPrice(p.getId(), new BigDecimal("1000"), "VND", days, accessStartsAt));
        return p;
    }

    private OrderDto pendingOrder(User buyer, Classroom c, Product p) {
        CreateOrderRequest req = new CreateOrderRequest();
        req.setClassId(c.getId());
        req.setProductId(p.getId());
        req.setIdempotencyKey("r19-" + UUID.randomUUID());
        return commerceService.createOrder(buyer.getId(), req);
    }

    private String body(String orderNumber, String providerRef, String eventType) {
        return String.format(Locale.ROOT,
                "{\"orderNumber\":\"%s\",\"providerRef\":\"%s\",\"eventType\":\"%s\",\"amount\":1000,\"currency\":\"VND\"}",
                orderNumber, providerRef, eventType);
    }

    private OrderDto webhook(String orderNumber, String providerRef, String eventType) {
        String body = body(orderNumber, providerRef, eventType);
        WebhookPayload payload = new WebhookPayload(orderNumber, providerRef, eventType, new BigDecimal("1000"), "VND");
        return commerceService.handlePaymentWebhook("MOCK", payload, body, mockPaymentProvider.generateSignature(body));
    }

    private OrderDto settle(String orderNumber) {
        return webhook(orderNumber, "txn-" + UUID.randomUUID(), "PAYMENT_SUCCESS");
    }

    private List<Entitlement> chain(User buyer, Classroom c, Product p) {
        return entitlementRepository.findByUserIdAndClassId(buyer.getId(), c.getId()).stream()
                .filter(e -> p.getId().equals(e.getProductId()))
                .sorted(Comparator.comparing(Entitlement::getStartsAt))
                .toList();
    }

    /** Runs the callables at the same instant on separate connections; rethrows the first failure. */
    private <T> List<T> runTogether(List<Callable<T>> jobs) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(jobs.size());
        try {
            CountDownLatch ready = new CountDownLatch(jobs.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> job : jobs) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    return job.call();
                }));
            }
            assertTrue(ready.await(20, TimeUnit.SECONDS));
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(90, TimeUnit.SECONDS));
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertNoWebhookRetries() {
        List<String> retries = commerceLog.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("retryable conflict"))
                .toList();
        assertTrue(retries.isEmpty(), "the lock order must make deadlocks/duplicate-key losers impossible here, but a webhook was retried: " + retries);
    }

    // ----- R19-01: parallel settles -----

    @Test
    @DisplayName("R19-01: N parallel settles of N orders for one buyer+product stack into a strictly sequential chain (N x duration), repeatedly")
    void parallelSettlesStackSequentially() throws Exception {
        User owner = newUser("owner");
        Classroom c = newClass(owner);
        User buyer = newUser("buyer");
        join(c, buyer);

        final int parallel = 6;
        for (int round = 1; round <= 4; round++) {
            Product p = publishedProduct(c, "R19 parallel " + round, 30, Instant.now().minusSeconds(60));
            List<OrderDto> orders = new ArrayList<>();
            for (int i = 0; i < parallel; i++) orders.add(pendingOrder(buyer, c, p));

            List<OrderDto> settled = runTogether(orders.stream()
                    .<Callable<OrderDto>>map(o -> () -> settle(o.getOrderNumber())).toList());
            settled.forEach(o -> assertEquals("PAID", o.getStatus()));

            List<Entitlement> chain = chain(buyer, c, p);
            assertEquals(parallel, chain.size(), "round " + round + ": one entitlement per paid order");
            for (int i = 0; i < chain.size(); i++) {
                Entitlement e = chain.get(i);
                assertEquals(Duration.ofDays(30), Duration.between(e.getStartsAt(), e.getExpiresAt()), "round " + round + " #" + i);
                if (i > 0) {
                    assertEquals(chain.get(i - 1).getExpiresAt(), e.getStartsAt(),
                            "round " + round + ": entitlement #" + i + " must start exactly when #" + (i - 1) + " ends (no overlap, no gap)");
                }
            }
            assertEquals(Duration.ofDays(30L * parallel),
                    Duration.between(chain.get(0).getStartsAt(), chain.get(parallel - 1).getExpiresAt()),
                    "round " + round + ": N x 30 days granted for N x 30 days paid");
            for (OrderDto o : orders) {
                assertEquals("PAID", orderRepository.findByOrderNumber(o.getOrderNumber()).orElseThrow().getStatus());
            }
        }
        assertNoWebhookRetries();
    }

    @Test
    @DisplayName("R19-01: the same PAYMENT_SUCCESS delivered in parallel grants exactly one entitlement")
    void duplicateWebhookDeliveredInParallelGrantsOnce() throws Exception {
        User owner = newUser("owner");
        Classroom c = newClass(owner);
        User buyer = newUser("buyer");
        join(c, buyer);
        Product p = publishedProduct(c, "R19 duplicate", 30, Instant.now().minusSeconds(60));
        OrderDto order = pendingOrder(buyer, c, p);
        String ref = "txn-" + UUID.randomUUID();

        List<OrderDto> results = runTogether(List.<Callable<OrderDto>>of(
                () -> webhook(order.getOrderNumber(), ref, "PAYMENT_SUCCESS"),
                () -> webhook(order.getOrderNumber(), ref, "PAYMENT_SUCCESS"),
                () -> webhook(order.getOrderNumber(), ref, "PAYMENT_SUCCESS"),
                () -> webhook(order.getOrderNumber(), ref, "PAYMENT_SUCCESS")));

        results.forEach(o -> assertEquals("PAID", o.getStatus()));
        assertEquals(1, chain(buyer, c, p).size());
        assertNoWebhookRetries();
    }

    @Test
    @DisplayName("R19-01: a refund racing a renewal never leaves the renewal scheduled behind the revoked period, repeatedly")
    void refundRacingRenewalDoesNotLeaveRenewalBehindRevokedPeriod() throws Exception {
        User owner = newUser("owner");
        Classroom c = newClass(owner);
        User buyer = newUser("buyer");
        join(c, buyer);

        for (int round = 1; round <= 8; round++) {
            Instant roundStart = Instant.now();
            Product p = publishedProduct(c, "R19 refund-vs-renewal " + round, 30, Instant.now().minusSeconds(60));
            OrderDto first = pendingOrder(buyer, c, p);
            String firstRef = "txn-first-" + UUID.randomUUID();
            webhook(first.getOrderNumber(), firstRef, "PAYMENT_SUCCESS");
            OrderDto renewal = pendingOrder(buyer, c, p);

            runTogether(List.<Callable<OrderDto>>of(
                    () -> webhook(first.getOrderNumber(), firstRef, "PAYMENT_REFUNDED"),
                    () -> settle(renewal.getOrderNumber())));

            List<Entitlement> chain = chain(buyer, c, p);
            assertEquals(2, chain.size(), "round " + round);
            assertEquals(1, chain.stream().filter(e -> "REVOKED".equals(e.getState())).count(), "round " + round);
            Entitlement renewed = chain.stream().filter(e -> "ACTIVE".equals(e.getState())).findFirst().orElseThrow();
            assertFalse(renewed.getStartsAt().isAfter(Instant.now().plusSeconds(2)),
                    "round " + round + ": the paid renewal must start now, not at the refunded period's old expiry " + renewed.getStartsAt());
            assertFalse(renewed.getStartsAt().isBefore(roundStart.minusSeconds(2)), "round " + round);
            assertEquals(Duration.ofDays(30), Duration.between(renewed.getStartsAt(), renewed.getExpiresAt()), "round " + round);
        }
        assertNoWebhookRetries();
    }

    // ----- R19-03: dates beyond 2038, failures that must not be silent -----

    @Test
    @DisplayName("R19-03: a 3650-day product bought twice settles fine, with the second expiry around 2046")
    void tenYearProductBoughtTwiceStacksPast2038() {
        User owner = newUser("owner");
        Classroom c = newClass(owner);
        User buyer = newUser("buyer");
        join(c, buyer);
        Product p = publishedProduct(c, "R19 10y", 3650, Instant.now().minusSeconds(60));

        OrderDto o1 = pendingOrder(buyer, c, p);
        OrderDto o2 = pendingOrder(buyer, c, p);
        assertEquals("PAID", settle(o1.getOrderNumber()).getStatus());
        assertEquals("PAID", settle(o2.getOrderNumber()).getStatus());

        List<Entitlement> chain = chain(buyer, c, p);
        assertEquals(2, chain.size());
        Instant end = chain.get(1).getExpiresAt();
        assertEquals(chain.get(0).getExpiresAt(), chain.get(1).getStartsAt());
        assertEquals(Duration.ofDays(7300), Duration.between(chain.get(0).getStartsAt(), end));
        assertTrue(end.atZone(ZoneOffset.UTC).getYear() >= 2045, "expiry " + end + " must be past 2038 (was rejected by TIMESTAMP)");

        ProductDto listed = commerceService.getProductsByClass(c.getId(), buyer.getId()).stream()
                .filter(d -> d.getId().equals(p.getId())).findFirst().orElseThrow();
        assertTrue(listed.isUserHasActiveEntitlement());
        assertEquals(end, listed.getEntitlementExpiresAt());
    }

    @Test
    @DisplayName("R19-03: a product whose access starts in 2039 can be created, sold and settled, and the dates round-trip exactly")
    void accessStartingIn2039IsAccepted() {
        User owner = newUser("owner");
        Classroom c = newClass(owner);
        User buyer = newUser("buyer");
        join(c, buyer);
        Instant start = Instant.parse("2039-01-01T00:00:00Z");

        Product created = commerceService.createProduct(c.getId(), null, "R19 2039", "d", new BigDecimal("1000"), 30, start, owner.getId());
        commerceService.publishProduct(created.getId(), owner.getId());
        OrderDto order = pendingOrder(buyer, c, created);
        assertEquals("PAID", settle(order.getOrderNumber()).getStatus());

        Entitlement e = chain(buyer, c, created).get(0);
        assertEquals(start, e.getStartsAt());
        assertEquals(start.plus(Duration.ofDays(30)), e.getExpiresAt());
        assertEquals("2039-01-01 00:00:00", jdbcTemplate.queryForObject(
                "SELECT DATE_FORMAT(starts_at, '%Y-%m-%d %H:%i:%s') FROM entitlements WHERE id = ?", String.class, e.getId()));
        assertEquals("2039-01-31 00:00:00", jdbcTemplate.queryForObject(
                "SELECT DATE_FORMAT(expires_at, '%Y-%m-%d %H:%i:%s') FROM entitlements WHERE id = ?", String.class, e.getId()));
    }

    @Test
    @DisplayName("R19-03: an entitlement that would run past the horizon is refused with a clear 400, the order stays PENDING and the failure is recorded")
    void settlingPastTheHorizonIsRejectedAndRecorded() {
        User owner = newUser("owner");
        Classroom c = newClass(owner);
        User buyer = newUser("buyer");
        join(c, buyer);
        // Starts 39 years out; one 10-year period fits inside 50 years, a second does not.
        Instant start = Instant.now().atZone(ZoneOffset.UTC).plusYears(39).toInstant();
        Product created = commerceService.createProduct(c.getId(), null, "R19 far", "d", new BigDecimal("1000"), 3650, start, owner.getId());
        commerceService.publishProduct(created.getId(), owner.getId());
        OrderDto first = pendingOrder(buyer, c, created);
        OrderDto second = pendingOrder(buyer, c, created); // both created while no entitlement exists yet

        assertEquals("PAID", settle(first.getOrderNumber()).getStatus());
        AppException ex = assertThrows(AppException.class, () -> settle(second.getOrderNumber()));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("giới hạn"), ex.getMessage());
        assertEquals("PENDING", orderRepository.findByOrderNumber(second.getOrderNumber()).orElseThrow().getStatus());
        assertEquals(1, chain(buyer, c, created).size(), "no entitlement may be granted for the refused order");
        Order persisted = orderRepository.findByOrderNumber(second.getOrderNumber()).orElseThrow();
        assertTrue(auditEventRepository.findByClassIdOrderByCreatedAtDesc(c.getId()).stream()
                        .map(AuditEvent::getAction).anyMatch("PAYMENT_SETTLE_FAILED"::equals),
                "an audit row must tell the operator this paid order was not fulfilled");
        assertTrue(outboxEventRepository.existsByAggregateTypeAndAggregateIdAndEventType("COMMERCE", persisted.getId(), "PAYMENT_SETTLE_FAILED"));

        // ... and the friendly path: a THIRD purchase is refused up front, at order creation.
        AppException creation = assertThrows(AppException.class, () -> pendingOrder(buyer, c, created));
        assertEquals(ErrorCode.BAD_REQUEST, creation.getErrorCode());
        assertTrue(creation.getMessage().contains("giới hạn"), creation.getMessage());
    }

    @Test
    @DisplayName("R19-03: a persistence failure while settling answers 500 PAYMENT_SETTLE_FAILED (retryable), keeps the order PENDING and leaves an audit trail")
    void persistenceFailureOnSettleIsRetryableAndNotSilent() {
        User owner = newUser("owner");
        Classroom c = newClass(owner);
        User buyer = newUser("buyer");
        join(c, buyer);
        Product p = publishedProduct(c, "R19 broken snapshot", 30, Instant.now().minusSeconds(60));
        OrderDto order = pendingOrder(buyer, c, p);
        // Make the entitlement insert violate fk_ent_course (the R16-03 shape: the target course vanished).
        jdbcTemplate.update("UPDATE order_items SET target_course_id_snapshot = ? WHERE order_id = ?",
                UUID.randomUUID().toString(), order.getId());

        AppException ex = assertThrows(AppException.class, () -> settle(order.getOrderNumber()));

        assertEquals(ErrorCode.PAYMENT_SETTLE_FAILED, ex.getErrorCode());
        assertEquals(500, ex.getErrorCode().getHttpStatus().value(), "5xx so a real payment provider retries");
        Order persisted = orderRepository.findByOrderNumber(order.getOrderNumber()).orElseThrow();
        assertEquals("PENDING", persisted.getStatus(), "the failed transaction must have rolled back completely");
        assertEquals(0, chain(buyer, c, p).size());
        assertTrue(auditEventRepository.findByClassIdOrderByCreatedAtDesc(c.getId()).stream()
                .map(AuditEvent::getAction).anyMatch("PAYMENT_SETTLE_FAILED"::equals));
        assertTrue(outboxEventRepository.existsByAggregateTypeAndAggregateIdAndEventType("COMMERCE", persisted.getId(), "PAYMENT_SETTLE_FAILED"));

        // Once the data problem is fixed, the provider's retry settles the very same order.
        jdbcTemplate.update("UPDATE order_items SET target_course_id_snapshot = NULL WHERE order_id = ?", order.getId());
        assertEquals("PAID", settle(order.getOrderNumber()).getStatus());
        assertEquals(1, chain(buyer, c, p).size());
    }

    // ----- R19-03: the V30 migration -----

    @Test
    @DisplayName("R19-03: V30 turned every future-date column into DATETIME")
    void v30ConvertedTheFutureDateColumns() {
        String[][] columns = {
                {"entitlements", "starts_at"}, {"entitlements", "expires_at"},
                {"product_prices", "access_starts_at"}, {"order_items", "access_starts_at_snapshot"},
                {"exams", "schedule_start"}, {"exams", "schedule_end"}, {"exam_attempts", "ends_at"},
                {"revoked_tokens", "expires_at"}, {"refresh_tokens", "expires_at"},
                {"leaderboard_recalc_jobs", "next_attempt_at"}};
        for (String[] col : columns) {
            String type = jdbcTemplate.queryForObject(
                    "SELECT DATA_TYPE FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                    String.class, col[0], col[1]);
            assertEquals("datetime", type, col[0] + "." + col[1]);
        }
    }

    @Test
    @DisplayName("R19-03: V30 keeps existing TIMESTAMP values unshifted even when the migrating session is not UTC, and restores the session zone")
    void v30PreservesExistingValuesUnderANonUtcSession() throws Exception {
        User owner = newUser("owner");
        Classroom c = newClass(owner);
        User buyer = newUser("buyer");
        Product p = publishedProduct(c, "R19 v30", 30, Instant.now());
        String entitlementId = UUID.randomUUID().toString();

        // Put the two columns back to what V1 declared, then store real TIMESTAMP data in them. A TIMESTAMP cannot
        // hold the post-2038 rows other tests of this class legitimately created (that is the very bug), so drop them first.
        jdbcTemplate.update("DELETE FROM entitlements WHERE expires_at >= '2038-01-01 00:00:00' OR starts_at >= '2038-01-01 00:00:00'");
        jdbcTemplate.execute("ALTER TABLE entitlements MODIFY COLUMN starts_at TIMESTAMP NOT NULL, MODIFY COLUMN expires_at TIMESTAMP NOT NULL");
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            String originalZone = scalar(st, "SELECT @@SESSION.time_zone");
            try {
                st.execute("SET SESSION time_zone = '+00:00'");
                try (PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO entitlements (id, user_id, class_id, product_id, starts_at, expires_at, state, created_at) "
                                + "VALUES (?, ?, ?, ?, '2030-06-15 12:34:56', '2037-12-31 23:59:59', 'ACTIVE', '2026-01-01 00:00:00')")) {
                    insert.setString(1, entitlementId);
                    insert.setString(2, buyer.getId());
                    insert.setString(3, c.getId());
                    insert.setString(4, p.getId());
                    insert.executeUpdate();
                }
                assertEquals("timestamp", scalar(st, "SELECT DATA_TYPE FROM information_schema.columns WHERE table_schema = DATABASE() "
                        + "AND table_name = 'entitlements' AND column_name = 'starts_at'"));

                // The migrating session is NOT on UTC.
                st.execute("SET SESSION time_zone = '+05:00'");
                ScriptUtils.executeSqlScript(conn, new ClassPathResource(V30));
                assertEquals("+05:00", scalar(st, "SELECT @@SESSION.time_zone"), "V30 must put the session time zone back the way it found it");

                st.execute("SET SESSION time_zone = '+00:00'");
                assertEquals("datetime", scalar(st, "SELECT DATA_TYPE FROM information_schema.columns WHERE table_schema = DATABASE() "
                        + "AND table_name = 'entitlements' AND column_name = 'starts_at'"));
                assertEquals("2030-06-15 12:34:56.000000", scalar(st,
                        "SELECT DATE_FORMAT(starts_at, '%Y-%m-%d %H:%i:%s.%f') FROM entitlements WHERE id = '" + entitlementId + "'"));
                assertEquals("2037-12-31 23:59:59.000000", scalar(st,
                        "SELECT DATE_FORMAT(expires_at, '%Y-%m-%d %H:%i:%s.%f') FROM entitlements WHERE id = '" + entitlementId + "'"));
            } finally {
                st.execute("SET SESSION time_zone = '" + originalZone + "'");
            }
        } finally {
            try (Connection conn = dataSource.getConnection()) { // whatever happened above, leave the schema on V30
                ScriptUtils.executeSqlScript(conn, new ClassPathResource(V30));
            }
        }
        Entitlement viaJpa = entitlementRepository.findById(entitlementId).orElseThrow();
        assertEquals(Instant.parse("2030-06-15T12:34:56Z"), viaJpa.getStartsAt());
        assertEquals(Instant.parse("2037-12-31T23:59:59Z"), viaJpa.getExpiresAt());
    }

    @Test
    @DisplayName("R19-03 (control): without pinning the session to UTC, MySQL WOULD shift the converted values - which is why V30 pins it")
    void unpinnedConversionShiftsValues() throws Exception {
        jdbcTemplate.execute("DROP TABLE IF EXISTS r19_v30_probe");
        jdbcTemplate.execute("CREATE TABLE r19_v30_probe (ts TIMESTAMP NOT NULL)");
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            String originalZone = scalar(st, "SELECT @@SESSION.time_zone");
            try {
                st.execute("SET SESSION time_zone = '+00:00'");
                st.execute("INSERT INTO r19_v30_probe (ts) VALUES ('2030-06-15 12:34:56')");
                st.execute("SET SESSION time_zone = '+05:00'");
                st.execute("ALTER TABLE r19_v30_probe MODIFY COLUMN ts DATETIME(6) NOT NULL");
                assertEquals("2030-06-15 17:34:56", scalar(st, "SELECT DATE_FORMAT(ts, '%Y-%m-%d %H:%i:%s') FROM r19_v30_probe"),
                        "a bare TIMESTAMP -> DATETIME ALTER converts through the session zone");
            } finally {
                st.execute("SET SESSION time_zone = '" + originalZone + "'");
                st.execute("DROP TABLE IF EXISTS r19_v30_probe");
            }
        }
    }

    private static String scalar(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    // ----- R19-06: pre-sale purchase -----

    @Test
    @DisplayName("R19-06: after buying a pre-sale product the store reports it as owned-upcoming with its start date (not as 'not owned')")
    void presalePurchaseIsListedAsOwnedUpcoming() {
        User owner = newUser("owner");
        Classroom c = newClass(owner);
        User buyer = newUser("buyer");
        join(c, buyer);
        Instant start = Instant.now().plus(Duration.ofDays(20)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Product created = commerceService.createProduct(c.getId(), null, "R19 presale", "d", new BigDecimal("1000"), 30, start, owner.getId());
        commerceService.publishProduct(created.getId(), owner.getId());
        OrderDto order = pendingOrder(buyer, c, created);

        assertFalse(listed(c, buyer, created).isUserOwnsUpcoming(), "nothing paid yet");
        settle(order.getOrderNumber());

        ProductDto dto = listed(c, buyer, created);
        assertFalse(dto.isUserHasActiveEntitlement(), "access has not started");
        assertTrue(dto.isUserOwnsUpcoming());
        assertEquals(start, dto.getEntitlementStartsAt());
        assertEquals(start.plus(Duration.ofDays(30)), dto.getEntitlementExpiresAt());
    }

    private ProductDto listed(Classroom c, User viewer, Product p) {
        return commerceService.getProductsByClass(c.getId(), viewer.getId()).stream()
                .filter(d -> d.getId().equals(p.getId())).findFirst().orElseThrow();
    }

    // ----- R19-12: purchasability of a course whose product was archived -----

    @Test
    @DisplayName("R19-12: canPurchase follows the product status; an owner keeps OWNED + expiry after the product is archived")
    void courseReportsWhetherItCanStillBeBought() {
        User owner = newUser("owner");
        Classroom c = newClass(owner);
        User owned = newUser("owned");
        User stranger = newUser("stranger");
        join(c, owned);
        join(c, stranger);
        Course course = courseRepository.save(new Course(c.getId(), "R19 course", "PURCHASE_REQUIRED"));
        course.setStatus("PUBLISHED");
        Product p = new Product(c.getId(), course.getId(), "R19 course product", "d");
        p.setStatus("PUBLISHED");
        p = productRepository.save(p);
        priceRepository.save(new ProductPrice(p.getId(), new BigDecimal("1000"), "VND", 30, Instant.now().minusSeconds(60)));
        course.setProductId(p.getId());
        courseRepository.save(course);
        settle(pendingOrder(owned, c, p).getOrderNumber());

        CourseDto forStranger = course(c, stranger, course);
        assertTrue(forStranger.isCanPurchase(), "a published product can be bought");
        assertEquals("PUBLISHED", forStranger.getProductStatus());

        commerceService.archiveProduct(p.getId(), owner.getId());

        CourseDto strangerAfter = course(c, stranger, course);
        assertFalse(strangerAfter.isCanPurchase(), "an archived product is not in the store any more");
        assertEquals("ARCHIVED", strangerAfter.getProductStatus());
        assertEquals("NOT_PURCHASED", strangerAfter.getAccessReason());

        CourseDto ownedAfter = course(c, owned, course);
        assertTrue(ownedAfter.isCanLearn());
        assertEquals("OWNED", ownedAfter.getAccessReason());
        assertNotNull(ownedAfter.getExpiresAt(), "an existing buyer must still be told when their access ends");
        assertFalse(ownedAfter.isCanPurchase());
        assertEquals("ARCHIVED", learningService.getCourseDetails(course.getId(), owned.getId()).getProductStatus());
    }

    private CourseDto course(Classroom c, User viewer, Course course) {
        return learningService.getCoursesByClass(c.getId(), viewer.getId()).stream()
                .filter(d -> d.getId().equals(course.getId())).findFirst().orElseThrow();
    }

    // ----- R19-01(c): the other "plain read, then lock" call sites -----

    @Test
    @DisplayName("R19-01(c): two documents cannot claim the same media asset when created at the same time, repeatedly")
    void concurrentDocumentsCannotShareOneMediaAsset() throws Exception {
        User owner = newUser("owner");
        Classroom c = newClass(owner);

        for (int round = 1; round <= 6; round++) {
            MediaAsset media = new MediaAsset(c.getId(), owner.getId(), "classes/" + c.getId() + "/media/r19-" + round + ".pdf",
                    "r19-" + round + ".pdf", "application/pdf", 1024L);
            media.setStatus("UPLOADED");
            final String mediaId = mediaAssetRepository.save(media).getId();

            List<Boolean> outcomes = runTogether(List.<Callable<Boolean>>of(
                    () -> createDocument(c, owner, mediaId, "A"),
                    () -> createDocument(c, owner, mediaId, "B")));

            assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count(), "round " + round + ": exactly one attach may win");
            assertEquals(1, documentAssetRepository.findByMediaAssetId(mediaId).size(), "round " + round);
        }
    }

    private boolean createDocument(Classroom c, User owner, String mediaId, String title) {
        try {
            DocumentAsset doc = documentService.createDocument(c.getId(), title, "d", mediaId, "FREE", owner.getId());
            return doc != null;
        } catch (AppException rejected) {
            assertEquals(ErrorCode.BAD_REQUEST, rejected.getErrorCode());
            return false;
        }
    }

    @Test
    @DisplayName("R19-01(c): concurrent question edits cannot jointly exceed the exam total-points cap, repeatedly")
    void concurrentQuestionEditsRespectTheTotalPointsCap() throws Exception {
        User owner = newUser("owner");
        Classroom c = newClass(owner);

        for (int round = 1; round <= 6; round++) {
            Exam exam = new Exam();
            exam.setTitle("R19 cap " + round + " " + System.nanoTime());
            exam.setAudienceScope("ALL");
            exam.setDurationMinutes(30);
            exam = examService.createExam(c.getId(), exam, owner.getId());
            final String examId = exam.getId();
            // 9 x 10_000 + 9_970 + 10 + 10 = 99_990 of the 100_000 cap (a single question is capped at 10_000).
            for (int big = 1; big <= 9; big++) {
                examService.addQuestion(examId, essay(10_000, "big-" + big), List.of(), owner.getId());
            }
            examService.addQuestion(examId, essay(9_970, "big-10"), List.of(), owner.getId());
            final String q1 = examService.addQuestion(examId, essay(10, "small-1"), List.of(), owner.getId()).getId();
            final String q2 = examService.addQuestion(examId, essay(10, "small-2"), List.of(), owner.getId()).getId();

            // Each raise alone fits (99_996); both together would be 100_002.
            List<Boolean> outcomes = runTogether(List.<Callable<Boolean>>of(
                    () -> editPoints(q1, 16, owner),
                    () -> editPoints(q2, 16, owner)));

            long total = questionRepository.findByExamIdOrderByPositionAsc(examId).stream().mapToLong(Question::getPoints).sum();
            assertTrue(total <= ExamService.MAX_EXAM_TOTAL_POINTS, "round " + round + ": total " + total + " exceeds the cap");
            assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count(), "round " + round + ": exactly one raise may fit");
        }
    }

    @Test
    @DisplayName("R19-01(c): removing a member never overwrites an attempt the learner submitted at that moment with a stale CANCELLED")
    void memberRemovalDoesNotOverwriteAConcurrentSubmit() throws Exception {
        User owner = newUser("owner");
        Classroom c = newClass(owner);
        User student = newUser("student");
        join(c, student);
        Exam exam = new Exam();
        exam.setTitle("R19 removal " + System.nanoTime());
        exam.setAudienceScope("ALL");
        exam.setDurationMinutes(30);
        exam = examService.createExam(c.getId(), exam, owner.getId());
        com.classroom.modules.exam.model.ExamAttempt attempt = attemptRepository.save(
                new com.classroom.modules.exam.model.ExamAttempt(exam.getId(), student.getId(), c.getId(), Instant.now().plusSeconds(1800), false));
        final String attemptId = attempt.getId();

        // "The learner submits": a transaction that holds the attempt row and commits SUBMITTED after a pause,
        // exactly like submitAttempt does. The removal starts while that transaction is still open.
        org.springframework.transaction.support.TransactionTemplate submit =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        CountDownLatch rowLocked = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> submitting = pool.submit(() -> submit.executeWithoutResult(tx -> {
                jdbcTemplate.queryForObject("SELECT id FROM exam_attempts WHERE id = ? FOR UPDATE", String.class, attemptId);
                rowLocked.countDown();
                try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                jdbcTemplate.update("UPDATE exam_attempts SET status = 'SUBMITTED' WHERE id = ?", attemptId);
            }));
            assertTrue(rowLocked.await(20, TimeUnit.SECONDS));
            Future<?> removing = pool.submit(() -> memberService.removeMember(c.getId(), student.getId(), owner.getId()));
            submitting.get(60, TimeUnit.SECONDS);
            removing.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals("SUBMITTED", attemptRepository.findById(attemptId).orElseThrow().getStatus(),
                "the removal read IN_PROGRESS from a stale snapshot and wrote CANCELLED over the learner's submission");
        assertEquals("REMOVED", memberRepository.findByClassIdAndUserId(c.getId(), student.getId()).orElseThrow().getState());
    }

    private boolean editPoints(String questionId, int points, User owner) {
        try {
            examService.updateQuestion(questionId, essay(points, "edited"), List.of(), owner.getId());
            return true;
        } catch (AppException rejected) {
            assertEquals(ErrorCode.BAD_REQUEST, rejected.getErrorCode());
            return false;
        }
    }

    private Question essay(int points, String text) {
        Question q = new Question();
        q.setQuestionText(text);
        q.setType("ESSAY");
        q.setPoints(points);
        return q;
    }
}
