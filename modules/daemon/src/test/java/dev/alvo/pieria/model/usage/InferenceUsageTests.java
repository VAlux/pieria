package dev.alvo.pieria.model.usage;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit coverage for the inference-usage tier mapping, accumulator, and thread-bound sink. */
class InferenceUsageTests {

  @Test
  void forStageMapsEveryPipelineStageToItsTier() {
    for (String stage : new String[] {"extract", "extractDetail", "verify", "classify", "extractGraph", "analyzeQuery"}) {
      assertEquals(InferenceTier.EXTRACTION, InferenceTier.forStage(stage), stage);
    }
    assertEquals(InferenceTier.SYNTHESIS, InferenceTier.forStage("synthesizeRecall"));
    assertEquals(InferenceTier.SYNTHESIS, InferenceTier.forStage("judgeAnswerFaithfulness"));
    assertEquals(InferenceTier.EMBEDDING, InferenceTier.forStage("embed"));
    // Unknown / null stages fall back to the structured-pipeline default.
    assertEquals(InferenceTier.EXTRACTION, InferenceTier.forStage("somethingNew"));
    assertEquals(InferenceTier.EXTRACTION, InferenceTier.forStage(null));
  }

  @Test
  void snapshotOmitsTiersWithNoActivity() {
    InferenceUsageAccumulator acc = new InferenceUsageAccumulator();
    acc.add(InferenceTier.SYNTHESIS, 30, 12, 1);

    Map<InferenceTier, TierUsage> snapshot = acc.snapshot();
    assertEquals(1, snapshot.size());
    TierUsage synthesis = snapshot.get(InferenceTier.SYNTHESIS);
    assertEquals(1, synthesis.calls());
    assertEquals(30, synthesis.promptTokens());
    assertEquals(12, synthesis.completionTokens());
    assertEquals(42, synthesis.totalTokens());
  }

  @Test
  void addAccumulatesConcurrentlyWithoutLosingUpdates() throws Exception {
    InferenceUsageAccumulator acc = new InferenceUsageAccumulator();
    int threads = 16;
    int perThread = 1_000;

    try (ExecutorService exec = Executors.newFixedThreadPool(threads)) {
      Future<?>[] futures = new Future<?>[threads];
      for (int t = 0; t < threads; t++) {
        futures[t] = exec.submit(() -> {
          for (int i = 0; i < perThread; i++) {
            acc.add(InferenceTier.EXTRACTION, 2, 1, 1);
          }
        });
      }
      for (Future<?> f : futures) {
        f.get();
      }
    }

    TierUsage extraction = acc.snapshot().get(InferenceTier.EXTRACTION);
    long expectedCalls = (long) threads * perThread;
    assertEquals(expectedCalls, extraction.calls());
    assertEquals(expectedCalls * 2, extraction.promptTokens());
    assertEquals(expectedCalls, extraction.completionTokens());
  }

  @Test
  void sinkRoutesWritesToTheBoundAccumulatorAndRestoresOnClose() {
    InferenceUsageAccumulator acc = new InferenceUsageAccumulator();

    // Nothing bound: current() is the shared no-op sink, not our accumulator.
    assertFalse(acc == InferenceUsageSink.current());

    try (InferenceUsageSink.Binding ignored = InferenceUsageSink.bind(acc)) {
      assertSame(acc, InferenceUsageSink.current());
      InferenceUsageSink.current().add(InferenceTier.EMBEDDING, 5, 0, 1);
    }

    // Binding restored (back to the no-op) once the scope closes.
    assertFalse(acc == InferenceUsageSink.current());
    assertEquals(5, acc.snapshot().get(InferenceTier.EMBEDDING).promptTokens());
  }

  @Test
  void sinkBindingsNestAndRestorePrevious() {
    InferenceUsageAccumulator outer = new InferenceUsageAccumulator();
    InferenceUsageAccumulator inner = new InferenceUsageAccumulator();

    try (InferenceUsageSink.Binding ignoredOuter = InferenceUsageSink.bind(outer)) {
      assertSame(outer, InferenceUsageSink.current());
      try (InferenceUsageSink.Binding ignoredInner = InferenceUsageSink.bind(inner)) {
        assertSame(inner, InferenceUsageSink.current());
      }
      // Inner scope closed: the outer binding is restored, not cleared.
      assertSame(outer, InferenceUsageSink.current());
    }
    assertTrue(outer.snapshot().isEmpty());
  }
}
