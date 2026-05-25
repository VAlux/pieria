package dev.alvo.pieria.cli.command;

import dev.alvo.pieria.cli.harness.HarnessInstaller;
import dev.alvo.pieria.cli.harness.HarnessRegistry;
import dev.alvo.pieria.cli.harness.Scope;
import dev.alvo.pieria.cli.harness.WiringContext;
import dev.alvo.pieria.cli.harness.WiringContextFactory;
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

  @Option(names = "--user", description = "Inspect user-level config (~) instead of the current project.")
  boolean user;

  @Option(names = "--project-dir", description = "Project directory for project scope (default: current directory).")
  Path projectDir = Path.of("");

  private final HarnessRegistry registry = new HarnessRegistry();

  @Override
  public Integer call() {
    Scope scope = user ? Scope.USER : Scope.PROJECT;
    WiringContext ctx = WiringContextFactory.from(scope, projectDir, null, null, false, System.out);
    System.out.printf("Harness wiring (%s scope):%n", scope.name().toLowerCase(java.util.Locale.ROOT));
    for (HarnessInstaller installer : registry.all()) {
      boolean wired;
      try {
        wired = installer.isInstalled(ctx);
      } catch (IOException e) {
        wired = false;
      }
      System.out.printf("  %-12s %s%n", installer.id(), wired ? "wired" : "not wired");
    }
    return 0;
  }
}
