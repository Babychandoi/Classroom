package com.classroom.modules.assessment.catalog;

import jakarta.persistence.*;

/** D-34: a foreign language and its voice / accent codes (space-separated), table {@code edu_languages} (V50). */
@Entity
@Table(name = "edu_languages")
public class EduLanguage {
    @Id
    @Column(length = 16)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 500)
    private String voices = "";

    public EduLanguage() {}

    public EduLanguage(String code, String name, String voices) {
        this.code = code;
        this.name = name;
        this.voices = voices == null ? "" : voices;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public String getVoices() { return voices; }
}
