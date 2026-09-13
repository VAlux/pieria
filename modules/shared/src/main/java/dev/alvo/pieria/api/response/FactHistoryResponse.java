package dev.alvo.pieria.api.response;

import java.time.Instant;
import java.util.List;

/** Complete non-forgotten history for the temporal fact group containing one memory. */
public record FactHistoryResponse(
  String factGroup,
  RevisionResponse selectedRevision,
  TemporalValidity selectedValidity,
  List<RevisionResponse> revisions,
  List<TemporalValidity> segments,
  TemporalContext temporal) {

  public record TemporalContext(Instant asOf, Instant knownAsOf, long snapshotSequence) {}
}
