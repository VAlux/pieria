package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.cli.modules.hook.HarnessHookSpec;
import picocli.CommandLine.Command;

/** Codex session-open primer. */
@Command(name = "session-start", description = "Codex SessionStart hook.")
public final class CodexSessionStartCommand extends AbstractPrimerHookCommand {

  @Override
  protected HarnessHookSpec spec() {
    return HarnessHookSpec.CODEX;
  }

  @Override
  protected String label() {
    return "pieria/codex-session-start";
  }
}
