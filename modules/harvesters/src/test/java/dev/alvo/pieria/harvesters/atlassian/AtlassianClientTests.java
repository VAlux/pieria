package dev.alvo.pieria.harvesters.atlassian;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvesters.JsonStub;
import dev.alvo.pieria.harvesters.JsonStub.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtlassianClientTests {

  private final JsonStub stub = JsonStub.start();

  @AfterEach
  void stop() {
    stub.close();
  }

  private AtlassianClient client() {
    return new AtlassianClient(new AtlassianSite(stub.baseUrl(), Deployment.DATA_CENTER, "Bearer t0ken"), Duration.ofSeconds(10));
  }

  private void assertFails(String path, ErrorClass errorClass, String message) {
    assertThatThrownBy(() -> client().get(path)).isInstanceOfSatisfying(HarvesterException.class, e -> {
      assertThat(e.errorClass()).isEqualTo(errorClass);
      assertThat(e.getMessage()).contains(message);
    });
  }

  @Test
  void sendsAuthorizationAndUserAgent() throws HarvesterException {
    stub.route("/rest/api/2/myself", _ -> Reply.json(Map.of("name", "me")));

    assertThat(client().get("/rest/api/2/myself?expand=groups").path("name").asString()).isEqualTo("me");

    JsonStub.Request request = stub.requests("/rest/api/2/myself").getFirst();
    assertThat(request.header("Authorization")).isEqualTo("Bearer t0ken");
    assertThat(request.header("User-Agent")).isEqualTo("PieriaHarvester/1.0");
    assertThat(request.header("Accept")).isEqualTo("application/json");
    assertThat(request.query()).containsEntry("expand", "groups");
  }

  @Test
  void unauthorizedIsAuth() {
    stub.route("/x", _ -> Reply.status(401));
    assertFails("/x", ErrorClass.AUTH, "rejected the credentials (HTTP 401)");
  }

  @Test
  void forbiddenIsAuth() {
    stub.route("/x", _ -> Reply.status(403));
    assertFails("/x", ErrorClass.AUTH, "rejected the credentials (HTTP 403)");
  }

  @Test
  void shortRetryAfterIsRetriedInProcess() throws HarvesterException {
    AtomicInteger calls = new AtomicInteger();
    stub.route("/x", _ -> calls.incrementAndGet() == 1
      ? new Reply(429, Map.of("Retry-After", "1"), "")
      : Reply.json(Map.of("ok", true)));

    assertThat(client().get("/x").path("ok").booleanValue()).isTrue();
    assertThat(stub.requests("/x")).hasSize(2);
  }

  @Test
  void retriesGiveUpAfterTheLimit() {
    stub.route("/x", _ -> new Reply(503, Map.of("Retry-After", "0"), ""));

    assertThatThrownBy(() -> client().get("/x")).isInstanceOfSatisfying(HarvesterException.class,
      e -> assertThat(e.errorClass()).isEqualTo(ErrorClass.RATE_LIMITED));
    assertThat(stub.requests("/x")).hasSize(AtlassianClient.MAX_RETRIES + 1);
  }

  @Test
  void longRetryAfterIsRateLimited() {
    stub.route("/x", _ -> new Reply(429, Map.of("Retry-After", "120"), ""));

    assertThatThrownBy(() -> client().get("/x")).isInstanceOfSatisfying(HarvesterException.class, e -> {
      assertThat(e.errorClass()).isEqualTo(ErrorClass.RATE_LIMITED);
      assertThat(e.retryAfterSeconds()).isEqualTo(120L);
    });
    assertThat(stub.requests("/x")).hasSize(1);
  }

  @Test
  void redirectIsNotFollowed() {
    stub.route("/x", _ -> new Reply(302, Map.of("Location", stub.baseUrl() + "/elsewhere"), ""));
    stub.route("/elsewhere", _ -> Reply.json(Map.of()));

    assertFails("/x", ErrorClass.CONFIG, "redirects to " + stub.baseUrl() + "/elsewhere; use that address as baseUrl");
    assertThat(stub.requests("/elsewhere")).isEmpty();
  }

  @Test
  void badRequestCarriesJiraErrorMessages() {
    stub.route("/x", _ -> new Reply(400, Map.of("Content-Type", "application/json"),
      "{\"errorMessages\":[\"Field 'nope' does not exist\"],\"errors\":{\"jql\":\"bad\"}}"));

    assertFails("/x", ErrorClass.CONFIG, "Field 'nope' does not exist");
    assertFails("/x", ErrorClass.CONFIG, "jql: bad");
  }

  @Test
  void badRequestCarriesConfluenceMessage() {
    stub.route("/x", _ -> new Reply(400, Map.of(), "{\"statusCode\":400,\"message\":\"Could not parse cql\"}"));

    assertFails("/x", ErrorClass.CONFIG, "Could not parse cql");
  }

  @Test
  void serverErrorIsTransient() {
    stub.route("/x", _ -> Reply.status(500));
    assertFails("/x", ErrorClass.TRANSIENT, "HTTP 500");
  }

  @Test
  void notFoundIsNullFromGetOrNull() throws HarvesterException {
    assertThat(client().getOrNull("/missing")).isNull();
    assertFails("/missing", ErrorClass.CONFIG, "not found");
  }

  @Test
  void serverTimeComesFromTheDateHeader() throws Exception {
    // The JDK's HttpServer always writes its own Date header, so this one answers from a raw socket.
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      Thread responder = Thread.ofVirtual().start(() -> {
        try (Socket socket = server.accept()) {
          socket.getInputStream().read(new byte[4096]);
          socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nDate: Wed, 21 Oct 2026 07:28:00 GMT\r\n"
            + "Content-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}").getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      });
      AtlassianClient client = new AtlassianClient(
        new AtlassianSite("http://127.0.0.1:" + server.getLocalPort(), Deployment.DATA_CENTER, "Bearer t"), Duration.ofSeconds(10));

      client.get("/x");
      responder.join();

      assertThat(client.serverTime()).isEqualTo(Instant.parse("2026-10-21T07:28:00Z"));
    }
  }

  @Test
  void serverTimeFallsBackToTheLocalClock() {
    Instant before = Instant.now().minusSeconds(1);
    assertThat(client().serverTime()).isAfter(before);
  }

  @Test
  void nonJsonBodyIsTransient() {
    stub.route("/x", _ -> new Reply(200, Map.of("Content-Type", "text/html"), "<html>login</html>"));
    assertFails("/x", ErrorClass.TRANSIENT, "not JSON");
  }

  @Test
  void unreachableHostIsTransient() {
    AtlassianClient client = new AtlassianClient(new AtlassianSite("http://127.0.0.1:1", Deployment.DATA_CENTER, "Bearer t"),
      Duration.ofSeconds(5));

    assertThatThrownBy(() -> client.get("/x")).isInstanceOfSatisfying(HarvesterException.class,
      e -> assertThat(e.errorClass()).isEqualTo(ErrorClass.TRANSIENT));
  }

  @Test
  void encodeUsesPercentTwentyForSpaces() {
    assertThat(AtlassianClient.encode("project = A & b")).isEqualTo("project%20%3D%20A%20%26%20b");
    assertThat(List.of(AtlassianClient.encode("\"-5m\""))).containsExactly("%22-5m%22");
  }
}
