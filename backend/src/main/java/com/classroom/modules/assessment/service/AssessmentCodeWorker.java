package com.classroom.modules.assessment.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@org.springframework.context.annotation.Profile("!test")
public class AssessmentCodeWorker {
    private static final Logger LOG=LoggerFactory.getLogger(AssessmentCodeWorker.class);
    private final AssessmentCodeQueue queue;private final AssessmentCodeService code;
    private final java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean running=new java.util.concurrent.atomic.AtomicBoolean();
    public AssessmentCodeWorker(AssessmentCodeQueue queue,AssessmentCodeService code){this.queue=queue;this.code=code;}
    @Scheduled(fixedDelay=3000)
    public void process(){
        if(running.compareAndSet(false,true))executor.execute(()->{try{executeNext();}catch(RuntimeException e){LOG.warn("Assessment code queue temporarily unavailable");}finally{running.set(false);}});
    }
    @jakarta.annotation.PreDestroy
    public void close(){executor.shutdownNow();}
    private void executeNext(){
        var next=queue.claim();if(next.isEmpty())return;var job=next.get();
        try {var results=code.execute(job.item(),job.code(),job.item().answerKey().path("tests"));int passed=0;for(var result:results)if(result.path("passed").asBoolean())passed++;queue.complete(job,passed,results.size());}
        catch(RuntimeException e){queue.retry(job);LOG.warn("Assessment code execution deferred; worker will retry");}
    }
}
