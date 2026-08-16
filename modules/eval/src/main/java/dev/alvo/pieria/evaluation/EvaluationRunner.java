package dev.alvo.pieria.evaluation;

import dev.alvo.pieria.api.request.IngestRequest;
import dev.alvo.pieria.api.response.MemoryResponse;
import dev.alvo.pieria.api.response.RecallResponse;
import dev.alvo.pieria.evaluation.EvaluationFixture.RecallExpectation;
import dev.alvo.pieria.evaluation.EvaluationReport.ConversationReport;
import dev.alvo.pieria.evaluation.EvaluationReport.Latency;
import dev.alvo.pieria.evaluation.EvaluationReport.QueryReport;
import dev.alvo.pieria.evaluation.EvaluationReport.Spend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import java.time.Duration;

/**
 * Drives a <em>real</em> running daemon over HTTP. For each conversation it POSTs the transcript to
 * {@code /ingest/async}, waits for the vectorization outbox to drain, then runs each question against
 * {@code /recall} and records the fused, ranked memories the daemon returns.
 *
 * <p>Nothing here instantiates the ingestion/retrieval services or a stub store: metrics reflect the
 * deployed pipeline (sqlite-vec + FTS5 + graph + RRF) and the daemon's own configuration.
 *
 * <p><strong>This pass scores nothing.</strong> Every judgement — the answer verdict and both funnel
 * gates — needs a model, and the judge gateway deliberately boots only after the daemon under test
 * has shut down so the two never compete for the provider. So this pass records the raw material
 * ({@link JudgeRunner} turns it into scores): the synthesized answer, the retrieved memories, and a
 * lexical shortlist of the stored memories most likely to carry each gold answer. Recording rather
 * than scoring is also what lets a written report be re-judged without re-driving the expensive run.
 */
public final class EvaluationRunner {

  private static final Logger log = LoggerFactory.getLogger(EvaluationRunner.class);

  /**
   * How many stored memories to put in front of the extraction judge per question. The shortlist is
   * lexical and deliberately generous: it only has to rank the right memory into the top slice, not
   * decide anything. Its threshold-free use is why the token overlap below is sound here while it was
   * useless as a pass/fail evidence matcher — extraction rewrites turns tersely enough that measured
   * containment tops out around 0.5, well under any threshold worth setting.
   */
  private static final int EXTRACTION_SHORTLIST = 20;

  /** Dropped before overlap scoring so filler words don't dominate short gold answers. */
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

    // The whole corpus, for the extraction gate: "was the fact stored at all", independent of rank.
    List<String> storedMemories = client.memories(profile);
    List<Set<String>> storedTokens = storedMemories.stream().map(EvaluationRunner::contentTokens).toList();

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
      queries.add(new QueryReport(
        expectation.query(),
        expectation.category(),
        expectation.expectAbstention(),
        expectation.expectedAnswer(),
        result.answer(),
        null, // verdict and both gates are filled in by the judge pass
        null,
        null,
        expectation.expectedEvidence(),
        retrieved,
        expectation.expectAbstention()
          ? List.of() // no gold fact to find, so the extraction gate does not apply
          : shortlist(expectation, storedMemories, storedTokens),
        latencyMs));

      int remaining = recalls.size() - (q + 1);
      long avgQueryMs = elapsedMs(queriesStart) / (q + 1);
      log.info("[{}/{}] {} — question [{}/{}] done in {}ms — answer='{}'{}",
        index, total, fixture.name(), q + 1, recalls.size(), latencyMs,
        truncate(result.answer(), 80),
        remaining == 0 ? "" : " (ETA " + formatDuration(avgQueryMs * remaining) + " for the rest)");
    }
    log.info("[{}/{}] {} — recall done ({}ms)", index, total, fixture.name(), recallMs);

    // Read after the recalls, so the figure covers this conversation's whole pipeline: extraction,
    // verification, graph, embedding and synthesis. The profile is unique per conversation and run,
    // so the counters are never shared with another fixture.
    Spend spend = client.spend(profile);
    log.info("[{}/{}] {} — spend {} prompt + {} completion tokens{}",
      index, total, fixture.name(), spend.promptTokens(), spend.completionTokens(),
      spend.priced() ? String.format(Locale.ROOT, " ($%.4f)", spend.costUsd()) : "");

    return new ConversationReport(
      fixture.name(),
      messages.size(),
      stored,
      EvaluationReport.score(queries),
      Latency.of(ingestionMs, recallMs),
      spend,
      queries,
      storedMemories);
  }

  /**
   * The stored memories most lexically related to a question's gold answer and evidence, best first.
   * Ties and near-misses are fine — the judge decides; this only has to keep the right memory from
   * falling out of a bounded prompt.
   */
  private static List<String> shortlist(RecallExpectation expectation,
                                        List<String> storedMemories,
                                        List<Set<String>> storedTokens) {
    if (storedMemories.size() <= EXTRACTION_SHORTLIST) {
      return storedMemories;
    }
    Set<String> target = new HashSet<>(contentTokens(expectation.expectedAnswer()));
    for (String evidence : expectation.expectedEvidence()) {
      target.addAll(contentTokens(evidence));
    }

    record Scored(String memory, double overlap) { }
    List<Scored> scored = new ArrayList<>(storedMemories.size());
    for (int i = 0; i < storedMemories.size(); i++) {
      scored.add(new Scored(storedMemories.get(i), overlap(target, storedTokens.get(i))));
    }
    return scored.stream()
      .sorted(Comparator.comparingDouble(Scored::overlap).reversed())
      .limit(EXTRACTION_SHORTLIST)
      .map(Scored::memory)
      .toList();
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

  /** Fraction of {@code target} tokens present in {@code candidate} (0 when {@code target} empty). */
  private static double overlap(Set<String> target, Set<String> candidate) {
    if (target.isEmpty()) {
      return 0.0;
    }
    int matched = 0;
    for (String token : target) {
      if (candidate.contains(token)) {
        matched++;
      }
    }
    return (double) matched / target.size();
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
