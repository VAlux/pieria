package dev.alvo.pieria.cli.command;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * {@code pieria harness} — wire AI-agent harnesses (Claude Code, Codex) into Pieria.
 */
@Command(
  name = "harness",
  description = "Install, remove, or inspect Pieria wiring for AI-agent harnesses.",
  mixinStandardHelpOptions = true,
  subcommands = {HarnessInstallCommand.class, HarnessUninstallCommand.class, HarnessListCommand.class}
)
public final class HarnessCommand implements Runnable {

  @Override
  public void run() {
    CommandLine.usage(this, System.out);
  }
}
