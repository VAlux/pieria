package dev.alvo.pieria.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Strongly-typed Pieria configuration. The two chat tiers (small/large) and the embedding
 * model are separate knobs from the start (SPEC 4.1) so Phases 2-3 do not reshape config.
 */
@ConfigurationProperties(prefix = "pieria")
public record PieriaProperties(
  Daemon daemon,
  Db db,
  Ollama ollama,
  Model model,
  Ingestion ingestion) {

  public record Daemon(@DefaultValue("127.0.0.1") String host,
                       @DefaultValue("8077") int port) {
  }

  public record Db(String path) {
  }

  public record Ollama(@DefaultValue("http://localhost:11434") String baseUrl) {
  }

  public record Model(String chatSmall,
                      String chatLarge,
                      String embedding,
                      @DefaultValue("1024") int embeddingDimension) {
  }

  /**
   * Ingestion pipeline tuning (SPEC 6). Chunk size/overlap, parallelism, the detail-pass message
   * threshold, and vectorization-outbox batching/retry limits.
   */
  public record Ingestion(@DefaultValue("10000") int chunkSizeChars,
                          @DefaultValue("2") int chunkOverlapMessages,
                          @DefaultValue("4") int maxExtractionConcurrency,
                          @DefaultValue("9") int detailPassMinMessages,
                          @DefaultValue("32") int outboxBatchSize,
                          @DefaultValue("5") int outboxMaxAttempts,
                          @DefaultValue("true") boolean vectorizationSchedulerEnabled,
                          @DefaultValue("5000") long vectorizationIntervalMs) {
  }
}
