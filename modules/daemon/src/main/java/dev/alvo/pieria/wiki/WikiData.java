package dev.alvo.pieria.wiki;

import dev.alvo.pieria.api.response.TemporalValidity;
import dev.alvo.pieria.domain.temporal.TemporalSnapshot;
import java.time.Instant;
import java.util.List;

/** Immutable values crossing the wiki persistence seam. No database handles escape a transaction. */
public final class WikiData {
  private WikiData() {}
  public record State(String cursor, long checkpoint, boolean initialized, long revision, String policy, Instant nextBoundary, long targetSequence, TemporalSnapshot scanSnapshot) {}
  public record Page(String id, String kind, String title, String anchor, String parentId, String lifecycle,
                     long revision, long scopeRevision, boolean suppressed, String currentVersion,
                     long checkedSequence, Instant nextBoundary, String signature, Instant createdAt) {}
  public record Source(String id, String type, String content, String topicKey, String payload,
                       List<TemporalValidity> validity, List<Anchor> entities, float[] embedding) {}
  public record Anchor(String id, String name, String type) {}
  public record Member(String memoryId, String role, double score) {}
  public record Link(String id, String title, String kind) {}
  public record Change(long sequence, String memoryId, String kind) {}
  public record Evidence(String ref, String memoryId, String content, String type, String role,
                         List<TemporalValidity> validity, boolean truncated) {}
  public record ClusterClaim(String text,List<String> citations) {}
  public record Bundle(String pageId, String title, long scopeRevision, long descriptorRevision,
                       String fingerprint, String rendering, TemporalSnapshot snapshot, long watermark,
                       List<Evidence> evidence, List<Link> links, List<String> temporalFacts, int totalSources, int omittedSources,
                       int maxOutputTokens, Instant nextBoundary, List<ClusterClaim> clusterClaims) {
    public Bundle(String pageId,String title,long scopeRevision,long descriptorRevision,String fingerprint,
                  String rendering,TemporalSnapshot snapshot,long watermark,List<Evidence> evidence,
                  List<Link> links,List<String> temporalFacts,int totalSources,int omittedSources,
                  int maxOutputTokens,Instant nextBoundary) {
      this(pageId,title,scopeRevision,descriptorRevision,fingerprint,rendering,snapshot,watermark,
        evidence,links,temporalFacts,totalSources,omittedSources,maxOutputTokens,nextBoundary,List.of());
    }
  }
  public record Version(String id, String pageId, String fingerprint, String body, Bundle bundle, Instant createdAt) {}
  public record Job(String id, String profileId, String pageId, String fingerprint, String status, int attempts,
                    long fence, Instant leaseUntil, String errorCode, String resultVersion, Bundle bundle) {}
}
