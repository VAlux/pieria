package dev.alvo.pieria.model;

import com.sun.net.httpserver.HttpServer;
import dev.alvo.pieria.api.request.RecallMode;
import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.VerifyMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/** Probes run against a throwaway local OpenAI-compatible server; nothing leaves the machine. */
class ProviderProbeServiceTests {

  private HttpServer server;
  private String baseUrl;
  private final Map<String, String> seenKeys = new ConcurrentHashMap<>();
  private volatile int chatStatus = 200;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      seenKeys.put("chat", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
      String body = chatStatus == 200
        ? "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"OK\"}}]}"
        : "{\"error\":{\"message\":\"bad key\",\"type\":\"invalid_request_error\"}}";
      respond(exchange, chatStatus, body);
    });
    server.createContext("/v1/embeddings", exchange -> respond(exchange, 200,
      "{\"object\":\"list\",\"model\":\"e\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.1,0.2,0.3]}],\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}"));
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private ProviderProbeService service(String savedBaseUrl, int dimension) {
    return new ProviderProbeService(new PieriaProperties(null, null,
      new PieriaProperties.Provider(savedBaseUrl, "saved-key", "local", "openai", "2024-10-21"),
      new PieriaProperties.Model("small", "large", "embed", dimension, 4, null, null),
      new PieriaProperties.Ingestion(10000, 2, 4, VerifyMode.ALWAYS, 1, 0, 0, false, 3, 3, 32, 5, false, 5000, true, 0.70),
      new PieriaProperties.Retrieval(false, 60, 3.0, 1.0, 1.0, 1.0, 0.5, 1.0, 2, 20, 8, 10, 3000, 0.0, 0.0, 2, 20, 8, "heuristic",
        RecallMode.SYNTHESIZED, 0.60, 0.78, true, 0.4, true, 30, 400, 4000L),
      null));
  }

  private static ProviderProbeService.Probe probe(String baseUrl, String key) {
    return new ProviderProbeService.Probe(null, baseUrl, key, null, null, null, null);
  }

  @Test
  void reachableTiersReportLatencyAndEmbeddingWidth() {
    List<ProviderProbeService.TierResult> results = service(baseUrl, 3).probe(probe(null, null));

    assertThat(results).extracting(ProviderProbeService.TierResult::tier).containsExactly("extraction", "synthesis", "embedding");
    assertThat(results).allSatisfy(r -> assertThat(r.reachable()).isTrue());
    assertThat(results.get(0).model()).isEqualTo("small");
    assertThat(results.get(2).embeddingDimensions()).isEqualTo(3);
    assertThat(results.get(2).dimensionMatches()).isTrue();
    assertThat(seenKeys.get("chat")).isEqualTo("Bearer saved-key");
  }

  @Test
  void dimensionMismatchIsReported() {
    assertThat(service(baseUrl, 1024).probe(probe(null, null)).get(2).dimensionMatches()).isFalse();
  }

  @Test
  void authFailuresAreClassifiedWithoutEchoingTheKey() {
    chatStatus = 401;
    ProviderProbeService.TierResult chat = service(baseUrl, 3).probe(probe(null, "typed-key")).get(0);

    assertThat(chat.reachable()).isFalse();
    assertThat(chat.errorKind()).isEqualTo("unauthorized");
    assertThat(chat.error()).doesNotContain("typed-key");
    assertThat(seenKeys.get("chat")).isEqualTo("Bearer typed-key");
  }

  @Test
  void savedKeyIsNeverSentToAnotherHost() {
    List<ProviderProbeService.TierResult> results = service("http://saved.example:11434", 3).probe(probe(baseUrl, null));

    assertThat(results).allSatisfy(r -> assertThat(r.errorKind()).isEqualTo("api_key_required"));
    assertThat(seenKeys).isEmpty();
  }

  @Test
  void unreachableHostsAreClassified() {
    server.stop(0);
    assertThat(service(baseUrl, 3).probe(probe(null, null)).get(0).errorKind()).isEqualTo("unreachable");
  }
}
