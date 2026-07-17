package dev.alvo.pieria.evaluation;

import dev.alvo.pieria.api.response.MemoryResponse;
import dev.alvo.pieria.api.response.ProfileStatsResponse;
import dev.alvo.pieria.api.response.RecallResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/** Drives the checked-in onboarding corpora through real composite onboarding tasks. */
public final class OnboardingEvaluationRunner {

  public static final int DEFAULT_RUN_COUNT = 3;
  public static final Path DEFAULT_OUTPUT_DIRECTORY = Path.of("pieria-eval-reports");

  private static final Set<String> STOP_WORDS = Set.of(
    "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "in", "is", "it",
    "of", "on", "or", "the", "to", "with");

  private final DaemonEvalClient client;
  private final Map<String, String> providerModelMetadata;

  public OnboardingEvaluationRunner(DaemonEvalClient client, Map<String, String> providerModelMetadata) {
    this.client = client;
    this.providerModelMetadata = Map.copyOf(providerModelMetadata);
  }

  public OnboardingEvaluationReport run(List<OnboardingCorpus> corpora, int runCount) {
    if (runCount < 3) {
      throw new IllegalArgumentException("onboarding evaluation requires at least three runs per variant");
    }
    List<VariantDefinition> definitions = variants();
    List<OnboardingEvaluationReport.VariantReport> reports = new ArrayList<>();
    for (VariantDefinition definition : definitions) {
      List<OnboardingTuningGate.RunMetrics> runs = new ArrayList<>();
      for (int run = 1; run <= runCount; run++) {
        runs.add(runOnce(definition, corpora, run));
      }
      reports.add(new OnboardingEvaluationReport.VariantReport(
        definition.name(), definition.overrides(), runs, OnboardingTuningGate.median(runs)));
    }

    OnboardingEvaluationReport.VariantReport baseline = reports.getFirst();
    OnboardingTuningGate.Variant baselineVariant = gateVariant(baseline);
    List<OnboardingTuningGate.Assessment> assessments = reports.stream().skip(1)
      .map(candidate -> OnboardingTuningGate.assess(
        baselineVariant, gateVariant(candidate), "combined".equals(candidate.name())))
      .toList();
    return new OnboardingEvaluationReport(
      Instant.now(), providerModelMetadata, runCount, reports, assessments);
  }

  private OnboardingTuningGate.RunMetrics runOnce(VariantDefinition variant,
                                                   List<OnboardingCorpus> corpora,
                                                   int runNumber) {
    long coreWallMs = 0;
    long structuredCalls = 0;
    long structuredOutputTokens = 0;
    int expectedFacts = 0;
    int coveredFacts = 0;
    int recallQueries = 0;
    int recalledEvidence = 0;
    int extractedMemories = 0;
    int unsupportedMemories = 0;

    for (OnboardingCorpus corpus : corpora) {
      String profile = "onboard-eval-" + variant.name() + "-" + runNumber + "-"
        + UUID.randomUUID().toString().substring(0, 8);
      Path documents = writeCorpus(corpus);
      try {
        client.configureIngestion(profile, variant.overrides());
        coreWallMs += client.onboardText(profile, documents).coreWallMs();

        ProfileStatsResponse stats = client.stats(profile);
        ProfileStatsResponse.ProfileSpend.TierSpend extraction = extractionSpend(stats);
        if (extraction != null) {
          structuredCalls += extraction.calls();
          structuredOutputTokens += extraction.completionTokens();
        }

        List<MemoryResponse> memories = client.memories(profile).memories();
        List<String> contents = memories.stream().map(MemoryResponse::content).toList();
        String sourceText = corpus.documents().stream().map(OnboardingCorpus.Document::text)
          .reduce("", (left, right) -> left + "\n" + right);
        expectedFacts += corpus.expectedFacts().size();
        coveredFacts += (int) corpus.expectedFacts().stream()
          .filter(expected -> contents.stream().anyMatch(actual -> matches(actual, expected)))
          .count();
        extractedMemories += contents.size();
        unsupportedMemories += (int) contents.stream()
          .filter(memory -> !supportedBy(memory, sourceText))
          .count();

        client.awaitVectorized(profile, Duration.ofMinutes(10));
        for (OnboardingCorpus.Recall recall : corpus.recalls()) {
          RecallResponse response = client.recall(profile, recall.query(), 10);
          recallQueries++;
          if (recall.expectedEvidence().stream().anyMatch(expected ->
            response.memories().stream().anyMatch(memory -> matches(memory.content(), expected)))) {
            recalledEvidence++;
          }
        }
      } finally {
        deleteRecursively(documents);
      }
    }

    return new OnboardingTuningGate.RunMetrics(
      coreWallMs,
      structuredCalls,
      structuredOutputTokens,
      ratio(coveredFacts, expectedFacts),
      ratio(recalledEvidence, recallQueries),
      ratio(unsupportedMemories, extractedMemories));
  }

