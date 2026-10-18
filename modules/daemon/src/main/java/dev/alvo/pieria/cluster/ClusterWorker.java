package dev.alvo.pieria.cluster;

import dev.alvo.pieria.cluster.ClusterRepository.Cluster;
import dev.alvo.pieria.cluster.ClusterRepository.Member;
import dev.alvo.pieria.cluster.ClusterRepository.Run;
import dev.alvo.pieria.cluster.ClusterRepository.Topic;
import dev.alvo.pieria.storage.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** One global generation lane. A slice is bounded by seeds, publications, and elapsed time. */
@Component
public class ClusterWorker {
  private static final Logger log=LoggerFactory.getLogger(ClusterWorker.class);
  private final MemoryStore store;
  private final ClusterGenerationService generation;
  private final ClusterDiscoveryService discovery;
  private final String workerId=UUID.randomUUID().toString();

  public ClusterWorker(MemoryStore store,ClusterGenerationService generation) {
    this.store=store;this.generation=generation;this.discovery=new ClusterDiscoveryService(store);
  }

  @Scheduled(fixedDelayString="${pieria.clustering.poll-ms:5000}",
             initialDelayString="${pieria.clustering.poll-ms:5000}")
  public void tick() {
    Optional<Run> claimed;
    try {
      store.writeClusters(repo->repo.activateDueBoundaries(Instant.now()));
      store.writeClusters(repo->repo.activateDueSchedules(Instant.now()));
      claimed=store.writeClusters(repo->repo.claimRun(workerId));
    }
    catch(RuntimeException e) {log.warn("cluster run claim failed",e);return;}
    if(claimed.isEmpty()) return;
    Run run=claimed.get();
    Instant started=Instant.now();
    int processed=0,published=0;
    boolean exhausted=false;
    String cursor=run.checkpoint();
    var heartbeats=Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("cluster-heartbeat-",0).factory());
    var pulse=heartbeats.scheduleAtFixedRate(()->{
      try {store.writeClusters(repo->{repo.renewRun(run.id(),workerId);return null;});}
      catch(RuntimeException e) {log.debug("cluster run heartbeat ended id={}",run.id(),e);}
    },30,30,TimeUnit.SECONDS);
    try {
      store.writeClusters(repo->{repo.enqueueDirtyRegenerations(run.id(),run.profileId());return null;});
      while(published<10 && Duration.between(started,Instant.now()).toMinutes()<30) {
        var pending=store.readClusters(repo->repo.pendingRegenerations(run.id(),1));
        if(pending.isEmpty()) break;
        var item=pending.getFirst();
        if(!store.<Boolean>readClusters(repo->repo.canPublishRun(run.profileId(),run.id(),workerId))) break;
        String status="failed",detail="unknown error";
        for(int attempt=0;attempt<3;attempt++) {
          try {
            generation.generateForProfileId(run.profileId(),item.clusterId(),item.expectedRevision(),run.id(),workerId);
            status="published";detail=item.clusterId();published++;break;
          } catch(ClusterConflictException e) {
            status="deferred";detail=e.getMessage();break;
          } catch(RuntimeException e) {
            detail=e.toString();
            if(attempt==2) log.warn("cluster regeneration failed item={}",item.itemId(),e);
          }
        }
        String resultStatus=status,resultDetail=detail;
        store.writeClusters(repo->{repo.finishRegeneration(item.itemId(),resultStatus,resultDetail);
          if(resultStatus.equals("published")) repo.enqueueDirtyRegenerations(run.id(),run.profileId());
          repo.renewRun(run.id(),workerId);return null;});
        if(status.equals("deferred")) break;
      }
      if("regenerate".equals(run.trigger())) {
        exhausted=store.<Boolean>readClusters(repo->repo.pendingRegenerations(run.id(),1).isEmpty());
        return;
      }
      while(processed<100 && published<10 && Duration.between(started,Instant.now()).toMinutes()<30) {
        final String after=cursor;
        boolean broader=after!=null && after.startsWith("broader:");
        boolean entity=after!=null && after.startsWith("entity:");
        boolean exploring=after!=null && after.startsWith("discovery:");
        List<Topic> topics=broader
          ? store.readClusters(repo->repo.broaderTopics(run.profileId(),after.substring("broader:".length()),1))
          : entity ? store.readClusters(repo->repo.entityTopics(run.profileId(),after.substring("entity:".length()),1,run.snapshot()))
          : exploring ? store.readClusters(repo->repo.discoverySeeds(run.profileId(),after.substring("discovery:".length()),1,
              run.snapshot(),run.eventAfter(),run.eventThrough()))
              .stream().map(seed->{
                Topic found=store.atSnapshot(run.snapshot(),()->discovery.discover(run.profileId(),seed));
                return found==null?new Topic("discovery:"+seed,List.of()):found;
              }).toList()
          : store.readClusters(repo->repo.topics(run.profileId(),after,1,run.snapshot()));
        if(topics.isEmpty()) {
          if(!broader) {
            cursor=exploring?"broader:":entity?"discovery:":"entity:";
            String nextPhase=cursor;
            store.writeClusters(repo->{repo.checkpoint(run.id(),nextPhase,"scan","next discovery route");return null;});
            continue;
          }
          exhausted=true;break;
        }
        Topic topic=topics.getFirst();
        cursor=topic.key();
        processed++;
        if(!store.<Boolean>readClusters(repo->repo.canPublishRun(run.profileId(),run.id(),workerId))) break;
        String status="skipped",detail="insufficient or oversized group";
        if(topic.members().size()>=(broader?2:3) && topic.members().size()<=20) {
          try {
            if(!store.atSnapshot(run.snapshot(),()->generation.coherent(run.profileId(),topic))) {
              detail="coherence rejected";
              String recordedCursor=cursor;
              store.writeClusters(repo->{repo.checkpoint(run.id(),recordedCursor,"rejected","coherence rejected");return null;});
              continue;
            }
            Cluster cluster=upsert(run.profileId(),topic);
            if(cluster!=null) {
              for(int attempt=0;attempt<3;attempt++) {
                try {
                  generation.generateForProfileId(run.profileId(),cluster.id(),cluster.revision(),run.id(),workerId);
                  published++;status="published";detail=cluster.id();break;
                } catch(ClusterConflictException e) { throw e; }
                catch(RuntimeException e) { if(attempt==2) throw e; }
              }
            } else detail="all candidate edges manually excluded";
          } catch(ClusterConflictException e) {
            status="deferred";detail=e.getMessage();
          } catch(RuntimeException e) {
            status="failed";detail=e.toString();
            log.warn("cluster topic generation failed profile={} key={}",run.profileId(),topic.key(),e);
          }
        }
        if(status.equals("deferred")) break; // Keep the prior checkpoint for a later resume.
        String recordedStatus=status,recordedDetail=detail,recordedCursor=cursor;
        store.writeClusters(repo->{
          if(repo.canPublishRun(run.profileId(),run.id(),workerId)) {
            repo.checkpoint(run.id(),recordedCursor,recordedStatus,recordedDetail);
            repo.renewRun(run.id(),workerId);
          }
          return null;
        });
      }
    } catch(RuntimeException e) {
      log.warn("cluster run slice interrupted id={}",run.id(),e);
    } finally {
      pulse.cancel(true);
      heartbeats.shutdownNow();
      boolean done=exhausted;
      store.writeClusters(repo->{
        if(repo.canPublishRun(run.profileId(),run.id(),workerId)) repo.finishSlice(run.id(),done);
        return null;
      });
    }
  }

  private Cluster upsert(String profileId,Topic topic) {
    return store.writeClusters(repo->{
      String scope=topic.scope();
      String title=topic.title();
      Cluster existing=repo.byScope(profileId,scope).orElse(null);
      if(existing==null && scope.startsWith("discovery:"))
        existing=repo.overlappingDiscovery(profileId,topic.members()).orElse(null);
      if(existing==null) return repo.protectedGroup(profileId,topic.members())?null:
        repo.create(profileId,title,scope,topic.members(),"automatic");
      var detail=repo.detail(profileId,existing.id(),null).orElseThrow();
      List<Member> merged=new ArrayList<>(detail.members());
      for(Member member:topic.members())
        if(!merged.contains(member) && !repo.excluded(profileId,existing.id(),member)) merged.add(member);
      if(merged.equals(detail.members()))
        return repo.synthesis(profileId,existing.id(),null).isPresent()?null:existing;
      return repo.edit(profileId,existing.id(),existing.revision(),existing.title(),existing.scope(),merged,
        "automatic","incremental-attach");
    });
  }
}
