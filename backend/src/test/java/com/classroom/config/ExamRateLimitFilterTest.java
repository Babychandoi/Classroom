package com.classroom.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R14-09 / R14-10: the exam throttle is keyed on user + endpoint KIND (not the resource path), its
 * tracking store is hard-capped, and a rejection is the standard UTF-8 ApiResponse envelope with a
 * Retry-After header.
 */
class ExamRateLimitFilterTest {

    private static final int START_LIMIT = 20;
    private static final int SUBMIT_LIMIT = 20;
    private static final int SAVE_LIMIT = 300;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- R14-10: key = user + kind

    @Test void prepaidAutosaveUsesOnlyReservedPermitsAndFailsClosed() throws Exception {
        var store = org.mockito.Mockito.mock(SharedRateLimitStore.class);
        org.mockito.Mockito.when(store.reserve("exam:user-1:SAVE_ANSWERS", 300, 8))
                .thenReturn(new SharedRateLimitStore.Reservation(8, 60000, 60))
                .thenReturn(new SharedRateLimitStore.Reservation(0, 60000, 60));
        var filter = new ExamRateLimitFilter();
        org.springframework.test.util.ReflectionTestUtils.setField(filter, "sharedStore", store);
        authenticateAs("user-1");
        for (int i = 0; i < 8; i++) assertEquals(200, call(filter, saveRequest("attempt-" + i)).getStatus());
        for (int i = 0; i < 5; i++) assertEquals(429, call(filter, saveRequest("other-" + i)).getStatus());
        org.mockito.Mockito.verify(store, org.mockito.Mockito.times(2)).reserve("exam:user-1:SAVE_ANSWERS", 300, 8);
    }

    @Test void alreadyExpiredReservedPermitsAreNeverAdmitted() throws Exception {
        var store = org.mockito.Mockito.mock(SharedRateLimitStore.class);
        org.mockito.Mockito.when(store.reserve("exam:user-1:SAVE_ANSWERS", 300, 8))
                .thenReturn(new SharedRateLimitStore.Reservation(8, 0, 1));
        org.mockito.Mockito.when(store.acquire("exam:user-1:SAVE_ANSWERS", 300)).thenReturn(60L);
        var filter = new ExamRateLimitFilter();
        org.springframework.test.util.ReflectionTestUtils.setField(filter, "sharedStore", store);
        authenticateAs("user-1");
        assertEquals(429, call(filter, saveRequest("attempt")).getStatus());
        org.mockito.Mockito.verify(store).acquire("exam:user-1:SAVE_ANSWERS", 300);
    }

    @Test void slowReservationRechecksCurrentWindowWithoutReusingExpiredBatch() throws Exception {
        var store = org.mockito.Mockito.mock(SharedRateLimitStore.class);
        org.mockito.Mockito.when(store.reserve("exam:user-1:SAVE_ANSWERS", 300, 8))
                .thenReturn(new SharedRateLimitStore.Reservation(8, 0, 1))
                .thenReturn(new SharedRateLimitStore.Reservation(0, 60000, 60));
        org.mockito.Mockito.when(store.acquire("exam:user-1:SAVE_ANSWERS", 300)).thenReturn(0L);
        var filter = new ExamRateLimitFilter();
        org.springframework.test.util.ReflectionTestUtils.setField(filter, "sharedStore", store);
        authenticateAs("user-1");
        assertEquals(200, call(filter, saveRequest("first")).getStatus());
        assertEquals(429, call(filter, saveRequest("second")).getStatus());
        org.mockito.Mockito.verify(store, org.mockito.Mockito.times(2)).reserve("exam:user-1:SAVE_ANSWERS", 300, 8);
        org.mockito.Mockito.verify(store).acquire("exam:user-1:SAVE_ANSWERS", 300);
    }

