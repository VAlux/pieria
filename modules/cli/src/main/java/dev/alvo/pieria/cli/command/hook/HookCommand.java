package dev.alvo.pieria.cli.command.hook;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * {@code pieria hook} — machine-invoked entry points wired into harness config by
 * {@code pieria harness install}. Hidden: a human never types these.
 *
 * <p>Every subcommand exits 0 unconditionally and reads its inputs from the environment, because the
 * emitted harness command may contain only literals — {@code $VAR} expansion needs a shell, which
 * Windows does not provide.
 */
@Command(
  name = "hook",
  hidden = true,
  description = "Harness-invoked lifecycle hooks (internal).",
  subcommands = {
    ClaudeCodeHookCommand.class,
    CodexHookCommand.class,
    OpenCodeHookCommand.class,
    HookRecallCommand.class,
    HookRememberCommand.class
  }
)
public final class HookCommand implements Runnable {

  @Override
  public void run() {
    CommandLine.usage(this, System.out);
  }
}
