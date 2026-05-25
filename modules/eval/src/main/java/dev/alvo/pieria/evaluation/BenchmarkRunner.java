package dev.alvo.pieria.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.storage.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Live benchmark entry point for the LoCoMo and LongMemEval adapters.
 *
 * <p>This is the seam that runs the <em>real</em> {@link dev.alvo.pieria.ingestion.IngestionService}
 * and {@link dev.alvo.pieria.retrieval.RetrievalService} against a live {@link ModelGateway} (the
 * daemon's {@code OllamaModelGateway}, or any other gateway you wire — e.g. a hosted Anthropic/OpenAI
 * baseline for comparison) and an in-memory {@link MemoryStore}.
 *
 * <p>It is intentionally <strong>not</strong> exercised by CI: it requires a local dataset file and a
 * reachable model provider. {@link #averageRuns(List, Supplier, Supplier, int)} drives the harness
 * {@code runCount} times (default {@code 3}) and averages the summary metrics so stochastic
 * model output is smoothed out, then {@link EvaluationReportWriter} writes the report into the
 * git-ignored {@code pieria-eval-reports/} directory.
 *
 * <h2>How to run the live path</h2>
 * Wire a live gateway and invoke:
 * <pre>{@code
 *   ModelGateway gateway = ...;            // e.g. daemon OllamaModelGateway bean
 *   PieriaProperties props = ...;          // chat-small/large, embedding model + dimension
 *   BenchmarkRunner runner = new BenchmarkRunner(props, () -> gateway);
 *
 *   // LoCoMo
 *   runner.runLoCoMo(Path.of("/data/locomo10.json"), 3);
 *   // LongMemEval
 *   runner.runLongMemEval(Path.of("/data/longmemeval_s.json"), 3);
 * }</pre>
 * <p>
 * The gateway supplier is shared across fixtures and runs; the store factory creates a fresh
 * {@link InMemoryEvaluationMemoryStore} per fixture so memories never leak between samples. To
 * compare a local model against a hosted baseline, construct two {@code BenchmarkRunner}s with
 * different gateway suppliers and diff the two reports.
 */
public final class BenchmarkRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(BenchmarkRunner.class);

  public static final int DEFAULT_RUN_COUNT = 3;

  private final EvaluationRunner runner;
  private final EvaluationReportWriter reportWriter;
  private final Supplier<ModelGateway> gatewayFactory;
  private final Supplier<MemoryStore> storeFactory;
  private final ObjectMapper objectMapper;

  public BenchmarkRunner(PieriaProperties properties, Supplier<ModelGateway> gatewayFactory) {
    this(
      new EvaluationRunner(properties),
      new EvaluationReportWriter(),
      gatewayFactory,
      InMemoryEvaluationMemoryStore::new,
      new ObjectMapper());
  }

  public BenchmarkRunner(EvaluationRunner runner,
                         EvaluationReportWriter reportWriter,
                         Supplier<ModelGateway> gatewayFactory,
                         Supplier<MemoryStore> storeFactory,
                         ObjectMapper objectMapper) {

    this.runner = runner;
    this.reportWriter = reportWriter;
    this.gatewayFactory = gatewayFactory;
    this.storeFactory = storeFactory;
    this.objectMapper = objectMapper;
  }

  public EvaluationReport runLoCoMo(Path datasetFile, int runCount) throws Exception {
    LOGGER.info("Loading LoCoMo dataset from {}", datasetFile);
    List<EvaluationFixture> fixtures = new LoCoMoBenchmarkAdapter(objectMapper).load(datasetFile);
    LOGGER.info("Loaded {} LoCoMo fixtures", fixtures.size());
    return averageRuns(fixtures, gatewayFactory, storeFactory, runCount);
  }

  public EvaluationReport runLongMemEval(Path datasetFile, int runCount) throws Exception {
    LOGGER.info("Loading LongMemEval dataset from {}", datasetFile);
    List<EvaluationFixture> fixtures = new LongMemEvalBenchmarkAdapter(objectMapper).load(datasetFile);
    LOGGER.info("Loaded {} LongMemEval fixtures", fixtures.size());
    return averageRuns(fixtures, gatewayFactory, storeFactory, runCount);
  }

  /**
   * Runs the harness {@code runCount} times over the same fixtures and returns a report whose
   * summary metrics are the mean across runs (the last run's per-fixture detail is retained, as
   * detail is mainly useful for spot-checking). Latency and token totals are averaged too.
   */
  public EvaluationReport averageRuns(List<EvaluationFixture> fixtures,
                                      Supplier<ModelGateway> gateways,
                                      Supplier<MemoryStore> stores,
                                      int runCount) {
    int runs = Math.max(1, runCount);
    List<EvaluationReport> reports = new ArrayList<>();
    for (int i = 0; i < runs; i++) {
      LOGGER.info("Starting run {}/{} over {} fixtures", i + 1, runs, fixtures.size());
      reports.add(runner.run(fixtures, gateways, stores));
      LOGGER.info("Run {}/{} complete", i + 1, runs);
    }
    EvaluationReport result = averageReports(reports);
    EvaluationReport.Summary s = result.summary();
    LOGGER.info("Benchmark complete — hitRate={} mrr={} faithfulness={} ingestion={}ms recall={}ms",
      String.format("%.3f", s.retrievalHitRate()),
      String.format("%.3f", s.meanReciprocalRank()),
      String.format("%.3f", s.answerFaithfulness()),
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
    long promptTokens = 0, completionTokens = 0, totalTokens = 0;
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
      promptTokens += s.tokenUsage().promptTokens();
      completionTokens += s.tokenUsage().completionTokens();
      totalTokens += s.tokenUsage().totalTokens();
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
      new EvaluationReport.TokenUsage(promptTokens / n, completionTokens / n, totalTokens / n,
        last.summary().tokenUsage().callsByStage()));
    return new EvaluationReport(last.generatedAt(), last.fixtures(), averaged);
  }

  /**
   * CLI entry point. Usage:
   * <pre>{@code
   *   java -cp <classpath> dev.alvo.pieria.evaluation.BenchmarkRunner \
   *       <locomo|longmemeval> <dataset.json> [runCount]
   * }</pre>
   * Wires the live Ollama gateway via {@link LiveModelGatewayFactory#fromSpring(String...)} and writes the
   * averaged report to {@code pieria-eval-reports/}. Requires a running model provider and a local
   * dataset file; this method is never called in CI.
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

    try (LiveModelGatewayFactory live = LiveModelGatewayFactory.fromSpring()) {
      var runner = new BenchmarkRunner(live.properties(), live.gatewayFactory());

      EvaluationReport report = switch (benchmark) {
        case "locomo" -> runner.runLoCoMo(dataset, runCount);
        case "longmemeval" -> runner.runLongMemEval(dataset, runCount);
        default -> throw new IllegalArgumentException("unknown benchmark: " + benchmark);
      };

      Path written = new EvaluationReportWriter().write(report, EvaluationReportWriter.DEFAULT_OUTPUT_DIRECTORY);

      Map<String, Object> summary = Map.of(
        "benchmark", benchmark,
        "fixtures", report.summary().fixtureCount(),
        "retrievalHitRate", report.summary().retrievalHitRate(),
        "meanReciprocalRank", report.summary().meanReciprocalRank(),
        "answerFaithfulness", report.summary().answerFaithfulness());

      System.out.println("report: " + written);
      System.out.println("summary: " + summary);
    }
  }
}
