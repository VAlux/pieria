package dev.alvo.pieria.cli.modules.hook;

import dev.alvo.pieria.cli.testsupport.StubDaemon;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptIngestorTests {

  private HookContext context(String daemonUrl, Path dir, Map<String, String> extra) {
    Map<String, String> env = new java.util.HashMap<>(extra);
    env.put("PIERIA_DAEMON_URL", daemonUrl);
    env.put("PIERIA_PROFILE", "proj");
    return new HookContext(env::get, dir, "claude-code");
  }

  @Test
  void postsFileBytesAndReportsSuccess(@TempDir Path tmp) throws IOException {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/ingest/transcript", 200, "{\"memories\":[]}");
      Path transcript = Files.writeString(tmp.resolve("t.jsonl"), "{\"role\":\"user\"}\n");
      HookContext ctx = context(daemon.baseUrl(), tmp, Map.of("CLAUDE_SESSION_ID", "s1"));

      HookOutcome outcome = TranscriptIngestor.ingestFile(ctx, HarnessHookSpec.CLAUDE_CODE, transcript, false);

      assertThat(outcome).isInstanceOf(HookOutcome.Ok.class);
      StubDaemon.Recorded request = daemon.lastRequestTo("/ingest/transcript");
      assertThat(request.body()).isEqualTo("{\"role\":\"user\"}\n");
      assertThat(request.rawQuery()).contains("sessionId=s1").contains("harness=claude-code");
      assertThat(request.path()).contains("/v1/profiles/proj/");
    }
  }

  @Test
  void finalCaptureOmitsThePartialFlagSoTheDaemonExtractsEverything(@TempDir Path tmp) throws IOException {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/ingest/transcript", 200, "{\"memories\":[]}");
      Path transcript = Files.writeString(tmp.resolve("t.jsonl"), "{\"role\":\"user\"}\n");
      HookContext ctx = context(daemon.baseUrl(), tmp, Map.of("CLAUDE_SESSION_ID", "s1"));

      TranscriptIngestor.ingestFile(ctx, HarnessHookSpec.CLAUDE_CODE, transcript, false);

      assertThat(daemon.lastRequestTo("/ingest/transcript").rawQuery()).doesNotContain("partial");
    }
  }

  @Test
  void endOfTurnCaptureSendsPartialSoTheTrailingChunkCanBeDeferred(@TempDir Path tmp) throws IOException {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/ingest/transcript", 200, "{\"memories\":[]}");
      Path transcript = Files.writeString(tmp.resolve("t.jsonl"), "{\"role\":\"user\"}\n");
      HookContext ctx = context(daemon.baseUrl(), tmp, Map.of("CLAUDE_SESSION_ID", "s1"));

      TranscriptIngestor.ingestFile(ctx, HarnessHookSpec.CLAUDE_CODE, transcript, true);

      assertThat(daemon.lastRequestTo("/ingest/transcript").rawQuery()).contains("partial=true");
    }
  }

  @Test
  void usesExplicitSessionIdFromHookPayload(@TempDir Path tmp) throws IOException {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/ingest/transcript", 200, "{\"memories\":[]}");
      Path transcript = Files.writeString(tmp.resolve("codex.jsonl"), "{\"role\":\"user\"}\n");
      HookContext ctx = context(daemon.baseUrl(), tmp, Map.of());

      HookOutcome outcome =
        TranscriptIngestor.ingestFile(ctx, HarnessHookSpec.CODEX, transcript, "thr_123", false);

      assertThat(outcome).isInstanceOf(HookOutcome.Ok.class);
      StubDaemon.Recorded request = daemon.lastRequestTo("/ingest/transcript");
      assertThat(request.rawQuery()).contains("sessionId=thr_123").contains("harness=codex");
    }
  }

  @Test
  void skipsMissingFileWithoutContactingTheDaemon(@TempDir Path tmp) {
    try (StubDaemon daemon = StubDaemon.start()) {
      HookContext ctx = context(daemon.baseUrl(), tmp, Map.of());

      HookOutcome outcome =
        TranscriptIngestor.ingestFile(ctx, HarnessHookSpec.CLAUDE_CODE, tmp.resolve("nope.jsonl"), false);

      assertThat(outcome).isInstanceOf(HookOutcome.Skipped.class);
      assertThat(daemon.lastRequestTo("/ingest/transcript")).isNull();
    }
  }

  @Test
  void skipsEmptyFileWithoutContactingTheDaemon(@TempDir Path tmp) throws IOException {
    try (StubDaemon daemon = StubDaemon.start()) {
      Path empty = Files.writeString(tmp.resolve("empty.jsonl"), "");
      HookContext ctx = context(daemon.baseUrl(), tmp, Map.of());

      HookOutcome outcome = TranscriptIngestor.ingestFile(ctx, HarnessHookSpec.CLAUDE_CODE, empty, false);

      assertThat(outcome).isInstanceOf(HookOutcome.Skipped.class);
      assertThat(daemon.lastRequestTo("/ingest/transcript")).isNull();
    }
  }

  @Test
  void skipsEmptyStdinBytes(@TempDir Path tmp) {
    try (StubDaemon daemon = StubDaemon.start()) {
      HookContext ctx = context(daemon.baseUrl(), tmp, Map.of());

      HookOutcome outcome = TranscriptIngestor.ingestBytes(ctx, HarnessHookSpec.OPENCODE, new byte[0], false);

      assertThat(outcome).isInstanceOf(HookOutcome.Skipped.class);
    }
  }

  @Test
  void reportsFailureOnNonSuccessStatus(@TempDir Path tmp) {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/ingest/transcript", 400, "{\"message\":\"unknown harness\"}");
      HookContext ctx = context(daemon.baseUrl(), tmp, Map.of());

      HookOutcome outcome = TranscriptIngestor.ingestBytes(
        ctx, HarnessHookSpec.CLAUDE_CODE, "{}\n".getBytes(StandardCharsets.UTF_8), false);

      assertThat(outcome).isInstanceOf(HookOutcome.Failed.class);
    }
  }

  @Test
  void reportsFailureWhenDaemonIsDown(@TempDir Path tmp) {
    HookContext ctx = context(StubDaemon.unreachableUrl(), tmp, Map.of());

    HookOutcome outcome = TranscriptIngestor.ingestBytes(
      ctx, HarnessHookSpec.CLAUDE_CODE, "{}\n".getBytes(StandardCharsets.UTF_8), false);

    assertThat(outcome).isInstanceOf(HookOutcome.Failed.class);
  }
}
