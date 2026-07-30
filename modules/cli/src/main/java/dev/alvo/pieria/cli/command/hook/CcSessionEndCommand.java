package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.cli.modules.hook.HarnessHookSpec;
import picocli.CommandLine.Command;

/** Claude Code {@code SessionEnd}: final capture, including on {@code /clear}. */
@Command(name = "session-end", description = "Claude Code SessionEnd hook.")
public final class CcSessionEndCommand extends AbstractIngestHookCommand {

  @Override
  protected HarnessHookSpec spec() {
    return HarnessHookSpec.CLAUDE_CODE;
  }

  @Override
  protected String label() {
    return "pieria/claude-code-session-end";
  }
}
