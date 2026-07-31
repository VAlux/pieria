package dev.alvo.pieria.cli.command.hook;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/** {@code pieria hook opencode} — the OpenCode experimental hook surfaces. */
@Command(
  name = "opencode",
  description = "OpenCode lifecycle hooks.",
  subcommands = {OpenCodeIngestCommand.class, OpenCodeRecallTransformCommand.class}
)
public final class OpenCodeHookCommand implements Runnable {

  @Override
  public void run() {
    CommandLine.usage(this, System.err);
  }
}
