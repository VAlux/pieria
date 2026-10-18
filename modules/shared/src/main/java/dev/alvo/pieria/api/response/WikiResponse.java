package dev.alvo.pieria.api.response;

import java.util.List;

/** Profile-scoped wiki wire values. These contain no worker bundles or provider details. */
public final class WikiResponse {
  private WikiResponse() {}
  public record State(boolean enabled,boolean initialized,long corpusWatermark,long projectionWatermark,long revision) {}
  public record Card(String id,String title,String kind,String scope,String parentId,long descriptorRevision,
                     int evidenceCount,String generation,String freshness,String excerpt) {
    public Card(String id,String title,String kind,String scope,String parentId,long descriptorRevision,
                int evidenceCount,String generation,String freshness) {
      this(id,title,kind,scope,parentId,descriptorRevision,evidenceCount,generation,freshness,null);
    }
    public Card withExcerpt(String text) {
      return new Card(id,title,kind,scope,parentId,descriptorRevision,evidenceCount,generation,freshness,text);
    }
  }
  public record Directory(State state,List<Card> pages,String nextCursor) {}
  public record Evidence(String ref,String memoryId,String content,String type,String role,
                         List<TemporalValidity> validity,boolean truncated) {}
  public record Link(String id,String title,String kind) {}
  public record Job(String id,String pageId,String status,int attempts,String errorCode,String versionId) {}
  public record Page(State state,Card page,String body,String versionId,List<Evidence> evidence,List<Link> links,
                     Job job,int totalSources,int omittedSources,int addedSources,int removedSources,boolean renderingUpgrade) {}
  public record EvidencePage(List<Evidence> evidence,String nextCursor) {}
  public record Search(State state,List<Card> pages,List<Evidence> unassigned,boolean degraded,boolean truncated) {}
  public record Evaluation(String status,String reason,Job job,String versionId) {}
  /**
   * Whole-wiki progress for the console: how far projection trails the corpus, how many topic and
   * entity pages have a written article, and what the article queue holds.
   */
  public record Status(State state,long projectionLag,int unassignedEvidence,int pagesTotal,int articlesReady,int articlesAbsent,
                       int articlesStale,int jobsQueued,int jobsGenerating,int jobsFailed) {}
  /** A wiki-wide build request: write missing articles, or also refresh stale ones. */
  public record BuildRequest(String scope) {}
}
