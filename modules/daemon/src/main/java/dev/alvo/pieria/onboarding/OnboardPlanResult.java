package dev.alvo.pieria.onboarding;

import java.util.List;

/**
 * Terminal result of the composite core-onboarding task. Successful source results contribute to
 * the aggregate counters; non-fatal source failures are returned in {@link #errors()} after every
 * requested source has been attempted.
 */
public record OnboardPlanResult(
  List<OnboardResult> sources,
  int documents,
  int memoriesStored,
  int documentsSkipped,
  int graphDeferred,
  int symbols,
  int edges,
  int summariesStored,
  String graphEnrichmentTaskId,
  long graphCandidates,
  List<OnboardError> errors) {

  public OnboardPlanResult {
    sources = sources == null ? List.of() : List.copyOf(sources);
    errors = errors == null ? List.of() : List.copyOf(errors);
  }
}
