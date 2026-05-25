package dev.alvo.pieria.cli.command;

import dev.alvo.pieria.cli.harness.HarnessInstaller;
import dev.alvo.pieria.cli.harness.HarnessRegistry;
import dev.alvo.pieria.cli.harness.Scope;
import dev.alvo.pieria.cli.harness.WiringContext;
import dev.alvo.pieria.cli.harness.WiringContextFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code pieria harness uninstall <name>} — remove only Pieria's MCP server and hook entries for a
 * harness, leaving unrelated config untouched.
 */
@Command(
  name = "uninstall",
  description = "Remove Pieria's MCP server and hooks for a harness.",
  mixinStandardHelpOptions = true
)
public final class HarnessUninstallCommand implements Callable<Integer> {

  @Parameters(index = "0", paramLabel = "HARNESS", description = "Harness id: claude-code, codex")
  String harness;

  @Option(names = "--user", description = "Target user-level config (~) instead of the current project.")
  boolean user;

  @Option(names = "--project-dir", description = "Project directory for project scope (default: current directory).")
  Path projectDir = Path.of("");

  @Option(names = "--dry-run", description = "Print intended changes without writing.")
  boolean dryRun;

  private final HarnessRegistry registry = new HarnessRegistry();

  @Override
  public Integer call() {
    HarnessInstaller installer = registry.find(harness).orElse(null);
    if (installer == null) {
      System.err.printf("Unknown harness '%s'. Known harnesses: %s%n", harness, String.join(", ", registry.ids()));
      return 2;
    }

    Scope scope = user ? Scope.USER : Scope.PROJECT;
    WiringContext ctx = WiringContextFactory.from(scope, projectDir, null, null, dryRun, System.out);

    System.out.printf("%s Pieria from %s (%s scope)%s%n",
      dryRun ? "Would unwire" : "Unwiring", installer.id(), scope.name().toLowerCase(java.util.Locale.ROOT),
      dryRun ? " [dry-run]" : "");
    try {
      installer.uninstall(ctx);
    } catch (IOException e) {
      System.err.printf("Failed to unwire %s: %s%n", installer.id(), e.getMessage());
      return 1;
    }
    return 0;
  }
}
