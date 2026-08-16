package dev.alvo.pieria.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.alvo.pieria.evaluation.EvaluationReport.ConversationReport;
import dev.alvo.pieria.evaluation.EvaluationReport.Latency;
import dev.alvo.pieria.evaluation.EvaluationReport.QueryReport;
import dev.alvo.pieria.evaluation.EvaluationReport.Score;
import dev.alvo.pieria.evaluation.EvaluationReport.Spend;
import dev.alvo.pieria.evaluation.EvaluationReport.Summary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The LoCoMo benchmark entry point. Boots a real {@link LiveDaemon} (the daemon's web stack on a
 * throwaway temp DB), drives it over HTTP through {@link EvaluationRunner} — exercising the real
 * ingestion/retrieval pipeline, sqlite-vec + FTS5 + graph + RRF, and the daemon's own configuration —
 * then writes a JSON report and its HTML rendering.
 *
 * <p>It requires a local dataset file and a reachable model provider (Ollama by default), so it is
 * never run by CI. Run it through Gradle:
 *
 * <pre>{@code
 *   ./gradlew :eval:locomo --args="--conversations=1 --sessions=3 --questions=10 --no-judge"
 * }</pre>
 *
 * <p>All scoring happens in a second pass ({@link JudgeRunner}) with a judge gateway deliberately
 * separate from the daemon under test, so a run can be re-scored without re-driving it.
 */
public final class BenchmarkRunner {

  private static final Logger log = LoggerFactory.getLogger(BenchmarkRunner.class);

  private static final String BENCHMARK = "locomo";

  private BenchmarkRunner() {
  }

  public static void main(String... args) throws Exception {
    if (List.of(args).contains("--help")) {
      System.out.println(BenchmarkConfig.USAGE);
      return;
    }

    BenchmarkConfig config = BenchmarkConfig.parse(args);
    Path dataset = config.datasetPath();
    if (!Files.exists(dataset)) {
      System.err.println("dataset not found: " + dataset.toAbsolutePath()
        + "\nPlace locomo10.json under datasets/locomo/ or pass --dataset=<path>.\n\n"
        + BenchmarkConfig.USAGE);
      System.exit(2);
      return;
    }

    Path configFile = config.configPath();
    if (configFile != null && !Files.isRegularFile(configFile)) {
      // Spring would treat a missing optional: location as "no config" and silently benchmark the
      // bundled defaults instead — exactly the confusion --config exists to remove.
      System.err.println("daemon config file not found: " + configFile.toAbsolutePath());
      System.exit(2);
      return;
    }

    List<EvaluationFixture> fixtures =
      new LoCoMoBenchmarkAdapter(new ObjectMapper()).load(dataset, config);
    if (fixtures.isEmpty()) {
      System.err.println("the requested subset selected no conversations: " + config.describeSubset());
      System.exit(2);
      return;
    }
    logSubset(config, fixtures);
    if (config.dryRun()) {
      log.info("--dry-run: nothing was ingested and no daemon was booted");
      return;
    }

    List<List<ConversationReport>> runs = new ArrayList<>(config.runs());
    Map<String, String> models;
    try (LiveDaemon daemon = LiveDaemon.start(configFile)) {
      DaemonEvalClient client = new DaemonEvalClient(daemon.baseUrl());
      if (!client.healthy()) {
        throw new IllegalStateException("the eval daemon did not become healthy at " + daemon.baseUrl());
      }
      models = daemon.modelMetadata();

      EvaluationRunner runner = new EvaluationRunner(client, config.recallLimit());
      for (int run = 1; run <= config.runs(); run++) {
        log.info("run {}/{} over {} conversations", run, config.runs(), fixtures.size());
        runs.add(runner.run(fixtures, "run" + run));
      }
    }

    // Judging is a separate pass over the recorded answers, so the daemon under test is already shut
    // down by the time the judge context boots — the two never compete for the model provider.
    Spend judgeSpend = Spend.NONE;
    if (config.judge()) {
      try (LiveModelGatewayFactory judge = LiveModelGatewayFactory.fromSpring(configFile)) {
        JudgeRunner judgeRunner = new JudgeRunner(judge.gateway());
        runs.replaceAll(judgeRunner::judge);
        judgeSpend = judgeRunner.spend(judge.tierPrices());
      }
    }

    EvaluationReport report = new EvaluationReport(
      Instant.now(), BENCHMARK, config, models, summarize(runs, judgeSpend), runs.getLast());

    Path json = new EvaluationReportWriter().write(report, config.outputPath());
    Path html = new HtmlReportWriter().write(report, config.outputPath());
    logResult(report, json, html);
  }

