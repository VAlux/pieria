package dev.alvo.pieria.retrieval;

import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryTimes;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.retrieval.model.RetrievalCandidate;
import dev.alvo.pieria.retrieval.model.RetrievalChannelType;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Weighted Reciprocal Rank Fusion over per-channel retrieval hits.
 *
 * <p>This is a plain, immutable, side-effect-free class (NOT a Spring bean). The orchestrator
 * instantiates it from configuration so RRF weights and {@code k} can be tuned.
 *
 * <h2>Algorithm</h2>
 * Candidates are grouped by {@link Memory#id()}. For each memory the fused score is
 * <pre>{@code  score(memory) = Σ_channels  weight[channel] / (k + rankInChannel) }</pre>
 * summed across every channel that produced a hit for that memory id. A memory surfaced by
 * multiple channels therefore accumulates a contribution from each, which is the whole point
 * of fusion: agreement across independent signals is rewarded.
 *
 * <h2>Weights</h2>
 * Weights are supplied per {@link RetrievalChannelType}. A channel with no entry in the map
 * defaults to weight {@code 0.0} (it contributes nothing). {@code k} is taken as a constructor
 * argument (default approximately 60) and is configurable.
 *
 * <h2>Deterministic ordering</h2>
 * Results are sorted by fused score descending. Ties are broken deterministically:
 * <ol>
 *   <li>more recently <em>stated</em> first, per {@link MemoryTimes#knowledgeTime} (a {@code null}
 *       timestamp sorts last);</li>
 *   <li>then {@link Memory#id()} ascending (lexicographic; a {@code null} id sorts last).</li>
 * </ol>
 * For a fixed input the output order is fully determined.
 *
 * <h2>{@code source} format</h2>
 * Each emitted {@link RecallCandidate} carries a {@code source} string of the form
 * {@code "rrf:<channel>[+<channel>...]"} where each {@code <channel>} is
 * {@link RetrievalChannelType#name()} lower-cased. Contributing channels are ordered by their
 * individual RRF contribution descending, then by channel name ascending for ties — e.g. a
 * memory hit at rank 1 by {@code EXACT_KEY} (weight 3) and rank 1 by {@code FTS_MEMORY}
 * (weight 1) yields {@code "rrf:exact_key+fts_memory"}.
 *
 * <h2>Defensive handling</h2>
 * <ul>
 *   <li>Null or empty input → empty list.</li>
 *   <li>A null candidate, a candidate with a null memory, or a null memory id is skipped.</li>
 *   <li>If the same memory id appears more than once from the <em>same</em> channel, only the
 *       best (lowest, i.e. strongest) {@code rankInChannel} for that channel is counted.</li>
 * </ul>
 */
public final class ReciprocalRankFusion {

  private final int k;
  private final Map<RetrievalChannelType, Double> weights;

  /**
   * Deterministic ordering: score desc, then recency desc, then id asc.
   *
   * <p>Recency is {@link MemoryTimes#knowledgeTime} — when the claim was <em>stated</em>, falling
   * back to when it was stored — not raw {@code createdAt}. On a replayed or back-filled corpus
   * those differ, and ranking by store time would call whichever memory was ingested last the most
   * recent regardless of what it actually says.
   */
  private static final Comparator<RecallCandidate> FUSED_ORDER =
    Comparator.comparingDouble(RecallCandidate::score)
      .reversed()
      .thenComparing(candidate -> MemoryTimes.knowledgeTime(candidate.memory()),
        Comparator.nullsLast(Comparator.reverseOrder()))
      .thenComparing(candidate -> candidate.memory().id(), Comparator.nullsLast(Comparator.naturalOrder()));

  /**
   * @param k       the RRF rank-smoothing constant (default approximately 60); must be {@code >= 1}
   * @param weights per-channel weights; a missing channel defaults to {@code 0.0}. May be empty.
   * @throws IllegalArgumentException if {@code k < 1}
   */
  public ReciprocalRankFusion(int k, Map<RetrievalChannelType, Double> weights) {
    if (k < 1) {
      throw new IllegalArgumentException("RRF k must be >= 1, was " + k);
    }
    this.k = k;

    this.weights = getWeights(weights);
  }

  private static Map<RetrievalChannelType, Double> getWeights(Map<RetrievalChannelType, Double> weights) {
    Map<RetrievalChannelType, Double> newWeights = new EnumMap<>(RetrievalChannelType.class);

    if (weights != null) {
      weights.forEach((channel, weight) -> {
        if (channel != null && weight != null) {
          newWeights.put(channel, weight);
        }
      });
    }

    return newWeights;
  }

  /**
   * Fuse per-channel hits into a ranked, synthesis-facing list.
   *
   * @param candidates per-channel pre-fusion hits (may be {@code null} or empty)
   * @return fused candidates sorted by score descending with deterministic tie-breaking;
   * empty if there is nothing to fuse
   */
  public List<RecallCandidate> fuse(List<RetrievalCandidate> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      return List.of();
    }

    // memory id -> accumulator
    Map<String, FusionAccumulator> byMemory = getAccumulatorMap(candidates);

    List<RecallCandidate> fused = new ArrayList<>(byMemory.size());
    for (FusionAccumulator acc : byMemory.values()) {
      double score = acc.score(k, weights);
      fused.add(new RecallCandidate(acc.memory, score, acc.source(k, weights)));
    }

    fused.sort(FUSED_ORDER);
    return fused;
  }

  private static @NonNull Map<String, FusionAccumulator> getAccumulatorMap(List<RetrievalCandidate> candidates) {
    Map<String, FusionAccumulator> byMemory = new LinkedHashMap<>();

    for (RetrievalCandidate candidate : candidates) {
      if (candidate == null) {
        continue;
      }

      Memory memory = candidate.memory();
      if (memory == null || memory.id() == null) {
        continue;
      }

      RetrievalChannelType channel = candidate.channel();
      if (channel == null) {
        continue;
      }

      FusionAccumulator accumulator = byMemory.computeIfAbsent(memory.id(), _ -> new FusionAccumulator(memory));
      accumulator.observe(channel, candidate.rankInChannel());
    }

    return byMemory;
  }

  /**
   * Per-memory fusion accumulator: best rank seen per channel.
   */
  private static final class FusionAccumulator {
    private final Memory memory;
    // best (lowest) rank seen per channel
    private final Map<RetrievalChannelType, Integer> bestRank = new EnumMap<>(RetrievalChannelType.class);

    FusionAccumulator(Memory memory) {
      this.memory = memory;
    }

    void observe(RetrievalChannelType channel, int rank) {
      bestRank.merge(channel, rank, Math::min);
    }

    double score(int k, Map<RetrievalChannelType, Double> weights) {
      double total = 0.0;
      for (Map.Entry<RetrievalChannelType, Integer> entry : bestRank.entrySet()) {
        total += contribution(entry.getKey(), entry.getValue(), k, weights);
      }
      return total;
    }

    String source(int k, Map<RetrievalChannelType, Double> weights) {
      String channels = bestRank.entrySet().stream()
        .sorted(Comparator
          .comparingDouble((Map.Entry<RetrievalChannelType, Integer> e) ->
            contribution(e.getKey(), e.getValue(), k, weights)).reversed()
          .thenComparing(e -> e.getKey().name()))
        .map(e -> e.getKey().name().toLowerCase(java.util.Locale.ROOT))
        .collect(Collectors.joining("+"));

      return "rrf:" + channels;
    }

    private static double contribution(RetrievalChannelType channel, int rank, int k,
                                       Map<RetrievalChannelType, Double> weights) {
      return weights.getOrDefault(channel, 0.0) / (k + rank);
    }
  }
}
