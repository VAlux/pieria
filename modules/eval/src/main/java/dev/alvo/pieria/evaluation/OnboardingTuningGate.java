package dev.alvo.pieria.evaluation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Median-based production gate for opt-in onboarding tuning experiments. */
public final class OnboardingTuningGate {

  private OnboardingTuningGate() {
  }

  public record RunMetrics(long coreWallMs, long structuredCalls, long structuredOutputTokens,
                           double factCoverage, double evidenceRecallAt10,
                           double unsupportedMemoryRate) {
  }

  public record Variant(String name, Map<String, String> providerModelMetadata,
                        List<RunMetrics> runs) {
    public Variant {
      providerModelMetadata = providerModelMetadata == null ? Map.of() : Map.copyOf(providerModelMetadata);
      runs = runs == null ? List.of() : List.copyOf(runs);
      if (runs.size() < 3) {
        throw new IllegalArgumentException("onboarding variants require at least three live runs");
      }
    }
  }

  public record Assessment(String variant, RunMetrics baselineMedian, RunMetrics candidateMedian,
                           boolean qualifies, List<String> failures) {
  }

  public static Assessment assess(Variant baseline, Variant candidate, boolean combined) {
    RunMetrics b = median(baseline.runs());
    RunMetrics c = median(candidate.runs());
    List<String> failures = new ArrayList<>();
    if (b.factCoverage() - c.factCoverage() > 0.02) failures.add("fact coverage declined by more than 2pp");
    if (b.evidenceRecallAt10() - c.evidenceRecallAt10() > 0.02) failures.add("recall@10 declined by more than 2pp");
    if (c.unsupportedMemoryRate() - b.unsupportedMemoryRate() > 0.01) failures.add("unsupported rate rose by more than 1pp");

    double wallGain = improvement(b.coreWallMs(), c.coreWallMs());
    double callGain = improvement(b.structuredCalls(), c.structuredCalls());
    double tokenGain = improvement(b.structuredOutputTokens(), c.structuredOutputTokens());
    if (combined) {
      if (wallGain < 0.25) failures.add("combined core wall-time improvement is below 25%");
      if (tokenGain < 0.20) failures.add("combined structured-token improvement is below 20%");
    } else if (Math.max(wallGain, Math.max(callGain, tokenGain)) < 0.10) {
      failures.add("no cost or wall-time metric improved by at least 10%");
    }
    return new Assessment(candidate.name(), b, c, failures.isEmpty(), List.copyOf(failures));
  }

  public static RunMetrics median(List<RunMetrics> runs) {
    return new RunMetrics(
      medianLong(runs.stream().map(RunMetrics::coreWallMs).toList()),
      medianLong(runs.stream().map(RunMetrics::structuredCalls).toList()),
      medianLong(runs.stream().map(RunMetrics::structuredOutputTokens).toList()),
      medianDouble(runs.stream().map(RunMetrics::factCoverage).toList()),
      medianDouble(runs.stream().map(RunMetrics::evidenceRecallAt10).toList()),
      medianDouble(runs.stream().map(RunMetrics::unsupportedMemoryRate).toList()));
  }

  private static long medianLong(List<Long> values) {
    List<Long> sorted = values.stream().sorted().toList();
    return sorted.get(sorted.size() / 2);
  }

  private static double medianDouble(List<Double> values) {
    List<Double> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
    return sorted.get(sorted.size() / 2);
  }

  private static double improvement(long baseline, long candidate) {
    return baseline <= 0 ? 0.0 : (baseline - candidate) / (double) baseline;
  }
}
