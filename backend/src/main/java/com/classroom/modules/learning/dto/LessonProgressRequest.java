package com.classroom.modules.learning.dto;

/**
 * R4-04: typed request body for lesson progress updates. A JSON body of
 * {"completed": null} previously unboxed to a primitive boolean and threw an
 * NPE (500). "completed" is optional and defaults to true (marking complete)
 * when absent or explicitly null.
 */
public class LessonProgressRequest {
    private Boolean completed;

    public Boolean getCompleted() {
        return completed;
    }

    public void setCompleted(Boolean completed) {
        this.completed = completed;
    }

    public boolean isCompletedOrDefault() {
        return completed == null || completed;
    }
}
