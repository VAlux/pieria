package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.cli.modules.hook.HarnessHookSpec;
import picocli.CommandLine.Command;

/** Codex session-close capture. Codex has no compaction event, so this is its only ingest point. */
@Command(name = "stop", description = "Codex Stop hook.")
public final class CodexStopCommand extends AbstractIngestHookCommand {

  @Override
  protected HarnessHookSpec spec() {
    return HarnessHookSpec.CODEX;
  }

  @Override
  protected String label() {
    return "pieria/codex-stop";
  }
}
