package dev.alvo.pieria.integration;

import dev.alvo.pieria.mcp.DaemonClient;
import dev.alvo.pieria.mcp.MemoryTools;
import dev.alvo.pieria.model.FakeModelGateway;
import dev.alvo.pieria.model.ModelGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end smoke test: the gateway's real {@link DaemonClient} +
 * {@link MemoryTools} driven against a REAL daemon booted on a random local port. The embedded
 * SQLite store is the real throwaway DB (application-test.properties), so storage is exercised
 * end-to-end; only the {@link ModelGateway} is faked (no model provider, no network egress).
 *
 * <p>This complements {@code MemoryToolsTests} (which uses a fake HttpServer): here the daemon's
 * actual REST stack + storage answer the gateway, proving the full round trip.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayDaemonSmokeTests {

  /** Override the model gateway with a deterministic, network-free fake — keep the real store. */
  @TestConfiguration
  static class FakeModelConfig {
    @Bean("gatewaySmokeFakeModelGateway")
    @Primary
    ModelGateway fakeModelGateway() {
      return new FakeModelGateway();
    }
  }

  @LocalServerPort
  int port;

  private MemoryTools tools;
  private String baseUrl;

  @BeforeEach
  void wireGatewayToLivePort() {
    baseUrl = "http://127.0.0.1:" + port;
    // The gateway's real forwarder, pointed at the live daemon — no test seam.
    tools = new MemoryTools(new DaemonClient(baseUrl), "smoke-proj");
  }

  @Test
  void pieriaHealthReportsUpAndDbOk() {
    ResponseEntity<String> resp = RestClient.create()
      .get().uri(baseUrl + "/pieria-health")
      .retrieve().toEntity(String.class);

    assertThat(resp.getStatusCode().value()).isEqualTo(200);
    assertThat(resp.getBody()).contains("\"status\":\"up\"").contains("\"db\":\"ok\"");
  }

  @Test
  void rememberListRecallForgetRoundTripsThroughTheDaemon() {
    // remember -> daemon stores it and returns the persisted memory.
    String remembered = tools.remember("fact", "Pieria runs as a local daemon", "sess-1",
      "pieria-runtime", null, null);
    assertThat(remembered)
      .contains("\"type\":\"fact\"")
      .contains("\"content\":\"Pieria runs as a local daemon\"")
      .contains("\"id\":");
    String id = extractId(remembered);
    assertThat(id).isNotBlank();

    // list -> the stored memory is visible.
    String listed = tools.list(null, null, null);
    assertThat(listed)
      .contains(id)
      .contains("Pieria runs as a local daemon");

    // recall -> retrieval runs end-to-end; the fake model's synthesized answer comes back.
    String recalled = tools.recall("how does Pieria run?", 5, null);
    assertThat(recalled)
      .contains("\"answer\":")
      .contains("\"memories\":");

    // forget -> deletes; subsequent list no longer shows it.
    String forgotten = tools.forget(id, null);
    assertThat(forgotten).contains("204");

    String afterForget = tools.list(null, null, null);
    assertThat(afterForget).doesNotContain(id);
  }

  /**
   * Daemon-down smoke check: a MemoryTools pointed at a closed port surfaces a concise string
   * (the {@code DaemonUnavailableException} path) instead of throwing. Complements
   * {@code MemoryToolsTests#daemonDownReturnsConciseErrorNotStackTrace}; kept here so the
   * full end-to-end suite also confirms the offline path stays clean.
   */
  @Test
  void daemonDownSurfacesConciseStringNotException() {
    MemoryTools offline = new MemoryTools(new DaemonClient("http://127.0.0.1:1"),
      "smoke-proj");

    String out = offline.recall("anything", null, null);

    assertThat(out).isEqualTo("Pieria daemon is not running at http://127.0.0.1:1");
    assertThat(out).doesNotContain("Exception").doesNotContain("\tat ");
  }

  /** Pull the "id":"<value>" out of a remember/list JSON response. */
  private static String extractId(String json) {
    int idx = json.indexOf("\"id\":\"") + 6;
    return json.substring(idx, json.indexOf('"', idx));
  }
}
