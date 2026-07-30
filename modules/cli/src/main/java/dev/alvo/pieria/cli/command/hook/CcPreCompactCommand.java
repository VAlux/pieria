package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.cli.modules.hook.HarnessHookSpec;
import picocli.CommandLine.Command;

/** Claude Code {@code PreCompact}: capture the transcript before context is discarded. */
@Command(name = "pre-compact", description = "Claude Code PreCompact hook.")
public final class CcPreCompactCommand extends AbstractIngestHookCommand {

  @Override
  protected HarnessHookSpec spec() {
    return HarnessHookSpec.CLAUDE_CODE;
  }

  @Override
  protected String label() {
    return "pieria/claude-code-pre-compact";
  }
}
