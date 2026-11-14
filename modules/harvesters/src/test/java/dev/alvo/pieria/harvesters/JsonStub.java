package dev.alvo.pieria.harvesters;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** A JSON API on localhost. Routes match the path without its query; anything else answers 404. */
public final class JsonStub implements AutoCloseable {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** A request as the stub received it. Query values are decoded, header names lowercased. */
  public record Request(String path, Map<String, String> query, Map<String, String> headers) {

    public String header(String name) {
      return headers.get(name.toLowerCase(Locale.ROOT));
    }
  }

  public record Reply(int status, Map<String, String> headers, String body) {

    public static Reply json(Object value) {
      return new Reply(200, Map.of("Content-Type", "application/json"), MAPPER.writeValueAsString(value));
    }

    public static Reply status(int status) {
      return new Reply(status, Map.of(), "");
    }
  }

  private final HttpServer server;
  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final Map<String, Function<Request, Reply>> routes = new ConcurrentHashMap<>();
  private final Map<String, List<Request>> received = new ConcurrentHashMap<>();

  private JsonStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.setExecutor(executor);
    server.createContext("/", this::handle);
    server.start();
  }

  public static JsonStub start() {
    try {
      return new JsonStub();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public JsonStub route(String path, Function<Request, Reply> handler) {
    routes.put(path, handler);
    return this;
  }

  public List<Request> requests(String path) {
    return List.copyOf(received.getOrDefault(path, List.of()));
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      String path = exchange.getRequestURI().getRawPath();
      Map<String, String> headers = new LinkedHashMap<>();
      exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values.getFirst()));
      Request request = new Request(path, query(exchange.getRequestURI().getRawQuery()), headers);
      received.computeIfAbsent(path, _ -> Collections.synchronizedList(new ArrayList<>())).add(request);
      Function<Request, Reply> handler = routes.get(path);
      Reply reply = handler == null ? Reply.status(404) : handler.apply(request);
      reply.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
      byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
      if (body.length > 0) {
        exchange.getResponseBody().write(body);
      }
    } catch (RuntimeException e) {
      e.printStackTrace();
      throw e;
    }
  }

  private static Map<String, String> query(String raw) {
    Map<String, String> query = new LinkedHashMap<>();
    if (raw == null || raw.isEmpty()) {
      return query;
    }
    for (String pair : raw.split("&")) {
      int equals = pair.indexOf('=');
      String name = equals < 0 ? pair : pair.substring(0, equals);
      String value = equals < 0 ? "" : pair.substring(equals + 1);
      query.put(URLDecoder.decode(name, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
    }
    return query;
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }
}
