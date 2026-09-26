package com.classroom.modules.classroom.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "staff_permissions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_staff_permission", columnNames = {"assignment_id", "module", "action", "scope_course_id"})
})
public class StaffPermission {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "assignment_id", nullable = false, length = 36)
    private String assignmentId;

    @Column(nullable = false, length = 64)
    private String module; // COURSE, EXAM, FEED, DOCUMENT, SEGMENT, STORE, STAFF

    @Column(nullable = false, length = 64)
    private String action; // VIEW, CREATE, EDIT, PUBLISH, GRADE, DELETE, PREVIEW

    @Column(name = "scope_course_id", length = 36)
    private String scopeCourseId; // null means whole class

    public StaffPermission() {
        this.id = UUID.randomUUID().toString();
    }

    public StaffPermission(String assignmentId, String module, String action, String scopeCourseId) {
        this.id = UUID.randomUUID().toString();
        this.assignmentId = assignmentId;
        this.module = module;
        this.action = action;
        this.scopeCourseId = scopeCourseId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAssignmentId() {
        return assignmentId;
    }

    public void setAssignmentId(String assignmentId) {
        this.assignmentId = assignmentId;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getScopeCourseId() {
        return scopeCourseId;
    }

    public void setScopeCourseId(String scopeCourseId) {
        this.scopeCourseId = scopeCourseId;
    }
}
