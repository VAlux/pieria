package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.cli.modules.hook.HarnessHookSpec;
import picocli.CommandLine.Command;

/** Codex session-close capture, including a final drain of every pending tool trace. */
@Command(name = "session-end", description = "Codex SessionEnd hook.")
public final class CodexSessionEndCommand extends AbstractIngestHookCommand {

  @Override
  protected HarnessHookSpec spec() {
    return HarnessHookSpec.CODEX;
  }

  @Override
  protected String label() {
    return "pieria/codex-session-end";
  }
}
