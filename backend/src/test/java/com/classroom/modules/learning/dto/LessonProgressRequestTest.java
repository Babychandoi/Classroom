package com.classroom.modules.learning.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R5-08/R4-04: a JSON body of {"completed": null} — or no body at all — must default to marking
 * the lesson complete, not throw an NPE unboxing a primitive boolean.
 */
class LessonProgressRequestTest {

    @Test
    @DisplayName("R5-08: completed defaults to true when the field is absent (unset)")
    void defaultsToTrueWhenUnset() {
        LessonProgressRequest request = new LessonProgressRequest();
        assertTrue(request.isCompletedOrDefault());
    }

    @Test
    @DisplayName("R5-08: completed defaults to true when explicitly null")
    void defaultsToTrueWhenExplicitlyNull() {
        LessonProgressRequest request = new LessonProgressRequest();
        request.setCompleted(null);
        assertTrue(request.isCompletedOrDefault());
    }

    @Test
    @DisplayName("completed=false is honored, not overridden by the default")
    void honorsExplicitFalse() {
        LessonProgressRequest request = new LessonProgressRequest();
        request.setCompleted(false);
        assertFalse(request.isCompletedOrDefault());
    }

    @Test
    @DisplayName("completed=true is honored")
    void honorsExplicitTrue() {
        LessonProgressRequest request = new LessonProgressRequest();
        request.setCompleted(true);
        assertTrue(request.isCompletedOrDefault());
    }
}