    @Test void databaseTransactionCannotStartReturnsServiceUnavailable() throws Exception {
        var store = org.mockito.Mockito.mock(SharedRateLimitStore.class);
        org.mockito.Mockito.when(store.reserve("exam:user-1:SAVE_ANSWERS", 300, 8))
                .thenThrow(new org.springframework.transaction.CannotCreateTransactionException("Database unavailable"));
        var filter = new ExamRateLimitFilter();
        org.springframework.test.util.ReflectionTestUtils.setField(filter, "sharedStore", store);
        authenticateAs("user-1");
        var response = call(filter, saveRequest("attempt"));
        assertEquals(503, response.getStatus());
        assertEquals("5", response.getHeader("Retry-After"));
    }

    @Test
    @DisplayName("R14-10: the same user hitting DIFFERENT exam ids shares one start-attempt budget")
    void sameUserDifferentExamIdsShareTheStartBudget() throws Exception {
        ExamRateLimitFilter filter = new ExamRateLimitFilter();
        authenticateAs("user-1");

        for (int i = 0; i < START_LIMIT; i++) {
            assertEquals(200, call(filter, startRequest(UUID.randomUUID().toString())).getStatus(),
                    "start #" + (i + 1) + " (each on a different exam id) must be within budget");
        }
        // A brand-new exam id must NOT get a fresh budget.
        assertEquals(429, call(filter, startRequest(UUID.randomUUID().toString())).getStatus());
        assertEquals(1, filter.trackedWindows(), "one (user, START) window regardless of how many exam ids");
    }

    @Test
    @DisplayName("R14-10: the same user hitting different attempt ids shares one save-answers budget")
    void sameUserDifferentAttemptIdsShareTheSaveBudget() throws Exception {
        ExamRateLimitFilter filter = new ExamRateLimitFilter();
        authenticateAs("user-1");

        for (int i = 0; i < SAVE_LIMIT; i++) {
            assertEquals(200, call(filter, saveRequest(UUID.randomUUID().toString())).getStatus());
        }
        assertEquals(429, call(filter, saveRequest(UUID.randomUUID().toString())).getStatus());
        assertEquals(1, filter.trackedWindows());
    }

    @Test
    @DisplayName("R14-10: start, save-answers and submit keep independent budgets for the same user")
    void kindsHaveIndependentBudgets() throws Exception {
        ExamRateLimitFilter filter = new ExamRateLimitFilter();
        authenticateAs("user-1");

        for (int i = 0; i < SUBMIT_LIMIT; i++) {
            assertEquals(200, call(filter, submitRequest(UUID.randomUUID().toString())).getStatus());
        }
        assertEquals(429, call(filter, submitRequest(UUID.randomUUID().toString())).getStatus());

        // Exhausting submit must not starve autosave or start.
        assertEquals(200, call(filter, saveRequest(UUID.randomUUID().toString())).getStatus());
        assertEquals(200, call(filter, startRequest(UUID.randomUUID().toString())).getStatus());
        assertEquals(3, filter.trackedWindows());
    }

    @Test
    @DisplayName("R14-10: different users never share a budget")
    void differentUsersDoNotShareABudget() throws Exception {
        ExamRateLimitFilter filter = new ExamRateLimitFilter();

        authenticateAs("user-1");
        for (int i = 0; i < START_LIMIT; i++) {
            assertEquals(200, call(filter, startRequest("exam-a")).getStatus());
        }
        assertEquals(429, call(filter, startRequest("exam-a")).getStatus());

        authenticateAs("user-2");
        assertEquals(200, call(filter, startRequest("exam-a")).getStatus());
    }

    @Test
    @DisplayName("R14-10: random ids cannot grow the tracking map - it stays at one entry per (user, kind)")
    void randomIdsDoNotGrowTheMap() throws Exception {
        ExamRateLimitFilter filter = new ExamRateLimitFilter();
        authenticateAs("user-1");

        for (int i = 0; i < 5000; i++) {
            call(filter, saveRequest(UUID.randomUUID().toString()));
            call(filter, startRequest(UUID.randomUUID().toString()));
            call(filter, submitRequest(UUID.randomUUID().toString()));
        }
        assertEquals(3, filter.trackedWindows(), "3 kinds x 1 user, independent of 15000 random ids");
    }

