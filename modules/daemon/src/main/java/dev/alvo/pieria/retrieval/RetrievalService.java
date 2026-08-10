package dev.alvo.pieria.retrieval;


import dev.alvo.pieria.api.request.RecallMode;
import dev.alvo.pieria.code.CodeIndexingService;
import dev.alvo.pieria.config.EffectiveConfigResolver;
import dev.alvo.pieria.config.PieriaProperties.Retrieval;
import dev.alvo.pieria.domain.code.EdgeConfidence;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.retrieval.model.GraphEvidence;
import dev.alvo.pieria.retrieval.model.QueryAnalysis;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.retrieval.model.RetrievalCandidate;
import dev.alvo.pieria.retrieval.model.RetrievalChannelType;
import dev.alvo.pieria.retrieval.model.TemporalFact;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.model.usage.InferenceUsageAccumulator;
import dev.alvo.pieria.model.usage.InferenceUsageSink;
import dev.alvo.pieria.retrieval.RetrievalDiagnostics.ChannelDiagnostics;
import dev.alvo.pieria.retrieval.channel.DirectVectorChannel;
import dev.alvo.pieria.retrieval.channel.ExactKeyChannel;
import dev.alvo.pieria.retrieval.channel.GraphChannel;
import dev.alvo.pieria.retrieval.channel.HydeVectorChannel;
import dev.alvo.pieria.retrieval.channel.MemoryFtsChannel;
import dev.alvo.pieria.retrieval.channel.MessageFtsChannel;
import dev.alvo.pieria.retrieval.channel.SymbolFtsChannel;
import dev.alvo.pieria.retrieval.channel.CodeGraphChannel;
import dev.alvo.pieria.storage.CodeIndexStore;
import dev.alvo.pieria.storage.MemoryStore;
import dev.alvo.pieria.tools.TextSimilarity;
import dev.alvo.pieria.tools.Timed;
import dev.alvo.pieria.tools.Tokens;
import dev.alvo.pieria.tools.Vectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Analyzes the query (model with a deterministic fallback), embeds the
 * raw query and the HyDE statement, fans the five retrieval channels out across virtual threads,
 * fuses their results with weighted Reciprocal Rank Fusion, computes deterministic temporal facts,
 * and asks the large model to synthesize an answer.
 *
 * <p>Failure policy (step 6): query-analysis failure falls back to the deterministic
 * analyzer; embedding failure disables the vector channels; a critical channel (FTS / exact-key,
 * i.e. local storage) failing aborts the recall, while a best-effort vector channel failing or
 * timing out is logged and contributes nothing. Synthesis failure propagates (mapped to 503).
 */
@Service
public class RetrievalService {

  private static final Logger LOGGER = LoggerFactory.getLogger(RetrievalService.class);

  private final MemoryStore store;
  private final CodeIndexStore codeStore;
  private final ModelGateway modelGateway;
  private final DeterministicQueryAnalyzer fallbackAnalyzer;
  private final TemporalExtractor temporalExtractor;
  private final EffectiveConfigResolver configResolver;

  public RetrievalService(MemoryStore store,
                          ModelGateway modelGateway,
                          DeterministicQueryAnalyzer fallbackAnalyzer,
                          CodeIndexStore codeStore,
                          EffectiveConfigResolver configResolver) {
    this.store = store;
    this.codeStore = codeStore;
    this.modelGateway = modelGateway;
    this.fallbackAnalyzer = fallbackAnalyzer;
    this.temporalExtractor = new TemporalExtractor();
    this.configResolver = configResolver;
  }

  /**
   * The retrieval machinery for one recall, built from the profile's effective config. Channels
   * are cheap stateless wrappers over the stores, so per-recall construction lets each profile
   * tune weights, waves, and limits independently.
   */
  private record Pipeline(ReciprocalRankFusion fusion,
                          List<RetrievalChannel> channels,
                          List<RetrievalChannel> secondWaveChannels,
                          int channelLimit,
                          long channelTimeoutMs,
                          double nearDuplicateThreshold,
                          double semanticDuplicateThreshold) {
  }

