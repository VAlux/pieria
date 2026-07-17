package dev.alvo.pieria.api.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** Composite onboarding request processed sequentially in source-list order. */
public record OnboardPlanRequest(
  @NotEmpty List<@Valid SourceSpec> sources,
  Boolean enrichGraph) {

  public OnboardPlanRequest {
    sources = sources == null ? List.of() : List.copyOf(sources);
    enrichGraph = enrichGraph == null ? Boolean.TRUE : enrichGraph;
  }

  public boolean graphEnrichmentEnabled() {
    return Boolean.TRUE.equals(enrichGraph);
  }
}
