package dev.alvo.pieria.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.alvo.pieria.api.request.RecallRequest;
import dev.alvo.pieria.api.request.RememberRequest;
import dev.alvo.pieria.api.request.SourceSpec;
import dev.alvo.pieria.api.request.OnboardPlanRequest;
import dev.alvo.pieria.client.exception.DaemonConflictException;
import dev.alvo.pieria.client.exception.DaemonHttpException;
import dev.alvo.pieria.client.exception.DaemonInterruptedException;
import dev.alvo.pieria.client.exception.DaemonNotFoundException;
import dev.alvo.pieria.client.exception.DaemonProtocolException;
import dev.alvo.pieria.client.exception.DaemonUnavailableException;
import dev.alvo.pieria.config.model.DaemonOverrides;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DaemonClientsTests {
  private HttpServer server;
  private HealthClient health;
  private ProfileClient profiles;
  private TaskClient tasks;
  private OnboardingClient onboarding;
  private ConfigClient config;
  private final List<Request> requests = new ArrayList<>();
  private volatile int forcedStatus;
  private volatile String forcedBody;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
    String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "///";
    health = new HealthClient(baseUrl);
    profiles = new ProfileClient(baseUrl);
    tasks = new TaskClient(baseUrl);
    onboarding = new OnboardingClient(baseUrl);
    config = new ConfigClient(baseUrl);
  }

  @AfterEach
  void stop() {
    Thread.interrupted();
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void healthSupportsReachabilitySnapshotAndWait() {
    assertThat(health.reachable()).isTrue();
    HealthClient.HealthStatusSnapshot snapshot = health.snapshot();
    assertThat(snapshot.healthy()).isTrue();
    assertThat(snapshot.health().status()).isEqualTo("up");
    assertThat(snapshot.status().backend()).isEqualTo("sqlite");
    assertThat(health.awaitReachable(Duration.ofMillis(50))).isTrue();
  }

  @Test
  void profileClientCoversCrudMemoryOperationsAndEncoding() {
    assertThat(profiles.list().profiles()).hasSize(1);
    assertThat(profiles.create("a profile/one").name()).isEqualTo("created");
    profiles.delete("a profile/one");
    assertThat(profiles.stats("a profile/one").name()).isEqualTo("stats");
    assertThat(profiles.memories("a profile/one", "task item", "s/1").memories()).isEmpty();
    assertThat(profiles.recall(
      "a profile/one", new RecallRequest("why?", 3, null, null)).answer()).isEqualTo("answer");
    assertThat(profiles.remember(
      "a profile/one", new RememberRequest("fact", "body", null, null, null)).id()).isEqualTo("m1");
    profiles.forget("a profile/one", "memory/1");
    assertThat(profiles.export("a profile/one")).isEqualTo("{\"id\":\"m1\"}\n");

    assertThat(requests).anySatisfy(r -> {
      assertThat(r.rawPath()).contains("a%20profile%2Fone/memories");
      assertThat(r.rawQuery()).isEqualTo("type=task+item&session=s%2F1");
    }).anySatisfy(r -> {
      assertThat(r.rawPath()).endsWith("/memory%2F1");
      assertThat(r.method()).isEqualTo("DELETE");
    }).anySatisfy(r -> assertThat(r.body()).contains("\"query\":\"why?\""));
  }

  @Test
  void declaredClientIdentityAndRequestCorrelationAreSent() {
    ProfileClient attributed = new ProfileClient("http://127.0.0.1:" + server.getAddress().getPort(),
      new ClientIdentity("gateway", "codex", "mcp", "1.2.3"));
    attributed.list();

    Request request = requests.getLast();
    assertThat(request.header("X-Pieria-Client")).isEqualTo("gateway");
    assertThat(request.header("X-Pieria-Harness")).isEqualTo("codex");
    assertThat(request.header("X-Pieria-Channel")).isEqualTo("mcp");
    assertThat(request.header("X-Pieria-Client-Version")).isEqualTo("1.2.3");
    assertThat(request.header("X-Pieria-Request-Id")).isNotBlank();
  }

  @Test
  void taskOnboardingAndConfigClientsAreTyped() {
    assertThat(tasks.list().tasks()).hasSize(1);
    assertThat(tasks.status("task/1").status()).isEqualTo("RUNNING");
    assertThat(tasks.cancel("task/1").status()).isEqualTo("CANCELLED");
    assertThat(onboarding
      .submit("my profile", new OnboardPlanRequest(
        List.of(new SourceSpec.Markdown("/tmp", false, 1, null)), true), "on board").taskId())
      .isEqualTo("task-1");

    DaemonOverrides empty = new DaemonOverrides(null, null);
    assertThat(config.put("my profile", empty).ingestion().chunkSizeChars()).isEqualTo(1000);
    assertThat(config.get("my profile").ingestion().chunkSizeChars()).isEqualTo(1000);
    assertThat(requests).anySatisfy(r -> {
      assertThat(r.rawPath()).contains("my%20profile/onboard/async");
      assertThat(r.rawQuery()).isEqualTo("label=on+board");
      assertThat(r.body()).contains("\"sources\"").contains("\"enrichGraph\":true");
    }).anySatisfy(r -> {
      if (r.rawPath().endsWith("/config") && r.method().equals("PUT")) {
        assertThat(r.body()).isEqualTo("{}");
      }
    });
  }

  @Test
  void mapsNotFoundConflictAndGeneralErrorsWithParsedMessages() {
    forcedStatus = 404;
    forcedBody = "{\"error\":\"not_found\",\"message\":\"missing\"}";
    assertThatThrownBy(profiles::list)
      .isInstanceOfSatisfying(DaemonNotFoundException.class, e -> {
        assertThat(e.status()).isEqualTo(404);
        assertThat(e.daemonMessage()).isEqualTo("missing");
        assertThat(e.body()).isEqualTo(forcedBody);
      });
    forcedStatus = 409;
    forcedBody = "{\"message\":\"exists\"}";
    assertThatThrownBy(profiles::list).isInstanceOf(DaemonConflictException.class);
    forcedStatus = 503;
    forcedBody = "plain failure";
    assertThatThrownBy(profiles::list)
      .isInstanceOfSatisfying(DaemonHttpException.class, e -> assertThat(e.daemonMessage()).isEqualTo("plain failure"));
  }

  @Test
  void malformedJsonIsAProtocolFailure() {
    forcedStatus = 200; forcedBody = "not-json";
    assertThatThrownBy(profiles::list).isInstanceOf(DaemonProtocolException.class);
  }

  @Test
  void unavailableAndInterruptedRequestsUseDedicatedFailures() {
    ProfileClient unavailable = new ProfileClient("http://127.0.0.1:1");
    assertThatThrownBy(unavailable::list).isInstanceOf(DaemonUnavailableException.class);

    Thread.currentThread().interrupt();
    assertThatThrownBy(profiles::list).isInstanceOf(DaemonInterruptedException.class);
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
  }

  private void handle(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
      exchange.getRequestURI().getRawQuery(), body, exchange.getRequestHeaders()));
    int status = forcedStatus == 0 ? defaultStatus(exchange) : forcedStatus;
    String response = forcedBody == null ? defaultBody(exchange) : forcedBody;
    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
    if (status != 204) {
      exchange.getResponseBody().write(bytes);
    }
    exchange.close();
  }

  private int defaultStatus(HttpExchange exchange) {
    boolean deletingMemory = exchange.getRequestMethod().equals("DELETE")
      && exchange.getRequestURI().getPath().contains("/memories/");
    return deletingMemory ? 204 : 200;
  }

  private String defaultBody(HttpExchange exchange) {
    String path = exchange.getRequestURI().getPath();
    String method = exchange.getRequestMethod();
    if (path.equals("/pieria-health")) {
      return "{\"status\":\"up\",\"db\":\"ok\",\"modelProvider\":\"reachable\"}";
    }
    if (path.equals("/pieria-status")) {
      return "{\"status\":\"ready\",\"backend\":\"sqlite\"}";
    }
    if (path.equals("/v1/profiles")) {
      return "{\"profiles\":[{\"name\":\"one\",\"memoryCount\":0}]}";
    }
    if (path.endsWith("/stats")) {
      return "{\"name\":\"stats\",\"totalActive\":0,\"superseded\":0,\"sessions\":0}";
    }
    if (path.endsWith("/recall")) {
      return "{\"answer\":\"answer\",\"memories\":[]}";
    }
    if (path.endsWith("/memories") && method.equals("POST")) {
      return "{\"id\":\"m1\",\"type\":\"fact\",\"content\":\"body\",\"superseded\":false}";
    }
    if (path.endsWith("/memories")) {
      return "{\"memories\":[]}";
    }
    if (path.endsWith("/export")) {
      return "{\"id\":\"m1\"}\n";
    }
    if (path.endsWith("/onboard/async")) {
      return "{\"taskId\":\"task-1\"}";
    }
    if (path.endsWith("/config")) {
      return "{\"ingestion\":{\"chunk-size-chars\":1000}}";
    }
    if (path.equals("/v1/tasks")) {
      return "{\"tasks\":[{\"id\":\"task-1\",\"status\":\"RUNNING\",\"lanes\":["
        + "{\"name\":\"ingest\",\"state\":\"RUNNING\",\"done\":0,\"total\":0,"
        + "\"phaseStartedAtEpochMs\":0}],\"startedAtEpochMs\":0}]}";
    }
    if (path.startsWith("/v1/tasks/")) {
      return method.equals("DELETE")
        ? "{\"status\":\"CANCELLED\",\"lanes\":[],\"startedAtEpochMs\":0}"
        : "{\"status\":\"RUNNING\",\"lanes\":[],\"startedAtEpochMs\":0}";
    }
    if (method.equals("PUT")) {
      return "{\"name\":\"created\",\"memoryCount\":0}";
    }
    return "";
  }

  private record Request(String method, String rawPath, String rawQuery, String body,
                         Map<String, List<String>> headers) {
    String header(String name) {
      return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
        .flatMap(e -> e.getValue().stream()).findFirst().orElse(null);
    }
  }
}
