package dev.alvo.pieria.cli.client;

import dev.alvo.pieria.api.request.RecallRequest;
import dev.alvo.pieria.cli.testsupport.StubDaemon;
import dev.alvo.pieria.client.ProfileClient;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileClientHookMethodsTests {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  @Test
  void ingestTranscriptPostsRawBytesWithSessionAndHarnessQuery() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/ingest/transcript", 200, "{\"memories\":[]}");
      ProfileClient client = new ProfileClient(daemon.baseUrl());

      client.ingestTranscript("proj", "sess-1", "claude-code",
        "{\"a\":1}\n{\"b\":2}\n".getBytes(StandardCharsets.UTF_8), TIMEOUT);

      StubDaemon.Recorded request = daemon.lastRequestTo("/ingest/transcript");
      assertThat(request.method()).isEqualTo("POST");
      assertThat(request.body()).isEqualTo("{\"a\":1}\n{\"b\":2}\n");
      assertThat(request.rawQuery()).contains("sessionId=sess-1").contains("harness=claude-code");
    }
  }

  @Test
  void ingestTranscriptOmitsBlankSessionIdSoDaemonGeneratesOne() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/ingest/transcript", 200, "{\"memories\":[]}");
      ProfileClient client = new ProfileClient(daemon.baseUrl());

      client.ingestTranscript("proj", null, "codex", "{}\n".getBytes(StandardCharsets.UTF_8), TIMEOUT);

      StubDaemon.Recorded request = daemon.lastRequestTo("/ingest/transcript");
      assertThat(request.rawQuery()).doesNotContain("sessionId").contains("harness=codex");
    }
  }

  @Test
  void recallTextReturnsBodyWhenDaemonAnswers() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/recall", 200, "[pieria] prior context\n- (fact) something\n");
      ProfileClient client = new ProfileClient(daemon.baseUrl());

      Optional<String> block = client.recallText("proj", new RecallRequest("q", 10, null, null), TIMEOUT);

      assertThat(block).contains("[pieria] prior context\n- (fact) something\n");
    }
  }

  @Test
  void recallTextIsEmptyOnNoContent() {
    try (StubDaemon daemon = StubDaemon.start()) {
      daemon.stub("/recall", 204, "");
      ProfileClient client = new ProfileClient(daemon.baseUrl());

      Optional<String> block = client.recallText("proj", new RecallRequest("q", 10, null, null), TIMEOUT);

      assertThat(block).isEmpty();
    }
  }
}
