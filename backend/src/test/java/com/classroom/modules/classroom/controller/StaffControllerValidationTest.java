package com.classroom.modules.classroom.controller;

import com.classroom.common.GlobalExceptionHandler;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.classroom.dto.StaffAssignmentDto;
import com.classroom.modules.classroom.service.StaffService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.ModelAndViewContainer;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * R5-08: StaffController's {@code PUT .../permissions} takes a
 * {@code @Valid @RequestBody List<@Valid StaffPermissionDto>}. Spring 6.2's method-level
 * validation for that shape throws {@link org.springframework.web.method.annotation.HandlerMethodValidationException},
 * which {@link GlobalExceptionHandler} maps to 400 (R4-02) — this test exercises the real
 * controller + real exception handler end to end (MockMvc standalone), rather than only asserting
 * the mapper handles the exception type in isolation.
 */
class StaffControllerValidationTest {

    private MockMvc mockMvc;
    private StaffService staffService;

    @BeforeEach
    void setUp() {
        staffService = mock(StaffService.class);
        StaffController controller = new StaffController(staffService);

        UserPrincipal principal = new UserPrincipal("owner-1", "owner@test.local", "hash", "Owner", "STAFF", "ACTIVE");

        HandlerMethodArgumentResolver principalResolver = new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType().equals(UserPrincipal.class);
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                           NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                return principal;
            }
        };

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(principalResolver)
                .setValidator(new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean())
                .build();
    }

    @Test
    @DisplayName("R5-08: a permission entry with a null module returns 400, not 500")
    void nullModuleInPermissionListReturns400() throws Exception {
        String body = new ObjectMapper().writeValueAsString(java.util.List.of(
                java.util.Map.of("action", "VIEW") // module intentionally omitted -> null
        ));

        mockMvc.perform(put("/api/v1/classes/class-1/staff/target-1/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("R5-08: a permission entry with a null action returns 400, not 500")
    void nullActionInPermissionListReturns400() throws Exception {
        String body = new ObjectMapper().writeValueAsString(java.util.List.of(
                java.util.Map.of("module", "FEED") // action intentionally omitted -> null
        ));

        mockMvc.perform(put("/api/v1/classes/class-1/staff/target-1/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("A valid permission list is accepted (control case for the validation tests above)")
    void validPermissionListIsAccepted() throws Exception {
        var dto = new StaffAssignmentDto();
        org.mockito.Mockito.when(staffService.assignStaff(
                org.mockito.ArgumentMatchers.eq("class-1"),
                org.mockito.ArgumentMatchers.eq("target-1"),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.eq("owner-1"))).thenReturn(dto);

        String body = new ObjectMapper().writeValueAsString(java.util.List.of(
                java.util.Map.of("module", "FEED", "action", "VIEW")
        ));

        mockMvc.perform(put("/api/v1/classes/class-1/staff/target-1/permissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }
}
