package dev.alvo.pieria.model.usage;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe accumulator for real inference token usage over one ingest or recall operation,
 * keyed by {@link InferenceTier}. Many virtual-thread workers add concurrently (ingestion fans
 * extraction/verify out across a virtual-thread-per-task executor), so each counter is a striped
 * {@link LongAdder}. The map structure is fixed at construction (one entry per tier) and never
 * mutated afterward, so concurrent {@link #add} and {@link #snapshot} are safe without locking.
 */
public final class InferenceUsageAccumulator {

  private final Map<InferenceTier, Counters> counters;

  public InferenceUsageAccumulator() {
    EnumMap<InferenceTier, Counters> initial = new EnumMap<>(InferenceTier.class);
    for (InferenceTier tier : InferenceTier.values()) {
      initial.put(tier, new Counters());
    }
    this.counters = initial;
  }

  /**
   * Add one model call's reported usage to {@code tier}.
   */
  public void add(InferenceTier tier, long promptTokens, long completionTokens, long calls) {
    Counters c = counters.get(tier);
    c.prompt.add(promptTokens);
    c.completion.add(completionTokens);
    c.calls.add(calls);
  }

  /**
   * An immutable per-tier snapshot of what has accumulated so far. Tiers with no recorded activity
   * are omitted so the snapshot carries only tiers actually used.
   */
  public Map<InferenceTier, TierUsage> snapshot() {
    EnumMap<InferenceTier, TierUsage> out = new EnumMap<>(InferenceTier.class);
    counters.forEach((tier, c) -> {
      long calls = c.calls.sum();
      long prompt = c.prompt.sum();
      long completion = c.completion.sum();
      if (calls != 0 || prompt != 0 || completion != 0) {
        out.put(tier, new TierUsage(calls, prompt, completion));
      }
    });
    return out;
  }

  private static final class Counters {
    private final LongAdder prompt = new LongAdder();
    private final LongAdder completion = new LongAdder();
    private final LongAdder calls = new LongAdder();
  }
}
