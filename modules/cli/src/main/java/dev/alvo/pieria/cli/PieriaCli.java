package dev.alvo.pieria.cli;

import dev.alvo.pieria.cli.command.config.ConfigCommand;
import dev.alvo.pieria.cli.command.daemon.DaemonLogsCommand;
import dev.alvo.pieria.cli.command.daemon.DaemonRestartCommand;
import dev.alvo.pieria.cli.command.daemon.DaemonStartCommand;
import dev.alvo.pieria.cli.command.daemon.DaemonStatusCommand;
import dev.alvo.pieria.cli.command.daemon.DaemonStopCommand;
import dev.alvo.pieria.cli.command.harness.HarnessCommand;
import dev.alvo.pieria.cli.command.hook.HookCommand;
import dev.alvo.pieria.cli.command.init.OnboardCommand;
import dev.alvo.pieria.cli.command.init.ReminisceCommand;
import dev.alvo.pieria.cli.command.profile.ProfileCommand;
import dev.alvo.pieria.cli.command.task.TaskCommand;
import dev.alvo.pieria.cli.command.update.UpdateCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * {@code pieria} — the user-facing front door to Pieria. A short-lived, human-invoked CLI, distinct
 * from the long-lived daemon and the per-session MCP gateway. Most commands are grouped as
 * {@code pieria <noun> <verb>}; daemon lifecycle verbs ({@code status}, {@code start}, {@code stop},
 * {@code restart}, {@code logs}) sit at the root for quick access.
 */
@Command(
  name = "pieria",
  mixinStandardHelpOptions = true,
  versionProvider = VersionProvider.class,
  description = "Persistent memory layer for AI agents.",
  subcommands = {
    HarnessCommand.class,
    ProfileCommand.class,
    OnboardCommand.class,
    ReminisceCommand.class,
    TaskCommand.class,
    DaemonStatusCommand.class,
    DaemonStartCommand.class,
    DaemonStopCommand.class,
    DaemonRestartCommand.class,
    DaemonLogsCommand.class,
    UpdateCommand.class,
    ConfigCommand.class,
    HookCommand.class
  }
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
