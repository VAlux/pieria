package dev.alvo.pieria.harvesters.html;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** A website on localhost. Anything not configured answers 404. */
final class StubSite implements AutoCloseable {

  private record Reply(int status, Map<String, String> headers, byte[] body, boolean stall) {
  }

  private final HttpServer server;
  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final Map<String, Reply> replies = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
  private final Map<String, Map<String, String>> lastHeaders = new ConcurrentHashMap<>();
  private final CountDownLatch closing = new CountDownLatch(1);

  private StubSite() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.setExecutor(executor);
    server.createContext("/", this::handle);
    server.start();
  }

  static StubSite start() {
    try {
      return new StubSite();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  int port() {
    return server.getAddress().getPort();
  }

  StubSite page(String path, String html) {
    return respond(path, 200, Map.of("Content-Type", "text/html; charset=utf-8"), html);
  }

  StubSite respond(String path, int status, Map<String, String> headers, String body) {
    return respond(path, status, headers, body.getBytes(StandardCharsets.UTF_8));
  }

  StubSite respond(String path, int status, Map<String, String> headers, byte[] body) {
    replies.put(path, new Reply(status, headers, body, false));
    return this;
  }

  /** Sends headers and part of a body, then never finishes. */
  StubSite stall(String path) {
    replies.put(path, new Reply(200, Map.of("Content-Type", "text/html"), new byte[]{'x'}, true));
    return this;
  }

  int hits(String path) {
    AtomicInteger count = hits.get(path);
    return count == null ? 0 : count.get();
  }

  String lastHeader(String path, String name) {
    return lastHeaders.getOrDefault(path, Map.of()).get(name.toLowerCase(java.util.Locale.ROOT));
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      String path = exchange.getRequestURI().toString();
      hits.computeIfAbsent(path, _ -> new AtomicInteger()).incrementAndGet();
      Map<String, String> seen = new ConcurrentHashMap<>();
      exchange.getRequestHeaders().forEach((name, values) -> seen.put(name.toLowerCase(java.util.Locale.ROOT), values.getFirst()));
      lastHeaders.put(path, seen);
      Reply reply = replies.get(path);
      if (reply == null) {
        exchange.sendResponseHeaders(404, -1);
        return;
      }
      reply.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
      if (reply.stall()) {
        exchange.sendResponseHeaders(reply.status(), 0);
        exchange.getResponseBody().write(reply.body());
        exchange.getResponseBody().flush();
        closing.await();
        return;
      }
      exchange.sendResponseHeaders(reply.status(), reply.body().length == 0 ? -1 : reply.body().length);
      if (reply.body().length > 0) {
        exchange.getResponseBody().write(reply.body());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void close() {
    closing.countDown();
    server.stop(0);
    executor.shutdownNow();
  }
}