  /**
   * Aggregates every run into one summary. Metrics pool all questions from all runs (a per-question
   * micro-average, the LoCoMo convention), while latency and stored-memory counts are averaged per
   * run; the report's conversation detail is the last run's, which is what spot-checking wants.
   */
  static Summary summarize(List<List<ConversationReport>> runs, Spend judgeSpend) {
    List<ConversationReport> last = runs.getLast();
    List<QueryReport> pooled = new ArrayList<>();
    List<Spend> spends = new ArrayList<>();
    long ingestionMs = 0;
    long recallMs = 0;
    long memories = 0;
    for (List<ConversationReport> conversations : runs) {
      pooled.addAll(EvaluationReport.allQueries(conversations));
      for (ConversationReport conversation : conversations) {
        ingestionMs += conversation.latency().ingestionMs();
        recallMs += conversation.latency().recallMs();
        memories += conversation.memoriesStored();
        spends.add(conversation.spend());
      }
    }

    // Spend is summed, not averaged: it is what the run actually cost, and every repeat of --runs is
    // a fresh profile paid for in full. Latency stays a per-run average, which is what it means.
    Spend pipelineSpend = Spend.sum(spends);
    Spend total = Spend.sum(List.of(pipelineSpend, judgeSpend == null ? Spend.NONE : judgeSpend));

    int n = runs.size();
    return new Summary(
      last.size(),
      EvaluationReport.allQueries(last).size(),
      (int) (memories / n),
      EvaluationReport.score(pooled),
      Latency.of(ingestionMs / n, recallMs / n),
      pipelineSpend,
      judgeSpend == null ? Spend.NONE : judgeSpend,
      total,
      EvaluationReport.scoreByCategory(pooled));
  }

  private static void logSubset(BenchmarkConfig config, List<EvaluationFixture> fixtures) {
    int turns = fixtures.stream().mapToInt(f -> f.transcript().size()).sum();
    int questions = fixtures.stream().mapToInt(f -> f.recalls().size()).sum();
    log.info("LoCoMo subset — {}", config.describeSubset());
    log.info("daemon config — {}", config.configPath() == null
      ? "bundled defaults (no --config given)" : config.configPath().toAbsolutePath());
    log.info("selected {} conversations: {} turns to ingest, {} questions per run",
      fixtures.size(), turns, questions);
    for (EvaluationFixture fixture : fixtures) {
      log.info("  {} — {} turns, {} questions", fixture.name(),
        fixture.transcript().size(), fixture.recalls().size());
    }
  }

  private static void logResult(EvaluationReport report, Path json, Path html) {
    Summary s = report.summary();
    Score overall = s.score();
    log.info("LoCoMo done — accuracy={} (correct {}, wrong {}, abstained {} of {})",
      pct(overall.accuracy()), overall.correct(), overall.wrong(), overall.abstained(),
      overall.questions());
    log.info("  funnel — extracted={} → retrieved={} → answered={} (over {} answerable questions)",
      pct(overall.extractionCoverage()), pct(overall.retrievalRecall()),
      pct(overall.synthesisAccuracy()), overall.gatedQuestions());
    log.info("  hallucination rate={}, ingest={}, recall={}",
      pct(overall.hallucinationRate()),
      EvaluationRunner.formatDuration(s.latency().ingestionMs()),
      EvaluationRunner.formatDuration(s.latency().recallMs()));
    log.info("  spend — pipeline {}, judge {}, total {}",
      describe(s.pipelineSpend()), describe(s.judgeSpend()), describe(s.spend()));
    s.byCategory().forEach((category, score) -> log.info(
      "  category {} — {} questions, accuracy={} extracted={} retrieved={}",
      category, score.questions(), pct(score.accuracy()),
      pct(score.extractionCoverage()), pct(score.retrievalRecall())));
    System.out.println("json report: " + json.toAbsolutePath());
    System.out.println("html report: " + html.toAbsolutePath());
  }

  private static String pct(double value) {
    return String.format("%.3f", value);
  }

  /**
   * Tokens always, dollars only when the benchmarked config prices its tiers — an unpriced run must
   * not read as a free one.
   */
  private static String describe(Spend spend) {
    String tokens = spend.promptTokens() + " in / " + spend.completionTokens() + " out";
    return spend.priced()
      ? String.format(Locale.ROOT, "$%.4f (%s)", spend.costUsd(), tokens)
      : tokens + " (no prices configured)";
  }
}
