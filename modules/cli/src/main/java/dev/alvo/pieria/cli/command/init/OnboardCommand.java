package dev.alvo.pieria.cli.command.init;

import dev.alvo.pieria.api.request.SourceSpec;
import dev.alvo.pieria.cli.log.Logger;
import dev.alvo.pieria.cli.log.ProgressReporter;
import dev.alvo.pieria.cli.modules.config.ConfigClient;
import dev.alvo.pieria.cli.modules.config.HttpConfigClient;
import dev.alvo.pieria.cli.modules.config.ProjectConfigLoader;
import dev.alvo.pieria.cli.modules.init.HttpOnboardClient;
import dev.alvo.pieria.cli.modules.init.OnboardClient;
import dev.alvo.pieria.cli.modules.init.Reachability;
import dev.alvo.pieria.config.model.PieriaConfigFile;
import dev.alvo.pieria.config.toml.ConfigCodec;
import dev.alvo.pieria.mapping.ProfileResolver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code pieria onboard} — seed a Pieria memory profile from a project's sources.
 *
 * <p>Names one or more <em>onboarding sources</em> and hands them to the daemon, which does the
 * discovery, reading, and fetching itself: markdown documentation (always), the source-code
 * intelligence index ({@code --source-code}), and web pages ({@code --web <url>}). Each source runs
 * as its own background task; re-running is idempotent (the daemon's content-addressed ids mean
 * unchanged content adds no duplicate memories).
 */
@Command(
  name = "onboard",
  description = "Seed a Pieria memory profile",
  mixinStandardHelpOptions = true
)
public final class OnboardCommand implements Callable<Integer> {

  private static final String DEFAULT_DAEMON_URL = "http://127.0.0.1:8077";

  private final Logger log = new Logger();

  @Option(names = "--project-dir", description = "Project directory to scan (default: current directory).")
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

  @Option(names = "--source-code", description = "Also build a source-code intelligence index from the repo's tracked source files.")
  boolean sourceCode;

  @Option(names = "--web", description = "Also seed from one or more web pages (repeatable).", paramLabel = "<url>")
  List<String> webUrls = new ArrayList<>();

  @Option(names = "--reindex", description = "Re-parse all source files even if unchanged (bypass the content-hash skip). Use after a parser upgrade. Only affects --source-code.")
  boolean reindex;

  @Option(names = "--summarize", description = "After indexing, write LLM-synthesized architecture/module summary memories (uses the daemon's synthesis model; unchanged code is skipped). Only affects --source-code.")
  boolean summarize;

  @Override
  public Integer call() {
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

    if (dryRun) {
      log.info("Would seed profile '{}' at {} from {} source(s):", resolvedProfile, url, sources.size());
      for (Source source : sources) {
        log.info("  {}", source.describe());
      }
      return 0;
    }

    OnboardClient client = new HttpOnboardClient(url, "onboard");
    if (client.ping() == Reachability.DAEMON_DOWN) {
      return daemonDown(url);
    }

    int firstFailure = 0;
    for (Source source : sources) {
      int rc = seed(client, resolvedProfile, source);
      if (rc != 0 && firstFailure == 0) {
        firstFailure = rc;
      }
    }

    pushConfigOverrides(resolvedProfile, url, config);
    return firstFailure;
  }

  /**
   * Assemble the sources to seed from the flags: markdown always, plus source-code / web when asked.
   */
  private List<Source> buildSources(Path dir, PieriaConfigFile config) {
    int samples = Math.max(1, extractionSamples);
    List<Source> sources = new ArrayList<>();
    sources.add(new Source("markdown documentation",
      new SourceSpec.Markdown(dir.toString(), includeAgentDocs, samples)));
    if (sourceCode) {
      sources.add(new Source("source-code index",
        new SourceSpec.SourceCode(dir.toString(), reindex, summarize ? Boolean.TRUE : null, config.discovery())));
    }
    if (!webUrls.isEmpty()) {
      sources.add(new Source("web pages (" + webUrls.size() + ")",
        new SourceSpec.Web(List.copyOf(webUrls), samples)));
    }
    return sources;
  }

  /**
   * Seed one source and map its outcome to an exit code, reporting progress and the terminal line.
   */
  private int seed(OnboardClient client, String profile, Source source) {
    log.info("Seeding profile '{}' from {}…", profile, source.label());
    ProgressReporter reporter = new ProgressReporter();
    OnboardClient.OnboardResult result = client.onboard(profile, source.spec(), reporter);
    reporter.finish();
    return switch (result) {
      case OnboardClient.Success s -> {
        report(source, s);
        yield 0;
      }
      case OnboardClient.ModelUnavailable mu -> {
        log.error("The daemon is up but the model call failed ({}).", source.label());
        if (mu.reason() != null && !mu.reason().isBlank()) {
          log.error("Reason: {}", mu.reason());
        } else {
          log.error("Start your model provider (e.g. Ollama or LM Studio) and re-run 'pieria onboard'.");
        }
        yield 4;
      }
      case OnboardClient.DaemonDown ignored -> daemonDown(resolveDaemonUrl());
      case OnboardClient.Failure f -> {
        log.error("Onboard failed for {} (HTTP {}): {}", source.label(), f.status(), f.body());
        yield 1;
      }
    };
  }

  /** Terminal "done" line — richer for the code index (symbols/edges/summaries), plain for content. */
  private void report(Source source, OnboardClient.Success s) {
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
    }
  }

  /**
   * Push the merged {@code [pieria]} overrides so the profile's daemon-side tuning follows the
   * project config from day one. Best-effort: onboarding succeeds even when the push fails
   * ({@code pieria config sync} can redo it), and nothing is pushed when no override is set.
   */
  private void pushConfigOverrides(String resolvedProfile, String url, PieriaConfigFile config) {
    if (config.pieria().isEmpty()) {
      return;
    }
    ConfigClient client = new HttpConfigClient(url);
    switch (client.put(resolvedProfile, ConfigCodec.toJson(config.pieria()))) {
      case ConfigClient.Success ignored ->
        log.info("Pushed project config overrides to profile '{}'.", resolvedProfile);
      case ConfigClient.DaemonDown ignored ->
        log.error("Could not push config overrides (daemon unreachable); run 'pieria config sync' later.");
      case ConfigClient.Failure f -> log.error("Could not push config overrides (HTTP {}): {}", f.status(), f.body());
    }
  }

  private int daemonDown(String url) {
    log.error("Pieria daemon is not reachable at {}.", url);
    log.error("Start it with 'pieria daemon start' and re-run 'pieria onboard'.");
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
    if (daemonUrl != null && !daemonUrl.isBlank()) {
      return daemonUrl;
    }
    return System.getenv().getOrDefault("PIERIA_DAEMON_URL", DEFAULT_DAEMON_URL);
  }

  /** A source to seed: a human label (for logs) and the wire spec sent to the daemon. */
  private record Source(String label, SourceSpec spec) {
    String describe() {
      return switch (spec) {
        case SourceSpec.Markdown m -> "markdown under " + m.root()
          + (m.includeAgentDocs() ? " (incl. agent docs)" : "");
        case SourceSpec.SourceCode c -> "source code under " + c.root()
          + (c.reindex() ? " (reindex)" : "") + (Boolean.TRUE.equals(c.summarize()) ? " (summarize)" : "");
        case SourceSpec.Web w -> "web pages: " + String.join(", ", w.urls());
      };
    }
  }
}