    @Test
    @DisplayName("R14-10: hard cap bounds the map across many users and evicts the OLDEST windows first")
    void hardCapEvictsOldestWindows() throws Exception {
        int cap = 50;
        AtomicLong clock = new AtomicLong(1_000_000L);
        ExamRateLimitFilter filter = new ExamRateLimitFilter(cap, clock::get);

        // Fill to the cap with users that are ALL rate-limited (budget exhausted), oldest = user-0.
        for (int u = 0; u < cap; u++) {
            authenticateAs("user-" + u);
            for (int i = 0; i < START_LIMIT; i++) {
                assertEquals(200, call(filter, startRequest("e")).getStatus());
            }
            clock.incrementAndGet(); // keep start times strictly ordered, all still inside one window
        }
        assertEquals(cap, filter.trackedWindows());

        // 450 more distinct users: the map never exceeds the cap.
        for (int u = cap; u < 500; u++) {
            authenticateAs("user-" + u);
            call(filter, startRequest("e"));
            assertTrue(filter.trackedWindows() <= cap, "tracked windows " + filter.trackedWindows() + " exceeded cap " + cap);
        }
        assertEquals(cap, filter.trackedWindows());

        // Oldest-first: the very first user's (long evicted) window is gone, so it starts fresh...
        authenticateAs("user-0");
        assertEquals(200, call(filter, startRequest("e")).getStatus());
        // ...whereas a recently tracked user still has its window and its count.
        authenticateAs("user-499");
        for (int i = 0; i < START_LIMIT - 1; i++) { // one call already made above
            assertEquals(200, call(filter, startRequest("e")).getStatus());
        }
        assertEquals(429, call(filter, startRequest("e")).getStatus());
        assertTrue(filter.trackedWindows() <= cap);
    }

    @Test
    @DisplayName("R14-10: expired windows are swept and the budget resets in the next window")
    void expiredWindowsAreSweptAndBudgetResets() throws Exception {
        AtomicLong clock = new AtomicLong(2_000_000L);
        ExamRateLimitFilter filter = new ExamRateLimitFilter(1000, clock::get);

        for (int u = 0; u < 100; u++) {
            authenticateAs("user-" + u);
            call(filter, startRequest("e"));
        }
        assertEquals(100, filter.trackedWindows());

        // 61s later a single request from anyone sweeps every expired window.
        clock.addAndGet(61);
        authenticateAs("late-user");
        assertEquals(200, call(filter, startRequest("e")).getStatus());
        assertEquals(1, filter.trackedWindows(), "all 100 expired windows are removed; only the new one remains");

        // ...and an exhausted user gets a fresh budget once its window has expired.
        authenticateAs("user-x");
        for (int i = 0; i < START_LIMIT; i++) {
            assertEquals(200, call(filter, startRequest("e")).getStatus());
        }
        assertEquals(429, call(filter, startRequest("e")).getStatus());
        clock.addAndGet(60);
        assertEquals(200, call(filter, startRequest("e")).getStatus());
    }

    @Test
    @DisplayName("R14-10: the budget is enforced exactly under concurrent requests")
    void concurrentRequestsNeverExceedTheBudget() throws Exception {
        ExamRateLimitFilter filter = new ExamRateLimitFilter();
        int threads = 8;
        int perThread = 40; // 320 attempts against a budget of 20
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                Callable<Integer> task = () -> {
                    authenticateAs("user-1"); // SecurityContext is thread-local
                    int ok = 0;
                    for (int i = 0; i < perThread; i++) {
                        if (call(filter, startRequest(UUID.randomUUID().toString())).getStatus() == 200) ok++;
                    }
                    SecurityContextHolder.clearContext();
                    return ok;
                };
                futures.add(pool.submit(task));
            }
            int allowed = 0;
            for (Future<Integer> f : futures) allowed += f.get();
            assertEquals(START_LIMIT, allowed);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------- R14-09: envelope, charset and Retry-After

