package dev.alvo.pieria.retrieval;


import dev.alvo.pieria.config.PieriaProperties;
import dev.alvo.pieria.config.PieriaProperties.Retrieval;
import dev.alvo.pieria.domain.Memory;
import dev.alvo.pieria.domain.NotFoundException;
import dev.alvo.pieria.domain.QueryAnalysis;
import dev.alvo.pieria.domain.RecallCandidate;
import dev.alvo.pieria.domain.RetrievalCandidate;
import dev.alvo.pieria.domain.RetrievalChannelType;
import dev.alvo.pieria.domain.TemporalFact;
import dev.alvo.pieria.model.ModelGateway;
import dev.alvo.pieria.retrieval.RetrievalDiagnostics.ChannelDiagnostics;
import dev.alvo.pieria.retrieval.channel.DirectVectorChannel;
import dev.alvo.pieria.retrieval.channel.ExactKeyChannel;
import dev.alvo.pieria.retrieval.channel.HydeVectorChannel;
import dev.alvo.pieria.retrieval.channel.MemoryFtsChannel;
import dev.alvo.pieria.retrieval.channel.MessageFtsChannel;
import dev.alvo.pieria.storage.MemoryStore;
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
  private final int channelLimit;
  private final long channelTimeoutMs;

  private record ChannelResult(List<RetrievalCandidate> candidates, long elapsedMs) {
  }

  public RetrievalService(MemoryStore store,
                          ModelGateway modelGateway,
                          DeterministicQueryAnalyzer fallbackAnalyzer,
                          PieriaProperties properties) {
    this.store = store;
    this.modelGateway = modelGateway;
    this.fallbackAnalyzer = fallbackAnalyzer;
    this.temporalExtractor = new TemporalExtractor();

    Retrieval retrievalConfig = properties.retrieval();

    this.channelLimit = retrievalConfig.channelLimit();
    this.channelTimeoutMs = retrievalConfig.channelTimeoutMs();

    Map<RetrievalChannelType, Double> weights = getWeightsForRetrievalChannels(retrievalConfig);
    this.fusion = new ReciprocalRankFusion(retrievalConfig.rrfK(), weights);

    // Channel order is the fixed fan-out order; fusion + tie-breaking make results deterministic.
    this.channels = List.of(
      new ExactKeyChannel(store),
      new MemoryFtsChannel(store),
      new MessageFtsChannel(store),
      new DirectVectorChannel(store),
      new HydeVectorChannel(store));
  }

  private static Map<RetrievalChannelType, Double> getWeightsForRetrievalChannels(Retrieval cfg) {
    Map<RetrievalChannelType, Double> weights = new EnumMap<>(RetrievalChannelType.class);

    weights.put(RetrievalChannelType.EXACT_KEY, cfg.weightExactKey());
    weights.put(RetrievalChannelType.FTS_MEMORY, cfg.weightFtsMemory());
    weights.put(RetrievalChannelType.HYDE_VECTOR, cfg.weightHydeVector());
    weights.put(RetrievalChannelType.DIRECT_VECTOR, cfg.weightDirectVector());
    weights.put(RetrievalChannelType.FTS_MESSAGE, cfg.weightFtsMessage());

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
    long stageStart = totalStart;
    var profile = store.findProfile(profileName).orElseThrow(() -> NotFoundException.profile(profileName));

    QueryAnalysis analysis = analyze(query);
    long analysisMs = elapsedMs(stageStart);
    stageStart = System.nanoTime();
    float[] queryEmbedding = embedQuietly(query);
    float[] hydeEmbedding = analysis.hydeStatement() == null ? null : embedQuietly(analysis.hydeStatement());
    long embeddingMs = elapsedMs(stageStart);

    var retrievalContext =
      new RetrievalContext(profile.id(), query, analysis, queryEmbedding, hydeEmbedding, channelLimit);

    List<ChannelDiagnostics> channelDiagnostics = new ArrayList<>();
    stageStart = System.nanoTime();
    List<RetrievalCandidate> hits = runChannels(retrievalContext, channelDiagnostics);
    long channelsMs = elapsedMs(stageStart);

    stageStart = System.nanoTime();
    List<RecallCandidate> fused = fusion.fuse(hits);
    if (fused.size() > limit) {
      fused = List.copyOf(fused.subList(0, limit));
    }
    long fusionMs = elapsedMs(stageStart);

    stageStart = System.nanoTime();
    List<Memory> evidence = fused.stream().map(RecallCandidate::memory).toList();
    List<TemporalFact> temporalFacts = temporalExtractor.extract(query, Instant.now(), evidence);
    long temporalMs = elapsedMs(stageStart);

    // Synthesis uses the large model; a failure here is genuine (mapped to 503), not soft.
    stageStart = System.nanoTime();
    String answer = modelGateway.synthesizeRecall(query, fused, temporalFacts);
    long synthesisMs = elapsedMs(stageStart);

    RetrievalDiagnostics diagnostics = debug ? new RetrievalDiagnostics(analysis, channelDiagnostics) : null;
    LOGGER.info("recall latency profile={} hits={} evidence={} analysisMs={} embeddingMs={} channelsMs={} fusionMs={} temporalMs={} synthesisMs={} totalMs={}",
      profileName, hits.size(), fused.size(), analysisMs, embeddingMs, channelsMs, fusionMs, temporalMs,
      synthesisMs, elapsedMs(totalStart));
    return new RecallResult(answer, fused, temporalFacts, diagnostics);
  }

  private static long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }

  /**
   * Model-driven analysis, falling back to the deterministic analyzer when the model is down.
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
  private List<RetrievalCandidate> runChannels(RetrievalContext ctx, List<ChannelDiagnostics> diags) {
    List<RetrievalCandidate> all = new ArrayList<>();

    try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
      Map<RetrievalChannel, Future<ChannelResult>> futures = new LinkedHashMap<>();
      for (RetrievalChannel channel : channels) {
        futures.put(channel, exec.submit(() -> {
          long start = System.nanoTime();
          List<RetrievalCandidate> candidates = channel.retrieve(ctx);
          long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
          return new ChannelResult(candidates, elapsedMs);
        }));
      }

      futures.forEach((channel, value) -> {
        try {
          ChannelResult result = value.get(channelTimeoutMs, TimeUnit.MILLISECONDS);
          all.addAll(result.candidates());
          diags.add(new ChannelDiagnostics(channel.type(), result.elapsedMs(), result.candidates().size(), false));
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