  private static ProfileStatsResponse.ProfileSpend.TierSpend extractionSpend(ProfileStatsResponse stats) {
    if (stats.spend() == null || stats.spend().tiers() == null) {
      return null;
    }
    return stats.spend().tiers().stream()
      .filter(tier -> "extraction".equals(tier.tier()))
      .findFirst().orElse(null);
  }

  private static Path writeCorpus(OnboardingCorpus corpus) {
    try {
      Path directory = Files.createTempDirectory("pieria-onboard-" + corpus.size() + "-");
      for (OnboardingCorpus.Document document : corpus.documents()) {
        Files.writeString(directory.resolve(document.name()), document.text());
      }
      return directory;
    } catch (IOException e) {
      throw new IllegalStateException("could not stage onboarding corpus " + corpus.name(), e);
    }
  }

  static boolean matches(String actual, String expected) {
    Set<String> expectedTokens = tokens(expected);
    if (expectedTokens.isEmpty()) {
      return false;
    }
    Set<String> actualTokens = tokens(actual);
    expectedTokens.retainAll(actualTokens);
    return expectedTokens.size() >= Math.max(1, (int) Math.ceil(tokens(expected).size() * 0.65));
  }

  static boolean supportedBy(String memory, String sourceText) {
    Set<String> memoryTokens = tokens(memory);
    if (memoryTokens.isEmpty()) {
      return true;
    }
    Set<String> overlap = new HashSet<>(memoryTokens);
    overlap.retainAll(tokens(sourceText));
    return overlap.size() >= Math.max(1, (int) Math.ceil(memoryTokens.size() * 0.55));
  }

  private static Set<String> tokens(String text) {
    Set<String> out = new HashSet<>();
    Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
      .filter(token -> token.length() > 1 && !STOP_WORDS.contains(token))
      .forEach(out::add);
    return out;
  }

  private static double ratio(int numerator, int denominator) {
    return denominator == 0 ? 0.0 : numerator / (double) denominator;
  }

  private static List<VariantDefinition> variants() {
    Map<String, Object> baseline = new LinkedHashMap<>();
    baseline.put("chunk-overlap-messages", 2);
    baseline.put("interrogative-queries-per-memory", 0);
    baseline.put("max-extracted-candidates-per-chunk", 0);
    baseline.put("graph-from-extraction", false);
    return List.of(
      new VariantDefinition("baseline", baseline),
      new VariantDefinition("no-overlap", with(baseline, "chunk-overlap-messages", 0)),
      new VariantDefinition("two-queries", with(baseline, "interrogative-queries-per-memory", 2)),
      new VariantDefinition("candidate-cap", with(baseline, "max-extracted-candidates-per-chunk", 12)),
      new VariantDefinition("combined", combined(baseline)));
  }

  private static Map<String, Object> with(Map<String, Object> baseline, String key, Object value) {
    Map<String, Object> result = new LinkedHashMap<>(baseline);
    result.put(key, value);
    return result;
  }

  private static Map<String, Object> combined(Map<String, Object> baseline) {
    Map<String, Object> result = new LinkedHashMap<>(baseline);
    result.put("chunk-overlap-messages", 0);
    result.put("interrogative-queries-per-memory", 2);
    result.put("max-extracted-candidates-per-chunk", 12);
    return result;
  }

  private OnboardingTuningGate.Variant gateVariant(OnboardingEvaluationReport.VariantReport report) {
    Map<String, String> metadata = new LinkedHashMap<>(providerModelMetadata);
    report.ingestionOverrides().forEach((key, value) -> metadata.put(key, String.valueOf(value)));
    return new OnboardingTuningGate.Variant(report.name(), metadata, report.runs());
  }

  private static void deleteRecursively(Path root) {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (IOException ignored) {
          // Best-effort cleanup; corpus staging contains no user data.
        }
      });
    } catch (IOException ignored) {
      // Best-effort cleanup.
    }
  }

  private record VariantDefinition(String name, Map<String, Object> overrides) {
    private VariantDefinition {
      overrides = Map.copyOf(overrides);
    }
  }
}