  private Pipeline buildPipeline(Retrieval cfg) {
    ReciprocalRankFusion fusion = new ReciprocalRankFusion(cfg.rrfK(), getWeightsForRetrievalChannels(cfg));

    // Wave 1: the primary channels run in a fixed fan-out order; fusion + tie-breaking make results
    // deterministic. The code SymbolFtsChannel joins wave 1 when enabled (weight > 0).
    List<RetrievalChannel> wave1 = new ArrayList<>(List.of(
      new ExactKeyChannel(store),
      new MemoryFtsChannel(store),
      new MessageFtsChannel(store),
      new DirectVectorChannel(store),
      new HydeVectorChannel(store)));
    if (cfg.weightSymbolFts() > 0.0) {
      wave1.add(new SymbolFtsChannel(store, codeStore));
    }

    // Wave 2: graph channels seeded from wave-1 hits. A weight of 0 disables a channel entirely
    // (no traversal, no fusion contribution).
    List<RetrievalChannel> wave2 = new ArrayList<>();
    if (cfg.weightGraph() > 0.0) {
      wave2.add(new GraphChannel(store, cfg.graphDepth(), cfg.graphFanout(), cfg.graphSeedLimit()));
    }
    if (cfg.weightCodeGraph() > 0.0) {
      wave2.add(new CodeGraphChannel(store, codeStore, cfg.codeGraphDepth(), cfg.codeGraphFanout(),
        cfg.codeGraphSeedLimit(), EdgeConfidence.fromWire(cfg.codeGraphMinConfidence())));
    }

    return new Pipeline(fusion, List.copyOf(wave1), List.copyOf(wave2),
      cfg.channelLimit(), cfg.channelTimeoutMs(), cfg.nearDuplicateThreshold(),
      cfg.semanticDuplicateThreshold());
  }

  private static Map<RetrievalChannelType, Double> getWeightsForRetrievalChannels(Retrieval config) {
    Map<RetrievalChannelType, Double> weights = new EnumMap<>(RetrievalChannelType.class);

    weights.put(RetrievalChannelType.EXACT_KEY, config.weightExactKey());
    weights.put(RetrievalChannelType.FTS_MEMORY, config.weightFtsMemory());
    weights.put(RetrievalChannelType.HYDE_VECTOR, config.weightHydeVector());
    weights.put(RetrievalChannelType.DIRECT_VECTOR, config.weightDirectVector());
    weights.put(RetrievalChannelType.FTS_MESSAGE, config.weightFtsMessage());
    weights.put(RetrievalChannelType.GRAPH, config.weightGraph());
    weights.put(RetrievalChannelType.SYMBOL_FTS, config.weightSymbolFts());
    weights.put(RetrievalChannelType.CODE_GRAPH, config.weightCodeGraph());

    return weights;
  }

  /**
   * Run the full retrieval pipeline for {@code query} within the named profile and synthesize an
   * answer.
   *
   * @param debug when true, collect and return per-channel diagnostics
   */
  public RecallResult recall(String profileName, String query, int limit, boolean debug) {
    return recall(profileName, query, limit, debug, null, false);
  }

  /**
   * As {@link #recall(String, String, int, boolean)}, but {@code requestMode} selects the inference
   * tier (see {@link RecallMode}); {@code null} defers to the profile's configured default. Does not
   * exclude code-indexer memories.
   */
  public RecallResult recall(String profileName, String query, int limit, boolean debug, RecallMode requestMode) {
    return recall(profileName, query, limit, debug, requestMode, false);
  }

