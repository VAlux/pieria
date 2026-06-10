package dev.alvo.pieria.retrieval;


import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.PieriaProperties.Retrieval;
import dev.alvo.pieria.domain.code.EdgeConfidence;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.error.NotFoundException;
import dev.alvo.pieria.retrieval.model.QueryAnalysis;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.retrieval.model.RetrievalCandidate;
import dev.alvo.pieria.retrieval.model.RetrievalChannelType;
import dev.alvo.pieria.retrieval.model.TemporalFact;
import dev.alvo.pieria.model.ModelGateway;
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
import dev.alvo.pieria.tools.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
  private final ModelGateway modelGateway;
  private final DeterministicQueryAnalyzer fallbackAnalyzer;
  private final TemporalExtractor temporalExtractor;
  private final ReciprocalRankFusion fusion;
  private final List<RetrievalChannel> channels;
  private final List<RetrievalChannel> secondWaveChannels;
  private final int channelLimit;
  private final long channelTimeoutMs;

  public RetrievalService(MemoryStore store,
                          ModelGateway modelGateway,
                          DeterministicQueryAnalyzer fallbackAnalyzer,
                          CodeIndexStore codeStore,
                          PieriaProperties properties) {
    this.store = store;
    this.modelGateway = modelGateway;
    this.fallbackAnalyzer = fallbackAnalyzer;
    this.temporalExtractor = new TemporalExtractor();

    Retrieval cfg = properties.retrieval();

    this.channelLimit = cfg.channelLimit();
    this.channelTimeoutMs = cfg.channelTimeoutMs();

    this.fusion = new ReciprocalRankFusion(cfg.rrfK(), getWeightsForRetrievalChannels(cfg));

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
    this.channels = List.copyOf(wave1);

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
    this.secondWaveChannels = List.copyOf(wave2);
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
   * @throws NotFoundException if the profile does not exist
   */
  public RecallResult recall(String profileName, String query, int limit, boolean debug) {
    long totalStart = System.nanoTime();

    var profile = store.findProfile(profileName).orElseThrow(() -> NotFoundException.profile(profileName));

    Timed<QueryAnalysis> analysis = Timed.measure(() -> analyze(query));
    Timed<Embeddings> embeddings = Timed.measure(() -> embed(query, analysis.value()));

    RetrievalContext context = new RetrievalContext(
      profile.id(), query, analysis.value(),
      embeddings.value().query(), embeddings.value().hyde(), channelLimit);

    List<ChannelDiagnostics> channelDiagnostics = new ArrayList<>();
    Timed<List<RetrievalCandidate>> hits = Timed.measure(() -> runWaves(context, channelDiagnostics));
    Timed<List<RecallCandidate>> fused = Timed.measure(() -> fuse(hits.value(), limit));
    Timed<List<TemporalFact>> temporal = Timed.measure(() -> extractTemporalFacts(query, fused.value()));
    Timed<String> answer = Timed.measure(() -> synthesize(query, fused.value(), temporal.value()));

    RetrievalDiagnostics diagnostics = debug ? new RetrievalDiagnostics(analysis.value(), channelDiagnostics) : null;
    LOGGER.info("recall latency profile={} hits={} evidence={} analysisMs={} embeddingMs={} channelsMs={} fusionMs={} temporalMs={} synthesisMs={} totalMs={}",
      profileName, hits.value().size(), fused.value().size(),
      analysis.millis(), embeddings.millis(), hits.millis(), fused.millis(),
      temporal.millis(), answer.millis(), Timed.elapsedMillis(totalStart));
    return new RecallResult(answer.value(), fused.value(), temporal.value(), diagnostics);
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
    float[] queryEmbedding = embedQuietly(query);
    float[] hydeEmbedding = analysis.hydeStatement() == null ? null : embedQuietly(analysis.hydeStatement());
    return new Embeddings(queryEmbedding, hydeEmbedding);
  }

  /**
   * Channel stage: wave 1 runs the primary channels in parallel; wave 2 runs the graph channel
   * seeded from wave-1 hits + query entities. Both waves feed the same weighted RRF downstream.
   */
  private List<RetrievalCandidate> runWaves(RetrievalContext context, List<ChannelDiagnostics> diags) {
    List<RetrievalCandidate> hits = new ArrayList<>(runChannels(channels, context, diags));
    if (!secondWaveChannels.isEmpty()) {
      hits.addAll(runChannels(secondWaveChannels, context.withSeedCandidates(hits), diags));
    }
    return hits;
  }

  /**
   * Fusion stage: weighted RRF over all channel hits, truncated to {@code limit}.
   */
  private List<RecallCandidate> fuse(List<RetrievalCandidate> hits, int limit) {
    List<RecallCandidate> fused = fusion.fuse(hits);
    return fused.size() > limit ? List.copyOf(fused.subList(0, limit)) : fused;
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
  private String synthesize(String query, List<RecallCandidate> fused, List<TemporalFact> temporalFacts) {
    return modelGateway.synthesizeRecall(query, fused, temporalFacts);
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
  private float[] embedQuietly(String text) {
    if (text == null || text.isBlank() || !store.isVectorSearchAvailable()) {
      return null;
    }

    try {
      float[] embedding = modelGateway.embed(text);
      return (embedding == null || embedding.length == 0) ? null : embedding;
    } catch (RuntimeException e) {
      LOGGER.warn("embedding failed ({}); vector channels will be skipped", e.toString());
      return null;
    }
  }

  /**
   * Fan the channels out on virtual threads, bound each by {@code channelTimeoutMs}. Critical
   * (local-storage) channel failures abort the recall; best-effort vector channels that fail or
   * time out are logged and contribute nothing.
   */
  private List<RetrievalCandidate> runChannels(List<RetrievalChannel> channelsToRun,
                                               RetrievalContext ctx,
                                               List<ChannelDiagnostics> diags) {

    List<RetrievalCandidate> all = new ArrayList<>();

    try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
      Map<RetrievalChannel, Future<Timed<List<RetrievalCandidate>>>> futures = new LinkedHashMap<>();
      for (RetrievalChannel channel : channelsToRun) {
        futures.put(channel, exec.submit(() -> Timed.measure(() -> channel.retrieve(ctx))));
      }

      futures.forEach((channel, value) -> {
        try {
          Timed<List<RetrievalCandidate>> result = value.get(channelTimeoutMs, TimeUnit.MILLISECONDS);
          all.addAll(result.value());
          diags.add(new ChannelDiagnostics(channel.type(), result.millis(), result.value().size(), false));
        } catch (TimeoutException | ExecutionException | InterruptedException e) {
          if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
          }

          handleChannelFailure(channel, e, diags);
        }
      });
    }

    return all;
  }

  private void handleChannelFailure(RetrievalChannel channel, Exception e, List<ChannelDiagnostics> diags) {
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
}
