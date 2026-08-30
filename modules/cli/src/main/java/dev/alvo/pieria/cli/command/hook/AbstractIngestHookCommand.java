package dev.alvo.pieria.cli.command.hook;

import dev.alvo.pieria.api.request.IngestRequest;
import dev.alvo.pieria.api.request.TraceEventDto;
import dev.alvo.pieria.cli.modules.hook.HarnessHookSpec;
import dev.alvo.pieria.cli.modules.hook.HookContext;
import dev.alvo.pieria.cli.modules.hook.HookInput;
import dev.alvo.pieria.cli.modules.hook.HookOutcome;
import dev.alvo.pieria.cli.modules.hook.TraceDrainPolicy;
import dev.alvo.pieria.cli.modules.hook.TraceSpool;
import dev.alvo.pieria.cli.modules.hook.TranscriptIngestor;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Shared body for the lifecycle events that ingest a transcript.
 *
 * <p>The transcript path comes from the harness's JSON stdin payload, which is how both Claude Code
 * and Codex actually hand it over; {@link HarnessHookSpec#transcriptEnvKeys()} is only a fallback for
 * a harness (or a hand-run command) that exports it instead. Resolving the payload first is the
 * whole point: probing the environment alone silently skipped every Claude Code ingest, because the
 * variables it was probing for do not exist.
 *
 * <p>These hooks are also where spooled tool calls are shipped. {@code PostToolUse} only writes to
 * disk — it runs inside the agent's loop — so the batch leaves the machine here, on the same
 * lifecycle events that already talk to the daemon.
 */
abstract class AbstractIngestHookCommand extends AbstractHookCommand {

  /** Spool thresholds, mirroring the daemon defaults; the hook cannot read daemon config. */
  private static final long STOP_DRAIN_THRESHOLD_BYTES = 65_536L;
  private static final int STOP_DRAIN_THRESHOLD_EVENTS = 50;
  private static final int SPOOL_RETENTION_DAYS = 7;
  private static final Duration TRACE_INGEST_TIMEOUT = Duration.ofSeconds(15);

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

    // Resolved before the transcript check: a session with tool calls but an unreadable transcript
    // should lose one capture, not both.
    String sessionId = input.sessionId() != null ? input.sessionId() : ctx.sessionId(spec());
    shipTraces(ctx, sessionId);

    Path transcript = input.transcriptPath() != null
      ? input.transcriptPath()
      : ctx.firstExistingTranscript(spec()).orElse(null);
    if (transcript == null) {
      return new HookOutcome.Skipped(
        "no transcript_path in the hook stdin payload and none found via "
          + spec().transcriptEnvKeys() + "; skipping ingest");
    }

    return TranscriptIngestor.ingestFile(ctx, spec(), transcript, sessionId, partial());
  }

  /**
   * Ship the spooled tool calls, if policy says to. Best-effort throughout: a trace failure must
   * never stop the transcript ingest that follows it, which is the capture that matters most.
   */
  private void shipTraces(HookContext ctx, String sessionId) {
    try {
      TraceSpool spool = new TraceSpool(TraceSpool.defaultRoot());
      spool.sweepStale(SPOOL_RETENTION_DAYS);
      if (!TraceDrainPolicy.shouldDrain(partial(), spool.sizeBytes(sessionId),
        spool.eventCount(sessionId), STOP_DRAIN_THRESHOLD_BYTES, STOP_DRAIN_THRESHOLD_EVENTS)) {
        return;
      }
      List<TraceEventDto> traces = spool.drain(sessionId);
      if (traces.isEmpty()) {
        return;
      }
      ctx.profiles().ingestTraces(ctx.profile(),
        new IngestRequest(sessionId, null, null, null, traces), TRACE_INGEST_TIMEOUT);
    } catch (RuntimeException e) {
      log.error("[{}] trace ingest failed: {}", label(), String.valueOf(e.getMessage()));
    }
  }
}
