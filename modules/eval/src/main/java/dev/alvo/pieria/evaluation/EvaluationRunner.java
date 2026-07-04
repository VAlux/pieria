package dev.alvo.pieria.evaluation;

import dev.alvo.pieria.api.request.IngestRequest;
import dev.alvo.pieria.api.response.IngestResponse;
import dev.alvo.pieria.api.response.MemoryResponse;
import dev.alvo.pieria.api.response.RecallResponse;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.evaluation.EvaluationFixture.ExpectedMemory;
import dev.alvo.pieria.evaluation.EvaluationFixture.RecallExpectation;
import dev.alvo.pieria.evaluation.EvaluationReport.ExtractionReport;
import dev.alvo.pieria.evaluation.EvaluationReport.FixtureReport;
import dev.alvo.pieria.evaluation.EvaluationReport.Latency;
import dev.alvo.pieria.evaluation.EvaluationReport.RecallReport;
import dev.alvo.pieria.evaluation.EvaluationReport.Summary;
import dev.alvo.pieria.evaluation.EvaluationReport.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Benchmark harness that drives a <em>real</em> running daemon over HTTP. For each fixture it POSTs
 * the transcript to {@code /ingest}, waits for the vectorization outbox to drain, then runs each
 * recall query against {@code /recall} and scores the fused, ranked memories the daemon returns.
 *
 * <p>Unlike the old in-process harness, nothing here instantiates the ingestion/retrieval services
 * or a stub store: metrics reflect the deployed pipeline (sqlite-vec + FTS5 + graph + RRF) and the
 * daemon's own configuration. Token accounting is not observable over the wire, so token usage is
 * reported as zero. Answer faithfulness is <strong>deferred</strong>: the daemon's synthesized
 * answer is recorded per query for a later judging pass ({@link FaithfulnessJudgeRunner}); the
 * {@code answerFaithful} flag stays {@code false} until that pass fills it in.
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

  private static final Duration DEFAULT_VECTORIZE_TIMEOUT = Duration.ofMinutes(5);
  private static final int DEFAULT_RECALL_LIMIT = 10;

  private final DaemonEvalClient client;
  private final Duration vectorizeTimeout;
  private final int recallLimit;

  public EvaluationRunner(DaemonEvalClient client) {
    this(client, DEFAULT_VECTORIZE_TIMEOUT, DEFAULT_RECALL_LIMIT);
  }

  public EvaluationRunner(DaemonEvalClient client, Duration vectorizeTimeout, int recallLimit) {
    this.client = Objects.requireNonNull(client, "client");
    this.vectorizeTimeout = Objects.requireNonNull(vectorizeTimeout, "vectorizeTimeout");
    this.recallLimit = recallLimit;
  }

  /**
   * Runs every fixture against the daemon. {@code runTag} disambiguates the per-fixture profile so
   * repeated runs (see {@link BenchmarkRunner#averageRuns}) ingest into fresh profiles rather than
   * hitting the idempotent insert-or-ignore path and skewing ingestion latency.
   */
  public EvaluationReport run(List<EvaluationFixture> fixtures, String runTag) {
    List<EvaluationFixture> list = fixtures == null ? List.of() : fixtures;
    List<FixtureReport> reports = new ArrayList<>();
    long runStart = System.nanoTime();
    for (int i = 0; i < list.size(); i++) {
      reports.add(runFixture(list.get(i), runTag, i + 1, list.size()));
      logRunProgress(runStart, i + 1, list.size());
    }
    return new EvaluationReport(Instant.now(), reports, summarize(reports));
  }

  private FixtureReport runFixture(EvaluationFixture fixture, String runTag, int index, int total) {
    String profile = uniqueProfile(fixture, runTag);

    List<IngestRequest.MessageDto> messages = fixture.transcript().stream()
      .map(m -> new IngestRequest.MessageDto(m.role(), m.content()))
      .toList();

    log.info("[{}/{}] {} — ingesting {} messages (profile {})",
      index, total, fixture.name(), messages.size(), profile);
    long ingestStart = System.nanoTime();
    IngestResponse ingested = client.ingest(profile, fixture.sessionId(), messages);
    long ingestPostMs = elapsedMs(ingestStart);
    long vectorizeMs = client.awaitVectorized(profile, vectorizeTimeout);
    long ingestionMs = ingestPostMs + vectorizeMs;
    List<MemoryResponse> stored = ingested.memories();
    log.info("[{}/{}] {} — ingest done ({} memories, {}ms extract + {}ms vectorize)",
      index, total, fixture.name(), stored.size(), ingestPostMs, vectorizeMs);

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
      RecallResponse result = client.recall(profile, expectation.query(), recallLimit);
      long latencyMs = elapsedMs(recallStart);
      recallMs += latencyMs;

      List<String> actualEvidence = result.memories().stream().map(MemoryResponse::content).toList();
      // Faithfulness is judged in a later pass; record the daemon's answer, leave the flag false.
      RecallReport report = recallReport(expectation, actualEvidence, result.answer(), latencyMs, false);

      int completedQueries = q + 1;
      int remainingQueries = recalls.size() - completedQueries;
      long avgQueryMs = elapsedMs(queriesStart) / completedQueries;
      log.info("[{}/{}] {} — query [{}/{}] done in {}ms — answer='{}'{}",
        index, total, fixture.name(), q + 1, recalls.size(), latencyMs,
        truncate(result.answer(), 80),
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
      TokenUsage.zero());
  }

  /**
   * Per-fixture (and per-run) profile so memories from different conversations never mix in the
   * daemon's single store. Non-identifier characters are collapsed to {@code -}.
   */
  private static String uniqueProfile(EvaluationFixture fixture, String runTag) {
    StringBuilder profile = new StringBuilder(sanitize(fixture.profileName()))
      .append("--").append(sanitize(fixture.name()));
    if (runTag != null && !runTag.isBlank()) {
      profile.append("--").append(sanitize(runTag));
    }
    return profile.toString();
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.strip().replaceAll("[^A-Za-z0-9._-]+", "-");
  }

  private static ExtractionReport extractionReport(EvaluationFixture fixture, List<MemoryResponse> stored) {
    Set<String> expected = new HashSet<>();
    for (ExpectedMemory memory : fixture.expectedMemories()) {
      expected.add(memory.key());
    }

    Set<String> actual = new HashSet<>();
    for (MemoryResponse memory : stored) {
      actual.add(EvaluationFixture.memoryKey(MemoryType.fromWire(memory.type()), memory.content(), memory.topicKey()));
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

  private static RecallReport recallReport(RecallExpectation expectation, List<String> actualEvidence,
                                           String actualAnswer, long latencyMs, boolean faithful) {
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
      actualAnswer,
      latencyMs);
  }

  private static Summary summarize(List<FixtureReport> reports) {
    long ingestionMs = 0;
    long recallMs = 0;
    for (FixtureReport report : reports) {
      ingestionMs += report.latency().ingestionMs();
      recallMs += report.latency().recallMs();
    }

    Latency latency = new Latency(ingestionMs, recallMs, ingestionMs + recallMs);

    return new Summary(
      reports.size(),
      average(reports.stream().mapToDouble(report -> report.extraction().precision()).toArray()),
      average(reports.stream().mapToDouble(report -> report.extraction().recall()).toArray()),
      average(reports.stream().mapToDouble(FixtureReport::retrievalHitRate).toArray()),
      average(reports.stream().mapToDouble(FixtureReport::meanReciprocalRank).toArray()),
      average(reports.stream().mapToDouble(FixtureReport::answerFaithfulness).toArray()),
      latency,
      TokenUsage.zero());
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
