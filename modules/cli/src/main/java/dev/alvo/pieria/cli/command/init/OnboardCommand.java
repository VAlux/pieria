package dev.alvo.pieria.cli.command.init;

import dev.alvo.pieria.api.request.IngestRequest;
import dev.alvo.pieria.cli.modules.init.HttpIngestClient;
import dev.alvo.pieria.cli.modules.init.IngestClient;
import dev.alvo.pieria.cli.modules.init.MarkdownDiscovery;
import dev.alvo.pieria.cli.modules.init.MarkdownDiscovery.Doc;
import dev.alvo.pieria.cli.modules.init.TranscriptBuilder;
import dev.alvo.pieria.cli.log.Logger;
import dev.alvo.pieria.mapping.ProfileResolver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code pieria init} — seed a Pieria memory profile from the project's markdown documentation.
 *
 * <p>Scans the repo for {@code .md} files (excluding the always-in-context {@code CLAUDE.md} /
 * {@code AGENTS.md}), packages them as a synthetic transcript, and POSTs them to the daemon's
 * ingest endpoint so the profile has useful memories from day one. Re-running is idempotent: the
 * fixed seed session id plus the daemon's content-addressed ids mean unchanged docs add no
 * duplicate memories.
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
  /**
   * Test seam: when set, used instead of constructing an {@link HttpIngestClient}.
   */
  public IngestClient clientOverride;
  @Option(names = "--profile", description = "Explicit profile slug; omit to auto-derive per directory.")
  String profile;
  @Option(names = "--daemon-url", description = "Daemon base URL (default: $PIERIA_DAEMON_URL or http://127.0.0.1:8077).")
  String daemonUrl;
  @Option(names = "--dry-run", description = "List the docs and messages that would be sent, without contacting the daemon.")
  boolean dryRun;
  @Option(names = "--include-agent-docs", description = "Also seed CLAUDE.md / AGENTS.md (excluded by default as already-in-context).")
  boolean includeAgentDocs;

  @Override
  public Integer call() {
    Path dir = projectDir.toAbsolutePath().normalize();
    String resolvedProfile = resolveProfile(dir);

    List<Doc> docs = MarkdownDiscovery.create(dir).discover(includeAgentDocs);
    if (docs.isEmpty()) {
      log.info("No markdown docs found under {} — nothing to seed.", dir);
      return 0;
    }

    IngestRequest body;
    try {
      body = new TranscriptBuilder().build(docs);
    } catch (IOException e) {
      log.error("Failed to read project docs: {}", e.getMessage());
      return 1;
    }
    if (body.messages().isEmpty()) {
      log.info("Found {} markdown file(s) but no extractable content — nothing to seed.", docs.size());
      return 0;
    }

    String url = resolveDaemonUrl();

    if (dryRun) {
      log.info("Would seed profile '{}' at {} from {} markdown file(s) → {} message(s):",
        resolvedProfile, url, docs.size(), body.messages().size());
      for (Doc doc : docs) {
        log.info("  {}", doc.relative());
      }
      return 0;
    }

    IngestClient client = (clientOverride != null) ? clientOverride : new HttpIngestClient(url);
    if (client.ping() == IngestClient.Reachability.DAEMON_DOWN) {
      return daemonDown(url);
    }

    log.info("Seeding profile '{}' from {} markdown file(s) → {} message(s)…",
      resolvedProfile, docs.size(), body.messages().size());
    return switch (client.ingest(resolvedProfile, body)) {
      case IngestClient.Success s -> {
        log.info("Done. Stored {} memor{} (vectorization runs asynchronously).",
          s.count(), s.count() == 1 ? "y" : "ies");
        yield 0;
      }
      case IngestClient.ModelUnavailable ignored -> {
        log.error("The daemon is up but its model provider is unavailable.");
        log.error("Start your model provider (e.g. Ollama or LM Studio) and re-run 'pieria init'.");
        yield 4;
      }
      case IngestClient.DaemonDown ignored -> daemonDown(url);
      case IngestClient.Failure f -> {
        log.error("Ingest failed (HTTP {}): {}", f.status(), f.body());
        yield 1;
      }
    };
  }

  private int daemonDown(String url) {
    log.error("Pieria daemon is not reachable at {}.", url);
    log.error("Start it with 'pieria daemon start' and re-run 'pieria init'.");
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
}
