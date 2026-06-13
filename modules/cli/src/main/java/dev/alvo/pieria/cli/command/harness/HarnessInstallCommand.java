package dev.alvo.pieria.cli.command.harness;

import dev.alvo.pieria.cli.log.Logger;
import dev.alvo.pieria.cli.modules.harness.HarnessInstaller;
import dev.alvo.pieria.cli.modules.harness.HarnessRegistry;
import dev.alvo.pieria.cli.modules.harness.Scope;
import dev.alvo.pieria.cli.modules.harness.WiringContext;
import dev.alvo.pieria.cli.modules.harness.WiringContextFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code pieria harness install <name>} — register the MCP gateway and install lifecycle hooks for
 * a harness. Writes project-level config by default; {@code --user} targets the user home.
 */
@Command(
  name = "install",
  description = "Register the Pieria MCP gateway and lifecycle hooks for a harness.",
  mixinStandardHelpOptions = true
)
public final class HarnessInstallCommand implements Callable<Integer> {

  private final HarnessRegistry registry = new HarnessRegistry();
  private final Logger log = new Logger();
  @Parameters(index = "0", paramLabel = "HARNESS", description = "Harness id: claude-code, codex")
  String harness;
  @Option(names = "--user", description = "Wire user-level config (~) instead of the current project.")
  boolean user;
  @Option(names = "--profile", description = "Explicit profile slug; omit to auto-derive per directory.")
  String profile;
  @Option(names = "--daemon-url", description = "Daemon base URL (default: $PIERIA_DAEMON_URL or http://127.0.0.1:8077).")
  String daemonUrl;
  @Option(names = "--project-dir", description = "Project directory for project scope (default: current directory).")
  Path projectDir = Path.of("");
  @Option(names = "--dry-run", description = "Print intended changes without writing.")
  boolean dryRun;

  @Override
  public Integer call() {
    HarnessInstaller installer = registry.find(harness).orElse(null);
    if (installer == null) {
      log.error("Unknown harness '{}'. Known harnesses: {}", harness, String.join(", ", registry.ids()));
      return 2;
    }

    Scope scope = user ? Scope.USER : Scope.PROJECT;
    WiringContext ctx = WiringContextFactory.from(scope, projectDir, profile, daemonUrl, dryRun, System.out);

    log.info("{} Pieria into {} ({} scope){}",
      dryRun ? "Would wire" : "Wiring", installer.id(), scope.name().toLowerCase(java.util.Locale.ROOT),
      dryRun ? " [dry-run]" : "");
    try {
      installer.install(ctx);
    } catch (IOException e) {
      log.error("Failed to wire {}: {}", installer.id(), e.getMessage());
      return 1;
    }
    if (!dryRun) {
      log.info("Done. {} is wired to the Pieria daemon at {}.", installer.id(), ctx.daemonUrl());
    }
    return 0;
  }
}
