package com.classroom;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableJpaRepositories(basePackages = {
        "com.classroom.modules.identity.repository",
        "com.classroom.modules.classroom.repository",
        "com.classroom.modules.learning.repository",
        "com.classroom.modules.media.repository",
        "com.classroom.modules.community.repository",
        "com.classroom.modules.exam.repository",
        "com.classroom.modules.ranking.repository",
        "com.classroom.modules.segment.repository",
        "com.classroom.modules.commerce.repository",
        "com.classroom.modules.outbox.repository",
        "com.classroom.modules.audit.repository"
})
public class ClassroomApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClassroomApplication.class, args);
    }
}
