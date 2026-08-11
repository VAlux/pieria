package dev.alvo.pieria.evaluation;

import dev.alvo.pieria.PieriaApplication;
import dev.alvo.pieria.config.PieriaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Boots the <em>real</em> Pieria daemon in-process — the full {@link PieriaApplication} web stack
 * (REST controllers, the embedded {@code SqliteMemoryStore} with sqlite-vec + FTS5, the async
 * vectorization outbox worker, RRF fusion, the live model gateway) — on a random loopback port,
 * pointed at a throwaway {@link EvalHome}.
 *
 * <p>This is what makes the benchmark "real": the harness does not instantiate the ingestion and
 * retrieval services against a stub store. It drives this daemon over HTTP exactly as a harness or
 * the console would, so LoCoMo numbers reflect the deployed pipeline and config rather than a
 * lexical-only approximation.
 *
 * <p>It still needs a reachable model provider (Ollama by default) for extraction/synthesis/embeddings
 * — it is never booted in CI. The vectorization scheduler is intentionally left at its production
 * default (enabled) so vectors are actually written and the vector channels are warm by the time the
 * harness recalls.
 */
public final class LiveDaemon implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(LiveDaemon.class);

  private final ConfigurableApplicationContext context;
  private final EvalHome home;
  private final String baseUrl;

  private LiveDaemon(ConfigurableApplicationContext context, EvalHome home, String baseUrl) {
    this.context = Objects.requireNonNull(context, "context");
    this.home = Objects.requireNonNull(home, "home");
    this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
  }

  /** Boots the daemon on a random loopback port against a fresh temp database. */
  public static LiveDaemon start() {
    return start(null);
  }

  /**
   * Boots the daemon with {@code configFile} (nullable) layered over the bundled defaults, so the
   * benchmark measures the pipeline the operator actually deploys rather than the shipped defaults.
   */
  public static LiveDaemon start(Path configFile) {
    EvalHome home = EvalHome.create();

    SpringApplication application = new SpringApplication(PieriaApplication.class);
    application.setWebApplicationType(WebApplicationType.SERVLET);

    List<String> args = new ArrayList<>(List.of(
      // server.port=8077 is pinned in application.properties; 0 gets us a free random port.
      "--server.port=0",
      "--server.address=127.0.0.1",
      "--pieria.daemon.host=127.0.0.1",
      // Tear the server down immediately on close instead of a 30s graceful drain — the harness
      // owns the daemon's whole lifecycle, so there is no external client to wait for.
      "--server.shutdown=immediate"));
    args.addAll(home.springArgs(configFile));

    ConfigurableApplicationContext context;
    try {
      context = application.run(args.toArray(String[]::new));
    } catch (RuntimeException e) {
      home.close();
      throw e;
    }

    try {
      int port = ((WebServerApplicationContext) context).getWebServer().getPort();
      String baseUrl = "http://127.0.0.1:" + port;
      log.info("live eval daemon up at {} (home {})", baseUrl, home.root());
      return new LiveDaemon(context, home, baseUrl);
    } catch (RuntimeException e) {
      context.close();
      home.close();
      throw e;
    }
  }

  /** Base URL of the running daemon, e.g. {@code http://127.0.0.1:54321}. */
  public String baseUrl() {
    return baseUrl;
  }

  /** Non-secret provider/model identity persisted with the benchmark report. */
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
      home.close();
    }
  }
}
