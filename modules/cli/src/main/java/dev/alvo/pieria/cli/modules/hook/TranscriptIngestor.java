package dev.alvo.pieria.cli.modules.hook;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Posts a harness session transcript to the daemon, which parses the NDJSON server-side. Replaces
 * {@code harness/ingest.sh}; unlike that script it needs no session-id generation, because the
 * daemon mints one when the query parameter is absent.
 */
public final class TranscriptIngestor {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private TranscriptIngestor() {
  }

  /** Ingest a transcript file, skipping when it is missing or empty. */
  public static HookOutcome ingestFile(HookContext ctx, HarnessHookSpec spec, Path transcript) {
    if (transcript == null || !Files.isRegularFile(transcript)) {
      return new HookOutcome.Skipped("transcript not found: " + transcript);
    }
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(transcript);
    } catch (IOException e) {
      return new HookOutcome.Failed("could not read transcript " + transcript + ": " + e.getMessage());
    }
    return ingestBytes(ctx, spec, bytes);
  }

  /** Ingest raw transcript bytes (OpenCode pipes them on stdin), skipping when empty. */
  public static HookOutcome ingestBytes(HookContext ctx, HarnessHookSpec spec, byte[] transcript) {
    if (transcript == null || transcript.length == 0) {
      return new HookOutcome.Skipped("transcript is empty; nothing to ingest");
    }
    try {
      ctx.profiles().ingestTranscript(ctx.profile(), ctx.sessionId(spec), spec.id(), transcript, TIMEOUT);
      return HookOutcome.ok();
    } catch (RuntimeException e) {
      return new HookOutcome.Failed("ingest failed: " + e.getMessage());
    }
  }
}