  /**
   * Run recall at the given inference tier. {@code requestMode} ({@link RecallMode}, {@code null} ⇒
   * the profile's configured default) governs how much of the model pipeline runs: {@code EVIDENCE}
   * uses deterministic query analysis and no synthesis (the direct-vector query embedding is then the
   * only model call, so it completes in ~1-3s and returns a {@code null} answer); {@code ANALYZED}
   * adds model query-analysis + HyDE but still no synthesis; {@code SYNTHESIZED} runs the full
   * pipeline including temporal facts and the large-model answer.
   *
   * <p>{@code excludeCodeDerived} drops code-indexer memories (see {@link #isCodeDerived}) from the
   * results; it is an injection-only concern (the text/plain context endpoint), independent of the
   * tier, so an {@code EVIDENCE} recall over JSON still surfaces code memories.
   *
   * <p>Recall against a profile that does not exist yet is not an error: it logs a warning and
   * returns an {@linkplain RecallResult#empty() empty result} (no answer, no candidates).
   */
  public RecallResult recall(String profileName,
                             String query,
                             int limit,
                             boolean debug,
                             RecallMode requestMode,
                             boolean excludeCodeDerived) {

    long totalStart = System.nanoTime();

    var maybeProfile = store.findProfile(profileName);
    if (maybeProfile.isEmpty()) {
      // A profile is created lazily on first ingest; recalling one that does not exist yet (e.g. a
      // harness auto-recall before anything has been stored) is not an error — there is simply
      // nothing to recall. Warn and return an empty result rather than throwing.
      LOGGER.warn("recall on unknown profile name={} — no memories to recall, returning empty result", profileName);
      return RecallResult.empty();
    }
    var profile = maybeProfile.get();
    LOGGER.debug("recall resolved profile name={} id={}", profileName, profile.id());

    // Per-profile effective config: global properties overlaid with any pushed overrides. The
    // request's mode wins when set; otherwise the profile's configured default tier applies.
    Retrieval cfg = configResolver.resolve(profile.id()).retrieval();
    RecallMode mode = requestMode != null ? requestMode : cfg.recallMode();
    Pipeline pipeline = buildPipeline(cfg);
    LOGGER.info("recall start profile={} limit={} debug={} mode={} excludeCodeDerived={} queryChars={}",
      profileName, limit, debug, mode, excludeCodeDerived, query == null ? 0 : query.length());

    LOGGER.debug("recall pipeline profile={} wave1Channels={} wave2Channels={} channelLimit={} channelTimeoutMs={}",
      profileName, channelTypes(pipeline.channels()), channelTypes(pipeline.secondWaveChannels()),
      pipeline.channelLimit(), pipeline.channelTimeoutMs());

    // One accumulator for this recall: analyzeQuery, embed, and synthesizeRecall all run on this
    // request thread, so a single main-thread binding captures their real provider token usage.
    InferenceUsageAccumulator inferenceUsage = new InferenceUsageAccumulator();
    InferenceUsageSink.Binding usageBinding = InferenceUsageSink.bind(inferenceUsage);
    try {

    // Tiers below ANALYZED force the deterministic analyzer (no analyzeQuery model call); the HyDE
    // channel then drops out on its own since deterministic analysis produces no hyde statement.
    Timed<QueryAnalysis> analysis = Timed.measure(
      () -> mode.usesModelAnalysis() ? analyze(query) : fallbackAnalyzer.analyze(query));
    LOGGER.debug("recall analysis profile={} topicKeys={} ftsTerms={} entities={} hasHyde={} analysisMs={}",
      profileName, analysis.value().topicKeys().size(), analysis.value().ftsTerms().size(),
      analysis.value().entities().size(), analysis.value().hydeStatement() != null, analysis.millis());

    Timed<Embeddings> embeddings = Timed.measure(() -> embed(query, analysis.value()));
    LOGGER.debug("recall embeddings profile={} queryEmbedding={} hydeEmbedding={} embeddingMs={}",
      profileName, embeddingDimensions(embeddings.value().query()), embeddingDimensions(embeddings.value().hyde()),
      embeddings.millis());

    RetrievalContext context = new RetrievalContext(
      profile.id(), query, analysis.value(),
      embeddings.value().query(), embeddings.value().hyde(), pipeline.channelLimit());

    List<ChannelDiagnostics> channelDiagnostics = new ArrayList<>();
    List<GraphEvidence> graphEvidence = new ArrayList<>();
    Timed<List<RetrievalCandidate>> hits =
      Timed.measure(() -> runWaves(pipeline, context, channelDiagnostics, graphEvidence));
    // Injection path drops code-indexer-derived memories (one-line symbol summaries the agent can grep
    // for itself) so the limited slots go to the decisions/conventions it can't cheaply re-derive.
    // Filtered before fusion so the limit still yields that many real hits.
    Timed<List<RecallCandidate>> fused = Timed.measure(() -> {
      List<RetrievalCandidate> forFusion = excludeCodeDerived
        ? hits.value().stream().filter(h -> !isCodeDerived(h.memory())).toList()
        : hits.value();
      return fuse(pipeline, forFusion, limit, profile.id());
    });
    LOGGER.debug("recall fused profile={} rawHits={} evidence={} sources={} fusionMs={}",
      profileName, hits.value().size(), fused.value().size(), sourceCounts(fused.value()), fused.millis());

    // Non-synthesizing tiers skip temporal facts + synthesis entirely: the answer is null and callers
    // (e.g. the injection hooks) consume the raw memories directly.
    Timed<List<TemporalFact>> temporal = Timed.measure(
      () -> mode.synthesizes() ? extractTemporalFacts(query, fused.value()) : List.<TemporalFact>of());
    LOGGER.debug("recall temporal profile={} facts={} temporalMs={}",
      profileName, temporal.value().size(), temporal.millis());

    List<GraphEvidence> evidence = graphEvidence.stream().distinct().toList();
    Timed<String> answer = Timed.measure(
      () -> mode.synthesizes() ? synthesize(query, fused.value(), temporal.value(), evidence) : null);
    LOGGER.debug("recall synthesis profile={} answerChars={} synthesisMs={}",
      profileName, answer.value() == null ? 0 : answer.value().length(), answer.millis());

    recordUsage(profile.id(), fused.value(), answer.value());

    RetrievalDiagnostics diagnostics = debug ? new RetrievalDiagnostics(analysis.value(), channelDiagnostics) : null;
    LOGGER.info("recall latency profile={} hits={} evidence={} analysisMs={} embeddingMs={} channelsMs={} fusionMs={} temporalMs={} synthesisMs={} totalMs={}",
      profileName, hits.value().size(), fused.value().size(),
      analysis.millis(), embeddings.millis(), hits.millis(), fused.millis(),
      temporal.millis(), answer.millis(), Timed.elapsedMillis(totalStart));
    return new RecallResult(answer.value(), fused.value(), temporal.value(), evidence, diagnostics);

    } finally {
      usageBinding.close();
      recordInferenceUsage(profile.id(), inferenceUsage);
    }
  }

