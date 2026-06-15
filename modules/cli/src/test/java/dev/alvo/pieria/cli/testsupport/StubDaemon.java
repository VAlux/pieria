package dev.alvo.pieria.cli.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A throwaway localhost HTTP server standing in for the Pieria daemon, so CLI command tests exercise
 * the real {@code Http*Client} code paths through {@code --daemon-url} rather than injecting fake
 * clients into the commands.
 *
 * <p>{@code GET /pieria-health} always answers {@code 200} so the clients' pre-flight ping treats
 * the daemon as reachable. Other routes return a canned status + body registered via {@link #stub};
 * every request is recorded for assertions. Use {@link #unreachableUrl()} to simulate a down daemon.
 */
public final class StubDaemon implements AutoCloseable {

  /** A request the stub received. */
  public record Recorded(String method, String path, String body) {
  }

  private record Response(int status, String body) {
  }

  private record Seq(List<Response> responses, java.util.concurrent.atomic.AtomicInteger index) {
  }

  private final HttpServer server;
  private final List<Recorded> requests = new CopyOnWriteArrayList<>();
  private final Map<String, Response> routes = new ConcurrentHashMap<>();
  private final Map<String, Seq> sequences = new ConcurrentHashMap<>();

  private StubDaemon(HttpServer server) {
    this.server = server;
  }

  public static StubDaemon start() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      StubDaemon stub = new StubDaemon(server);
      server.createContext("/", stub::handle);
      server.start();
      return stub;
    } catch (IOException e) {
      throw new UncheckedIOException("failed to start stub daemon", e);
    }
  }

  /** Register a canned response for any request whose path ends with {@code pathSuffix}. */
  public StubDaemon stub(String pathSuffix, int status, String body) {
    routes.put(pathSuffix, new Response(status, body));
    return this;
  }

  /**
   * Register a sequence of {@code 200} response bodies for {@code pathSuffix}: successive matching
   * requests get successive bodies, sticking on the last once exhausted. Useful for polling a task
   * through RUNNING → terminal. Sequences take precedence over {@link #stub} for the same suffix.
   */
  public StubDaemon stubSequence(String pathSuffix, String... bodies) {
    List<Response> responses = new ArrayList<>();
    for (String body : bodies) {
      responses.add(new Response(200, body));
    }
    sequences.put(pathSuffix, new Seq(responses, new java.util.concurrent.atomic.AtomicInteger(0)));
    return this;
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public List<Recorded> requests() {
    return new ArrayList<>(requests);
  }

  /** The most recent request whose path ends with {@code pathSuffix}, or {@code null} if none. */
  public Recorded lastRequestTo(String pathSuffix) {
    Recorded match = null;
    for (Recorded r : requests) {
      if (r.path().endsWith(pathSuffix)) {
        match = r;
      }
    }
    return match;
  }

  /**
   * A localhost URL pointing at a closed port, so a client connecting to it sees connection-refused
   * — the signal the {@code Http*Client}s map to {@code DAEMON_DOWN}.
   */
  public static String unreachableUrl() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return "http://127.0.0.1:" + socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    byte[] body = exchange.getRequestBody().readAllBytes();
    requests.add(new Recorded(exchange.getRequestMethod(), path, new String(body, StandardCharsets.UTF_8)));

    Response response = resolve(path);
    byte[] out = response.body().getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(response.status(), out.length == 0 ? -1 : out.length);
    try (var os = exchange.getResponseBody()) {
      os.write(out);
    }
  }

  private Response resolve(String path) {
    for (Map.Entry<String, Seq> seq : sequences.entrySet()) {
      if (path.endsWith(seq.getKey())) {
        List<Response> responses = seq.getValue().responses();
        int i = seq.getValue().index().getAndUpdate(n -> Math.min(n + 1, responses.size() - 1));
        return responses.get(i);
      }
    }
    for (Map.Entry<String, Response> route : routes.entrySet()) {
      if (path.endsWith(route.getKey())) {
        return route.getValue();
      }
    }
    return new Response(200, ""); // default (covers /pieria-health and unstubbed routes).
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
