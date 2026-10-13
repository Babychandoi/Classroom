package com.classroom.modules.assessment.catalog;

import jakarta.persistence.*;

import java.io.Serializable;
import java.util.Objects;

/** D-34: "subject X is taught in grade N", table {@code edu_grade_subjects} (V50). */
@Entity
@Table(name = "edu_grade_subjects")
@IdClass(EduGradeSubject.Key.class)
public class EduGradeSubject {
    @Id
    @Column(name = "grade")
    private Integer grade;

    @Id
    @Column(name = "subject_code", length = 48)
    private String subjectCode;

    public EduGradeSubject() {}

    public EduGradeSubject(int grade, String subjectCode) {
        this.grade = grade;
        this.subjectCode = subjectCode;
    }

    public Integer getGrade() { return grade; }
    public String getSubjectCode() { return subjectCode; }

    public static class Key implements Serializable {
        private Integer grade;
        private String subjectCode;

        public Key() {}

        public Key(Integer grade, String subjectCode) {
            this.grade = grade;
            this.subjectCode = subjectCode;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(grade, k.grade) && Objects.equals(subjectCode, k.subjectCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(grade, subjectCode);
        }
    }
}
