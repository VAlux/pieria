package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.cli.modules.hook.HarnessHookSpec;
import picocli.CommandLine.Command;

/** Claude Code {@code Stop}: capture the transcript at the end of a turn. */
@Command(name = "stop", description = "Claude Code Stop hook.")
public final class CcStopCommand extends AbstractIngestHookCommand {

  @Override
  protected HarnessHookSpec spec() {
    return HarnessHookSpec.CLAUDE_CODE;
  }

  /** End of a turn, not the session: the trailing chunk is still growing and can wait. */
  @Override
  protected boolean partial() {
    return true;
  }

  @Override
  protected String label() {
    return "pieria/claude-code-stop";
  }
}
