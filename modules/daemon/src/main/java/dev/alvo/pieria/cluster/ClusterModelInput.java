package dev.alvo.pieria.cluster;

import java.util.List;

/** Bounded, deduplicated source evidence and validated child claims for one generation attempt. */
public record ClusterModelInput(String title,String scope,List<ClusterRepository.Evidence> evidence,
                                List<Child> children,int maxOutputTokens) {
  public record Child(String clusterId,String synthesisId,List<ClusterRepository.Claim> claims) {}
  public record Draft(String body,List<DraftClaim> claims) {}
  public record DraftClaim(String text,List<String> supports,List<String> covers) {}
}
