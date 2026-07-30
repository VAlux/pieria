package dev.alvo.pieria.cli.command.hook;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/** {@code pieria hook codex} — the Codex CLI lifecycle events. */
@Command(
  name = "codex",
  description = "Codex CLI lifecycle hooks.",
  subcommands = {CodexSessionStartCommand.class, CodexStopCommand.class}
)
public final class CodexHookCommand implements Runnable {

  @Override
  public void run() {
    CommandLine.usage(this, System.out);
  }
}
