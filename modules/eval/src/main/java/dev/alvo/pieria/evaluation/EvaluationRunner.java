package dev.alvo.pieria.evaluation;

import dev.alvo.pieria.config.EffectiveConfigResolver;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.PieriaProperties.Ingestion;
import dev.alvo.pieria.config.PieriaProperties.Retrieval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.evaluation.EvaluationFixture.ExpectedMemory;
import dev.alvo.pieria.evaluation.EvaluationFixture.RecallExpectation;
import dev.alvo.pieria.evaluation.EvaluationReport.ExtractionReport;
import dev.alvo.pieria.evaluation.EvaluationReport.FixtureReport;
import dev.alvo.pieria.evaluation.EvaluationReport.Latency;
import dev.alvo.pieria.evaluation.EvaluationReport.RecallReport;
import dev.alvo.pieria.evaluation.EvaluationReport.Summary;
import dev.alvo.pieria.evaluation.EvaluationReport.TokenUsage;
import dev.alvo.pieria.ingestion.Chunker;
import dev.alvo.pieria.ingestion.IngestionService;
import dev.alvo.pieria.ingestion.TranscriptNormalizer;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.retrieval.DeterministicQueryAnalyzer;
import dev.alvo.pieria.retrieval.RecallResult;
import dev.alvo.pieria.retrieval.RetrievalService;
import dev.alvo.pieria.storage.MemoryStore;
import dev.alvo.pieria.storage.NoOpCodeIndexStore;

