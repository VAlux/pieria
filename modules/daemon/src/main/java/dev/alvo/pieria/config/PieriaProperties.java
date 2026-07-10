package dev.alvo.pieria.config;

import dev.alvo.pieria.api.request.RecallMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;

/**
 * Strongly-typed Pieria configuration. The two chat tiers (extraction/synthesis) and the
 * embedding model are separate configuration knobs from the start.
 */
@ConfigurationProperties(prefix = "pieria")
public record PieriaProperties(
  Daemon daemon,
  Db db,
  Provider provider,
  Model model,
  Ingestion ingestion,
  Retrieval retrieval,
  Stats stats) {

  public record Daemon(@DefaultValue("127.0.0.1") String host,
                       @DefaultValue("8077") int port) {
  }

  /**
   * Display tuning for the per-profile stats panels. {@code pricePerMillionTokens} converts
   * estimated <em>saved</em> tokens into an approximate cost figure for the "Pieria impact" panel;
   * {@code 0.0} (the default) omits that cost line. {@code contextWindowTokens} is the model context
   * size used to express savings as a number of windows. {@code spend} carries the per-tier
   * input/output prices used to cost the "Inference spend" panel (the real tokens Pieria spent),
   * keyed by lower-case tier name ({@code extraction}/{@code synthesis}/{@code embedding}); a tier
   * with no entry, or all-zero prices, simply contributes no cost. None of these affect
   * retrieval/ingestion behavior.
   */
  public record Stats(@DefaultValue("0.0") double pricePerMillionTokens,
                      @DefaultValue("200000") int contextWindowTokens,
                      Map<String, TierPrice> spend) {

    public Stats {
      spend = spend == null ? Map.of() : Map.copyOf(spend);
    }

    /**
     * Per-million-token input/output price for a single model tier. Both default to {@code 0.0},
     * which hides the cost contribution for that tier.
     */
    public record TierPrice(@DefaultValue("0.0") double inputPrice,
                            @DefaultValue("0.0") double outputPrice) {
    }
  }

  public record Db(String path) {
  }

  /**
   * LLM provider connection config. The provider must expose an OpenAI-compatible API
   * ({@code /v1/chat/completions}, {@code /v1/embeddings}, {@code /v1/models}); this covers Ollama,
   * LM Studio, llama.cpp's server, vLLM, OpenRouter, and OpenAI itself. {@code baseUrl} is the API
   * root WITHOUT the {@code /v1} suffix (the client appends it). {@code apiKey} is forwarded as the
   * bearer token — local providers ignore it, so any non-blank placeholder works. {@code name} is a
   * display-only label surfaced on the status/health endpoints (it does not change behavior).
   *
   * <p>{@code type} selects the wire dialect: {@code openai} (default) for any vanilla
   * OpenAI-compatible endpoint, or {@code azure} for Azure OpenAI / Microsoft Foundry. In
   * {@code azure} mode {@link ProviderEnvironmentPostProcessor} flips the Spring AI Microsoft
   * Foundry switches, {@code baseUrl} is the Azure resource endpoint
   * ({@code https://<resource>.openai.azure.com}), and the {@code pieria.model.*} names are
   * interpreted as Azure <em>deployment names</em>. {@code apiVersion} is the Azure REST API version
   * and is used only when {@code type=azure}.
   */
  public record Provider(@DefaultValue("http://localhost:11434") String baseUrl,
                         @DefaultValue("ollama") String apiKey,
                         @DefaultValue("openai") String name,
                         @DefaultValue("openai") String type,
                         @DefaultValue("2024-10-21") String apiVersion) {

    /** {@code true} when the provider is configured for Azure OpenAI / Microsoft Foundry. */
    public boolean isAzure() {
      return type != null && type.strip().equalsIgnoreCase("azure");
    }
  }

  public record Model(String extractionModel,
                      String synthesisModel,
                      String embedding,
                      @DefaultValue("1024") int embeddingDimension,
                      @DefaultValue Reasoning reasoning,
                      @DefaultValue Retry retry) {

    public Model {
      if (reasoning == null) {
        reasoning = Reasoning.DEFAULT;
      }
      if (retry == null) {
        retry = Retry.DEFAULT;
      }
    }

    /**
     * Bounded retry-with-backoff for transient model-call failures (rate limits, 5xx, brief provider
     * outages — see {@code ModelFailures.isTransient}). Applies to every chat and embedding call, so a
     * momentary blip no longer aborts a multi-hour onboard: the failing call is retried in place
     * before the exception surfaces. Deterministic failures (a 400 on a poison chunk, auth errors)
     * are never retried.
     *
     * <p>{@code maxAttempts} counts the <em>total</em> tries including the first (so {@code 1} disables
     * retry). Backoff for retry {@code n} (1-based) is {@code min(maxBackoffMs, initialBackoffMs *
     * multiplier^(n-1))}, then randomized by up to ±{@code jitter} (a fraction, {@code 0.2} = ±20%) to
     * avoid synchronized retry storms across the concurrent extraction workers.
     */
    public record Retry(@DefaultValue("3") int maxAttempts,
                        @DefaultValue("500") long initialBackoffMs,
                        @DefaultValue("8000") long maxBackoffMs,
                        @DefaultValue("2.0") double multiplier,
                        @DefaultValue("0.2") double jitter) {

      public static final Retry DEFAULT = new Retry(3, 500, 8000, 2.0, 0.2);

      public Retry {
        maxAttempts = Math.max(1, maxAttempts);
        initialBackoffMs = Math.max(0, initialBackoffMs);
        maxBackoffMs = Math.max(initialBackoffMs, maxBackoffMs);
        multiplier = multiplier < 1.0 ? 1.0 : multiplier;
        jitter = Math.min(1.0, Math.max(0.0, jitter));
      }
    }

    /**
     * Per-stage reasoning ("thinking") control. Reasoning is pure latency/cost overhead on the
     * structured pipeline stages (extract, verify, classify, extractGraph,
     * analyzeQuery) — they emit structured JSON, not a chain of thought — so it is disabled there by
     * default. It is also disabled for synthesis by default: the reasoning chain over already-retrieved
     * memories rarely earns its latency. Enable it (per stage or via the tier flag) when answer quality
     * over the retrieved evidence justifies the extra generation cost.
     *
     * <p>Control is via the OpenAI {@code reasoning_effort} request option (NOT a prompt token: the
     * {@code /no_think} soft switch is ignored over Ollama's OpenAI-compatible endpoint). Disabled
     * stages send {@code disabledEffort}; {@code none} fully suppresses the reasoning chain, including
     * on Ollama's OpenAI-compatible endpoint for Qwen3. Enabled stages send {@code enabledEffort}
     * when set, otherwise nothing (the provider default). The option is harmless to non-reasoning
     * models (Ollama ignores it); for OpenAI reasoning models use a supported level such as
     * {@code minimal}/{@code low} rather than {@code none}.
     *
     * <p>{@code structured} and {@code synthesis} are the tier defaults; {@code stages} overrides any
     * individual stage by name (e.g. {@code pieria.model.reasoning.stages.verify=true}).
     */
    public record Reasoning(@DefaultValue("false") boolean structured,
                            @DefaultValue("false") boolean synthesis,
                            @DefaultValue("none") String disabledEffort,
                            @DefaultValue("") String enabledEffort,
                            Map<String, Boolean> stages) {

      public static final Reasoning DEFAULT = new Reasoning(false, false, "none", "", Map.of());

      public Reasoning {
        stages = stages == null ? Map.of() : Map.copyOf(stages);
      }

      /**
       * Whether reasoning is enabled for {@code stage}. A per-stage override in {@code stages} wins;
       * otherwise the synthesis stages ({@code synthesizeRecall}, {@code judgeAnswerFaithfulness},
       * {@code summarizeCode}) use {@code synthesis} and every other (structured) stage uses
       * {@code structured}.
       */
      public boolean enabledFor(String stage) {
        Boolean override = stages.get(stage);
        if (override != null) {
          return override;
        }
        return switch (stage) {
          case "synthesizeRecall", "judgeAnswerFaithfulness", "summarizeCode" -> synthesis;
          default -> structured;
        };
      }

      /**
       * The {@code reasoning_effort} to send for {@code stage}, or {@code null} to leave it unset
       * (provider default). Disabled stages use {@code disabledEffort}; enabled stages use
       * {@code enabledEffort} when non-blank, else {@code null}.
       */
      public String effortFor(String stage) {
        String effort = enabledFor(stage) ? enabledEffort : disabledEffort;
        return (effort == null || effort.isBlank()) ? null : effort.strip();
      }
    }
  }

  /**
   * Ingestion pipeline tuning. Chunk size/overlap, parallelism, verification mode,
   * extraction sampling, and vectorization-outbox batching/retry limits.
   *
   * <p>{@code verifyMode} controls the verification stage: {@code grounded} (default) sends only
   * candidates that fail the deterministic {@code GroundingFilter} to the model verifier;
   * {@code always} model-verifies every candidate; {@code never} trusts extraction outright.
   * Process-global on purpose (not per-profile overridable).
   *
   * <p>{@code extractionSamples} is how many independent unified-extraction passes to run per
   * chunk: extraction is a stochastic model call, so each sample catches a slightly different
   * subset of the facts in a chunk, and their union is de-duplicated by content before
   * verification. The default of {@code 1} keeps the cheap single-pass behavior for the
   * high-frequency transcript-hook ingests; callers that want saturation on a one-off bulk seed
   * (notably {@code pieria onboard}) raise it per request rather than for every ingest.
   */
  public record Ingestion(@DefaultValue("10000") int chunkSizeChars,
                          @DefaultValue("2") int chunkOverlapMessages,
                          @DefaultValue("4") int maxExtractionConcurrency,
                          @DefaultValue("grounded") VerifyMode verifyMode,
                          @DefaultValue("1") int extractionSamples,
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
   * @param weightGraph        fusion weight for the graph channel (0 disables it); a primary-tier
   *                           signal below exact-key, comparable to FTS/vector, tunable in eval
   * @param graphDepth         graph neighborhood expansion depth in hops (second wave)
   * @param graphFanout        max newly-discovered entities per hop during graph expansion
   * @param graphSeedLimit     max seed entities taken from query entities and from wave-1 candidates
   * @param channelLimit       max hits each channel returns before fusion
   * @param channelTimeoutMs   per-channel timeout in milliseconds for the parallel fan-out
   * @param weightSymbolFts    fusion weight for the code symbol-FTS channel (0 disables it)
   * @param weightCodeGraph    fusion weight for the precise code-graph channel (0 disables it); a
   *                           primary-tier signal comparable to {@code weightGraph}, tunable in eval
   * @param codeGraphDepth     code-graph neighborhood expansion depth in hops (second wave)
   * @param codeGraphFanout    max newly-discovered symbols per hop during code-graph expansion
   * @param codeGraphSeedLimit max seed symbols taken from query terms and wave-1 candidates
   * @param codeGraphMinConfidence minimum edge confidence to traverse ({@code resolved}|{@code heuristic})
   * @param recallMode         default inference tier for recall (see {@link RecallMode}); a request may
   *                           override it per call. Defaults to {@code SYNTHESIZED} (full pipeline).
   */
  public record Retrieval(@DefaultValue("true") boolean vectorEnabled,
                          @DefaultValue("60") int rrfK,
                          @DefaultValue("3.0") double weightExactKey,
                          @DefaultValue("1.0") double weightFtsMemory,
                          @DefaultValue("1.0") double weightHydeVector,
                          @DefaultValue("1.0") double weightDirectVector,
                          @DefaultValue("0.5") double weightFtsMessage,
                          @DefaultValue("1.0") double weightGraph,
                          @DefaultValue("2") int graphDepth,
                          @DefaultValue("20") int graphFanout,
                          @DefaultValue("8") int graphSeedLimit,
                          @DefaultValue("10") int channelLimit,
                          @DefaultValue("3000") long channelTimeoutMs,
                          @DefaultValue("1.0") double weightSymbolFts,
                          @DefaultValue("1.0") double weightCodeGraph,
                          @DefaultValue("2") int codeGraphDepth,
                          @DefaultValue("20") int codeGraphFanout,
                          @DefaultValue("8") int codeGraphSeedLimit,
                          @DefaultValue("heuristic") String codeGraphMinConfidence,
                          @DefaultValue("SYNTHESIZED") RecallMode recallMode) {
  }
}
