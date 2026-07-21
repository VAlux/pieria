package dev.alvo.pieria.cli.command.init;

import dev.alvo.pieria.api.request.SourceSpec;
import dev.alvo.pieria.api.request.OnboardPlanRequest;
import dev.alvo.pieria.api.response.TaskStatusResponse;
import dev.alvo.pieria.client.*;
import dev.alvo.pieria.cli.log.Logger;
import dev.alvo.pieria.cli.log.ProgressReporter;
import dev.alvo.pieria.cli.modules.config.ProjectConfigLoader;
import dev.alvo.pieria.cli.modules.daemon.DaemonUrls;
import dev.alvo.pieria.cli.modules.task.TaskPoller;
import dev.alvo.pieria.cli.modules.update.BuildInfo;
import dev.alvo.pieria.client.exception.DaemonClientException;
import dev.alvo.pieria.client.exception.DaemonHttpException;
import dev.alvo.pieria.client.exception.DaemonUnavailableException;
import dev.alvo.pieria.config.model.PieriaConfigFile;
import dev.alvo.pieria.mapping.ProfileResolver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code pieria onboard} — seed a Pieria memory profile from a project's sources.
 *
 * <p>Two modes, chosen by whether positional {@code TARGET}s are given:
 * <ul>
 *   <li><b>No targets</b> — scan the project dir for <em>everything</em>: markdown, plain-text, and
 *       PDF documents, plus the source-code intelligence index when {@code --source-code} is set.</li>
 *   <li><b>Targets given</b> — onboard only the named targets, each dispatched by type: an
 *       {@code http(s)://} URL → web page; a {@code .md}/{@code .txt}/{@code .pdf} file → that single
 *       document; a directory → scanned like the no-targets mode.</li>
 * </ul>
 *
 * <p>The daemon does the discovery, reading, and fetching itself. All sources run sequentially in
 * one composite background task; re-running is idempotent (the daemon's content-addressed ids mean
 * unchanged content adds no duplicate memories).
 */
@Command(
  name = "onboard",
  description = "Seed a Pieria memory profile",
  mixinStandardHelpOptions = true
)
public final class OnboardCommand implements Callable<Integer> {

  private final Logger log = new Logger();

  @Parameters(
    paramLabel = "TARGET",
    arity = "0..*",
    description = "URLs / files / directories to onboard; omit to scan the project dir. "
      + "Each is dispatched by type: http(s) URL → web page, .md/.txt/.pdf → that file, directory → scanned.")
  List<String> targets = new ArrayList<>();

  @Option(names = "--project-dir", description = "Project directory to scan when no TARGET is given (default: current directory).")
  public Path projectDir = Path.of("");

  @Option(names = "--profile", description = "Explicit profile slug; omit to auto-derive per directory.")
  String profile;

  @Option(names = "--daemon-url", description = "Daemon base URL (default: $PIERIA_DAEMON_URL or http://127.0.0.1:8077).")
  String daemonUrl;

  @Option(names = "--config-dir", description = "Directory holding the global config.toml (default: $PIERIA_CONFIG_DIR or the OS config dir).")
  Path configDir;

  @Option(names = "--dry-run", description = "List the sources that would be sent, without contacting the daemon.")
  boolean dryRun;

  @Option(names = "--include-agent-docs", description = "Also seed CLAUDE.md / AGENTS.md (excluded by default as already-in-context).")
  boolean includeAgentDocs;

  @Option(names = "--extraction-samples",
    description = """
      How many independent extract passes to run per chunk (default: 1). Extraction is \
      stochastic, so more samples catch more of each chunk's facts in one run; their union is \
      de-duplicated. Higher = more complete but proportionally more model calls.""")
  int extractionSamples = 1;

  @Option(names = "--source-code", description = "Also build a source-code intelligence index from a directory target's tracked source files.")
  boolean sourceCode;

  @Option(names = "--reindex", description = "Re-parse all source files even if unchanged (bypass the content-hash skip). Use after a parser upgrade. Only affects --source-code.")
  boolean reindex;

  @Option(names = "--refresh", description = "Re-ingest all content documents even if unchanged since the last onboard (bypass the ingest ledger). Affects markdown/text/pdf/web sources.")
  boolean refresh;

  @Option(names = "--summarize", description = "After indexing, write LLM-synthesized architecture/module summary memories (uses the daemon's synthesis model; unchanged code is skipped). Only affects --source-code.")
  boolean summarize;

  @Option(names = "--no-enrich-graph", description = "Skip automatic background graph enrichment.")
  boolean noEnrichGraph;

  @Option(names = "--wait-for-enrichment", description = "Wait for background graph enrichment before exiting.")
  boolean waitForEnrichment;

  @Override
  public Integer call() {
    if (noEnrichGraph && waitForEnrichment) {
      log.error("--no-enrich-graph and --wait-for-enrichment are mutually exclusive.");
      return 2;
    }
    Path dir = projectDir.toAbsolutePath().normalize();
    String resolvedProfile = resolveProfile(dir);
    String url = resolveDaemonUrl();

    ProjectConfigLoader loader = ProjectConfigLoader.create(dir, configDir);
    PieriaConfigFile config;
    try {
      config = loader.load();
    } catch (Exception e) {
      log.error("Failed to load config ({} / {}): {}",
        loader.globalConfigFile(), loader.projectConfigFile(), e.getMessage());
      return 2;
    }

    List<Source> sources = buildSources(dir, config);
    if (sources.isEmpty()) {
      log.error("No onboardable targets (all were unsupported or missing).");
      return 2;
    }

    if (dryRun) {
      log.info("Would seed profile '{}' at {} from {} source(s):", resolvedProfile, url, sources.size());
      for (Source source : sources) {
        log.info("  {}", source.describe());
      }
      return 0;
    }

    if (!new HealthClient(url, BuildInfo.clientIdentity()).reachable()) {
      return daemonDown(url);
    }
    OnboardingClient onboarding = new OnboardingClient(url, BuildInfo.clientIdentity());
    TaskClient tasks = new TaskClient(url, BuildInfo.clientIdentity());
    pushConfigOverrides(url, resolvedProfile, config);
    return seed(onboarding, tasks, resolvedProfile, sources);
  }

  /**
   * Assemble the sources to seed. No targets ⇒ scan the project dir for everything; targets given
   * ⇒ dispatch each by type (URL / .md / .txt / .pdf / directory), URLs coalesced into one web source.
   */
  private List<Source> buildSources(Path dir, PieriaConfigFile config) {
    int samples = Math.max(1, extractionSamples);
    if (targets.isEmpty()) {
      return scanDirectory(dir, config, samples);
    }
    return classifyTargets(config, samples);
  }

  /** The everything-in-a-directory expansion: markdown + text + pdf, plus source-code when asked. */
  private List<Source> scanDirectory(Path dir, PieriaConfigFile config, int samples) {
    List<Source> sources = new ArrayList<>();
    sources.add(new Source("markdown documentation",
      new SourceSpec.Markdown(dir.toString(), includeAgentDocs, samples, refreshOrNull())));
    sources.add(new Source("text documents", new SourceSpec.Text(dir.toString(), samples, refreshOrNull())));
    sources.add(new Source("PDF documents", new SourceSpec.Pdf(dir.toString(), samples, refreshOrNull())));
    if (sourceCode) {
      sources.add(new Source("source-code index",
        new SourceSpec.SourceCode(dir.toString(), reindex, summarize ? Boolean.TRUE : null, config.discovery())));
    }
    return sources;
  }

  /** Dispatch each positional target by type; unsupported/missing targets are warned and skipped. */
  private List<Source> classifyTargets(PieriaConfigFile config, int samples) {
    Path cwd = Path.of("").toAbsolutePath();
    List<Source> sources = new ArrayList<>();
    List<String> urls = new ArrayList<>();
    boolean sawDirectory = false;

    for (String target : targets) {
      if (isUrl(target)) {
        urls.add(target);
        continue;
      }
      Path abs = toAbsolute(cwd, target);
      if (abs == null) {
        log.error("Unsupported target (not a URL, file, or directory): {} (skipped).", target);
        continue;
      }
      if (Files.isDirectory(abs)) {
        sources.addAll(scanDirectory(abs, config, samples));
        sawDirectory = true;
        continue;
      }
      String name = abs.getFileName() == null ? "" : abs.getFileName().toString().toLowerCase(Locale.ROOT);
      SourceSpec spec = fileSpec(abs, name, samples);
      if (spec == null) {
        log.error("Unsupported target (not a URL, .md/.txt/.pdf file, or directory): {} (skipped).", target);
        continue;
      }
      if (!Files.exists(abs)) {
        log.error("Target does not exist: {} (the daemon will reject it).", target);
      }
      sources.add(new Source(abs.getFileName() + " (" + name.substring(name.lastIndexOf('.') + 1) + ")", spec));
    }

    if (!urls.isEmpty()) {
      sources.add(new Source("web pages (" + urls.size() + ")",
        new SourceSpec.Web(List.copyOf(urls), samples, refreshOrNull())));
    }
    if (sourceCode && !sawDirectory) {
      log.error("--source-code needs a directory target; ignoring.");
    }
    return sources;
  }

  /** A single-file source spec for a known documentation extension, or null when unsupported. */
  private SourceSpec fileSpec(Path abs, String lowerName, int samples) {
    if (lowerName.endsWith(".md")) {
      return new SourceSpec.Markdown(abs.toString(), includeAgentDocs, samples, refreshOrNull());
    }
    if (lowerName.endsWith(".txt")) {
      return new SourceSpec.Text(abs.toString(), samples, refreshOrNull());
    }
    if (lowerName.endsWith(".pdf")) {
      return new SourceSpec.Pdf(abs.toString(), samples, refreshOrNull());
    }
    return null;
  }

  /** The wire form of {@code --refresh}: true when set, null (daemon default: false) otherwise. */
  private Boolean refreshOrNull() {
    return refresh ? Boolean.TRUE : null;
  }

  private static boolean isUrl(String target) {
    String t = target.toLowerCase(Locale.ROOT);
    return t.startsWith("http://") || t.startsWith("https://");
  }

  /** Resolve a target against the process CWD and normalize; null when it is not a valid path. */
  private static Path toAbsolute(Path cwd, String target) {
    try {
      Path p = Path.of(target);
      return (p.isAbsolute() ? p : cwd.resolve(p)).normalize();
    } catch (InvalidPathException e) {
      return null;
    }
  }

  /**
   * Seed one ordered source plan and map its outcome to an exit code.
   */
  private int seed(OnboardingClient onboarding, TaskClient tasks, String profile, List<Source> sources) {
    log.info("Seeding profile '{}' from {} source(s)…", profile, sources.size());
    ProgressReporter reporter = new ProgressReporter();
    try {
      OnboardPlanRequest request = new OnboardPlanRequest(
        sources.stream().map(Source::spec).toList(), !noEnrichGraph);
      String taskId = onboarding.submit(profile, request, "onboard").taskId();
      TaskStatusResponse task = new TaskPoller(tasks).await(taskId, reporter);
      reporter.finish();
      if ("SUCCEEDED".equals(task.status())) {
        PlanSuccess success = planSuccess(task.result());
        reportSources(sources, success);
        int enrichmentExit = 0;
        if (success.graphTaskId() != null && !success.graphTaskId().isBlank()) {
          log.info("Core ready. Graph enrichment queued as task {} for {} candidate(s).",
            success.graphTaskId(), success.graphCandidates());
          if (waitForEnrichment) {
            enrichmentExit = awaitEnrichment(tasks, success.graphTaskId());
          }
        } else if (noEnrichGraph) {
          log.info("Core ready. Graph enrichment was skipped.");
        } else {
          log.info("Core ready. No graph enrichment candidates remain.");
        }
        reportErrors(sources, success.errors());
        if (enrichmentExit != 0) {
          return enrichmentExit;
        }
        return success.errors().isEmpty() ? 0 : 1;
      }
      if ("model-unavailable".equals(task.errorKind())) {
        log.error("The daemon is up but a core onboarding model call failed.");
        if (task.errorMessage() != null && !task.errorMessage().isBlank()) {
          log.error("Reason: {}", task.errorMessage());
        } else {
          log.error("Start your model provider (e.g. Ollama or LM Studio) and re-run 'pieria onboard'.");
        }
        return 4;
      }
      log.error("Onboard failed (HTTP -1): {}",
        task.errorMessage() == null ? "onboard task failed" : task.errorMessage());
      return 1;
    } catch (DaemonUnavailableException e) {
      reporter.finish();
      return daemonDown(resolveDaemonUrl());
    } catch (DaemonHttpException e) {
      reporter.finish();
      if (e.status() == 503) {
        log.error("The daemon is up but a core onboarding model call failed.");
        if (e.daemonMessage() != null && !e.daemonMessage().isBlank()) {
          log.error("Reason: {}", e.daemonMessage());
        }
        return 4;
      }
      log.error("Onboard failed (HTTP {}): {}", e.status(), e.body());
      return 1;
    } catch (DaemonClientException e) {
      reporter.finish();
      log.error("Onboard failed (HTTP -1): {}", e.getMessage());
      return 1;
    }
  }

  /** Report successful source results while accounting for failed sources omitted from the list. */
  private void reportSources(List<Source> requested, PlanSuccess success) {
    Set<Integer> failed = new HashSet<>();
    for (SourceFailure error : success.errors()) {
      if (error.sourceNumber() > 0) {
        failed.add(error.sourceNumber());
      }
    }

    int resultIndex = 0;
    for (int i = 0; i < requested.size() && resultIndex < success.sources().size(); i++) {
      if (failed.contains(i + 1)) {
        continue;
      }
      report(requested.get(i), success.sources().get(resultIndex++));
    }
    while (resultIndex < success.sources().size()) {
      Success result = success.sources().get(resultIndex++);
      report(new Source(result.sourceType(), null), result);
    }
  }

  /** Print the complete non-fatal source error list after every source has been attempted. */
  private void reportErrors(List<Source> sources, List<SourceFailure> errors) {
    if (errors.isEmpty()) {
      return;
    }
    log.error("Onboarding completed with {} source error{}:", errors.size(),
      errors.size() == 1 ? "" : "s");
    for (SourceFailure error : errors) {
      String label = error.sourceNumber() > 0 && error.sourceNumber() <= sources.size()
        ? sources.get(error.sourceNumber() - 1).label()
        : error.sourceType();
      log.error("  - source {}/{} ({}): {}: {}", error.sourceNumber(), sources.size(), label,
        error.errorType(), error.message());
    }
  }

  private int awaitEnrichment(TaskClient tasks, String taskId) {
    log.info("Waiting for graph enrichment task {}…", taskId);
    ProgressReporter reporter = new ProgressReporter();
    try {
      TaskStatusResponse task = new TaskPoller(tasks).await(taskId, reporter);
      reporter.finish();
      if ("SUCCEEDED".equals(task.status())) {
        log.info("Graph enrichment complete.");
        return 0;
      }
      if ("model-unavailable".equals(task.errorKind())) {
        log.error("Core onboarding succeeded, but graph enrichment could not reach the model: {}",
          task.errorMessage() == null ? "model unavailable" : task.errorMessage());
        return 4;
      }
      log.error("Core onboarding succeeded, but graph enrichment failed: {}",
        task.errorMessage() == null ? "task failed" : task.errorMessage());
      return 1;
    } catch (DaemonClientException e) {
      reporter.finish();
      log.error("Core onboarding succeeded, but graph enrichment polling failed: {}", e.getMessage());
      return 1;
    }
  }

  /** Terminal "done" line — richer for the code index (symbols/edges/summaries), plain for content. */
  private void report(Source source, Success s) {
    if (s.symbols() != null) {
      log.info("Done ({}). Indexed {} file(s), {} symbol(s), {} edge(s); stored {} memor{}.",
        source.label(), s.documents(), s.symbols(), s.edges(),
        s.memoriesStored(), s.memoriesStored() == 1 ? "y" : "ies");
      if (s.summariesStored() != null && s.summariesStored() > 0) {
        log.info("  {} summary memor{} written.", s.summariesStored(), s.summariesStored() == 1 ? "y" : "ies");
      }
    } else {
      log.info("Done ({}). Stored {} memor{} from {} document(s) (vectorization runs asynchronously).",
        source.label(), s.memoriesStored(), s.memoriesStored() == 1 ? "y" : "ies", s.documents());
      if (s.documentsSkipped() != null && s.documentsSkipped() > 0) {
        log.info("  {} document(s) unchanged since the last onboard were skipped (--refresh to force).",
          s.documentsSkipped());
      }
      if (s.graphDeferred() > 0) {
        log.info("  {} memor{} ready with graph enrichment deferred.", s.graphDeferred(),
          s.graphDeferred() == 1 ? "y is" : "ies are");
      }
    }
  }

  /**
   * Push the merged {@code [pieria]} overrides so the profile's daemon-side tuning follows the
   * project config from day one. Best-effort: onboarding succeeds even when the push fails
   * ({@code pieria config sync} can redo it), and nothing is pushed when no override is set.
   */
  private void pushConfigOverrides(String url, String resolvedProfile, PieriaConfigFile config) {
    if (config.pieria().isEmpty()) {
      return;
    }
    try {
      new ConfigClient(url, BuildInfo.clientIdentity()).put(resolvedProfile, config.pieria());
      log.info("Pushed project config overrides to profile '{}'.", resolvedProfile);
    } catch (DaemonUnavailableException e) {
      log.error("Could not push config overrides (daemon unreachable); run 'pieria config sync' later.");
    } catch (DaemonHttpException e) {
      log.error("Could not push config overrides (HTTP {}): {}", e.status(), e.body());
    }
  }

  private int daemonDown(String url) {
    log.error("Pieria daemon is not reachable at {}.", url);
    log.error("Start it with 'pieria start' and re-run 'pieria onboard'.");
    return 3;
  }

  /**
   * {@code --profile} wins; otherwise auto-derive via the shared resolver (env → git remote → dir).
   */
  private String resolveProfile(Path dir) {
    if (profile != null && !profile.isBlank()) {
      return ProfileResolver.normalize(profile);
    }
    return ProfileResolver.create(dir).resolve();
  }

  /**
   * {@code --daemon-url} wins, then $PIERIA_DAEMON_URL, then the localhost default.
   */
  private String resolveDaemonUrl() {
    return DaemonUrls.resolve(daemonUrl);
  }

  private static Success success(tools.jackson.databind.JsonNode result) {
    if (result == null) {
      return new Success("", 0, 0, 0, null, null, null, null);
    }
    return new Success(text(result, "sourceType"), integer(result, "documents", 0),
      integer(result, "memoriesStored", 0), integer(result, "graphDeferred", 0),
      integer(result, "documentsSkipped", null),
      integer(result, "symbols", null),
      integer(result, "edges", null), integer(result, "summariesStored", null));
  }

  private static PlanSuccess planSuccess(tools.jackson.databind.JsonNode result) {
    if (result == null) {
      return new PlanSuccess(List.of(), null, 0, List.of());
    }
    List<Success> sources = new ArrayList<>();
    var values = result.get("sources");
    if (values != null && values.isArray()) {
      for (var value : values) {
        sources.add(success(value));
      }
    }
    List<SourceFailure> errors = new ArrayList<>();
    var errorValues = result.get("errors");
    if (errorValues != null && errorValues.isArray()) {
      for (var value : errorValues) {
        errors.add(new SourceFailure(integer(value, "sourceNumber", 0),
          text(value, "sourceType"), text(value, "errorType"), text(value, "message")));
      }
    }
    return new PlanSuccess(sources, nullableText(result, "graphEnrichmentTaskId"),
      integer(result, "graphCandidates", 0), errors);
  }

  private static String text(tools.jackson.databind.JsonNode node, String field) {
    var value = node.get(field); return value == null || value.isNull() ? "" : value.asString();
  }

  private static String nullableText(tools.jackson.databind.JsonNode node, String field) {
    var value = node.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }

  private static Integer integer(tools.jackson.databind.JsonNode node, String field, Integer fallback) {
    var value = node.get(field);
    if (value == null || value.isNull()) {
      return fallback;
    }
    return value.asInt(0);
  }

  private record Success(String sourceType, int documents, int memoriesStored, int graphDeferred,
                         Integer documentsSkipped,
                         Integer symbols, Integer edges, Integer summariesStored) { }

  private record SourceFailure(int sourceNumber, String sourceType, String errorType, String message) { }

  private record PlanSuccess(List<Success> sources, String graphTaskId, int graphCandidates,
                             List<SourceFailure> errors) { }

  /** A source to seed: a human label (for logs) and the wire spec sent to the daemon. */
  private record Source(String label, SourceSpec spec) {
    String describe() {
      if (spec == null) {
        return label;
      }
      return switch (spec) {
        case SourceSpec.Markdown m -> "markdown under " + m.root()
          + (m.includeAgentDocs() ? " (incl. agent docs)" : "");
        case SourceSpec.SourceCode c -> "source code under " + c.root()
          + (c.reindex() ? " (reindex)" : "") + (Boolean.TRUE.equals(c.summarize()) ? " (summarize)" : "");
        case SourceSpec.Web w -> "web pages: " + String.join(", ", w.urls());
        case SourceSpec.Pdf p -> "PDFs under " + p.root();
        case SourceSpec.Text t -> "text under " + t.root();
      };
    }
  }
}
