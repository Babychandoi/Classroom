package com.classroom.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Protocol-level error mapping: client mistakes must never surface as 500.
 */
class GlobalExceptionHandlerTest {

    @RestController
    @RequestMapping("/api/v1/probe")
    static class ProbeController {
        record Body(String name) {}

        @PostMapping
        public ApiResponse<String> create(@RequestBody Body body) {
            return ApiResponse.ok(body.name());
        }

        @GetMapping("/{id}")
        public ApiResponse<String> get(@PathVariable String id) {
            return ApiResponse.ok(id);
        }

        @PostMapping("/referenced")
        public ApiResponse<String> triggerReferencedRow() {
            throw new org.springframework.dao.DataIntegrityViolationException("could not execute statement",
                    new java.sql.SQLIntegrityConstraintViolationException(
                            "Cannot delete or update a parent row: a foreign key constraint fails (`classroom_db`.`staff_permissions`, CONSTRAINT `fk_staff_perm_course`)",
                            "23000", 1451));
        }

        @PostMapping("/missing-parent")
        public ApiResponse<String> triggerMissingParent() {
            throw new org.springframework.dao.DataIntegrityViolationException("could not execute statement",
                    new java.sql.SQLIntegrityConstraintViolationException(
                            "Cannot add or update a child row: a foreign key constraint fails", "23000", 1452));
        }

        @PostMapping("/not-null")
        public ApiResponse<String> triggerNotNull() {
            throw new org.springframework.dao.DataIntegrityViolationException("could not execute statement",
                    new java.sql.SQLIntegrityConstraintViolationException("Column 'title' cannot be null", "23000", 1048));
        }

        @PostMapping("/unavailable")
        public ApiResponse<String> triggerUnavailable() {
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE, "Kho lưu trữ tạm thời không khả dụng", 5);
        }

        @PostMapping("/conflict")
        public ApiResponse<String> triggerConflict() {
            throw new AppException(ErrorCode.CONFLICT, "trùng");
        }

        @PostMapping("/optimistic-conflict")
        public ApiResponse<String> triggerOptimisticConflict() {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException("SomeEntity", "id-1");
        }
    }

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("Malformed JSON body returns 400, not 500")
    void testMalformedJsonReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("Missing request body returns 400, not 500")
    void testMissingBodyReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/probe").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("Unsupported HTTP method returns 405 with Allow header, not 500")
    void testUnsupportedMethodReturnsMethodNotAllowed() throws Exception {
        mockMvc.perform(put("/api/v1/probe/abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("Unsupported content type returns 415, not 500")
    void testUnsupportedMediaTypeReturns415() throws Exception {
        mockMvc.perform(post("/api/v1/probe")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("name=x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    @DisplayName("R12-01: OptimisticLockingFailureException (e.g. a racing bulk update) returns 409 CONFLICT, not 500")
    void testOptimisticLockingConflictReturnsConflict() throws Exception {
        mockMvc.perform(post("/api/v1/probe/optimistic-conflict")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    @Test
    @DisplayName("R18-07: a delete blocked by a foreign key from another table is a 409 with a clear message, not a misleading 400")
    void testReferencedRowViolationReturnsConflict() throws Exception {
        mockMvc.perform(post("/api/v1/probe/referenced").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"))
                .andExpect(jsonPath("$.error.message").value(GlobalExceptionHandler.REFERENCED_DATA_MESSAGE))
                // the driver text (table/constraint names) must never be echoed back
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("fk_staff_perm_course"))));
    }

    @Test
    @DisplayName("R18-07: other integrity violations (missing parent, NOT NULL) keep the generic 400")
    void testOtherIntegrityViolationsStayBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/probe/missing-parent").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
        mockMvc.perform(post("/api/v1/probe/not-null").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("R20-12: an AppException carrying a retry hint answers 503 with Retry-After in the standard envelope")
    void testServiceUnavailableCarriesRetryAfter() throws Exception {
        mockMvc.perform(post("/api/v1/probe/unavailable").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.message").value("Kho lưu trữ tạm thời không khả dụng"));
        // an AppException without a hint sends no Retry-After
        mockMvc.perform(post("/api/v1/probe/conflict").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(header().doesNotExist("Retry-After"));
    }
}
