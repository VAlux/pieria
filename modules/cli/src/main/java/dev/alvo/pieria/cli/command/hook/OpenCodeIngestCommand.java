package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.cli.modules.hook.HarnessHookSpec;
import dev.alvo.pieria.cli.modules.hook.HookContext;
import dev.alvo.pieria.cli.modules.hook.HookOutcome;
import dev.alvo.pieria.cli.modules.hook.TranscriptIngestor;
import picocli.CommandLine.Command;

import java.io.IOException;

/** OpenCode compaction plugin: the transcript arrives on stdin rather than via a path. */
@Command(name = "ingest", description = "OpenCode compaction hook; reads the transcript from stdin.")
public final class OpenCodeIngestCommand extends AbstractHookCommand {

  @Override
  protected HookOutcome execute() {
    byte[] transcript;
    try {
      transcript = System.in.readAllBytes();
    } catch (IOException e) {
      return new HookOutcome.Failed("could not read transcript from stdin: " + e.getMessage());
    }
    // Compaction hook: context is about to be discarded, so extract everything outstanding.
    return TranscriptIngestor.ingestBytes(
      HookContext.create(HarnessHookSpec.OPENCODE.id()), HarnessHookSpec.OPENCODE, transcript, false);
  }

  @Override
  protected String label() {
    return "pieria/opencode-ingest";
  }
}
