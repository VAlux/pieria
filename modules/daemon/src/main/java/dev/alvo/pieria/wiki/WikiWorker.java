package dev.alvo.pieria.wiki;

import dev.alvo.pieria.config.WikiProperties;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.model.usage.InferenceUsageAccumulator;
import dev.alvo.pieria.model.usage.InferenceUsageSink;
import dev.alvo.pieria.storage.MemoryStore;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static dev.alvo.pieria.wiki.WikiData.*;

@Component
public class WikiWorker {
  private static final Logger LOG=LoggerFactory.getLogger(WikiWorker.class);
  private static final int MAX_PROJECTION_BATCHES_PER_TICK=32;
  private static final long PROJECTION_BUDGET_NANOS=TimeUnit.SECONDS.toNanos(2);
  private final MemoryStore store;private final WikiProjectionService projection;private final WikiService wiki;private final ModelGateway model;
  private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
  private final ScheduledExecutorService leaseRenewer=Executors.newSingleThreadScheduledExecutor(
    Thread.ofPlatform().daemon(true).name("wiki-lease").factory());
  private final AtomicReference<Job> active=new AtomicReference<>();
  public WikiWorker(MemoryStore store,WikiProjectionService projection,WikiService wiki,ModelGateway model) {
    this.store=store;this.projection=projection;this.wiki=wiki;this.model=model;
  }
  @Scheduled(fixedDelayString="${pieria.wiki.interval-ms:5000}",initialDelayString="${pieria.wiki.interval-ms:5000}")
  public void tick() {
    try {
      long deadline=System.nanoTime()+PROJECTION_BUDGET_NANOS;
      for(var profile:store.listProfiles()) {
        String p=profile.profile().id();WikiProperties cfg=wiki.settings(p);if(!cfg.enabled()) continue;
        try {
          // A batch commits before the next one begins, so large bootstraps make progress
          // without holding SQLite's writer for the whole scheduling interval.
          for(int batch=0;batch<MAX_PROJECTION_BATCHES_PER_TICK;batch++) {
            if(!projection.tick(p,cfg) || System.nanoTime()>=deadline) break;
          }
          if(active.get()!=null) continue;
          // Queued articles must wait for projection to catch up instead of becoming
          // obsolete when their evidence is still being assigned.
          boolean ready=store.readWiki(w->{State state=w.state(p);
            return state.initialized() && state.checkpoint()==w.watermark(p)
              && state.policy().equals(WikiProjectionService.policy(cfg));});
          if(!ready) continue;
          var job=store.writeWiki(w->w.claim(p,Instant.now(),cfg.maxAttempts()));
          if(job.isPresent() && active.compareAndSet(null,job.get())) executor.submit(()->run(job.get(),cfg));
        } catch(RuntimeException e) {
          Throwable cause=e;while(cause.getCause()!=null)cause=cause.getCause();
          LOG.warn("wiki projection profile={} failed type={} cause={} sqliteCode={} message={}",p,
            e.getClass().getSimpleName(),cause.getClass().getSimpleName(),
            cause instanceof SQLException sql?sql.getErrorCode():null,cause.getMessage());
        }
      }
    } catch(UnsupportedOperationException ignored) { /* Storage backend without wiki support. */ }
  }
  @PostConstruct void startHeartbeats() {leaseRenewer.scheduleWithFixedDelay(this::heartbeat,10,10,TimeUnit.SECONDS);}
  public void heartbeat() {
    Job job=active.get();if(job==null)return;
    try {store.writeWiki(w->w.heartbeat(job,Instant.now()));}
    catch(RuntimeException e) {LOG.warn("wiki heartbeat job={} failed",job.id());}
  }
  private void run(Job job,WikiProperties cfg) {
    Future<String> call=null;
    try {
      Bundle input=store.readWiki(w->{
        if(!w.exists(job.profileId()) || !wiki.settings(job.profileId()).enabled() || job.bundle()==null || w.forgotten(job.profileId(),job.bundle())) throw new WikiException(409,"source_unavailable");
        Page page=w.page(job.profileId(),job.pageId()).orElseThrow();
        Bundle now=WikiEvidenceSelector.select(w,job.profileId(),page,cfg,wiki.rendering(job.profileId()));
        if(!now.fingerprint().equals(job.fingerprint())) throw new WikiException(409,"inputs_changed");return now;
      });
      call=executor.submit(()-> {
        var usage=new InferenceUsageAccumulator();
        try(var ignored=InferenceUsageSink.bind(usage)) {return model.synthesizeWiki(input);}
        finally {
          try {if(store.readWiki(w->w.exists(job.profileId()))) store.recordInferenceUsage(job.profileId(),usage.snapshot());}
          catch(RuntimeException ignored) {LOG.warn("wiki usage job={} unavailable",job.id());}
        }
      });
      String body=WikiEvidenceSelector.validate(call.get(cfg.timeoutSeconds(),TimeUnit.SECONDS),input);
      boolean published=store.writeWiki(w->{
        if(!w.exists(job.profileId()) || !wiki.settings(job.profileId()).enabled()) return false;
        Bundle current=WikiEvidenceSelector.select(w,job.profileId(),w.page(job.profileId(),job.pageId()).orElseThrow(),wiki.settings(job.profileId()),wiki.rendering(job.profileId()));
        return w.publish(job,current,body);
      });
      if(!published) finish(job,"obsolete","inputs_changed",null);
    } catch(WikiException e) {
      finish(job,e.status()==409?"obsolete":"failed",e.getMessage(),null);
    } catch(InterruptedException e) {
      Thread.currentThread().interrupt();finish(job,"failed","interrupted",null);
    } catch(ExecutionException | TimeoutException e) {
      boolean retry=job.attempts()<cfg.maxAttempts();
      finish(job,retry?"queued":"failed",e instanceof TimeoutException?"model_timeout":"model_unavailable",Instant.now().plusSeconds(5));
    } catch(RuntimeException e) {
      finish(job,"failed","generation_failed",null);
    } finally {if(call!=null && !call.isDone())call.cancel(true);active.compareAndSet(job,null);}
  }
  private void finish(Job job,String status,String reason,Instant retry) {
    try {store.writeWiki(w->{w.finish(job,status,reason,retry);return null;});}
    catch(RuntimeException e) {LOG.warn("wiki job={} could not record terminal state",job.id());}
  }
  @PreDestroy public void close() {leaseRenewer.shutdownNow();executor.shutdownNow();}
}
