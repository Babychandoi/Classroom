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
}
