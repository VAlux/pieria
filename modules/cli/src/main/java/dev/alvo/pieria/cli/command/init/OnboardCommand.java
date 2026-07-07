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
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * <p>The daemon does the discovery, reading, and fetching itself. Each source runs as its own
 * background task; re-running is idempotent (the daemon's content-addressed ids mean unchanged
 * content adds no duplicate memories).
 */
@Command(
  name = "onboard",
  description = "Seed a Pieria memory profile",
  mixinStandardHelpOptions = true
)
public final class OnboardCommand implements Callable<Integer> {

  private static final String DEFAULT_DAEMON_URL = "http://127.0.0.1:8077";

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
      new SourceSpec.Markdown(dir.toString(), includeAgentDocs, samples)));
    sources.add(new Source("text documents", new SourceSpec.Text(dir.toString(), samples)));
    sources.add(new Source("PDF documents", new SourceSpec.Pdf(dir.toString(), samples)));
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
      sources.add(new Source("web pages (" + urls.size() + ")", new SourceSpec.Web(List.copyOf(urls), samples)));
    }
    if (sourceCode && !sawDirectory) {
      log.error("--source-code needs a directory target; ignoring.");
    }
    return sources;
  }

  /** A single-file source spec for a known documentation extension, or null when unsupported. */
  private SourceSpec fileSpec(Path abs, String lowerName, int samples) {
    if (lowerName.endsWith(".md")) {
      return new SourceSpec.Markdown(abs.toString(), includeAgentDocs, samples);
    }
    if (lowerName.endsWith(".txt")) {
      return new SourceSpec.Text(abs.toString(), samples);
    }
    if (lowerName.endsWith(".pdf")) {
      return new SourceSpec.Pdf(abs.toString(), samples);
    }
    return null;
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
        case SourceSpec.Pdf p -> "PDFs under " + p.root();
        case SourceSpec.Text t -> "text under " + t.root();
      };
    }
  }
}