  /**
   * Accumulate this recall's real provider token usage into the profile's lifetime inference-spend
   * counters, per model tier. Accounting-only and best-effort: a failure here must never break
   * recall, so it is logged and swallowed.
   */
  private void recordInferenceUsage(String profileId, InferenceUsageAccumulator usage) {
    try {
      store.recordInferenceUsage(profileId, usage.snapshot());
    } catch (RuntimeException e) {
      LOGGER.warn("recording recall inference usage failed ({}); continuing", e.toString());
    }
  }

  /**
   * Accumulate this recall into the profile's lifetime savings counter: the raw source material
   * behind the evidence, minus what the caller actually received in its place. For synthesizing
   * tiers that replacement is the synthesized answer; for the non-synthesizing tiers
   * ({@code EVIDENCE}/{@code ANALYZED}, where {@code answer} is null) the caller is served the raw
   * memory contents, so those tokens are the subtrahend instead of zero. Accounting-only and
   * best-effort: a failure here must never break recall, so it is logged and swallowed.
   */
  private void recordUsage(String profileId, List<RecallCandidate> evidence, String answer) {
    try {
      List<String> ids = evidence.stream()
        .map(c -> c.memory().id())
        .filter(Objects::nonNull)
        .toList();
      long sourceTokens = store.sumActiveSourceTokens(profileId, ids);
      long servedTokens = answer != null
        ? Tokens.estimate(answer)
        : evidence.stream().mapToLong(c -> Tokens.estimate(c.memory().content())).sum();
      store.recordRecallUsage(profileId, sourceTokens, servedTokens);
    } catch (RuntimeException e) {
      LOGGER.warn("recording recall usage failed ({}); continuing", e.toString());
    }
  }

