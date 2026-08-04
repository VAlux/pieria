package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.cli.modules.hook.HarnessHookSpec;
import dev.alvo.pieria.cli.modules.hook.HookContext;
import dev.alvo.pieria.cli.modules.hook.HookInput;
import dev.alvo.pieria.cli.modules.hook.HookOutcome;
import dev.alvo.pieria.cli.modules.hook.TranscriptIngestor;

import java.nio.file.Path;

/**
 * Shared body for the lifecycle events that ingest a transcript.
 *
 * <p>The transcript path comes from the harness's JSON stdin payload, which is how both Claude Code
 * and Codex actually hand it over; {@link HarnessHookSpec#transcriptEnvKeys()} is only a fallback for
 * a harness (or a hand-run command) that exports it instead. Resolving the payload first is the
 * whole point: probing the environment alone silently skipped every Claude Code ingest, because the
 * variables it was probing for do not exist.
 */
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
    HookInput input = HookInput.readLenient(System.in);
    HookContext ctx = HookContext.create(spec().id());

    Path transcript = input.transcriptPath() != null
      ? input.transcriptPath()
      : ctx.firstExistingTranscript(spec()).orElse(null);
    if (transcript == null) {
      return new HookOutcome.Skipped(
        "no transcript_path in the hook stdin payload and none found via "
          + spec().transcriptEnvKeys() + "; skipping ingest");
    }

    String sessionId = input.sessionId() != null ? input.sessionId() : ctx.sessionId(spec());
    return TranscriptIngestor.ingestFile(ctx, spec(), transcript, sessionId, partial());
  }
}
