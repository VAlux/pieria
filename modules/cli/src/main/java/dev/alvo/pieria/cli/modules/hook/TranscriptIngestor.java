package dev.alvo.pieria.cli.modules.hook;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Posts a harness session transcript to the daemon, which parses the NDJSON server-side. Needs no
 * session-id generation: the daemon mints one when the query parameter is absent.
 */
public final class TranscriptIngestor {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private TranscriptIngestor() {
  }

  /** Ingest a transcript file, skipping when it is missing or empty. */
  public static HookOutcome ingestFile(HookContext ctx, HarnessHookSpec spec, Path transcript) {
    return ingestFile(ctx, spec, transcript, ctx.sessionId(spec));
  }

  /** Ingest a transcript file with an explicit session id supplied by the harness payload. */
  public static HookOutcome ingestFile(
    HookContext ctx, HarnessHookSpec spec, Path transcript, String sessionId
  ) {
    if (transcript == null || !Files.isRegularFile(transcript)) {
      return new HookOutcome.Skipped("transcript not found: " + transcript);
    }
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(transcript);
    } catch (IOException e) {
      return new HookOutcome.Failed("could not read transcript " + transcript + ": " + e.getMessage());
    }
    return ingestBytes(ctx, spec, bytes, sessionId);
  }

  /** Ingest raw transcript bytes (OpenCode pipes them on stdin), skipping when empty. */
  public static HookOutcome ingestBytes(HookContext ctx, HarnessHookSpec spec, byte[] transcript) {
    return ingestBytes(ctx, spec, transcript, ctx.sessionId(spec));
  }

  private static HookOutcome ingestBytes(
    HookContext ctx, HarnessHookSpec spec, byte[] transcript, String sessionId
  ) {
    if (transcript == null || transcript.length == 0) {
      return new HookOutcome.Skipped("transcript is empty; nothing to ingest");
    }
    try {
      ctx.profiles().ingestTranscript(ctx.profile(), sessionId, spec.id(), transcript, TIMEOUT);
      return HookOutcome.ok();
    } catch (RuntimeException e) {
      return new HookOutcome.Failed("ingest failed: " + e.getMessage());
    }
  }
}
