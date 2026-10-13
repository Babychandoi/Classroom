package com.classroom.modules.assessment.catalog;

import jakarta.persistence.*;

/** D-34: an education level (TH / THCS / THPT), table {@code edu_levels} (V50). */
@Entity
@Table(name = "edu_levels")
public class EduLevel {
    @Id
    @Column(length = 8)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public EduLevel() {}

    public EduLevel(String code, String name, int sortOrder) {
        this.code = code;
        this.name = name;
        this.sortOrder = sortOrder;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public int getSortOrder() { return sortOrder; }
}