  /**
   * Embeddings for the vector channels: the raw query and the HyDE statement (either may be null).
   */
  private record Embeddings(float[] query, float[] hyde) {
  }

  /**
   * Embed stage: embed the raw query and, when present, the HyDE statement. Best-effort — a null
   * embedding simply disables the corresponding vector channel.
   */
  private Embeddings embed(String query, QueryAnalysis analysis) {
    if (!store.isVectorSearchAvailable()) {
      LOGGER.debug("recall embeddings skipped because vector search is unavailable");
      return new Embeddings(null, null);
    }

    float[] queryEmbedding = embedQuietly("query", query);
    float[] hydeEmbedding = analysis.hydeStatement() == null ? null : embedQuietly("hyde", analysis.hydeStatement());
    if (analysis.hydeStatement() == null) {
      LOGGER.debug("recall hyde embedding skipped because analysis did not produce a HyDE statement");
    }
    return new Embeddings(queryEmbedding, hydeEmbedding);
  }

  /**
   * Channel stage: wave 1 runs the primary channels in parallel; wave 2 runs the graph channel
   * seeded from wave-1 hits + query entities. Both waves feed the same weighted RRF downstream.
   */
  private List<RetrievalCandidate> runWaves(Pipeline pipeline, RetrievalContext context,
                                            List<ChannelDiagnostics> diags, List<GraphEvidence> evidenceOut) {
    LOGGER.debug("recall wave=1 start channels={} seedCandidates=0", channelTypes(pipeline.channels()));
    List<RetrievalCandidate> hits =
      new ArrayList<>(runChannels(pipeline, pipeline.channels(), context, diags, evidenceOut));
    LOGGER.debug("recall wave=1 completed hits={}", hits.size());
    if (!pipeline.secondWaveChannels().isEmpty()) {
      int firstWaveHits = hits.size();
      LOGGER.debug("recall wave=2 start channels={} seedCandidates={}",
        channelTypes(pipeline.secondWaveChannels()), firstWaveHits);
      List<RetrievalCandidate> secondWaveHits =
        runChannels(pipeline, pipeline.secondWaveChannels(), context.withSeedCandidates(hits), diags, evidenceOut);
      hits.addAll(secondWaveHits);
      LOGGER.debug("recall wave=2 completed hits={} totalHits={}", secondWaveHits.size(), hits.size());
    }
    return hits;
  }

  /**
   * Fusion stage: weighted RRF over all channel hits, truncated to {@code limit}.
   */
  /**
   * Whether {@code memory} was derived by the code indexer (vs. a conversational memory), keyed off
   * the stable {@link CodeIndexingService#CODE_SESSION} session id every code-index memory carries.
   * Such memories are valuable for the code-graph channels but noise for prompt-context injection.
   */
  private static boolean isCodeDerived(Memory memory) {
    return CodeIndexingService.CODE_SESSION.equals(memory.sessionId());
  }

  private List<RecallCandidate> fuse(Pipeline pipeline, List<RetrievalCandidate> hits, int limit,
                                     String profileId) {
    List<RecallCandidate> fused = pipeline.fusion().fuse(hits);
    List<RecallCandidate> distinct = collapseNearDuplicates(fused, pipeline.nearDuplicateThreshold(),
      pipeline.semanticDuplicateThreshold(), profileId);
    return distinct.size() > limit ? List.copyOf(distinct.subList(0, limit)) : distinct;
  }

