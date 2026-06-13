package dev.alvo.pieria.cli.command.harness;

import dev.alvo.pieria.cli.log.Logger;
import dev.alvo.pieria.cli.modules.harness.HarnessInstaller;
import dev.alvo.pieria.cli.modules.harness.HarnessRegistry;
import dev.alvo.pieria.cli.modules.harness.Scope;
import dev.alvo.pieria.cli.modules.harness.WiringContext;
import dev.alvo.pieria.cli.modules.harness.WiringContextFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code pieria harness list} — report which harnesses are wired for the chosen scope.
 */
@Command(
  name = "list",
  description = "Show which harnesses are wired to Pieria.",
  mixinStandardHelpOptions = true
)
public final class HarnessListCommand implements Callable<Integer> {

  private final HarnessRegistry registry = new HarnessRegistry();
  private final Logger log = new Logger();
  @Option(names = "--user", description = "Inspect user-level config (~) instead of the current project.")
  boolean user;
  @Option(names = "--project-dir", description = "Project directory for project scope (default: current directory).")
  Path projectDir = Path.of("");

  @Override
  public Integer call() {
    Scope scope = user ? Scope.USER : Scope.PROJECT;
    WiringContext ctx = WiringContextFactory.from(scope, projectDir, null, null, false, System.out);
    log.info("Harness wiring ({} scope):", scope.name().toLowerCase(java.util.Locale.ROOT));
    for (HarnessInstaller installer : registry.all()) {
      boolean wired;
      try {
        wired = installer.isInstalled(ctx);
      } catch (IOException e) {
        wired = false;
      }
      log.info(String.format("  %-12s %s", installer.id(), wired ? "wired" : "not wired"));
    }
    return 0;
  }
}
