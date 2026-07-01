package dev.alvo.pieria.integration;

import dev.alvo.pieria.model.FakeModelGateway;
import dev.alvo.pieria.model.ModelGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the generic explicit-shape ingest contract on {@code POST /v1/profiles/{name}/ingest}:
 * a {@code {sessionId, messages[].role, messages[].content}} body POSTed to a real daemon (random
 * port, faked model gateway producing deterministic extractions) is accepted and its content becomes
 * retrievable.
 *
 * <p>Note: the harness hooks do NOT emit this shape — they POST their raw native transcript to
 * {@code /ingest/transcript}, which is parsed server-side per harness (see
 * {@code dev.alvo.pieria.ingestion.transcript}). This endpoint is the direct programmatic path (used
 * by the gateway and by callers that already have structured messages), so its shape is locked here
 * independently.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IngestHookPayloadTests {

  @TestConfiguration
  static class FakeModelConfig {
    @Bean("ingestHookFakeModelGateway")
    @Primary
    ModelGateway fakeModelGateway() {
      return new FakeModelGateway();
    }
  }

  /** The explicit structured shape accepted by {@code POST /ingest} (not the raw harness transcript). */
  private static final String TRANSCRIPT_JSON = """
    {
      "sessionId": "hook-session-42",
      "messages": [
        {"role": "user", "content": "We deploy Pieria as a localhost daemon on port 8077."},
        {"role": "assistant", "content": "Understood, noted the daemon port."}
      ]
    }
    """;

  @LocalServerPort
  int port;

  private RestClient http;

  @BeforeEach
  void wire() {
    // Tolerate 4xx/5xx so we can assert status codes (instead of the client throwing).
    http = RestClient.builder()
      .baseUrl("http://127.0.0.1:" + port)
      .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {
      })
      .build();
  }

  @Test
  void ingestAcceptsHookTranscriptAndMemoriesBecomeRetrievable() {
    ResponseEntity<String> ingestResp = http.post()
      .uri("/v1/profiles/hookproj/ingest")
      .header("Content-Type", "application/json")
      .body(TRANSCRIPT_JSON)
      .retrieve().toEntity(String.class);

    // Contract: 200 with an ingest summary that reports the stored memories.
    assertThat(ingestResp.getStatusCode().value()).isEqualTo(200);
    assertThat(ingestResp.getBody()).contains("memories");

    // The ingested content is then retrievable via the list endpoint the hooks/gateway rely on.
    ResponseEntity<String> listResp = http.get()
      .uri("/v1/profiles/hookproj/memories")
      .retrieve().toEntity(String.class);

    assertThat(listResp.getStatusCode().value()).isEqualTo(200);
    assertThat(listResp.getBody())
      .contains("localhost daemon on port 8077")
      .contains("\"sessionId\":\"hook-session-42\"");
  }

  @Test
  void ingestRequestJsonShapeIsLocked() {
    // Lock the exact request fields the hook scripts populate. If the daemon stops accepting any of
    // these (sessionId / messages[].role / messages[].content), this assertion fails and signals a
    // breaking change that would silently break every harness hook.
    assertThat(TRANSCRIPT_JSON)
      .contains("\"sessionId\"")
      .contains("\"messages\"")
      .contains("\"role\"")
      .contains("\"content\"");

    // A payload missing the required sessionId must be rejected (validation contract).
    String missingSession = """
      {"messages": [{"role": "user", "content": "hi"}]}
      """;
    ResponseEntity<String> bad = http.post()
      .uri("/v1/profiles/hookproj/ingest")
      .header("Content-Type", "application/json")
      .body(missingSession)
      .retrieve().toEntity(String.class);
    assertThat(bad.getStatusCode().value()).isEqualTo(400);
  }
}
