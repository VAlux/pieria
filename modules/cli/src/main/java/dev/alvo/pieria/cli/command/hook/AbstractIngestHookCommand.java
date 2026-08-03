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

  /**
   * Whether this is a routine mid-session capture whose trailing chunk may be deferred. Defaults to
   * {@code false} — a final capture that extracts everything outstanding — so a new lifecycle hook
   * is safe by default and only the end-of-turn hooks opt in.
   */
  protected boolean partial() {
    return false;
  }

  @Override
  protected HookOutcome execute() {
    HookContext ctx = HookContext.create(spec().id());
    Optional<Path> transcript = ctx.firstExistingTranscript(spec());
    if (transcript.isEmpty()) {
      return new HookOutcome.Skipped(
        "no transcript found via " + spec().transcriptEnvKeys() + "; skipping ingest");
    }
    return TranscriptIngestor.ingestFile(ctx, spec(), transcript.get(), partial());
  }
}
