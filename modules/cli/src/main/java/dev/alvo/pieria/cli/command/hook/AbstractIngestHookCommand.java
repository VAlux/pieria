package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.cli.modules.hook.HarnessHookSpec;
import dev.alvo.pieria.cli.modules.hook.HookContext;
import dev.alvo.pieria.cli.modules.hook.HookOutcome;
import dev.alvo.pieria.cli.modules.hook.TranscriptIngestor;

import java.nio.file.Path;
import java.util.Optional;

/** Shared body for the lifecycle events that ingest a transcript located via the environment. */
abstract class AbstractIngestHookCommand extends AbstractHookCommand {

  protected abstract HarnessHookSpec spec();

  @Override
  protected HookOutcome execute() {
    HookContext ctx = HookContext.create(spec().id());
    Optional<Path> transcript = ctx.firstExistingTranscript(spec());
    if (transcript.isEmpty()) {
      return new HookOutcome.Skipped(
        "no transcript found via " + spec().transcriptEnvKeys() + "; skipping ingest");
    }
    return TranscriptIngestor.ingestFile(ctx, spec(), transcript.get());
  }
}
