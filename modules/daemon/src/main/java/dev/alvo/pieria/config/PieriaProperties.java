package dev.alvo.pieria.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Strongly-typed Pieria configuration. The two chat tiers (small/large) and the embedding
 * model are separate configuration knobs from the start.
 */
@ConfigurationProperties(prefix = "pieria")
public record PieriaProperties(
  Daemon daemon,
  Db db,
  Ollama ollama,
  Model model,
  Ingestion ingestion,
  Retrieval retrieval) {

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
   * Ingestion pipeline tuning. Chunk size/overlap, parallelism, the detail-pass message
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

  /**
   * Retrieval pipeline tuning. Vector support can be disabled so recall
   * degrades gracefully to FTS + keyed lookup. RRF {@code k} and the per-channel weights are
   * configurable; channel limit/timeout bound each parallel channel.
   *
   * @param vectorEnabled      master switch for the two vector channels (off ⇒ FTS + keyed only)
   * @param rrfK               RRF rank constant {@code k} (default 60)
   * @param weightExactKey     fusion weight for the exact topic-key channel (highest signal)
   * @param weightFtsMemory    fusion weight for the memory FTS channel
   * @param weightHydeVector   fusion weight for the HyDE vector channel
   * @param weightDirectVector fusion weight for the direct vector channel
   * @param weightFtsMessage   fusion weight for the raw-message FTS safety-net channel (lowest)
   * @param channelLimit       max hits each channel returns before fusion
   * @param channelTimeoutMs   per-channel timeout in milliseconds for the parallel fan-out
   */
  public record Retrieval(@DefaultValue("true") boolean vectorEnabled,
                          @DefaultValue("60") int rrfK,
                          @DefaultValue("3.0") double weightExactKey,
                          @DefaultValue("1.0") double weightFtsMemory,
                          @DefaultValue("1.0") double weightHydeVector,
                          @DefaultValue("1.0") double weightDirectVector,
                          @DefaultValue("0.5") double weightFtsMessage,
                          @DefaultValue("10") int channelLimit,
                          @DefaultValue("3000") long channelTimeoutMs) {
  }
}