    @Test
    @DisplayName("R14-09: a 429 is UTF-8 JSON in the standard ApiResponse envelope with a Retry-After header")
    void rateLimitedResponseIsUtf8EnvelopeWithRetryAfter() throws Exception {
        AtomicLong clock = new AtomicLong(3_000_000L);
        ExamRateLimitFilter filter = new ExamRateLimitFilter(100, clock::get);
        authenticateAs("user-1");
        for (int i = 0; i < START_LIMIT; i++) {
            call(filter, startRequest("e"));
        }
        clock.addAndGet(25);

        MockHttpServletRequest blocked = startRequest("e");
        blocked.addHeader("X-Request-Id", "req-exam-429");
        MockHttpServletResponse response = call(filter, blocked);

        assertEquals(429, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"), response.getContentType());
        assertTrue(response.getCharacterEncoding().equalsIgnoreCase("UTF-8"), response.getCharacterEncoding());
        assertEquals("35", response.getHeader("Retry-After"), "60s window - 25s elapsed");

        String raw = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertTrue(raw.contains("Quá nhiều yêu cầu làm bài thi"), "Vietnamese text must round-trip as UTF-8: " + raw);
        JsonNode json = objectMapper.readTree(raw);
        assertFalse(json.get("success").asBoolean());
        assertEquals("RATE_LIMITED", json.get("error").get("code").asText());
        assertEquals(ExamRateLimitFilter.RATE_LIMITED_MESSAGE, json.get("error").get("message").asText());
        assertEquals("req-exam-429", json.get("error").get("requestId").asText());
        assertEquals("req-exam-429", json.get("requestId").asText());
        assertNotNull(json.get("timestamp"));
        assertFalse(json.has("code"), "no legacy top-level {code,message} body");
    }

    @Test
    @DisplayName("R14-09: Retry-After is always within 1..60 seconds")
    void retryAfterIsBounded() throws Exception {
        AtomicLong clock = new AtomicLong(4_000_000L);
        ExamRateLimitFilter filter = new ExamRateLimitFilter(100, clock::get);
        authenticateAs("user-1");
        for (int i = 0; i < START_LIMIT; i++) {
            call(filter, startRequest("e"));
        }
        MockHttpServletResponse response = call(filter, startRequest("e"));
        assertEquals(429, response.getStatus());
        long retryAfter = Long.parseLong(response.getHeader("Retry-After"));
        assertTrue(retryAfter >= 1 && retryAfter <= 60, "Retry-After=" + retryAfter);
    }

    // ------------------------------------------------------------------------- pass-through paths

    @Test
    @DisplayName("Requests without an authenticated principal or on other endpoints are not throttled")
    void unauthenticatedAndUnrelatedRequestsPassThrough() throws Exception {
        ExamRateLimitFilter filter = new ExamRateLimitFilter();

        // No authentication on the context: never throttled here, nothing tracked.
        for (int i = 0; i < START_LIMIT + 5; i++) {
            assertEquals(200, call(filter, startRequest("e")).getStatus());
        }
        assertEquals(0, filter.trackedWindows());

        authenticateAs("user-1");
        MockHttpServletRequest other = new MockHttpServletRequest("GET", "/api/v1/exams/e/attempts");
        other.setServletPath("/api/v1/exams/e/attempts");
        assertTrue(filter.shouldNotFilter(other), "GET on the attempts collection is not a throttled endpoint");
        MockHttpServletRequest unrelated = new MockHttpServletRequest("POST", "/api/v1/courses");
        unrelated.setServletPath("/api/v1/courses");
        assertTrue(filter.shouldNotFilter(unrelated));
        assertFalse(filter.shouldNotFilter(startRequest("e")));
    }

    // ------------------------------------------------------------- R20-13: match on the URI, not the servlet path

    @Test
    @DisplayName("R20-13: a request whose servletPath is EMPTY (MockMvc / other servlet mapping) is still throttled - it used to fail open")
    void emptyServletPathIsStillThrottled() throws Exception {
        ExamRateLimitFilter filter = new ExamRateLimitFilter();
        authenticateAs("user-1");

        MockHttpServletRequest probe = new MockHttpServletRequest("POST", "/api/v1/exams/e/attempts"); // servletPath left empty
        assertEquals("", probe.getServletPath());
        assertFalse(filter.shouldNotFilter(probe), "the start-attempt endpoint must be recognised without a servlet path");

        for (int i = 0; i < START_LIMIT; i++) {
            assertEquals(200, call(filter, new MockHttpServletRequest("POST", "/api/v1/exams/e" + i + "/attempts")).getStatus());
        }
        assertEquals(429, call(filter, new MockHttpServletRequest("POST", "/api/v1/exams/again/attempts")).getStatus());
        assertEquals(200, call(filter, new MockHttpServletRequest("PUT", "/api/v1/attempts/a1/answers")).getStatus(),
                "the save-answers budget is separate and still open");
    }

    @Test
    @DisplayName("R20-13: under a context path the throttle still matches (URI minus context path), for all three endpoint kinds")
    void contextPathIsStrippedBeforeMatching() throws Exception {
        ExamRateLimitFilter filter = new ExamRateLimitFilter();
        authenticateAs("user-1");

        MockHttpServletRequest start = inContext("/classroom", "POST", "/api/v1/exams/e/attempts");
        MockHttpServletRequest save = inContext("/classroom", "PUT", "/api/v1/attempts/a/answers");
        MockHttpServletRequest submit = inContext("/classroom", "POST", "/api/v1/attempts/a/submit");
        assertFalse(filter.shouldNotFilter(start));
        assertFalse(filter.shouldNotFilter(save));
        assertFalse(filter.shouldNotFilter(submit));

        for (int i = 0; i < SUBMIT_LIMIT; i++) {
            assertEquals(200, call(filter, inContext("/classroom", "POST", "/api/v1/attempts/a" + i + "/submit")).getStatus());
        }
        MockHttpServletResponse blocked = call(filter, inContext("/classroom", "POST", "/api/v1/attempts/zzz/submit"));
        assertEquals(429, blocked.getStatus());
        assertNotNull(blocked.getHeader("Retry-After"));

        // Other endpoints under the same context path stay untouched.
        assertTrue(filter.shouldNotFilter(inContext("/classroom", "POST", "/api/v1/courses")));
        assertTrue(filter.shouldNotFilter(inContext("/classroom", "GET", "/api/v1/exams/e/attempts")));
    }

    @Test
    @DisplayName("R20-13: a context path is only stripped at a path-segment boundary (/app must not eat /application)")
    void contextPathOnlyStrippedAtSegmentBoundary() {
        MockHttpServletRequest lookalike = new MockHttpServletRequest("POST", "/application/api/v1/attempts/a/submit");
        lookalike.setContextPath("/app");
        assertEquals("/application/api/v1/attempts/a/submit", RequestPaths.withinContext(lookalike));
        assertEquals("/api/v1/x", RequestPaths.withinContext(inContext("/app", "GET", "/api/v1/x")));
        assertEquals("/api/v1/x", RequestPaths.withinContext(new MockHttpServletRequest("GET", "/api/v1/x")));
        assertEquals("", RequestPaths.withinContext(inContext("/app", "GET", "")));
    }

    // ----------------------------------------------------------------------------------- helpers

    private static void authenticateAs(String userId) {
        UserPrincipal principal = new UserPrincipal(userId, userId + "@example.test", "x", "Student " + userId, "STUDENT", "ACTIVE");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static MockHttpServletResponse call(ExamRateLimitFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, new MockFilterChain());
        return response;
    }

    private static MockHttpServletRequest startRequest(String examId) {
        return request("POST", "/api/v1/exams/" + examId + "/attempts");
    }

    private static MockHttpServletRequest saveRequest(String attemptId) {
        return request("PUT", "/api/v1/attempts/" + attemptId + "/answers");
    }

    private static MockHttpServletRequest submitRequest(String attemptId) {
        return request("POST", "/api/v1/attempts/" + attemptId + "/submit");
    }

    /** A request served under {@code contextPath} with an EMPTY servlet path, exactly what MockMvc and a prefix servlet mapping produce. */
    private static MockHttpServletRequest inContext(String contextPath, String method, String pathBelowContext) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, contextPath + pathBelowContext);
        request.setContextPath(contextPath);
        return request;
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }
}
