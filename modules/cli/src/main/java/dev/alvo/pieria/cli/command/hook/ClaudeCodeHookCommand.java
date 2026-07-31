package dev.alvo.pieria.cli.command.hook;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/** {@code pieria hook claude-code} — the Claude Code lifecycle events. */
@Command(
  name = "claude-code",
  description = "Claude Code lifecycle hooks.",
  subcommands = {
    CcSessionStartCommand.class,
    CcPreCompactCommand.class,
    CcStopCommand.class,
    CcSessionEndCommand.class
  }
)
public final class ClaudeCodeHookCommand implements Runnable {

  @Override
  public void run() {
    CommandLine.usage(this, System.err);
  }
}
