package dev.alvo.pieria.evaluation;

import dev.alvo.pieria.api.request.IngestRequest;
import dev.alvo.pieria.api.response.MemoryResponse;
import dev.alvo.pieria.api.response.RecallResponse;
import dev.alvo.pieria.evaluation.EvaluationFixture.RecallExpectation;
import dev.alvo.pieria.evaluation.EvaluationReport.ConversationReport;
import dev.alvo.pieria.evaluation.EvaluationReport.Latency;
import dev.alvo.pieria.evaluation.EvaluationReport.QueryReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Drives a <em>real</em> running daemon over HTTP. For each conversation it POSTs the transcript to
 * {@code /ingest/async}, waits for the vectorization outbox to drain, then runs each question against
 * {@code /recall} and scores the fused, ranked memories the daemon returns.
 *
 * <p>Nothing here instantiates the ingestion/retrieval services or a stub store: metrics reflect the
 * deployed pipeline (sqlite-vec + FTS5 + graph + RRF) and the daemon's own configuration. Answer
 * faithfulness is <strong>deferred</strong>: the daemon's synthesized answer is recorded per question
 * for a later judging pass ({@link FaithfulnessJudgeRunner}); {@code answerFaithful} stays
 * {@code false} until that pass fills it in.
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

  private static final Duration VECTORIZE_TIMEOUT = Duration.ofMinutes(10);

  private final DaemonEvalClient client;
  private final int recallLimit;

  public EvaluationRunner(DaemonEvalClient client, int recallLimit) {
    this.client = Objects.requireNonNull(client, "client");
    this.recallLimit = recallLimit;
  }

  /**
   * Runs every conversation against the daemon. {@code runTag} disambiguates the per-conversation
   * profile so repeated runs (see {@link BenchmarkRunner}) ingest into fresh profiles rather than
   * hitting the idempotent insert-or-ignore path and skewing ingestion latency.
   */
  public List<ConversationReport> run(List<EvaluationFixture> fixtures, String runTag) {
    List<EvaluationFixture> list = fixtures == null ? List.of() : fixtures;
    List<ConversationReport> reports = new ArrayList<>();
    long runStart = System.nanoTime();
    for (int i = 0; i < list.size(); i++) {
      reports.add(runFixture(list.get(i), runTag, i + 1, list.size()));
      logRunProgress(runStart, i + 1, list.size());
    }
    return reports;
  }

  private ConversationReport runFixture(EvaluationFixture fixture, String runTag, int index, int total) {
    String profile = uniqueProfile(fixture, runTag);

    // Each turn carries its session's date-time, so the daemon resolves the transcript's relative
    // dates against when the conversation happened rather than against the ingest wall clock.
    List<IngestRequest.MessageDto> messages = fixture.transcript().stream()
      .map(m -> new IngestRequest.MessageDto(m.role(), m.content(), m.timestamp()))
      .toList();

    log.info("[{}/{}] {} — ingesting {} turns (profile {})",
      index, total, fixture.name(), messages.size(), profile);
    long ingestStart = System.nanoTime();
    int stored = client.ingest(profile, fixture.sessionId(), messages);
    long ingestPostMs = elapsedMs(ingestStart);
    long vectorizeMs = client.awaitVectorized(profile, VECTORIZE_TIMEOUT);
    long ingestionMs = ingestPostMs + vectorizeMs;
    log.info("[{}/{}] {} — ingest done ({} memories, {}ms extract + {}ms vectorize)",
      index, total, fixture.name(), stored, ingestPostMs, vectorizeMs);

    List<QueryReport> queries = new ArrayList<>();
    long recallMs = 0;
    List<RecallExpectation> recalls = fixture.recalls();
    log.info("[{}/{}] {} — running {} questions", index, total, fixture.name(), recalls.size());
    long queriesStart = System.nanoTime();
    for (int q = 0; q < recalls.size(); q++) {
      RecallExpectation expectation = recalls.get(q);
      log.info("[{}/{}] {} — question [{}/{}]: {}",
        index, total, fixture.name(), q + 1, recalls.size(), expectation.query());
      long recallStart = System.nanoTime();
      RecallResponse result = client.recall(profile, expectation.query(), recallLimit);
      long latencyMs = elapsedMs(recallStart);
      recallMs += latencyMs;

      List<String> retrieved = result.memories().stream().map(MemoryResponse::content).toList();
      queries.add(queryReport(expectation, retrieved, result.answer(), latencyMs));

      int remaining = recalls.size() - (q + 1);
      long avgQueryMs = elapsedMs(queriesStart) / (q + 1);
      log.info("[{}/{}] {} — question [{}/{}] done in {}ms — answer='{}'{}",
        index, total, fixture.name(), q + 1, recalls.size(), latencyMs,
        truncate(result.answer(), 80),
        remaining == 0 ? "" : " (ETA " + formatDuration(avgQueryMs * remaining) + " for the rest)");
    }
    log.info("[{}/{}] {} — recall done ({}ms)", index, total, fixture.name(), recallMs);

    EvaluationReport.CategoryScore score = EvaluationReport.score(queries);
    return new ConversationReport(
      fixture.name(),
      messages.size(),
      stored,
      score.answerFaithfulness(),
      score.retrievalHitRate(),
      score.meanReciprocalRank(),
      Latency.of(ingestionMs, recallMs),
      queries);
  }

  /**
   * Per-conversation (and per-run) profile so memories from different conversations never mix in the
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

  private static QueryReport queryReport(RecallExpectation expectation, List<String> retrieved,
                                         String actualAnswer, long latencyMs) {
    // Extraction rewrites turns into terse memories, so an evidence turn rarely equals a memory
    // verbatim. Match on stopword-filtered token containment instead: an expected evidence item
    // "hits" when some retrieved memory covers at least EVIDENCE_MATCH_THRESHOLD of its content
    // words. MRR is the reciprocal of the best (smallest) rank among all matching memories.
    List<Set<String>> retrievedTokens = new ArrayList<>(retrieved.size());
    for (String content : retrieved) {
      retrievedTokens.add(contentTokens(content));
    }

    int hits = 0;
    int firstRank = 0;
    for (String expected : expectation.expectedEvidence()) {
      int rank = firstMatchingRank(contentTokens(expected), retrievedTokens);
      if (rank > 0) {
        hits++;
        if (firstRank == 0 || rank < firstRank) {
          firstRank = rank;
        }
      }
    }

    return new QueryReport(
      expectation.query(),
      expectation.category(),
      expectation.expectedAnswer(),
      actualAnswer,
      false, // judged in a later pass
      expectation.expectedEvidence(),
      retrieved,
      ratio(hits, expectation.expectedEvidence().size()),
      firstRank == 0 ? 0.0 : 1.0 / firstRank,
      latencyMs);
  }

  /** Rank (1-based) of the first memory whose tokens cover the expected evidence, or 0 for none. */
  private static int firstMatchingRank(Set<String> expectedTokens, List<Set<String>> retrievedTokens) {
    if (expectedTokens.isEmpty()) {
      return 0;
    }
    for (int i = 0; i < retrievedTokens.size(); i++) {
      if (containment(expectedTokens, retrievedTokens.get(i)) >= EVIDENCE_MATCH_THRESHOLD) {
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

  private static long elapsedMs(long startedAtNanos) {
    return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000L);
  }

  /** Logs elapsed/average/ETA across the whole run, using completed conversations as the sample. */
  private static void logRunProgress(long runStartNanos, int completed, int total) {
    long elapsedMs = elapsedMs(runStartNanos);
    int remaining = total - completed;
    if (remaining == 0) {
      log.info("[{}/{}] run complete — elapsed {}", completed, total, formatDuration(elapsedMs));
      return;
    }
    long avgMs = elapsedMs / completed;
    log.info("[{}/{}] run progress — elapsed {}, avg {}/conversation, ETA {} ({} left)",
      completed, total, formatDuration(elapsedMs), formatDuration(avgMs),
      formatDuration(avgMs * remaining), remaining);
  }

  static String formatDuration(long millis) {
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
    if (s == null) {
      return "";
    }
    String stripped = s.strip().replace('\n', ' ');
    return stripped.length() <= max ? stripped : stripped.substring(0, max) + "…";
  }
}
