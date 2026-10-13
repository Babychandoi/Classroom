package com.classroom.modules.assessment.catalog;

import jakarta.persistence.*;

/** D-34: a grade 1..12 and its level, table {@code edu_grades} (V50). */
@Entity
@Table(name = "edu_grades")
public class EduGrade {
    @Id
    @Column(name = "grade")
    private Integer grade;

    @Column(name = "level_code", nullable = false, length = 8)
    private String levelCode;

    @Column(nullable = false, length = 40)
    private String name;

    public EduGrade() {}

    public EduGrade(int grade, String levelCode, String name) {
        this.grade = grade;
        this.levelCode = levelCode;
        this.name = name;
    }

    public Integer getGrade() { return grade; }
    public String getLevelCode() { return levelCode; }
    public String getName() { return name; }
}
