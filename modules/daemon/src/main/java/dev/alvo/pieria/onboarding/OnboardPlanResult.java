package dev.alvo.pieria.onboarding;

import java.util.List;

/** Terminal result of the composite core-onboarding task. */
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
  long graphCandidates) {

  public OnboardPlanResult {
    sources = sources == null ? List.of() : List.copyOf(sources);
  }
}
