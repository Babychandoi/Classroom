package com.classroom.modules.assessment.catalog;

import jakarta.persistence.*;

import java.io.Serializable;
import java.util.Objects;

/** D-34: a component of an integrated subject, table {@code edu_subject_components} (V50). */
@Entity
@Table(name = "edu_subject_components")
@IdClass(EduSubjectComponent.Key.class)
public class EduSubjectComponent {
    @Id
    @Column(name = "parent_code", length = 48)
    private String parentCode;

    @Id
    @Column(name = "component_code", length = 48)
    private String componentCode;

    public EduSubjectComponent() {}

    public EduSubjectComponent(String parentCode, String componentCode) {
        this.parentCode = parentCode;
        this.componentCode = componentCode;
    }

    public String getParentCode() { return parentCode; }
    public String getComponentCode() { return componentCode; }

    public static class Key implements Serializable {
        private String parentCode;
        private String componentCode;

        public Key() {}

        public Key(String parentCode, String componentCode) {
            this.parentCode = parentCode;
            this.componentCode = componentCode;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(parentCode, k.parentCode) && Objects.equals(componentCode, k.componentCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(parentCode, componentCode);
        }
    }
}
