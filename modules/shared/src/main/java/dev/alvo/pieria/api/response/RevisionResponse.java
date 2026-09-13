package dev.alvo.pieria.api.response;

import java.time.Instant;

public record RevisionResponse(String assertionId, String revisionId, String memoryId, String content,
                               Instant validFrom, Instant validTo, Instant statedAt,
                               Instant recordedFrom, Instant recordedTo, String corrects,
                               String sourceOccurrence, boolean invalidated, boolean approximate,
                               String factGroup, String sourcePayload, String conflictEvidence) {
  public RevisionResponse(String assertionId, String revisionId, String memoryId, String content, Instant validFrom,
                          Instant validTo, Instant statedAt, Instant recordedFrom, Instant recordedTo, String corrects,
                          String sourceOccurrence, boolean invalidated, boolean approximate) {
    this(assertionId, revisionId, memoryId, content, validFrom, validTo, statedAt, recordedFrom, recordedTo, corrects,
      sourceOccurrence, invalidated, approximate, null, "{}", null);
  }
}
