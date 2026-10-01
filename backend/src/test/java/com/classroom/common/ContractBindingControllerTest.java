package com.classroom.common;

import com.classroom.config.UserPrincipal;
import com.classroom.modules.commerce.controller.CommerceController;
import com.classroom.modules.commerce.service.CommerceService;
import com.classroom.modules.learning.controller.AssignmentController;
import com.classroom.modules.learning.controller.LessonController;
import com.classroom.modules.learning.service.AssignmentService;
import com.classroom.modules.learning.service.LearningService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.math.BigDecimal;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * R5-08: controller-level regression coverage for request-binding edge cases that must answer 400
 * (a client contract error), never a 500 from an unhandled parse/format exception — exercised
 * through the real controller + {@link GlobalExceptionHandler}, not just at the service layer.
 */
class ContractBindingControllerTest {

    private static final UserPrincipal PRINCIPAL =
            new UserPrincipal("user-1", "user@test.local", "hash", "User", "STUDENT", "ACTIVE");

    private static MockMvc standaloneMvcFor(Object controller) {
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
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(principalResolver)
                .build();
    }

    // --- LessonController: LessonProgressRequest completed=null defaults to true (R4-04) ---

    private LearningService learningService;
    private MockMvc lessonMvc;

    @BeforeEach
    void setUpLesson() {
        learningService = mock(LearningService.class);
        lessonMvc = standaloneMvcFor(new LessonController(learningService));
    }

    @Test
    @DisplayName("R5-08: PUT lesson progress with completed=null marks the lesson complete (defaults true)")
    void lessonProgressNullCompletedDefaultsTrue() throws Exception {
        lessonMvc.perform(put("/api/v1/lessons/lesson-1/progress")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completed\": null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completed").value(true));

        verify(learningService).markLessonProgress("lesson-1", "user-1", true);
    }

    @Test
    @DisplayName("R5-08: PUT lesson progress with no body at all also defaults to completed=true")
    void lessonProgressMissingBodyDefaultsTrue() throws Exception {
        lessonMvc.perform(put("/api/v1/lessons/lesson-1/progress")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completed").value(true));

        verify(learningService).markLessonProgress("lesson-1", "user-1", true);
    }

    // --- CommerceController: CreateProductRequest binding + malformed accessStartsAt (R4-05) ---

    @Test
    @DisplayName("R5-08: createProduct with a malformed accessStartsAt returns 400, not 500")
    void createProductRejectsMalformedAccessStartsAt() throws Exception {
        CommerceService commerceService = mock(CommerceService.class);
        MockMvc mvc = standaloneMvcFor(new CommerceController(commerceService));

        String body = new ObjectMapper().writeValueAsString(Map.of(
                "title", "Khóa học Toán",
                "description", "Mô tả",
                "price", 199000,
                "durationDays", 30,
                "accessStartsAt", "not-a-date"
        ));

        mvc.perform(post("/api/v1/classes/class-1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));

        verify(commerceService, never()).createProduct(any(), any(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("R5-08: createProduct with a non-numeric price is a 400 contract error (JSON binding), not 500")
    void createProductRejectsNonNumericPrice() throws Exception {
        CommerceService commerceService = mock(CommerceService.class);
        MockMvc mvc = standaloneMvcFor(new CommerceController(commerceService));

        // price is typed BigDecimal on CreateProductRequest: a non-numeric JSON value fails to
        // bind and must surface as 400 (HttpMessageNotReadableException), not 500.
        String body = "{\"title\":\"T\",\"description\":\"D\",\"price\":\"not-a-number\"}";

        mvc.perform(post("/api/v1/classes/class-1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    // --- AssignmentController: non-numeric score is a 400 contract error, not 500 (R4-05) ---

    @Test
    @DisplayName("R5-08: grade with a non-numeric score returns 400, not 500")
    void gradeRejectsNonNumericScore() throws Exception {
        AssignmentService assignmentService = mock(AssignmentService.class);
        MockMvc mvc = standaloneMvcFor(new AssignmentController(assignmentService));

        String body = "{\"score\": \"not-a-number\", \"feedback\": \"Cố gắng hơn nhé\"}";

        mvc.perform(put("/api/v1/assignment-submissions/sub-1/grade")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));

        verify(assignmentService, never()).grade(anyString(), anyString(), any(BigDecimal.class), any());
    }

    @Test
    @DisplayName("A valid numeric score is accepted (control case)")
    void gradeAcceptsNumericScore() throws Exception {
        AssignmentService assignmentService = mock(AssignmentService.class);
        MockMvc mvc = standaloneMvcFor(new AssignmentController(assignmentService));
        when(assignmentService.grade(eq("sub-1"), eq("user-1"), eq(new BigDecimal("8.5")), eq("Tốt")))
                .thenReturn(new com.classroom.modules.learning.dto.AssignmentSubmissionDto());

        String body = "{\"score\": 8.5, \"feedback\": \"Tốt\"}";

        mvc.perform(put("/api/v1/assignment-submissions/sub-1/grade")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }
}
