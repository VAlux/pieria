package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.cli.modules.hook.HarnessHookSpec;
import picocli.CommandLine.Command;

/** Codex end-of-turn capture. Codex supplies the transcript path as JSON on stdin. */
@Command(name = "stop", description = "Codex Stop hook.")
public final class CodexStopCommand extends AbstractIngestHookCommand {

  @Override
  protected HarnessHookSpec spec() {
    return HarnessHookSpec.CODEX;
  }

  /** End of a turn, not the session: the trailing chunk is still growing and can wait. */
  @Override
  protected boolean partial() {
    return true;
  }

  @Override
  protected String label() {
    return "pieria/codex-stop";
  }
}
