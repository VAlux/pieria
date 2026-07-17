package dev.alvo.pieria.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Persisted output of the opt-in, provider-backed onboarding acceleration experiment. */
public record OnboardingEvaluationReport(
  Instant generatedAt,
  Map<String, String> providerModelMetadata,
  int runsPerVariant,
  List<VariantReport> variants,
  List<OnboardingTuningGate.Assessment> assessments) {

  public OnboardingEvaluationReport {
    providerModelMetadata = Map.copyOf(providerModelMetadata);
    variants = List.copyOf(variants);
    assessments = List.copyOf(assessments);
  }

  public record VariantReport(
    String name,
    Map<String, Object> ingestionOverrides,
    List<OnboardingTuningGate.RunMetrics> runs,
    OnboardingTuningGate.RunMetrics median) {

    public VariantReport {
      ingestionOverrides = Map.copyOf(ingestionOverrides);
      runs = List.copyOf(runs);
    }
  }
}
