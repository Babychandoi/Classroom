package com.classroom.modules.assessment.service;

import com.classroom.modules.assessment.repository.AssessmentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
import static com.classroom.modules.assessment.repository.AssessmentStore.time;

/** The attempt row lock makes expiration safe when several backend instances run this job. */
@Component
@org.springframework.context.annotation.Profile("!test")
public class AssessmentDeadlineWorker {
    private static final Logger LOG=LoggerFactory.getLogger(AssessmentDeadlineWorker.class);
    private final AssessmentStore store;
    private final AssessmentAttemptService attempts;
    public AssessmentDeadlineWorker(AssessmentStore store,AssessmentAttemptService attempts){this.store=store;this.attempts=attempts;}
    @Scheduled(fixedDelayString="${assessment.deadline-scan-ms:15000}")
    public void expire(){
        for(var row:store.rows("SELECT id FROM assessment_attempts WHERE status='IN_PROGRESS' AND deadline_at<=? ORDER BY deadline_at LIMIT 200",time(Instant.now())))
            try{attempts.expire(row.text("id"));}catch(RuntimeException e){LOG.warn("Could not expire assessment attempt {}: {}",row.text("id"),e.getClass().getSimpleName());}
    }
}