import java.util.function.Supplier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Fixture-first evaluation harness. It runs the real ingestion/retrieval orchestration
 * against an injectable {@link ModelGateway} and {@link MemoryStore}, then emits local metrics and
 * token/latency accounting.
 *
 * <p>By default it uses a {@link PinnedEvaluationModelGateway} (deterministic, fixture-pinned) and a
 * fresh {@link InMemoryEvaluationMemoryStore} per fixture — this is the network-free path used by
 * CI and by {@link #run(List)}. Benchmark adapters that want to drive a live model (e.g. the
 * daemon's {@code OpenAiModelGateway}) call {@link #run(List, Supplier, Supplier)} and supply their
 * own gateway/store factories, which is the seam that also enables comparing a default local model
 * against a hosted baseline: wire a different {@link ModelGateway} and run twice.
 */
public final class EvaluationRunner {

  private static final Logger log = LoggerFactory.getLogger(EvaluationRunner.class);

  /** Fraction of an evidence turn's content words a memory must cover to count as retrieved. */
  private static final double EVIDENCE_MATCH_THRESHOLD = 0.6;

  /** Dropped before containment so filler words don't dominate short evidence turns. */
  private static final Set<String> STOPWORDS = Set.of(
    "a", "an", "the", "i", "you", "we", "he", "she", "it", "they", "me", "my", "your", "our",
    "is", "are", "was", "were", "be", "been", "am", "do", "does", "did", "have", "has", "had",
    "to", "of", "in", "on", "at", "for", "with", "and", "or", "but", "so", "just", "this", "that",
    "these", "those", "as", "from", "by", "about", "last", "next", "here", "there");

  private final PieriaProperties properties;

  public EvaluationRunner(PieriaProperties properties) {
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  public EvaluationRunner() {
    this(defaultProperties());
  }

  /**
   * Deterministic, network-free run: each fixture gets its own pinned gateway + in-memory store.
   */
  public EvaluationReport run(List<EvaluationFixture> fixtures) {
    List<EvaluationFixture> list = fixtures == null ? List.of() : fixtures;
    List<FixtureReport> reports = new ArrayList<>();
    long runStart = System.nanoTime();
    for (int i = 0; i < list.size(); i++) {
      EvaluationFixture fixture = list.get(i);
      EvaluationTokenUsage tokenUsage = new EvaluationTokenUsage();
      ModelGateway gateway = new PinnedEvaluationModelGateway(fixture, tokenUsage);
      reports.add(runFixture(fixture, gateway, new InMemoryEvaluationMemoryStore(), tokenUsage, i + 1, list.size()));
      logRunProgress(runStart, i + 1, list.size());
    }
    return new EvaluationReport(Instant.now(), reports, summarize(reports));
  }

  /**
   * Live (or arbitrary) run: the caller supplies a {@link ModelGateway} factory and a
   * {@link MemoryStore} factory. A fresh store is created per fixture so memories from one fixture
   * never leak into another. Token usage is not tracked for live gateways (it cannot be observed
   * generically), so reports use {@link EvaluationTokenUsage} with zero counts.
   *
   * @param fixtures      fixtures parsed from a benchmark dataset (or hand-authored)
   * @param gatewayFactory supplies the model gateway to drive ingestion + retrieval
   * @param storeFactory  supplies a fresh memory store per fixture
   */
  public EvaluationReport run(List<EvaluationFixture> fixtures,
                              Supplier<ModelGateway> gatewayFactory,
                              Supplier<MemoryStore> storeFactory) {
    Objects.requireNonNull(gatewayFactory, "gatewayFactory");
    Objects.requireNonNull(storeFactory, "storeFactory");
    List<EvaluationFixture> list = fixtures == null ? List.of() : fixtures;
    List<FixtureReport> reports = new ArrayList<>();
    long runStart = System.nanoTime();
    for (int i = 0; i < list.size(); i++) {
      EvaluationFixture fixture = list.get(i);
      EvaluationTokenUsage tokenUsage = new EvaluationTokenUsage();
      reports.add(runFixture(fixture, gatewayFactory.get(), storeFactory.get(), tokenUsage, i + 1, list.size()));
      logRunProgress(runStart, i + 1, list.size());
    }
    return new EvaluationReport(Instant.now(), reports, summarize(reports));
  }

  private FixtureReport runFixture(EvaluationFixture fixture,
                                   ModelGateway modelGateway,
                                   MemoryStore store,
                                   EvaluationTokenUsage tokenUsage,
                                   int index,
                                   int total) {
    TranscriptNormalizer normalizer = new TranscriptNormalizer();
    // Eval runs against the global config only — no per-profile overrides in the harness.
    EffectiveConfigResolver configResolver = EffectiveConfigResolver.withoutOverrides(properties);
    Chunker chunker = new Chunker(normalizer);
    IngestionService ingestion = new IngestionService(store, modelGateway, normalizer, chunker, configResolver);
    RetrievalService retrieval = new RetrievalService(store, modelGateway, new DeterministicQueryAnalyzer(),
      new NoOpCodeIndexStore(), configResolver);

    log.info("[{}/{}] {} — ingesting {} messages", index, total, fixture.name(), fixture.transcript().size());
    long ingestStart = System.nanoTime();
    List<Memory> stored = ingestion.ingest(fixture.profileName(), fixture.sessionId(), fixture.toMessages());
    long ingestionMs = elapsedMs(ingestStart);
    log.info("[{}/{}] {} — ingestion done ({} memories, {}ms)", index, total, fixture.name(), stored.size(), ingestionMs);

    ExtractionReport extraction = extractionReport(fixture, stored);
    List<RecallReport> recallReports = new ArrayList<>();
    long recallMs = 0;
    List<RecallExpectation> recalls = fixture.recalls();
    log.info("[{}/{}] {} — running {} recall queries", index, total, fixture.name(), recalls.size());
    long queriesStart = System.nanoTime();
    for (int q = 0; q < recalls.size(); q++) {
      RecallExpectation expectation = recalls.get(q);
      log.info("[{}/{}] {} — query [{}/{}]: {}", index, total, fixture.name(), q + 1, recalls.size(), expectation.query());
      long recallStart = System.nanoTime();
      RecallResult result = retrieval.recall(fixture.profileName(), expectation.query(), 10, true);
      long latencyMs = elapsedMs(recallStart);
      recallMs += latencyMs;
      boolean faithful = modelGateway.judgeAnswerFaithfulness(
        expectation.query(), expectation.expectedAnswer(), result.answer());
      RecallReport report = recallReport(expectation, result, latencyMs, faithful);
      int completedQueries = q + 1;
      int remainingQueries = recalls.size() - completedQueries;
      long avgQueryMs = elapsedMs(queriesStart) / completedQueries;
      log.info("[{}/{}] {} — query [{}/{}] done in {}ms — faithful={} answer='{}'{}",
        index, total, fixture.name(), q + 1, recalls.size(), latencyMs,
        faithful, truncate(result.answer(), 80),
        remainingQueries == 0 ? "" : " (ETA " + formatDuration(avgQueryMs * remainingQueries) + " for remaining queries)");
      recallReports.add(report);
    }
    log.info("[{}/{}] {} — recall done ({}ms)", index, total, fixture.name(), recallMs);

    return new FixtureReport(
      fixture.name(),
      extraction,
      recallReports,
      average(recallReports.stream().mapToDouble(RecallReport::hitRate).toArray()),
      average(recallReports.stream().mapToDouble(RecallReport::reciprocalRank).toArray()),
      average(recallReports.stream().mapToDouble(report -> report.answerFaithful() ? 1.0 : 0.0).toArray()),
      new Latency(ingestionMs, recallMs, ingestionMs + recallMs),
      tokenUsage.snapshot());
  }

  private static ExtractionReport extractionReport(EvaluationFixture fixture, List<Memory> stored) {
    Set<String> expected = new HashSet<>();
    for (ExpectedMemory memory : fixture.expectedMemories()) {
      expected.add(memory.key());
    }

    Set<String> actual = new HashSet<>();
    for (Memory memory : stored) {
      actual.add(EvaluationFixture.memoryKey(memory.type(), memory.content(), memory.topicKey()));
    }

    int truePositive = 0;
    for (String key : actual) {
      if (expected.contains(key)) {
        truePositive++;
      }
    }

    return new ExtractionReport(
      expected.size(),
      actual.size(),
      truePositive,
      ratio(truePositive, actual.size()),
      ratio(truePositive, expected.size()));
  }

  private static RecallReport recallReport(RecallExpectation expectation, RecallResult result, long latencyMs, boolean faithful) {
    List<String> actualEvidence = result.memories().stream().map(Memory::content).toList();
    // Extraction rewrites turns into terse memories, so an evidence turn rarely equals a memory
    // verbatim. Match on stopword-filtered token containment instead: an expected evidence item
    // "hits" when some retrieved memory covers at least EVIDENCE_MATCH_THRESHOLD of its content
    // words. MRR is the reciprocal of the best (smallest) rank among all matching memories.
    List<Set<String>> actualTokens = new ArrayList<>(actualEvidence.size());
    for (String content : actualEvidence) {
      actualTokens.add(contentTokens(content));
    }

    int hits = 0;
    int firstRank = 0;
    for (String expected : expectation.expectedEvidence()) {
      int rank = firstMatchingRank(contentTokens(expected), actualTokens);
      if (rank > 0) {
        hits++;
        if (firstRank == 0 || rank < firstRank) {
          firstRank = rank;
        }
      }
    }

    return new RecallReport(
      expectation.query(),
      expectation.expectedEvidence(),
      actualEvidence,
      ratio(hits, expectation.expectedEvidence().size()),
      firstRank == 0 ? 0.0 : 1.0 / firstRank,
      faithful,
      expectation.expectedAnswer(),
      result.answer(),
      latencyMs);
  }

  private static Summary summarize(List<FixtureReport> reports) {
    long ingestionMs = 0;
    long recallMs = 0;
    long promptTokens = 0;
    long completionTokens = 0;
    Map<String, Integer> callsByStage = new LinkedHashMap<>();

    for (FixtureReport report : reports) {
      ingestionMs += report.latency().ingestionMs();
      recallMs += report.latency().recallMs();
      promptTokens += report.tokenUsage().promptTokens();
      completionTokens += report.tokenUsage().completionTokens();
      report.tokenUsage().callsByStage().forEach((stage, calls) ->
        callsByStage.merge(stage, calls, Integer::sum));
    }

    Latency latency = new Latency(ingestionMs, recallMs, ingestionMs + recallMs);
    TokenUsage tokenUsage = new TokenUsage(promptTokens, completionTokens, promptTokens + completionTokens, callsByStage);

    return new Summary(
      reports.size(),
      average(reports.stream().mapToDouble(report -> report.extraction().precision()).toArray()),
      average(reports.stream().mapToDouble(report -> report.extraction().recall()).toArray()),
      average(reports.stream().mapToDouble(FixtureReport::retrievalHitRate).toArray()),
      average(reports.stream().mapToDouble(FixtureReport::meanReciprocalRank).toArray()),
      average(reports.stream().mapToDouble(FixtureReport::answerFaithfulness).toArray()),
      latency,
      tokenUsage);
  }

  private static PieriaProperties defaultProperties() {
    var retrieval = new Retrieval(false,
      60,
      3.0,
      1.0,
      1.0,
      1.0,
      0.5,
      1.0,
      2,
      20,
      8,
      10,
      3000,
      0.0,
      0.0,
      2,
      20,
      8,
      "heuristic");

    var ingestion = new Ingestion(10000,
      2,
      4,
      999,
      32,
      5,
      false,
      5000);

    return new PieriaProperties(null, null, null, null, ingestion, retrieval, null);
  }

  /** Rank (1-based) of the first memory whose tokens cover the expected evidence, or 0 for none. */
  private static int firstMatchingRank(Set<String> expectedTokens, List<Set<String>> actualTokens) {
    if (expectedTokens.isEmpty()) {
      return 0;
    }
    for (int i = 0; i < actualTokens.size(); i++) {
      if (containment(expectedTokens, actualTokens.get(i)) >= EVIDENCE_MATCH_THRESHOLD) {
        return i + 1;
      }
    }
    return 0;
  }

  /** Fraction of {@code expected} tokens present in {@code actual} (0 when {@code expected} empty). */
  private static double containment(Set<String> expected, Set<String> actual) {
    if (expected.isEmpty()) {
      return 0.0;
    }
    int overlap = 0;
    for (String token : expected) {
      if (actual.contains(token)) {
        overlap++;
      }
    }
    return (double) overlap / expected.size();
  }

  private static Set<String> contentTokens(String content) {
    if (content == null || content.isBlank()) {
      return Set.of();
    }
    Set<String> tokens = new HashSet<>();
    for (String token : content.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
      if (!token.isBlank() && !STOPWORDS.contains(token)) {
        tokens.add(token);
      }
    }
    return tokens;
  }

  private static double ratio(int numerator, int denominator) {
    if (denominator == 0) {
      return numerator == 0 ? 1.0 : 0.0;
    }
    return (double) numerator / denominator;
  }

  private static double average(double[] values) {
    if (values.length == 0) {
      return 0.0;
    }
    double total = 0;
    for (double value : values) {
      total += value;
    }
    return total / values.length;
  }

  private static long elapsedMs(long startedAtNanos) {
    return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000L);
  }

  /** Logs elapsed/average/ETA across the whole fixture run, using completed fixtures as the sample. */
  private static void logRunProgress(long runStartNanos, int completed, int total) {
    long elapsedMs = elapsedMs(runStartNanos);
    int remaining = total - completed;
    if (remaining == 0) {
      log.info("[{}/{}] run complete — elapsed {}", completed, total, formatDuration(elapsedMs));
      return;
    }
    long avgMs = elapsedMs / completed;
    log.info("[{}/{}] run progress — elapsed {}, avg {}/fixture, ETA {} ({} fixtures left)",
      completed, total, formatDuration(elapsedMs), formatDuration(avgMs), formatDuration(avgMs * remaining), remaining);
  }

  private static String formatDuration(long millis) {
    long totalSeconds = millis / 1000;
    long hours = totalSeconds / 3600;
    long minutes = (totalSeconds % 3600) / 60;
    long seconds = totalSeconds % 60;
    if (hours > 0) {
      return String.format(Locale.ROOT, "%dh%02dm%02ds", hours, minutes, seconds);
    }
    if (minutes > 0) {
      return String.format(Locale.ROOT, "%dm%02ds", minutes, seconds);
    }
    return seconds + "s";
  }

  private static String truncate(String s, int max) {
    if (s == null) return "";
    String stripped = s.strip().replace('\n', ' ');
    return stripped.length() <= max ? stripped : stripped.substring(0, max) + "…";
  }
}
