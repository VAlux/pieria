package dev.alvo.pieria.wiki;

import dev.alvo.pieria.domain.temporal.TemporalSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import static dev.alvo.pieria.wiki.WikiData.*;

/** Only available inside MemoryStore.readWiki/writeWiki; all methods are profile-scoped. */
public interface WikiRepository {
  boolean exists(String profile);
  State state(String profile);
  long watermark(String profile);
  TemporalSnapshot snapshot();
  List<Source> sources(String profile, TemporalSnapshot snapshot, String after, int limit);
  Optional<Source> source(String profile, String id, TemporalSnapshot snapshot);
  Instant nextBoundary(String profile, Instant now);
  List<Change> changes(String profile, long after, int limit);
  List<String> factPeers(String profile, String memoryId);
  List<String> candidateMemories(String profile, Source source, TemporalSnapshot snapshot, int limit);
  boolean distinctiveEntity(String profile, String entity, TemporalSnapshot snapshot);
  List<Page> pages(String profile, String after, int limit);
  List<Page> directory(String profile, String parent, String after, int limit);
  int evidenceCount(String profile, String page, TemporalSnapshot snapshot);
  Optional<Page> page(String profile, String id);
  Optional<Page> anchor(String profile, String anchor);
  Page ensurePage(String profile, String kind, String title, String anchor, String parent);
  List<Member> members(String profile, String page);
  List<Member> evidenceMembers(String profile, String page, TemporalSnapshot snapshot, String after, int limit);
  List<Page> splitChildren(String profile, String parent);
  void replaceMembers(String profile, String page, List<String> memories);
  List<String> placements(String profile, String memory);
  void membership(String profile, String page, String memory, String role, double score);
  void removeMemberships(String profile, String memory);
  void refreshPage(String profile, String page, TemporalSnapshot snapshot);
  void syncClusters(String profile, TemporalSnapshot snapshot);
  List<ClusterClaim> clusterClaims(String profile, Page page, TemporalSnapshot snapshot, List<Evidence> selected);
  void checkpoint(String profile, State state);
  void checked(String profile, String page, long watermark, Instant boundary);
  List<Link> links(String profile, String page);
  void related(String profile, String page, String target);
  String canonical(String profile, String id);
  void merge(String profile, String from, String into);
  boolean confirmMerge(String profile, String left, String right, String signature, String round);
  boolean pendingMerges(String profile);
  void finishMergeRound(String profile, String round);
  List<String> searchPages(String profile, String match, int limit);
  List<Source> searchSources(String profile, String match, float[] embedding, TemporalSnapshot snapshot, int limit);
  Optional<Version> version(String profile, String id);
  Optional<Job> job(String profile, String id);
  Optional<Job> latestJob(String profile, String page);
  Optional<Job> liveJob(String profile, String page);
  Job enqueue(String profile, Bundle bundle, int queueLimit);
  Optional<Job> claim(String profile, Instant now, int maxAttempts);
  boolean heartbeat(Job job, Instant now);
  void finish(Job job, String status, String code, Instant retryAt);
  boolean publish(Job job, Bundle current, String body);
  boolean forgotten(String profile, Bundle bundle);
}
