package dev.alvo.pieria.evaluation;

import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.PieriaProperties.Ingestion;
import dev.alvo.pieria.config.PieriaProperties.Retrieval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.alvo.pieria.domain.Memory;
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

import java.util.function.Supplier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static dev.alvo.pieria.evaluation.EvaluationFixture.normalizedContent;

/**
 * Fixture-first evaluation harness for Phase 5. It runs the real ingestion/retrieval orchestration
 * against an injectable {@link ModelGateway} and {@link MemoryStore}, then emits local metrics and
 * token/latency accounting.
 *
 * <p>By default it uses a {@link PinnedEvaluationModelGateway} (deterministic, fixture-pinned) and a
 * fresh {@link InMemoryEvaluationMemoryStore} per fixture — this is the network-free path used by
 * CI and by {@link #run(List)}. Benchmark adapters that want to drive a live model (e.g. the
 * daemon's {@code OllamaModelGateway}) call {@link #run(List, Supplier, Supplier)} and supply their
 * own gateway/store factories, which is the seam that also enables comparing a default local model
 * against a hosted baseline: wire a different {@link ModelGateway} and run twice.
 */
public final class EvaluationRunner {

  private static final Logger log = LoggerFactory.getLogger(EvaluationRunner.class);

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
    for (int i = 0; i < list.size(); i++) {
      EvaluationFixture fixture = list.get(i);
      EvaluationTokenUsage tokenUsage = new EvaluationTokenUsage();
      ModelGateway gateway = new PinnedEvaluationModelGateway(fixture, tokenUsage);
      reports.add(runFixture(fixture, gateway, new InMemoryEvaluationMemoryStore(), tokenUsage, i + 1, list.size()));
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
    for (int i = 0; i < list.size(); i++) {
      EvaluationFixture fixture = list.get(i);
      EvaluationTokenUsage tokenUsage = new EvaluationTokenUsage();
      reports.add(runFixture(fixture, gatewayFactory.get(), storeFactory.get(), tokenUsage, i + 1, list.size()));
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
    Chunker chunker = new Chunker(normalizer, properties);
    IngestionService ingestion = new IngestionService(store, modelGateway, normalizer, chunker, properties);
    RetrievalService retrieval = new RetrievalService(store, modelGateway, new DeterministicQueryAnalyzer(), properties);

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
      log.info("[{}/{}] {} — query [{}/{}] done in {}ms — faithful={} answer='{}'",
        index, total, fixture.name(), q + 1, recalls.size(), latencyMs,
        faithful, truncate(result.answer(), 80));
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
    Set<String> actualNormalized = new HashSet<>();

    for (String content : actualEvidence) {
      actualNormalized.add(normalizedContent(content));
    }

    int hits = 0;
    int firstRank = 0;
    for (String expected : expectation.expectedEvidence()) {
      String normalizedExpected = normalizedContent(expected);
      if (actualNormalized.contains(normalizedExpected)) {
        hits++;
        if (firstRank == 0) {
          for (int i = 0; i < actualEvidence.size(); i++) {
            if (normalizedContent(actualEvidence.get(i)).equals(normalizedExpected)) {
              firstRank = i + 1;
              break;
            }
          }
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
      10,
      3000);

    var ingestion = new Ingestion(10000,
      2,
      4,
      999,
      32,
      5,
      false,
      5000);

    return new PieriaProperties(null, null, null, null, ingestion, retrieval);
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

  private static String truncate(String s, int max) {
    if (s == null) return "";
    String stripped = s.strip().replace('\n', ' ');
    return stripped.length() <= max ? stripped : stripped.substring(0, max) + "…";
  }
}
