package dev.alvo.pieria.cluster;

import dev.alvo.pieria.domain.temporal.TemporalSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.Map;

/** Persistence seam for versioned knowledge containers. Mutations run inside MemoryStore.writeClusters. */
public interface ClusterRepository {
  record Member(String kind, String id) {
    public Member {
      if (!"memory".equals(kind) && !"cluster".equals(kind))
        throw new IllegalArgumentException("member kind must be memory or cluster");
      if (id == null || id.isBlank()) throw new IllegalArgumentException("member id is required");
    }
  }
  record Cluster(String id, String profileId, int revision, String title, String scope,
                 String state, int directCount, int distinctOriginalCount, String freshness) {}
  record Structure(int revision, String title, String scope, String state,
                   String recordedAt, String actor, String reason, List<Member> members) {}
  record Detail(Cluster cluster, List<Member> members, List<String> parents,
                List<Structure> history) {}
  record Source(String memoryId, String assertionRevisionId) {}
  record Evidence(String memoryId, String assertionRevisionId, String content, String sessionId) {}
  record Claim(String text, List<Source> supports, List<Source> covers) {}
  record Synthesis(String id, String clusterId, int structureRevision, String body,
                   String fingerprint, String status, List<Claim> claims) {}
  record Run(String id, String profileId, String state, String trigger, String checkpoint,
             String createdAt, String updatedAt, TemporalSnapshot snapshot,
             long eventAfter, long eventThrough) {}
  record Regeneration(String itemId, String clusterId, int expectedRevision, int attempts) {}
  record Work(String id,String subjectKind,String subjectId,String stage,String status,
              int attempts,String output) {}
  record Schedule(boolean enabled,String localTime,String timezone,String lastLocalDate) {}
  record Topic(String key,String title,String scope,List<Member> members) {
    public Topic(String key,List<Member> members) {this(key,key,"topic-key:"+key,members);}
  }

  Cluster create(String profileId, String title, String scope, List<Member> members, String actor);
  Cluster edit(String profileId, String clusterId, int expectedRevision, String title, String scope,
               List<Member> members, String actor, String reason);
  Cluster dissolve(String profileId, String clusterId, int expectedRevision, String actor);
  Cluster restore(String profileId, String clusterId, int expectedRevision, int sourceRevision, String actor);
  Optional<Detail> detail(String profileId, String clusterId, TemporalSnapshot snapshot);
  List<Cluster> list(String profileId, String query, int limit, TemporalSnapshot snapshot);
  List<Member> descendants(String profileId, String clusterId, int offset, int limit, TemporalSnapshot snapshot);
  Optional<Synthesis> synthesis(String profileId, String clusterId, TemporalSnapshot snapshot);
  List<Evidence> evidence(String profileId, String clusterId, TemporalSnapshot snapshot);
  String fingerprint(String profileId, String clusterId);
  Synthesis publish(String profileId, String clusterId, int expectedRevision, String fingerprint,
                    String body, List<Claim> claims, String modelVersion, String promptVersion,
                    String validation, String actor, List<float[]> claimEmbeddings);
  default Synthesis publish(String profileId, String clusterId, int expectedRevision, String fingerprint,
                    String body, List<Claim> claims, String modelVersion, String promptVersion,
                    String validation, String actor) {
    return publish(profileId,clusterId,expectedRevision,fingerprint,body,claims,modelVersion,
      promptVersion,validation,actor,List.of());
  }
  Run startRun(String profileId, String trigger);
  Optional<Run> run(String profileId, String runId);
  List<Run> runs(String profileId, int limit);
  List<Work> work(String profileId, String runId, int limit);
  List<Map<String,Object>> auditEvents(String profileId,String runId,int limit);
  Run transitionRun(String profileId, String runId, String action);
  Optional<Run> claimRun(String workerId);
  void renewRun(String runId, String workerId);
  boolean canPublishRun(String profileId, String runId, String workerId);
  List<Topic> topics(String profileId, String after, int limit, TemporalSnapshot snapshot);
  List<Topic> entityTopics(String profileId,String after,int limit,TemporalSnapshot snapshot);
  List<String> discoverySeeds(String profileId,String after,int limit,TemporalSnapshot snapshot,
                              long eventAfter,long eventThrough);
  List<Topic> broaderTopics(String profileId,String after,int limit);
  boolean protectedGroup(String profileId,List<Member> members);
  Optional<Cluster> overlappingDiscovery(String profileId,List<Member> members);
  void checkpoint(String runId, String topicKey, String status, String detail);
  void finishSlice(String runId, boolean exhausted);
  void enqueueRegeneration(String runId, String clusterId, int expectedRevision);
  void enqueueDirtyRegenerations(String runId,String profileId);
  List<Regeneration> pendingRegenerations(String runId, int limit);
  void finishRegeneration(String itemId, String status, String detail);
  Optional<Cluster> byScope(String profileId, String scope);
  boolean excluded(String profileId, String clusterId, Member member);
  Schedule schedule(String profileId);
  Schedule setSchedule(String profileId, boolean enabled, String localTime, String timezone);
  List<Run> activateDueSchedules(java.time.Instant now);
  List<Run> activateDueBoundaries(java.time.Instant now);
  Map<String,Object> exportProfile(String profileId);
  void deleteProfile(String profileId);
}
