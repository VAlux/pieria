package dev.alvo.pieria.evaluation;

import dev.alvo.pieria.PieriaApplication;
import dev.alvo.pieria.config.PieriaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Boots the <em>real</em> Pieria daemon in-process — the full {@link PieriaApplication} web stack
 * (REST controllers, the embedded {@code SqliteMemoryStore} with sqlite-vec + FTS5, the async
 * vectorization outbox worker, RRF fusion, the live model gateway) — on a random loopback port,
 * pointed at a throwaway temp database.
 *
 * <p>This is what makes the benchmark "real": the harness no longer instantiates the ingestion and
 * retrieval services in-process against a stub store. It drives this daemon over HTTP exactly as a
 * harness or the console would, so LoCoMo numbers reflect the deployed pipeline and config rather
 * than a lexical-only approximation.
 *
 * <p>The context is a dedicated, isolated instance: a fresh temp {@code PIERIA_HOME} + SQLite file
 * per {@link #start()}, discarded on {@link #close()}. It still needs a reachable model provider
 * (Ollama by default) for extraction/synthesis/embeddings — it is never booted in CI. The
 * command-line-style property overrides win over the daemon's bundled {@code application.properties}
 * (in particular {@code server.port=0} overrides the pinned {@code 8077}), and the vectorization
 * scheduler is intentionally left at its production default (enabled) so vectors are actually
 * written and the vector channels are warm by the time the harness recalls.
 */
public final class LiveDaemon implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(LiveDaemon.class);

  private final ConfigurableApplicationContext context;
  private final Path home;
  private final String baseUrl;

  private LiveDaemon(ConfigurableApplicationContext context, Path home, String baseUrl) {
    this.context = Objects.requireNonNull(context, "context");
    this.home = Objects.requireNonNull(home, "home");
    this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
  }

  /** Boots the daemon on a random loopback port against a fresh temp database. */
  public static LiveDaemon start() {
    Path home;
    try {
      home = Files.createTempDirectory("pieria-eval-");
    } catch (IOException e) {
      throw new IllegalStateException("could not create temp PIERIA_HOME for the eval daemon", e);
    }

    SpringApplication application = new SpringApplication(PieriaApplication.class);
    application.setWebApplicationType(WebApplicationType.SERVLET);

    ConfigurableApplicationContext context;
    try {
      // Command-line args have the highest precedence, so they override application.properties —
      // notably server.port=8077, which we must replace with 0 to get a free random port.
      context = application.run(
        "--server.port=0",
        "--server.address=127.0.0.1",
        "--pieria.daemon.host=127.0.0.1",
        // Tear the server down immediately on close instead of a 30s graceful drain — the harness
        // owns the daemon's whole lifecycle, so there is no external client to wait for.
        "--server.shutdown=immediate",
        "--pieria.db.path=" + home.resolve("pieria.db"),
        "--pieria.app-data.root=" + home,
        "--pieria.app-data.database-dir=" + home.resolve("db"),
        "--pieria.app-data.config-dir=" + home.resolve("config"),
        "--pieria.app-data.logs-dir=" + home.resolve("logs"),
        "--pieria.app-data.runtime-dir=" + home.resolve("run"),
        // Assume the operator already has the configured models; never pull during a benchmark.
        "--pieria.first-run.check-models=false");
    } catch (RuntimeException e) {
      deleteRecursively(home);
      throw e;
    }

    try {
      int port = ((WebServerApplicationContext) context).getWebServer().getPort();
      String baseUrl = "http://127.0.0.1:" + port;
      log.info("live eval daemon up at {} (home {})", baseUrl, home);
      return new LiveDaemon(context, home, baseUrl);
    } catch (RuntimeException e) {
      context.close();
      deleteRecursively(home);
      throw e;
    }
  }

  /** Base URL of the running daemon, e.g. {@code http://127.0.0.1:54321}. */
  public String baseUrl() {
    return baseUrl;
  }

  /** Non-secret provider/model identity persisted with live benchmark reports. */
  public Map<String, String> modelMetadata() {
    PieriaProperties properties = context.getBean(PieriaProperties.class);
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("provider", properties.provider().name());
    metadata.put("providerType", properties.provider().type());
    metadata.put("extractionModel", properties.model().extractionModel());
    metadata.put("synthesisModel", properties.model().synthesisModel());
    metadata.put("embeddingModel", properties.model().embedding());
    return Map.copyOf(metadata);
  }

  @Override
  public void close() {
    try {
      context.close();
    } finally {
      deleteRecursively(home);
    }
  }

  private static void deleteRecursively(Path root) {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (IOException ignored) {
          // Best-effort cleanup of a temp dir; a leftover file is harmless.
        }
      });
    } catch (IOException e) {
      log.warn("could not fully clean eval daemon home {}: {}", root, e.getMessage());
    }
  }
}
