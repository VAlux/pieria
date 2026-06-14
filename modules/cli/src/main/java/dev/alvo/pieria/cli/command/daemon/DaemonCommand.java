package dev.alvo.pieria.cli.command.daemon;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * {@code pieria daemon} — inspect and control the local Pieria daemon process.
 */
@Command(
  name = "daemon",
  description = "Report status, start, stop, restart, or tail logs for the local Pieria daemon.",
  mixinStandardHelpOptions = true,
  subcommands = {DaemonStatusCommand.class, DaemonStartCommand.class, DaemonStopCommand.class, DaemonRestartCommand.class, DaemonLogsCommand.class}
)
public final class DaemonCommand implements Runnable {

  @Override
  public void run() {
    CommandLine.usage(this, System.out);
  }
}
