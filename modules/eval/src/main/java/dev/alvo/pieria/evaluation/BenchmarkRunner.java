package dev.alvo.pieria.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Live benchmark entry point for the LoCoMo and LongMemEval adapters, run against a <em>real</em>
 * daemon.
 *
 * <p>The harness boots a full {@link LiveDaemon} (the daemon's web stack on a throwaway temp DB) and
 * drives it over HTTP via {@link DaemonEvalClient} — exercising the real ingestion/retrieval
 * pipeline, sqlite-vec + FTS5 + graph + RRF, and the daemon's own configuration. It is intentionally
 * <strong>not</strong> exercised by CI: it requires a local dataset file and a reachable model
 * provider (Ollama by default). {@link #averageRuns(List, int)} drives the harness {@code runCount}
 * times (default {@code 3}) and averages the summary metrics so stochastic model output is smoothed
 * out, then {@link EvaluationReportWriter} writes the report into the git-ignored
 * {@code pieria-eval-reports/} directory.
 *
 * <p>Answer faithfulness is deferred: the daemon run records each synthesized answer but leaves the
 * faithfulness flag unjudged. {@link #main} runs {@link FaithfulnessJudgeRunner} as a second pass to
 * fill it in and write a judged report alongside the raw one.
 */
public final class BenchmarkRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(BenchmarkRunner.class);

  public static final int DEFAULT_RUN_COUNT = 3;

  private final EvaluationRunner runner;
  private final EvaluationReportWriter reportWriter;
  private final ObjectMapper objectMapper;

  public BenchmarkRunner(DaemonEvalClient client) {
    this(new EvaluationRunner(client), new EvaluationReportWriter(), new ObjectMapper());
  }

  public BenchmarkRunner(EvaluationRunner runner,
                         EvaluationReportWriter reportWriter,
                         ObjectMapper objectMapper) {
    this.runner = runner;
    this.reportWriter = reportWriter;
    this.objectMapper = objectMapper;
  }

  public EvaluationReport runLoCoMo(Path datasetFile, int runCount) throws Exception {
    LOGGER.info("Loading LoCoMo dataset from {}", datasetFile);
    List<EvaluationFixture> fixtures = new LoCoMoBenchmarkAdapter(objectMapper).load(datasetFile);
    LOGGER.info("Loaded {} LoCoMo fixtures", fixtures.size());
    return averageRuns(fixtures, runCount);
  }

  public EvaluationReport runLongMemEval(Path datasetFile, int runCount) throws Exception {
    LOGGER.info("Loading LongMemEval dataset from {}", datasetFile);
    List<EvaluationFixture> fixtures = new LongMemEvalBenchmarkAdapter(objectMapper).load(datasetFile);
    LOGGER.info("Loaded {} LongMemEval fixtures", fixtures.size());
    return averageRuns(fixtures, runCount);
  }

  /**
   * Runs the harness {@code runCount} times over the same fixtures and returns a report whose
   * summary metrics are the mean across runs (the last run's per-fixture detail is retained, as
   * detail is mainly useful for spot-checking). Each run uses a distinct profile tag so repeated
   * ingests hit fresh profiles. Latency totals are averaged too.
   */
  public EvaluationReport averageRuns(List<EvaluationFixture> fixtures, int runCount) {
    int runs = Math.max(1, runCount);
    List<EvaluationReport> reports = new ArrayList<>();
    for (int i = 0; i < runs; i++) {
      LOGGER.info("Starting run {}/{} over {} fixtures", i + 1, runs, fixtures.size());
      reports.add(runner.run(fixtures, "run" + (i + 1)));
      LOGGER.info("Run {}/{} complete", i + 1, runs);
    }
    EvaluationReport result = averageReports(reports);
    EvaluationReport.Summary s = result.summary();
    LOGGER.info("Benchmark complete — hitRate={} mrr={} ingestion={}ms recall={}ms (faithfulness deferred to judge pass)",
      String.format("%.3f", s.retrievalHitRate()),
      String.format("%.3f", s.meanReciprocalRank()),
      s.latency().ingestionMs(), s.latency().recallMs());
    return result;
  }

  private static EvaluationReport averageReports(List<EvaluationReport> reports) {
    EvaluationReport last = reports.getLast();
    if (reports.size() == 1) {
      return last;
    }
    double precision = 0, recall = 0, hitRate = 0, mrr = 0, faithful = 0;
    long ingestionMs = 0, recallMs = 0, totalMs = 0;
    for (EvaluationReport report : reports) {
      EvaluationReport.Summary s = report.summary();
      precision += s.extractionPrecision();
      recall += s.extractionRecall();
      hitRate += s.retrievalHitRate();
      mrr += s.meanReciprocalRank();
      faithful += s.answerFaithfulness();
      ingestionMs += s.latency().ingestionMs();
      recallMs += s.latency().recallMs();
      totalMs += s.latency().totalMs();
    }
    int n = reports.size();
    EvaluationReport.Summary averaged = new EvaluationReport.Summary(
      last.summary().fixtureCount(),
      precision / n,
      recall / n,
      hitRate / n,
      mrr / n,
      faithful / n,
      new EvaluationReport.Latency(ingestionMs / n, recallMs / n, totalMs / n),
      EvaluationReport.TokenUsage.zero());
    return new EvaluationReport(last.generatedAt(), last.fixtures(), averaged);
  }

  /**
   * CLI entry point. Usage:
   * <pre>{@code
   *   java -cp <classpath> dev.alvo.pieria.evaluation.BenchmarkRunner \
   *       <locomo|longmemeval> <dataset.json> [runCount]
   * }</pre>
   * Boots a real daemon on a throwaway DB ({@link LiveDaemon}), runs the benchmark against it, writes
   * the averaged raw report, then judges answer faithfulness ({@link FaithfulnessJudgeRunner}) and
   * writes a judged report. Requires a running model provider and a local dataset file; never called
   * in CI.
   */
  static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.err.println("usage: BenchmarkRunner <locomo|longmemeval> <dataset.json> [runCount]");
      System.exit(2);
      return;
    }

    System.out.println("Starting the benchmark...");

    String benchmark = args[0].toLowerCase(java.util.Locale.ROOT);
    Path dataset = Path.of(args[1]);
    int runCount = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_RUN_COUNT;

    EvaluationReportWriter writer = new EvaluationReportWriter();

    try (LiveDaemon daemon = LiveDaemon.start()) {
      DaemonEvalClient client = new DaemonEvalClient(daemon.baseUrl());
      BenchmarkRunner runner = new BenchmarkRunner(client);

      EvaluationReport report = switch (benchmark) {
        case "locomo" -> runner.runLoCoMo(dataset, runCount);
        case "longmemeval" -> runner.runLongMemEval(dataset, runCount);
        default -> throw new IllegalArgumentException("unknown benchmark: " + benchmark);
      };

      Path rawReport = writer.write(report, EvaluationReportWriter.DEFAULT_OUTPUT_DIRECTORY);
      System.out.println("raw report: " + rawReport);

      // Judge faithfulness as a separate pass so it can be re-run without re-driving the daemon.
      EvaluationReport judged;
      try (LiveModelGatewayFactory judge = LiveModelGatewayFactory.fromSpring()) {
        judged = new FaithfulnessJudgeRunner(judge.gateway()).judge(report);
      }
      Path judgedReport = writer.write(judged, EvaluationReportWriter.DEFAULT_OUTPUT_DIRECTORY);

      Map<String, Object> summary = Map.of(
        "benchmark", benchmark,
        "fixtures", judged.summary().fixtureCount(),
        "retrievalHitRate", judged.summary().retrievalHitRate(),
        "meanReciprocalRank", judged.summary().meanReciprocalRank(),
        "answerFaithfulness", judged.summary().answerFaithfulness());

      System.out.println("judged report: " + judgedReport);
      System.out.println("summary: " + summary);
    }
  }
}
