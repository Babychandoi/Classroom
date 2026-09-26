package com.classroom.modules.classroom.dto;

public class StaffPermissionDto {
    private String id;
    private String module;
    private String action;
    private String scopeCourseId;

    public StaffPermissionDto() {}

    public StaffPermissionDto(String module, String action, String scopeCourseId) {
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
