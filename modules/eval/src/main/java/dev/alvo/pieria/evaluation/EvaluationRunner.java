package dev.alvo.pieria.evaluation;

import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.PieriaProperties.Ingestion;
import dev.alvo.pieria.config.PieriaProperties.Retrieval;
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
import dev.alvo.pieria.retrieval.DeterministicQueryAnalyzer;
import dev.alvo.pieria.retrieval.RecallResult;
import dev.alvo.pieria.retrieval.RetrievalService;

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
 * with a pinned model gateway and in-memory store, then emits local metrics and token/latency
 * accounting.
 */
public final class EvaluationRunner {

  private final PieriaProperties properties;

  public EvaluationRunner(PieriaProperties properties) {
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  public EvaluationRunner() {
    this(defaultProperties());
  }

  public EvaluationReport run(List<EvaluationFixture> fixtures) {
    List<FixtureReport> reports = new ArrayList<>();
    for (EvaluationFixture fixture : fixtures == null ? List.<EvaluationFixture>of() : fixtures) {
      reports.add(runFixture(fixture));
    }
    return new EvaluationReport(Instant.now(), reports, summarize(reports));
  }

  private FixtureReport runFixture(EvaluationFixture fixture) {
    EvaluationTokenUsage tokenUsage = new EvaluationTokenUsage();
    PinnedEvaluationModelGateway modelGateway = new PinnedEvaluationModelGateway(fixture, tokenUsage);
    InMemoryEvaluationMemoryStore store = new InMemoryEvaluationMemoryStore();
    TranscriptNormalizer normalizer = new TranscriptNormalizer();
    Chunker chunker = new Chunker(normalizer, properties);
    IngestionService ingestion = new IngestionService(store, modelGateway, normalizer, chunker, properties);
    RetrievalService retrieval = new RetrievalService(store, modelGateway, new DeterministicQueryAnalyzer(), properties);

    long ingestStart = System.nanoTime();
    List<Memory> stored = ingestion.ingest(fixture.profileName(), fixture.sessionId(), fixture.toMessages());
    long ingestionMs = elapsedMs(ingestStart);

    ExtractionReport extraction = extractionReport(fixture, stored);
    List<RecallReport> recallReports = new ArrayList<>();
    long recallMs = 0;
    for (RecallExpectation expectation : fixture.recalls()) {
      long recallStart = System.nanoTime();
      RecallResult result = retrieval.recall(fixture.profileName(), expectation.query(), 10, true);
      long latencyMs = elapsedMs(recallStart);
      recallMs += latencyMs;
      recallReports.add(recallReport(expectation, result, latencyMs));
    }

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

  private static RecallReport recallReport(RecallExpectation expectation, RecallResult result, long latencyMs) {
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

    boolean faithful = normalizedContent(result.answer()).equals(normalizedContent(expectation.expectedAnswer()));

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
}
