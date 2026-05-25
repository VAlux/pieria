package dev.alvo.pieria.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the gateway's forwarder ({@link DaemonClient}) and {@link MemoryTools} directly against a
 * fake daemon ({@link HttpServer} on an ephemeral port) — no Spring AI MCP transport involved. Each
 * test asserts the forwarded method/path/body and that the daemon's response is passed through. The
 * last test covers the daemon-down path (closed port ⇒ concise error, no stack trace).
 */
class MemoryToolsTests {

  private HttpServer server;
  private MemoryTools tools;
  private final AtomicReference<String> lastMethod = new AtomicReference<>();
  private final AtomicReference<String> lastPath = new AtomicReference<>();
  private final AtomicReference<String> lastQuery = new AtomicReference<>();
  private final AtomicReference<String> lastBody = new AtomicReference<>();
  private volatile int responseStatus = 200;
  private volatile String responseBody = "{}";

  @BeforeEach
  void startFakeDaemon() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
    String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    tools = new MemoryTools(new DaemonClient(baseUrl), "myproj");
  }

  @AfterEach
  void stopFakeDaemon() {
    if (server != null) {
      server.stop(0);
    }
  }

  private void handle(HttpExchange exchange) throws IOException {
    lastMethod.set(exchange.getRequestMethod());
    lastPath.set(exchange.getRequestURI().getPath());
    lastQuery.set(exchange.getRequestURI().getQuery());
    lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(responseStatus, bytes.length == 0 ? -1 : bytes.length);
    try (var os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  @Test
  void recallForwardsPostWithQueryAndLimit() {
    responseBody = "{\"answer\":\"hi\",\"memories\":[]}";

    String out = tools.recall("what is the db?", 5, null);

    assertThat(lastMethod.get()).isEqualTo("POST");
    assertThat(lastPath.get()).isEqualTo("/v1/profiles/myproj/recall");
    assertThat(lastBody.get()).contains("\"query\":\"what is the db?\"").contains("\"limit\":5");
    assertThat(out).isEqualTo(responseBody);
  }

  @Test
  void recallOmitsLimitWhenNull() {
    String out = tools.recall("q", null, null);

    assertThat(lastBody.get()).contains("\"query\":\"q\"").doesNotContain("limit");
    assertThat(out).isNotNull();
  }

  @Test
  void rememberForwardsPostWithAllFields() {
    responseBody = "{\"id\":\"abc\",\"type\":\"fact\"}";

    String out = tools.remember("fact", "the sky is blue", "sess-1", "sky", "p", null);

    assertThat(lastMethod.get()).isEqualTo("POST");
    assertThat(lastPath.get()).isEqualTo("/v1/profiles/myproj/memories");
    assertThat(lastBody.get())
      .contains("\"type\":\"fact\"")
      .contains("\"content\":\"the sky is blue\"")
      .contains("\"sessionId\":\"sess-1\"")
      .contains("\"topicKey\":\"sky\"")
      .contains("\"payload\":\"p\"");
    assertThat(out).isEqualTo(responseBody);
  }

  @Test
  void rememberOmitsOptionalNulls() {
    tools.remember("fact", "c", null, null, null, null);

    assertThat(lastBody.get())
      .contains("\"type\":\"fact\"").contains("\"content\":\"c\"")
      .doesNotContain("sessionId").doesNotContain("topicKey").doesNotContain("payload");
  }

  @Test
  void listForwardsGetWithFilters() {
    responseBody = "{\"memories\":[]}";

    String out = tools.list("fact", "sess-9", null);

    assertThat(lastMethod.get()).isEqualTo("GET");
    assertThat(lastPath.get()).isEqualTo("/v1/profiles/myproj/memories");
    assertThat(lastQuery.get()).isEqualTo("type=fact&session=sess-9");
    assertThat(out).isEqualTo(responseBody);
  }

  @Test
  void listOmitsAbsentFilters() {
    tools.list(null, null, null);

    assertThat(lastMethod.get()).isEqualTo("GET");
    assertThat(lastPath.get()).isEqualTo("/v1/profiles/myproj/memories");
    assertThat(lastQuery.get()).isNull();
  }

  @Test
  void forgetForwardsDeleteWithId() {
    responseStatus = 204;
    responseBody = "";

    String out = tools.forget("mem-123", null);

    assertThat(lastMethod.get()).isEqualTo("DELETE");
    assertThat(lastPath.get()).isEqualTo("/v1/profiles/myproj/memories/mem-123");
    assertThat(out).contains("204");
  }

  @Test
  void profileOverrideTakesPrecedenceOverDefault() {
    tools.list(null, null, "other-proj");

    assertThat(lastPath.get()).isEqualTo("/v1/profiles/other-proj/memories");
  }

  @Test
  void daemonDownReturnsConciseErrorNotStackTrace() {
    // Point at a closed port — connection refused.
    DaemonClient closed = new DaemonClient("http://127.0.0.1:1");
    MemoryTools offline = new MemoryTools(closed, "myproj");

    String out = offline.recall("anything", null, null);

    assertThat(out).isEqualTo("Pieria daemon is not running at http://127.0.0.1:1");
    assertThat(out).doesNotContain("Exception").doesNotContain("\tat ");
  }
}