  /**
   * Drop results that restate one already kept, preserving fused rank order.
   *
   * <p>Fusion groups by memory id, which is exactly what it should do — but the same statement
   * stored several times under drifted topic keys is several ids, so a single fact could fill the
   * whole result list. Collapsing here, <em>before</em> the limit is applied, means the freed slots
   * go to distinct memories rather than being lost.
   *
   * <p>Each candidate is compared only against the representatives already kept, so a chain of
   * pairwise-similar-but-collectively-different memories cannot collapse transitively: dropping a
   * candidate requires it to be similar to something that survived, not merely to something that
   * did not.
   *
   * <p>Two measures run in the same pass and either one firing is enough. Shingle-Jaccard catches a
   * <em>restatement</em> — the same sentence rewritten — but its trigrams encode word order, so it is
   * structurally blind to a <em>paraphrase</em>: one fact carried by different words. Measured on
   * this repository, three memories restating the module layout score 0.03-0.08 on trigrams and
   * 0.76-0.83 on embedding cosine. The semantic half is what sees those, and it costs one store read
   * — the vectors are already persisted, so no model call is involved.
   *
   * <p>Code-indexer memories are exempt from both: their summaries are templated, so two of them
   * describing different source files score as near-identical — 0.85-0.90 by cosine, higher than any
   * genuine duplicate — and collapsing them would hide a real result.
   */
  private List<RecallCandidate> collapseNearDuplicates(
    List<RecallCandidate> ranked, double threshold, double semanticThreshold, String profileId) {
    if ((threshold <= 0.0 && semanticThreshold <= 0.0) || ranked.size() < 2) {
      return ranked;
    }
    Map<String, float[]> embeddings = semanticThreshold <= 0.0
      ? Map.of()
      : embeddingsForCollapse(profileId, ranked);

    List<RecallCandidate> kept = new ArrayList<>(ranked.size());
    List<Set<String>> keptShingles = new ArrayList<>(ranked.size());
    List<float[]> keptVectors = new ArrayList<>(ranked.size());
    for (RecallCandidate candidate : ranked) {
      boolean exempt = isCodeDerived(candidate.memory());
      // An empty shingle set and a null vector never match, which is how the exemption is expressed.
      Set<String> shingles = exempt ? Set.of() : TextSimilarity.shingles(candidate.memory().content());
      float[] vector = exempt ? null : embeddings.get(candidate.memory().id());

      boolean duplicate = false;
      for (int i = 0; i < kept.size() && !duplicate; i++) {
        duplicate = (threshold > 0.0 && TextSimilarity.jaccard(shingles, keptShingles.get(i)) >= threshold)
          || (semanticThreshold > 0.0 && vector != null && keptVectors.get(i) != null
              && Vectors.cosine(vector, keptVectors.get(i)) >= semanticThreshold);
      }
      if (!duplicate) {
        kept.add(candidate);
        keptShingles.add(shingles);
        keptVectors.add(vector);
      }
    }
    if (kept.size() < ranked.size()) {
      LOGGER.debug("recall collapsed {} near-duplicate result(s) of {}",
        ranked.size() - kept.size(), ranked.size());
    }
    return kept;
  }

  /**
   * Embeddings for the collapse pass, in one store read. A backend that does not implement the
   * lookup returns an empty map, which degrades to the lexical check rather than failing the recall.
   */
  private Map<String, float[]> embeddingsForCollapse(String profileId, List<RecallCandidate> ranked) {
    List<String> ids = ranked.stream()
      .filter(candidate -> !isCodeDerived(candidate.memory()))
      .map(candidate -> candidate.memory().id())
      .toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    try {
      return store.embeddingsFor(profileId, ids);
    } catch (RuntimeException e) {
      LOGGER.debug("recall semantic collapse unavailable, falling back to lexical: {}", e.getMessage());
      return Map.of();
    }
  }

  /**
   * Temporal stage: deterministic temporal facts over the fused evidence (date math in Java).
   */
  private List<TemporalFact> extractTemporalFacts(String query, List<RecallCandidate> fused) {
    List<Memory> evidence = fused.stream().map(RecallCandidate::memory).toList();
    return temporalExtractor.extract(query, Instant.now(), evidence);
  }

  /**
   * Synthesis stage: the large model composes the answer from the fused evidence and temporal facts.
   * A failure here is genuine (mapped to 503), not soft.
   */
  private String synthesize(String query, List<RecallCandidate> fused, List<TemporalFact> temporalFacts,
                            List<GraphEvidence> graphEvidence) {
    return modelGateway.synthesizeRecall(query, fused, temporalFacts, graphEvidence);
  }

  /**
   * Analysis stage: model-driven analysis, falling back to the deterministic analyzer when the
   * model is down.
   */
  private QueryAnalysis analyze(String query) {
    try {
      return modelGateway.analyzeQuery(query);
    } catch (RuntimeException e) {
      LOGGER.warn("query analysis via model failed ({}); using deterministic fallback", e.toString());
      return fallbackAnalyzer.analyze(query);
    }
  }

