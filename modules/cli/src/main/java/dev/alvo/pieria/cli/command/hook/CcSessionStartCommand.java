package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.cli.modules.hook.HarnessHookSpec;
import picocli.CommandLine.Command;

/** Claude Code {@code SessionStart}: primes the session with recalled project context. */
@Command(name = "session-start", description = "Claude Code SessionStart hook.")
public final class CcSessionStartCommand extends AbstractPrimerHookCommand {

  @Override
  protected HarnessHookSpec spec() {
    return HarnessHookSpec.CLAUDE_CODE;
  }

  @Override
  protected String label() {
    return "pieria/claude-code-session-start";
  }
}
