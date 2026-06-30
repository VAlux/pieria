package dev.alvo.pieria.cli;

import dev.alvo.pieria.cli.command.config.ConfigCommand;
import dev.alvo.pieria.cli.command.daemon.DaemonCommand;
import dev.alvo.pieria.cli.command.harness.HarnessCommand;
import dev.alvo.pieria.cli.command.init.OnboardCommand;
import dev.alvo.pieria.cli.command.profile.ProfileCommand;
import dev.alvo.pieria.cli.command.task.TaskCommand;
import dev.alvo.pieria.cli.command.update.UpdateCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * {@code pieria} — the user-facing front door to Pieria. A short-lived, human-invoked CLI, distinct
 * from the long-lived daemon and the per-session MCP gateway. Commands are grouped as
 * {@code pieria <noun> <verb>}; the first capability is harness wiring.
 */
@Command(
  name = "pieria",
  mixinStandardHelpOptions = true,
  versionProvider = VersionProvider.class,
  description = "Persistent memory layer for AI agents.",
  subcommands = {HarnessCommand.class, ProfileCommand.class, OnboardCommand.class, TaskCommand.class, DaemonCommand.class, UpdateCommand.class, ConfigCommand.class}
)
public final class PieriaCli implements Runnable {

  static void main(String[] args) {
    int exitCode = new CommandLine(new PieriaCli()).execute(args);
    System.exit(exitCode);
  }

  @Override
  public void run() {
    // No subcommand given: show usage.
    CommandLine.usage(this, System.out);
  }
}
