package com.classroom.modules.exam.service;

import com.classroom.modules.exam.repository.ExamRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
public class ExamPublicationSnapshotUpgrade implements ApplicationRunner {
    private final ExamRepository exams;
    private final ExamService service;
    public ExamPublicationSnapshotUpgrade(ExamRepository exams, ExamService service) { this.exams = exams; this.service = service; }
    @Override public void run(ApplicationArguments args) {
        String afterId = "";
        while (true) {
            var ids = exams.findPublicationSnapshotBacklog(afterId, PageRequest.of(0, 100));
            if (ids.isEmpty()) return;
            for (String id : ids) service.prepareLegacyPublicationSnapshot(id);
            afterId = ids.get(ids.size() - 1);
        }
    }
}
