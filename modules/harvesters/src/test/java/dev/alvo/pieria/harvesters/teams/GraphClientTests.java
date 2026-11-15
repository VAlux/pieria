package dev.alvo.pieria.harvesters.teams;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvesters.JsonStub;
import dev.alvo.pieria.harvesters.http.JsonTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphClientTests {

  private final StubMicrosoft microsoft = new StubMicrosoft();

  @AfterEach
  void stop() {
    microsoft.close();
  }

  private GraphClient client() {
    return new GraphClient(microsoft.baseUrl(), "at1", new JsonTransport("Microsoft", Duration.ofSeconds(10)));
  }

  @Test
  void bearerAndUserAgent() throws HarvesterException {
    assertThat(client().get("/me?$select=id").path("id").asString()).isEqualTo("u1");

    JsonStub.Request request = microsoft.requests("/v1.0/me").getFirst();
    assertThat(request.header("Authorization")).isEqualTo("Bearer at1");
    assertThat(request.header("User-Agent")).isEqualTo("PieriaHarvester/1.0");
    assertThat(request.query()).containsEntry("$select", "id");
  }

  @Test
  void followRefusesAForeignLink() {
    assertThatThrownBy(() -> client().follow("https://evil.example.com/v1.0/me/chats?$skiptoken=x"))
      .isInstanceOfSatisfying(HarvesterException.class, e -> {
        assertThat(e.errorClass()).isEqualTo(ErrorClass.TRANSIENT);
        assertThat(e.getMessage()).isEqualTo("graph returned a next link outside graphUrl");
      });
    assertThatThrownBy(() -> client().follow(microsoft.baseUrl() + "/beta/me")).isInstanceOf(HarvesterException.class);
  }

  @Test
  void followAcceptsALinkUnderGraphUrl() throws HarvesterException {
    microsoft.graph("/v1.0/me/chats", request -> JsonStub.Reply.json(Map.of("value", java.util.List.of())));

    assertThat(client().follow(microsoft.baseUrl() + "/v1.0/me/chats?$skiptoken=abc").has("value")).isTrue();
    assertThat(microsoft.requests("/v1.0/me/chats").getFirst().query()).containsEntry("$skiptoken", "abc");
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', value = {
    "401|get|AUTH|Microsoft Graph rejected the sign-in (HTTP 401)",
    "403|get|AUTH|Microsoft Graph denied access (HTTP 403): Missing role",
    "400|get|CONFIG|Microsoft Graph refused /teams/x (HTTP 400): Missing role",
    "404|get|CONFIG|/teams/x was not found",
    "500|get|TRANSIENT|Microsoft Graph failed (HTTP 500)",
    "302|get|TRANSIENT|Microsoft Graph failed (HTTP 302)"
  })
  void statusMapping(int status, String call, ErrorClass errorClass, String message) {
    microsoft.graph("/v1.0/teams/x", request -> new JsonStub.Reply(status, Map.of("Content-Type", "application/json"),
      "{\"error\":{\"code\":\"Forbidden\",\"message\":\"Missing role\"}}"));

    assertThatThrownBy(() -> client().get("/teams/x?$top=5")).isInstanceOfSatisfying(HarvesterException.class, e -> {
      assertThat(e.errorClass()).isEqualTo(errorClass);
      assertThat(e.getMessage()).isEqualTo(message);
    });
  }

  @Test
  void deniedOrMissingIsNullOnlyWhereAsked() throws HarvesterException {
    microsoft.graph("/v1.0/chats/x/messages", request -> JsonStub.Reply.status(403));

    assertThat(client().readableOrNull("/chats/x/messages")).isNull();
    assertThat(client().readableOrNull("/chats/gone/messages")).isNull();
    assertThatThrownBy(() -> client().getOrNull("/chats/x/messages")).isInstanceOfSatisfying(HarvesterException.class,
      e -> assertThat(e.errorClass()).isEqualTo(ErrorClass.AUTH));
  }

  @Test
  void missingThroughGetOrNullIsNull() throws HarvesterException {
    assertThat(client().getOrNull("/chats/gone/messages")).isNull();
  }

  @Test
  void serverTimeComesFromTheDateHeader() throws Exception {
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
      GraphClient client = new GraphClient("http://127.0.0.1:" + server.getLocalPort(), "at1",
        new JsonTransport("Microsoft", Duration.ofSeconds(10)));

      client.get("/me");
      responder.join();

      assertThat(client.serverTime()).isEqualTo(Instant.parse("2026-10-21T07:28:00Z"));
    }
  }
}
