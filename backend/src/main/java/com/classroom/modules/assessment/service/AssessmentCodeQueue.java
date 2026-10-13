package com.classroom.modules.assessment.service;

import com.classroom.modules.assessment.doc.*;
import com.classroom.modules.assessment.grading.ScoreMath;
import com.classroom.modules.assessment.repository.AssessmentStore;
import com.classroom.modules.assessment.repository.AssessmentStore.Row;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static com.classroom.modules.assessment.repository.AssessmentStore.time;

/** Short transactions claim/complete jobs; container execution never holds a database connection. */
@Service
public class AssessmentCodeQueue {
    private final AssessmentStore store;private final AssessmentAttemptService attempts;
    public AssessmentCodeQueue(AssessmentStore store,AssessmentAttemptService attempts){this.store=store;this.attempts=attempts;}
    public record Job(String answerId,String attemptId,ItemNode item,String code){}
    @Transactional
    public Optional<Job> claim(){
        // Lock attempts first, consistently with submit/teacher grading. Old claims retry after a process crash.
        var candidate=store.first("SELECT attempt_id FROM assessment_attempt_answers WHERE status='PENDING_CODE' OR (status='CODE_RUNNING' AND updated_at<?) ORDER BY updated_at LIMIT 1",time(Instant.now().minusSeconds(150)));
        if(candidate.isEmpty())return Optional.empty();
        Row attempt=store.attempt(candidate.get().text("attempt_id"),true);
        var answer=store.first("SELECT * FROM assessment_attempt_answers WHERE attempt_id=? AND (status='PENDING_CODE' OR (status='CODE_RUNNING' AND updated_at<?)) ORDER BY updated_at LIMIT 1 FOR UPDATE SKIP LOCKED",attempt.text("id"),time(Instant.now().minusSeconds(150)));
        if(answer.isEmpty())return Optional.empty();Row a=answer.get();
        store.write("UPDATE assessment_attempt_answers SET status='CODE_RUNNING',updated_at=? WHERE id=?",time(Instant.now()),a.text("id"));
        return Optional.of(new Job(a.text("id"),attempt.text("id"),attempts.item(attempt,a.text("item_id")),DocumentCodec.readTree(a.text("response_json")).path("code").asText()));
    }
    @Transactional
    public void complete(Job job,int passed,int total){
        Row attempt=store.attempt(job.attemptId(),true);
        BigDecimal fraction=BigDecimal.valueOf(passed).divide(BigDecimal.valueOf(total),12,java.math.RoundingMode.HALF_UP);
        if(!job.item().grading().partialCredit() && passed<total)fraction=BigDecimal.ZERO;
        BigDecimal points=ScoreMath.ofFraction(fraction,job.item().points());String correct=passed==total?"CORRECT":passed==0?"WRONG":"PARTIAL";
        boolean hybrid="HYBRID".equals(job.item().mode());
        int changed=store.write("UPDATE assessment_attempt_answers SET status=?,correctness=?,auto_points=?,final_points=?,sub_results_json=?,updated_at=? WHERE id=? AND status='CODE_RUNNING'",
            hybrid?"PENDING_MANUAL":"AUTO_GRADED",correct,points,hybrid?null:points,DocumentCodec.writeJson(Map.of("passed",passed,"total",total)),time(Instant.now()),job.answerId());
        if(changed==0)return;List<Row> answers=attempts.answerRows(job.attemptId());
        BigDecimal auto=BigDecimal.ZERO,manual=BigDecimal.ZERO;boolean pending=false;
        for(Row answer:answers){if(answer.decimal("auto_points")!=null)auto=auto.add(answer.decimal("auto_points"));if(answer.decimal("manual_points")!=null)manual=manual.add(answer.decimal("manual_points"));if(answer.decimal("final_points")==null)pending=true;}
        BigDecimal totalScore=answers.stream().map(a->a.decimal("final_points")).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add);
        store.write("UPDATE assessment_attempts SET auto_score=?,manual_score=?,total_score=?,status=?,revision=revision+1 WHERE id=?",
            ScoreMath.round(auto),pending?null:ScoreMath.round(manual),pending?null:ScoreMath.round(totalScore),pending?"SUBMITTED":"GRADED",job.attemptId());
    }
    @Transactional
    public void retry(Job job){store.attempt(job.attemptId(),true);store.write("UPDATE assessment_attempt_answers SET status='PENDING_CODE',updated_at=? WHERE id=? AND status='CODE_RUNNING'",time(Instant.now()),job.answerId());}
}
