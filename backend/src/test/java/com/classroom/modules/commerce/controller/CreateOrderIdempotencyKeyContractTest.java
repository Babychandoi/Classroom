package com.classroom.modules.commerce.controller;

import com.classroom.common.GlobalExceptionHandler;
import com.classroom.config.UserPrincipal;
import com.classroom.modules.commerce.dto.CreateOrderRequest;
import com.classroom.modules.commerce.service.CommerceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R20-12: {@code POST /orders} accepts the idempotency key in the {@code Idempotency-Key} header, in the body, or both. It used to
 * answer 400 for a header-only request, because bean validation of the body ran before the header was copied into it.
 * Contract (docs/API.md): a key is REQUIRED (header or body); if both are sent they must be equal.
 */
class CreateOrderIdempotencyKeyContractTest {

    private static final UserPrincipal PRINCIPAL = new UserPrincipal("user-1", "user@test.local", "hash", "User", "USER", "ACTIVE");
    private CommerceService commerceService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        commerceService = mock(CommerceService.class);
        HandlerMethodArgumentResolver principalResolver = new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType().equals(UserPrincipal.class);
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                          NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                return PRINCIPAL;
            }
        };
        mvc = MockMvcBuilders.standaloneSetup(new CommerceController(commerceService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(principalResolver)
                .build();
    }

    private static String body(String key) {
        String keyField = key == null ? "" : ",\"idempotencyKey\":\"" + key + "\"";
        return "{\"classId\":\"class-1\",\"productId\":\"product-1\"" + keyField + "}";
    }

    private String keyPassedToService() {
        ArgumentCaptor<CreateOrderRequest> captor = ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(commerceService).createOrder(eq("user-1"), captor.capture());
        return captor.getValue().getIdempotencyKey();
    }

    @Test
    @DisplayName("header only: 200 and the header value is the key (was 400 from @Valid on the body)")
    void headerOnly() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "  key-from-header  ").content(body(null)))
                .andExpect(status().isOk());
        assertEquals("key-from-header", keyPassedToService());
    }

    @Test
    @DisplayName("body only: 200 and the body value is the key")
    void bodyOnly() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(body("key-from-body")))
                .andExpect(status().isOk());
        assertEquals("key-from-body", keyPassedToService());
    }

    @Test
    @DisplayName("both, equal: 200 (what the web app sends)")
    void bothEqual() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "same-key").content(body("same-key")))
                .andExpect(status().isOk());
        assertEquals("same-key", keyPassedToService());
    }

    @Test
    @DisplayName("both, different: 400 - silently preferring one could create a second order on a retry")
    void bothDifferent() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "header-key").content(body("body-key")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("không khớp")));
        verify(commerceService, never()).createOrder(any(), any());
    }

    @Test
    @DisplayName("neither (or only blanks): 400 - the key is required by the contract")
    void neither() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(body(null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("Idempotency-Key")));
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "   ").content(body("  ")))
                .andExpect(status().isBadRequest());
        verify(commerceService, never()).createOrder(any(), any());
    }

    @Test
    @DisplayName("the other body fields are still validated when the key comes from the header")
    void otherFieldsStillValidated() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "k").content("{\"classId\":\"\",\"productId\":\"p\"}"))
                .andExpect(status().isBadRequest());
        verify(commerceService, never()).createOrder(any(), any());
    }
}