  /**
   * Embed best-effort: skip entirely when vector search is off; never fail recall on embed error.
   */
  private float[] embedQuietly(String label, String text) {
    if (text == null || text.isBlank()) {
      LOGGER.debug("recall {} embedding skipped because text is blank", label);
      return null;
    }

    try {
      float[] embedding = modelGateway.embed(text);
      LOGGER.debug("recall {} embedding completed dimensions={} textChars={}",
        label, embeddingDimensions(embedding), text.length());
      return (embedding == null || embedding.length == 0) ? null : embedding;
    } catch (RuntimeException e) {
      LOGGER.warn("recall {} embedding failed ({}); corresponding vector channel will be skipped",
        label, e.toString());
      return null;
    }
  }

  /**
   * Fan the channels out on virtual threads, bound each by {@code channelTimeoutMs}. Critical
   * (local-storage) channel failures abort the recall; best-effort vector channels that fail or
   * time out are logged and contribute nothing.
   */
  private List<RetrievalCandidate> runChannels(Pipeline pipeline,
                                               List<RetrievalChannel> channelsToRun,
                                               RetrievalContext ctx,
                                               List<ChannelDiagnostics> diags,
                                               List<GraphEvidence> evidenceOut) {

    List<RetrievalCandidate> all = new ArrayList<>();

    try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
      Map<RetrievalChannel, Future<Timed<RetrievalChannel.ChannelResult>>> futures = new LinkedHashMap<>();
      for (RetrievalChannel channel : channelsToRun) {
        LOGGER.debug("retrieval channel {} scheduled critical={} limit={} seedCandidates={}",
          channel.type(), channel.critical(), ctx.limit(), ctx.seedCandidates().size());
        futures.put(channel, exec.submit(() -> {
          LOGGER.debug("retrieval channel {} started", channel.type());
          return Timed.measure(() -> channel.retrieveWithEvidence(ctx));
        }));
      }

      futures.forEach((channel, value) -> {
        try {
          Timed<RetrievalChannel.ChannelResult> result = value.get(pipeline.channelTimeoutMs(), TimeUnit.MILLISECONDS);
          all.addAll(result.value().candidates());
          evidenceOut.addAll(result.value().evidence());
          LOGGER.debug("retrieval channel {} completed hits={} evidence={} ms={}",
            channel.type(), result.value().candidates().size(), result.value().evidence().size(), result.millis());
          diags.add(new ChannelDiagnostics(channel.type(), result.millis(), result.value().candidates().size(), false));
        } catch (TimeoutException | ExecutionException | InterruptedException e) {
          if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
          }

          handleChannelFailure(channel, e, diags, pipeline.channelTimeoutMs());
        }
      });
    }

    return all;
  }

  private void handleChannelFailure(RetrievalChannel channel, Exception e, List<ChannelDiagnostics> diags,
                                    long channelTimeoutMs) {
    Throwable cause = (e instanceof ExecutionException && e.getCause() != null) ? e.getCause() : e;

    if (channel.critical()) {
      LOGGER.error("critical retrieval channel {} failed", channel.type(), cause);
      if (cause instanceof RuntimeException re) {
        throw re;
      }
      throw new IllegalStateException("retrieval channel " + channel.type() + " failed", cause);
    }

    LOGGER.warn("retrieval channel {} failed/timed out ({}); skipping", channel.type(), cause.toString());
    diags.add(new ChannelDiagnostics(channel.type(), channelTimeoutMs, 0, true));
  }

  private static int embeddingDimensions(float[] embedding) {
    return embedding == null ? 0 : embedding.length;
  }

  private static List<RetrievalChannelType> channelTypes(List<RetrievalChannel> channels) {
    return channels.stream().map(RetrievalChannel::type).toList();
  }

  private static Map<String, Long> sourceCounts(List<RecallCandidate> candidates) {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (RecallCandidate candidate : candidates) {
      counts.merge(candidate.source(), 1L, Long::sum);
    }
    return counts;
  }
}
