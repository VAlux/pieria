package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.cli.modules.hook.CodexHookInput;
import dev.alvo.pieria.cli.modules.hook.HarnessHookSpec;
import dev.alvo.pieria.cli.modules.hook.HookContext;
import dev.alvo.pieria.cli.modules.hook.HookOutcome;
import dev.alvo.pieria.cli.modules.hook.TranscriptIngestor;
import picocli.CommandLine.Command;

import java.io.IOException;

/** Codex end-of-turn capture. Codex supplies the transcript path as JSON on stdin. */
@Command(name = "stop", description = "Codex Stop hook.")
public final class CodexStopCommand extends AbstractHookCommand {

  @Override
  protected HookOutcome execute() {
    CodexHookInput input;
    try {
      input = CodexHookInput.read(System.in);
    } catch (IOException | RuntimeException e) {
      return new HookOutcome.Failed("invalid Codex hook input: " + e.getMessage());
    }
    HookContext ctx = HookContext.create(HarnessHookSpec.CODEX.id());
    return TranscriptIngestor.ingestFile(
      ctx, HarnessHookSpec.CODEX, input.transcriptPath(), input.sessionId());
  }

  @Override
  protected String label() {
    return "pieria/codex-stop";
  }
}
