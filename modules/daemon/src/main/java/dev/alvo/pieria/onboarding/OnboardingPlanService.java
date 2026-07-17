package dev.alvo.pieria.onboarding;

import dev.alvo.pieria.api.request.OnboardPlanRequest;
import dev.alvo.pieria.api.request.SourceSpec;
import dev.alvo.pieria.ingestion.IngestProgressListener;
import dev.alvo.pieria.reminiscence.ReminiscenceService;
import dev.alvo.pieria.task.TaskRegistry;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Runs a composite onboarding request and schedules graph enrichment after complete core success.
 */
@Service
public class OnboardingPlanService {

  private final OnboardingService onboarding;
  private final ReminiscenceService reminiscence;
  private final TaskRegistry tasks;
  private final ObjectMapper objectMapper;

  public OnboardingPlanService(OnboardingService onboarding, ReminiscenceService reminiscence,
                               TaskRegistry tasks, ObjectMapper objectMapper) {
    this.onboarding = onboarding;
    this.reminiscence = reminiscence;
    this.tasks = tasks;
    this.objectMapper = objectMapper;
  }

  public OnboardPlanResult ingest(String profile, OnboardPlanRequest request,
                                  IngestProgressListener progress) {
    List<SourceSpec> specs = request.sources();
    List<OnboardResult> results = new ArrayList<>(specs.size());
    for (int i = 0; i < specs.size(); i++) {
      SourceSpec spec = specs.get(i);
      String prefix = "source " + (i + 1) + "/" + specs.size() + " " + sourceType(spec);
      progress.onPhase(prefix, 0, 1);
      IngestProgressListener prefixed = (phase, done, total) ->
        progress.onPhase(prefix + ": " + phase, done, total);
      results.add(onboarding.ingest(profile, spec, prefixed));
      progress.onPhase(prefix, 1, 1);
    }

    long graphCandidates = request.graphEnrichmentEnabled()
      ? reminiscence.countOnboardingOrphans(profile) : 0L;
    String graphTaskId = null;
    if (request.graphEnrichmentEnabled() && graphCandidates > 0) {
      UUID submitted = tasks.submit("onboard-graph", profile, childProgress ->
        objectMapper.valueToTree(reminiscence.adoptOnboardingOrphans(profile, childProgress)));
      graphTaskId = submitted.toString();
    }

    return aggregate(results, graphTaskId, graphCandidates);
  }

  private static OnboardPlanResult aggregate(List<OnboardResult> results, String graphTaskId,
                                             long graphCandidates) {
    int documents = results.stream().mapToInt(OnboardResult::documents).sum();
    int memories = results.stream().mapToInt(OnboardResult::memoriesStored).sum();
    int skipped = results.stream().map(OnboardResult::documentsSkipped)
      .filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).sum();
    int deferred = results.stream().mapToInt(OnboardResult::graphDeferred).sum();
    int symbols = sumOptional(results, OnboardResult::symbols);
    int edges = sumOptional(results, OnboardResult::edges);
    int summaries = sumOptional(results, OnboardResult::summariesStored);
    return new OnboardPlanResult(results, documents, memories, skipped, deferred, symbols, edges,
      summaries, graphTaskId, graphCandidates);
  }

  private static int sumOptional(List<OnboardResult> results,
                                 java.util.function.Function<OnboardResult, Integer> value) {
    return results.stream().map(value).filter(java.util.Objects::nonNull)
      .mapToInt(Integer::intValue).sum();
  }

  private static String sourceType(SourceSpec spec) {
    return switch (spec) {
      case SourceSpec.Markdown ignored -> "markdown";
      case SourceSpec.SourceCode ignored -> "source-code";
      case SourceSpec.Web ignored -> "web";
      case SourceSpec.Pdf ignored -> "pdf";
      case SourceSpec.Text ignored -> "text";
    };
  }
}
