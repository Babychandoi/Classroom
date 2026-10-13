package com.classroom.modules.assessment.catalog;

import jakarta.persistence.*;

/** D-34: a subject, integrated-subject component, activity or topic cluster, table {@code edu_subjects} (V50). */
@Entity
@Table(name = "edu_subjects")
public class EduSubject {
    @Id
    @Column(length = 48)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 16)
    private String kind;

    @Column(nullable = false)
    private boolean integrated;

    @Column(name = "group_label", length = 60)
    private String groupLabel;

    @Column(name = "parent_code", length = 48)
    private String parentCode;

    @Column(name = "language_required", nullable = false)
    private boolean languageRequired;

    @Column(name = "default_language_code", length = 16)
    private String defaultLanguageCode;

    /** Space-separated question type codes. */
    @Column(name = "suggested_types", nullable = false, length = 255)
    private String suggestedTypes = "";

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active = true;

    public EduSubject() {}

    public EduSubject(String code, String name, String kind, boolean integrated, String groupLabel, String parentCode,
                      boolean languageRequired, String defaultLanguageCode, String suggestedTypes, int sortOrder) {
        this.code = code;
        this.name = name;
        this.kind = kind;
        this.integrated = integrated;
        this.groupLabel = groupLabel;
        this.parentCode = parentCode;
        this.languageRequired = languageRequired;
        this.defaultLanguageCode = defaultLanguageCode;
        this.suggestedTypes = suggestedTypes == null ? "" : suggestedTypes;
        this.sortOrder = sortOrder;
        this.active = true;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getKind() { return kind; }
    public boolean isIntegrated() { return integrated; }
    public String getGroupLabel() { return groupLabel; }
    public String getParentCode() { return parentCode; }
    public boolean isLanguageRequired() { return languageRequired; }
    public String getDefaultLanguageCode() { return defaultLanguageCode; }
    public String getSuggestedTypes() { return suggestedTypes; }
    public int getSortOrder() { return sortOrder; }
    public boolean isActive() { return active; }
}
