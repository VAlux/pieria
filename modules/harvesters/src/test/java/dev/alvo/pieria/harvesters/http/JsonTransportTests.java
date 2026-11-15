package dev.alvo.pieria.harvesters.http;

import dev.alvo.pieria.harvester.HarvesterException;
import dev.alvo.pieria.harvester.protocol.ErrorClass;
import dev.alvo.pieria.harvesters.JsonStub;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonTransportTests {

  private static final JsonTransport TRANSPORT = new JsonTransport("stub", Duration.ofSeconds(10));

  @Test
  void postsTheFormBody() throws HarvesterException {
    try (JsonStub stub = JsonStub.start()) {
      stub.route("/token", request -> JsonStub.Reply.json(Map.of("ok", true)));

      JsonTransport.Response response = TRANSPORT.send(HttpRequest.newBuilder(URI.create(stub.baseUrl() + "/token"))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString("a=1&b=2")).build());

      assertThat(response.status()).isEqualTo(200);
      JsonStub.Request received = stub.requests("/token").getFirst();
      assertThat(received.method()).isEqualTo("POST");
      assertThat(received.body()).isEqualTo("a=1&b=2");
      assertThat(JsonTransport.json(response.body(), "stub").path("ok").asBoolean()).isTrue();
    }
  }

  @Test
  void retriesAShortRetryAfter() throws HarvesterException {
    try (JsonStub stub = JsonStub.start()) {
      AtomicInteger calls = new AtomicInteger();
      stub.route("/x", request -> calls.getAndIncrement() == 0
        ? new JsonStub.Reply(429, Map.of("Retry-After", "0"), "")
        : JsonStub.Reply.json(Map.of("n", 1)));

      JsonTransport.Response response = TRANSPORT.send(HttpRequest.newBuilder(URI.create(stub.baseUrl() + "/x")).GET().build());

      assertThat(response.status()).isEqualTo(200);
      assertThat(calls).hasValue(2);
    }
  }

  @Test
  void longRetryAfterIsRateLimited() {
    try (JsonStub stub = JsonStub.start()) {
      stub.route("/x", request -> new JsonStub.Reply(503, Map.of("Retry-After", "120"), ""));

      assertThatThrownBy(() -> TRANSPORT.send(HttpRequest.newBuilder(URI.create(stub.baseUrl() + "/x")).GET().build()))
        .isInstanceOfSatisfying(HarvesterException.class, e -> {
          assertThat(e.errorClass()).isEqualTo(ErrorClass.RATE_LIMITED);
          assertThat(e.getMessage()).isEqualTo("stub is limiting requests (HTTP 503)");
          assertThat(e.retryAfterSeconds()).isEqualTo(120L);
        });
    }
  }

  @Test
  void nonJsonBodyIsTransient() {
    assertThatThrownBy(() -> JsonTransport.json("\"text\"".getBytes(), "stub")).isInstanceOfSatisfying(HarvesterException.class, e -> {
      assertThat(e.errorClass()).isEqualTo(ErrorClass.TRANSIENT);
      assertThat(e.getMessage()).isEqualTo("stub answered with something that is not JSON");
    });
  }
}
