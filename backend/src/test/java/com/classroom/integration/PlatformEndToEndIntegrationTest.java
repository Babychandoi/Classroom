package com.classroom.integration;

import com.classroom.common.AppException;
import com.classroom.common.ErrorCode;
import com.classroom.config.JwtTokenProvider;
import com.classroom.modules.classroom.model.Classroom;
import com.classroom.modules.classroom.model.ClassMember;
import com.classroom.modules.classroom.repository.ClassMemberRepository;
import com.classroom.modules.classroom.repository.ClassroomRepository;
import com.classroom.modules.commerce.dto.CreateOrderRequest;
import com.classroom.modules.commerce.model.Entitlement;
import com.classroom.modules.commerce.model.Order;
import com.classroom.modules.commerce.model.Product;
import com.classroom.modules.commerce.model.ProductPrice;
import com.classroom.modules.commerce.payment.MockPaymentProvider;
import com.classroom.modules.commerce.repository.EntitlementRepository;
import com.classroom.modules.commerce.repository.OrderItemRepository;
import com.classroom.modules.commerce.repository.OrderRepository;
import com.classroom.modules.commerce.repository.ProductPriceRepository;
import com.classroom.modules.commerce.repository.ProductRepository;
import com.classroom.modules.commerce.service.CommerceService;
import com.classroom.modules.exam.dto.ExamAttemptDto;
import com.classroom.modules.exam.dto.SubmitAttemptRequest;
import com.classroom.modules.exam.model.AnswerOption;
import com.classroom.modules.exam.model.Exam;
import com.classroom.modules.exam.model.ExamAttempt;
import com.classroom.modules.exam.model.Question;
import com.classroom.modules.exam.repository.AnswerOptionRepository;
import com.classroom.modules.exam.repository.ExamAttemptRepository;
import com.classroom.modules.exam.repository.ExamRepository;
import com.classroom.modules.exam.repository.QuestionRepository;
import com.classroom.modules.exam.service.ExamService;
import com.classroom.modules.identity.model.User;
import com.classroom.modules.identity.repository.UserRepository;
import com.classroom.modules.learning.model.Course;
import com.classroom.modules.learning.model.Lesson;
import com.classroom.modules.learning.policy.LearningPolicy;
import com.classroom.modules.learning.repository.CourseRepository;
import com.classroom.modules.learning.repository.LessonRepository;
import com.classroom.modules.learning.repository.SectionRepository;
import com.classroom.modules.media.model.MediaAsset;
import com.classroom.modules.media.repository.MediaAssetRepository;
import com.classroom.modules.outbox.model.OutboxEvent;
import com.classroom.modules.outbox.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class PlatformEndToEndIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ClassroomRepository classroomRepository;

    @Autowired
    private ClassMemberRepository memberRepository;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductPriceRepository productPriceRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private EntitlementRepository entitlementRepository;

    @Autowired
    private ExamRepository examRepository;

    @Autowired
    private QuestionRepository questionRepository;

    @Autowired
    private AnswerOptionRepository answerOptionRepository;

    @Autowired
    private ExamAttemptRepository attemptRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private MediaAssetRepository mediaAssetRepository;

    @Autowired
    private ExamService examService;

    @Autowired
    private CommerceService commerceService;

    @Autowired
    private LearningPolicy learningPolicy;

    @Autowired private LessonRepository lessonRepository;
    @Autowired private SectionRepository sectionRepository;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private MockPaymentProvider mockPaymentProvider;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean(name = "minioClient")
    private MinioClient minioClient;

    @MockBean(name = "minioPresigningClient")
    private MinioClient minioPresigningClient;

    private User student;
    private User otherOwner;
    private Classroom classA;
    private Classroom classB;
    private String studentToken;

    @BeforeEach
    void setUp() throws Exception {
        when(minioPresigningClient.getPresignedObjectUrl(any()))
                .thenReturn("https://minio.test/classroom-media/authorized-signed-url");

        // Retrieve seeded user and class
        student = userRepository.findByEmail("student.free@classroom.local")
                .orElseThrow();
        classA = classroomRepository.findBySlug("lop-toan-nang-cao")
                .orElseThrow();

        studentToken = tokenProvider.generateToken(student.getId(), student.getEmail(), student.getRole());

        // Create second isolated classroom owned by another user
        otherOwner = userRepository.findByEmail("other.owner@classroom.local").orElseGet(() -> {
            User u = new User(null, "other.owner@classroom.local", "hash", "Chủ Lớp Vật Lý", "USER");
            return userRepository.save(u);
        });

        classB = classroomRepository.findBySlug("lop-vat-ly-chuyen").orElseGet(() -> {
            Classroom c = new Classroom();
            c.setOwnerId(otherOwner.getId());
            c.setSlug("lop-vat-ly-chuyen");
            c.setTitle("Lớp Học Vật Lý Chuyên");
            c.setStatus("ACTIVE");
            Classroom saved = classroomRepository.save(c);

            memberRepository.save(new ClassMember(saved.getId(), otherOwner.getId(), "OWNER"));
            return saved;
        });
    }


    @Test
    @DisplayName("Finding 3: Cross-class access isolation rejects non-member requests across all major endpoints")
    void testCrossClassAccessDeniedAcrossEndpoints() throws Exception {
        // student is member of classA, but NOT classB
        assertFalse(memberRepository.findByClassIdAndUserId(classB.getId(), student.getId()).isPresent());

        // 1. Members endpoint
        mockMvc.perform(get("/api/v1/classes/" + classB.getId() + "/members")
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isForbidden());

        // 2. Feed create post endpoint (strictly requires membership)
        mockMvc.perform(post("/api/v1/classes/" + classB.getId() + "/posts")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Post in foreign class\",\"content\":\"Hello\",\"visibility\":\"FREE\"}"))
                .andExpect(status().isForbidden());

        // 3. Exams endpoint
        mockMvc.perform(get("/api/v1/classes/" + classB.getId() + "/exams")
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isForbidden());

        // 4. Documents endpoint
        mockMvc.perform(get("/api/v1/classes/" + classB.getId() + "/documents")
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isForbidden());

        // 5. Courses create endpoint (requires teacher/authoring rights in classB)
        mockMvc.perform(post("/api/v1/classes/" + classB.getId() + "/courses")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Course in foreign class\",\"accessMode\":\"FREE\"}"))
                .andExpect(status().isForbidden());

        // 6. Leaderboard endpoint
        mockMvc.perform(get("/api/v1/classes/" + classB.getId() + "/leaderboard")
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isForbidden());

        // 7. Studio endpoint
        mockMvc.perform(get("/api/v1/studio/classes/" + classB.getId() + "/overview")
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Finding 1 & 2: Active exam attempt resumes at attempt limit=1 and submits concurrently with row lock")
    void testExamResumeAtAttemptLimitAndConcurrentSubmissions() throws Exception {
        // Create an exam in classA with attemptLimit = 1
        Exam exam = new Exam(classA.getId(), "Kiểm Tra Toán Tốc Độ", "ALL", 30);
        exam.setAttemptLimit(1);
        exam.setStatus("PUBLISHED");
        exam.setScheduleStart(Instant.now().minus(1, ChronoUnit.HOURS));
        exam.setScheduleEnd(Instant.now().plus(2, ChronoUnit.HOURS));
        Exam savedExam = examRepository.save(exam);

        Question q = new Question(savedExam.getId(), "Căn bậc hai của 81 là bao nhiêu?", "MULTIPLE_CHOICE", 10, 1, "9");
        Question savedQ = questionRepository.save(q);
        answerOptionRepository.save(new AnswerOption(savedQ.getId(), "A", "7", 1));
        answerOptionRepository.save(new AnswerOption(savedQ.getId(), "B", "8", 2));
        answerOptionRepository.save(new AnswerOption(savedQ.getId(), "C", "9", 3));

        // 1. Student starts initial attempt -> attempt is created (attemptNumber = 1)
        ExamAttemptDto attempt1 = examService.startAttempt(savedExam.getId(), student.getId(), false);
        assertNotNull(attempt1);
        assertEquals("IN_PROGRESS", attempt1.getStatus());
        assertEquals(savedExam.getId(), attempt1.getExamId());

        // 2. Student calls startAttempt again -> Must RESUME active attempt without being blocked by attemptLimit=1
        ExamAttemptDto resumedAttempt = examService.startAttempt(savedExam.getId(), student.getId(), false);
        assertNotNull(resumedAttempt);
        assertEquals(attempt1.getId(), resumedAttempt.getId(), "Resumed attempt must match original attempt ID");
        assertEquals("IN_PROGRESS", resumedAttempt.getStatus());

        // 3. Concurrent submit test: 4 threads simultaneously submitting the same attempt
        SubmitAttemptRequest submitReq = new SubmitAttemptRequest();
        submitReq.setAnswers(Map.of(savedQ.getId(), "9"));

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<ExamAttemptDto>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                return examService.submitAttempt(attempt1.getId(), student.getId(), submitReq);
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        for (Future<ExamAttemptDto> future : futures) {
            ExamAttemptDto dto = future.get(10, TimeUnit.SECONDS);
            assertNotNull(dto);
            assertEquals("PUBLISHED", dto.getStatus());
            assertEquals(BigDecimal.valueOf(100).setScale(2), dto.getScore().setScale(2));
        }
        executor.shutdown();

        // Verify database state: exactly 1 attempt record, status PUBLISHED
        ExamAttempt persistedAttempt = attemptRepository.findById(attempt1.getId()).orElseThrow();
        assertEquals("PUBLISHED", persistedAttempt.getStatus());
        assertEquals(BigDecimal.valueOf(100).setScale(2), persistedAttempt.getScore().setScale(2));

        // Verify outbox idempotency: exactly ONE EXAM_SUBMITTED and ONE EXAM_PUBLISHED event for this attempt
        List<OutboxEvent> outboxEvents = outboxEventRepository.findAll();
        long submitEvents = outboxEvents.stream()
                .filter(e -> "EXAM".equals(e.getAggregateType())
                        && attempt1.getId().equals(e.getAggregateId())
                        && "EXAM_SUBMITTED".equals(e.getEventType()))
                .count();
        long publishEvents = outboxEvents.stream()
                .filter(e -> "EXAM".equals(e.getAggregateType())
                        && attempt1.getId().equals(e.getAggregateId())
                        && "EXAM_PUBLISHED".equals(e.getEventType()))
                .count();

        assertEquals(1, submitEvents, "Exactly one EXAM_SUBMITTED outbox event must be recorded");
        assertEquals(1, publishEvents, "Exactly one EXAM_PUBLISHED outbox event must be recorded");

        // 4. Calling startAttempt a 3rd time must now fail with EXAM_ATTEMPT_LIMIT_REACHED
        AppException ex = assertThrows(AppException.class, () ->
                examService.startAttempt(savedExam.getId(), student.getId(), false)
        );
        assertEquals(ErrorCode.EXAM_ATTEMPT_LIMIT_REACHED, ex.getErrorCode());
    }

    @Test
    @DisplayName("Finding 1: startAttempt enforces scheduleEnd boundary")
    void testExamScheduleEndBoundaryEnforced() {
        Exam expiredExam = new Exam(classA.getId(), "Kỳ Thi Đã Hết Hạn", "ALL", 30);
        expiredExam.setStatus("PUBLISHED");
        expiredExam.setScheduleStart(Instant.now().minus(2, ChronoUnit.HOURS));
        expiredExam.setScheduleEnd(Instant.now().minus(10, ChronoUnit.MINUTES));
        Exam saved = examRepository.save(expiredExam);

        AppException ex = assertThrows(AppException.class, () ->
                examService.startAttempt(saved.getId(), student.getId(), false)
        );
        assertEquals(ErrorCode.EXAM_NOT_OPEN, ex.getErrorCode());
    }

    @Test
    @DisplayName("Finding 3: Payment webhook HMAC verification, order state transition, and refund entitlement revocation")
    void testPaymentWebhookAndRefundEntitlementConsistency() throws Exception {
        // 1. Create paid course in classA
        Course paidCourse = new Course(classA.getId(), "Khóa Luyện Thi Chuyên Đề A", "PURCHASE_REQUIRED");
        paidCourse.setStatus("PUBLISHED");
        final Course savedPaidCourse = courseRepository.save(paidCourse);
        final String targetCourseId = savedPaidCourse.getId();

        // 2. Create product for this course
        Product product = new Product(classA.getId(), targetCourseId, "Gói Học Chuyên Đề A 30 Ngày", "Mô tả");
        product.setStatus("PUBLISHED");
        product = productRepository.save(product);
        savedPaidCourse.setProductId(product.getId());
        courseRepository.save(savedPaidCourse);

        ProductPrice price = new ProductPrice(product.getId(), new BigDecimal("199000"), "VND", 30);
        productPriceRepository.save(price);

        // Verify student does not have access initially
        assertFalse(learningPolicy.canLearn(student.getId(), savedPaidCourse));

        // 3. Student creates order
        CreateOrderRequest orderReq = new CreateOrderRequest();
        orderReq.setClassId(classA.getId());
        orderReq.setProductId(product.getId());
        orderReq.setIdempotencyKey("idem-" + UUID.randomUUID());

        var orderDto = commerceService.createOrder(student.getId(), orderReq);
        assertEquals("PENDING", orderDto.getStatus());
        assertEquals(0, new BigDecimal("199000").compareTo(orderDto.getTotalAmount()));

        // 4. Send valid payment webhook signed with HMAC
        String webhookPayload = String.format(
                "{\"orderNumber\":\"%s\",\"providerRef\":\"txn-12345\",\"eventType\":\"PAYMENT_SUCCESS\",\"amount\":199000,\"currency\":\"VND\"}",
                orderDto.getOrderNumber()
        );
        String signature = mockPaymentProvider.generateSignature(webhookPayload);

        mockMvc.perform(post("/api/v1/payments/mock/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", signature)
                        .content(webhookPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"));

        // Verify order is now PAID
        Order paidOrder = orderRepository.findByOrderNumber(orderDto.getOrderNumber()).orElseThrow();
        assertEquals("PAID", paidOrder.getStatus());
        assertTrue(orderItemRepository.countSettledPurchasesByProductId(product.getId()) > 0,
                "Settled product purchase query must identify the product as non-repurposable");

        // Verify Entitlement is ACTIVE
        List<Entitlement> entitlements = entitlementRepository.findByUserIdAndClassId(student.getId(), classA.getId());
        boolean hasActiveCourseEntitlement = entitlements.stream()
                .anyMatch(e -> targetCourseId.equals(e.getTargetCourseId()) && "ACTIVE".equals(e.getState()));
        assertTrue(hasActiveCourseEntitlement, "User must hold active entitlement for purchased course");

        // Verify student can now learn the course!
        assertTrue(learningPolicy.canLearn(student.getId(), savedPaidCourse));

        // Exercise real persisted entitlement lookup through exam authoring and the attempt API.
        User owner = userRepository.findById(classA.getOwnerId()).orElseThrow();
        String ownerToken = tokenProvider.generateToken(owner.getId(), owner.getEmail(), owner.getRole());
        String examJson = objectMapper.writeValueAsString(Map.of(
                "title", "Paid course exam", "audienceScope", "COURSE", "targetCourseId", targetCourseId,
                "durationMinutes", 30, "attemptLimit", 2));
        String examResponse = mockMvc.perform(post("/api/v1/classes/" + classA.getId() + "/exams")
                        .header("Authorization", "Bearer " + ownerToken).contentType(MediaType.APPLICATION_JSON).content(examJson))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String examId = objectMapper.readTree(examResponse).path("data").path("id").asText();
        Exam publishedExam = examRepository.findById(examId).orElseThrow();
        publishedExam.setStatus("PUBLISHED");
        examRepository.save(publishedExam);
        mockMvc.perform(post("/api/v1/exams/" + examId + "/attempts")
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isOk());

        // 5. Send duplicate payment webhook -> must be idempotent without creating duplicate entitlements
        mockMvc.perform(post("/api/v1/payments/mock/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", signature)
                        .content(webhookPayload))
                .andExpect(status().isOk());

        long activeCourseEntitlements = entitlementRepository.findByUserIdAndClassId(student.getId(), classA.getId()).stream()
                .filter(e -> targetCourseId.equals(e.getTargetCourseId()) && "ACTIVE".equals(e.getState()))
                .count();
        assertEquals(1, activeCourseEntitlements, "Duplicate webhook must not generate duplicate active entitlements");

        // 6. Send tampered webhook -> rejected with 400 Bad Request
        mockMvc.perform(post("/api/v1/payments/mock/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", "invalid-fake-signature")
                        .content(webhookPayload))
                .andExpect(status().isBadRequest());

        // 7. Send refund webhook -> transitions order to REFUNDED and revokes entitlement
        String refundPayload = String.format(
                "{\"orderNumber\":\"%s\",\"providerRef\":\"txn-12345\",\"eventType\":\"PAYMENT_REFUNDED\",\"amount\":199000,\"currency\":\"VND\"}",
                orderDto.getOrderNumber()
        );
        String refundSignature = mockPaymentProvider.generateSignature(refundPayload);

        mockMvc.perform(post("/api/v1/payments/mock/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", refundSignature)
                        .content(refundPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REFUNDED"));

        Order refundedOrder = orderRepository.findByOrderNumber(orderDto.getOrderNumber()).orElseThrow();
        assertEquals("REFUNDED", refundedOrder.getStatus());

        // Verify entitlement is now REVOKED
        boolean hasRevokedEntitlement = entitlementRepository.findByUserIdAndClassId(student.getId(), classA.getId()).stream()
                .anyMatch(e -> targetCourseId.equals(e.getTargetCourseId()) && "REVOKED".equals(e.getState()));
        assertTrue(hasRevokedEntitlement, "Entitlement must transition to REVOKED upon refund");

        // The already-started attempt remains resumable by design; make this assertion exercise
        // admission for a fresh attempt after the refund instead.
        attemptRepository.findByExamIdAndUserIdOrderByStartedAtDesc(examId, student.getId()).stream()
                .filter(attempt -> "IN_PROGRESS".equalsIgnoreCase(attempt.getStatus()))
                .forEach(attempt -> {
                    attempt.setStatus("SUBMITTED");
                    attemptRepository.save(attempt);
                });
        mockMvc.perform(post("/api/v1/exams/" + examId + "/attempts")
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isForbidden());

        // Verify student access is immediately revoked!
        assertFalse(learningPolicy.canLearn(student.getId(), savedPaidCourse));
    }

    @Test
    @DisplayName("Refund and paid renewal serialize on the product row and preserve immediate renewal access")
    void concurrentRefundAndRenewalRemainConsistent() throws Exception {
        Course course = courseRepository.save(new Course(classA.getId(), "Concurrent renewal", "PURCHASE_REQUIRED"));
        Product product = new Product(classA.getId(), course.getId(), "Concurrent renewal product", "Test");
        product.setStatus("PUBLISHED");
        product = productRepository.save(product);
        final String productId = product.getId();
        course.setProductId(product.getId());
        courseRepository.save(course);
        productPriceRepository.save(new ProductPrice(product.getId(), new BigDecimal("199000"), "VND", 30));

        CreateOrderRequest firstRequest = new CreateOrderRequest();
        firstRequest.setClassId(classA.getId());
        firstRequest.setProductId(product.getId());
        firstRequest.setIdempotencyKey("refund-renewal-first-" + UUID.randomUUID());
        var firstOrder = commerceService.createOrder(student.getId(), firstRequest);
        String firstPaid = webhook(firstOrder.getOrderNumber(), "txn-first-" + UUID.randomUUID(), "PAYMENT_SUCCESS");
        commerceService.handlePaymentWebhook("MOCK", objectMapper.readValue(firstPaid, com.classroom.modules.commerce.dto.WebhookPayload.class),
                firstPaid, mockPaymentProvider.generateSignature(firstPaid));

        CreateOrderRequest renewalRequest = new CreateOrderRequest();
        renewalRequest.setClassId(classA.getId());
        renewalRequest.setProductId(product.getId());
        renewalRequest.setIdempotencyKey("refund-renewal-next-" + UUID.randomUUID());
        var renewalOrder = commerceService.createOrder(student.getId(), renewalRequest);
        // Use the persisted transaction reference from the first successful payment.
        String providerRef = orderRepository.findByOrderNumber(firstOrder.getOrderNumber()).orElseThrow().getProviderRef();
        String refund = webhook(firstOrder.getOrderNumber(), providerRef, "PAYMENT_REFUNDED");
        String renewal = webhook(renewalOrder.getOrderNumber(), "txn-next-" + UUID.randomUUID(), "PAYMENT_SUCCESS");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> refundFuture = executor.submit(() -> {
            start.await();
            commerceService.handlePaymentWebhook("MOCK", objectMapper.readValue(refund, com.classroom.modules.commerce.dto.WebhookPayload.class), refund,
                    mockPaymentProvider.generateSignature(refund));
            return null;
        });
        Future<?> renewalFuture = executor.submit(() -> {
            start.await();
            commerceService.handlePaymentWebhook("MOCK", objectMapper.readValue(renewal, com.classroom.modules.commerce.dto.WebhookPayload.class), renewal,
                    mockPaymentProvider.generateSignature(renewal));
            return null;
        });
        start.countDown();
        refundFuture.get(15, TimeUnit.SECONDS);
        renewalFuture.get(15, TimeUnit.SECONDS);
        executor.shutdownNow();

        List<Entitlement> productEntitlements = entitlementRepository.findByUserIdAndClassId(student.getId(), classA.getId()).stream()
                .filter(e -> productId.equals(e.getProductId())).toList();
        assertEquals(1, productEntitlements.stream().filter(e -> "REVOKED".equals(e.getState())).count());
        Entitlement renewed = productEntitlements.stream().filter(e -> "ACTIVE".equals(e.getState())).findFirst().orElseThrow();
        assertFalse(renewed.getStartsAt().isAfter(Instant.now().plusSeconds(2)), "Paid renewal must not remain scheduled behind refunded time");
        assertEquals(30L, ChronoUnit.DAYS.between(renewed.getStartsAt(), renewed.getExpiresAt()));
    }

    private String webhook(String orderNumber, String providerRef, String eventType) {
        return String.format(Locale.ROOT,
                "{\"orderNumber\":\"%s\",\"providerRef\":\"%s\",\"eventType\":\"%s\",\"amount\":199000,\"currency\":\"VND\"}",
                orderNumber, providerRef, eventType);
    }

    @Test
    @DisplayName("Finding 3: Media authorization rejects unauthenticated and non-member download requests")
    void testMediaAuthorizationSecurity() throws Exception {
        MediaAsset asset = new MediaAsset(classA.getId(), student.getId(), "classes/" + classA.getId() + "/media/test.pdf",
                "test.pdf", "application/pdf", 1024L);
        asset.setStatus("UPLOADED");
        MediaAsset savedAsset = mediaAssetRepository.save(asset);

        // 1. Unauthenticated download request -> 401
        mockMvc.perform(get("/api/v1/media/" + savedAsset.getId() + "/download-url"))
                .andExpect(status().isUnauthorized());

        // 2. Non-member download request -> 403
        User stranger = userRepository.save(new User(null, "stranger@classroom.local", "pass", "Người Lạ", "USER"));
        String strangerToken = tokenProvider.generateToken(stranger.getId(), stranger.getEmail(), stranger.getRole());

        mockMvc.perform(get("/api/v1/media/" + savedAsset.getId() + "/download-url")
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isForbidden());
    }
}
