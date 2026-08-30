package dev.alvo.pieria.retrieval;

import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.retrieval.model.RetrievalCandidate;
import dev.alvo.pieria.retrieval.model.RetrievalChannelType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceRecallBoostTests {

  private static final Memory TRACE =
    memory("t1", "`./gradlew test` succeeded (exit 0)", "{\"source\":\"trace\"}");
  private static final Memory CHAT = memory("c1", "We should run the tests.", "{}");

  private static Memory memory(String id, String content, String payload) {
    return new Memory(id, "s1", MemoryType.EVENT, content, null, null, false, payload, content, null);
  }

  private static Map<RetrievalChannelType, Double> weights() {
    return Map.of(RetrievalChannelType.FTS_MEMORY, 1.0);
  }

  // Default 1.0 must leave ranking exactly as it is today.
  @Test
  void aBoostOfOneChangesNothing() {
    List<RetrievalCandidate> hits = List.of(
      new RetrievalCandidate(CHAT, RetrievalChannelType.FTS_MEMORY, 1, null),
      new RetrievalCandidate(TRACE, RetrievalChannelType.FTS_MEMORY, 2, null));

    List<RecallCandidate> unboosted = new ReciprocalRankFusion(60, weights()).fuse(hits);
    List<RecallCandidate> explicit = new ReciprocalRankFusion(60, weights(), 1.0).fuse(hits);

    assertThat(explicit.stream().map(c -> c.memory().id()).toList())
      .isEqualTo(unboosted.stream().map(c -> c.memory().id()).toList());
  }

  @Test
  void aBoostAboveOneLiftsTraceCandidates() {
    List<RetrievalCandidate> hits = List.of(
      new RetrievalCandidate(CHAT, RetrievalChannelType.FTS_MEMORY, 1, null),
      new RetrievalCandidate(TRACE, RetrievalChannelType.FTS_MEMORY, 2, null));

    List<RecallCandidate> boosted = new ReciprocalRankFusion(60, weights(), 3.0).fuse(hits);

    assertThat(boosted.getFirst().memory().id()).isEqualTo("t1");
  }

  @Test
  void nonTraceCandidatesAreNeverBoosted() {
    List<RecallCandidate> boosted = new ReciprocalRankFusion(60, weights(), 3.0)
      .fuse(List.of(new RetrievalCandidate(CHAT, RetrievalChannelType.FTS_MEMORY, 1, null)));
    List<RecallCandidate> plain = new ReciprocalRankFusion(60, weights(), 1.0)
      .fuse(List.of(new RetrievalCandidate(CHAT, RetrievalChannelType.FTS_MEMORY, 1, null)));

    assertThat(boosted.getFirst().score()).isEqualTo(plain.getFirst().score());
  }

  @Test
  void aMalformedPayloadIsTreatedAsNonTrace() {
    Memory broken = memory("b1", "x", "not json at all");

    List<RecallCandidate> boosted = new ReciprocalRankFusion(60, weights(), 3.0)
      .fuse(List.of(new RetrievalCandidate(broken, RetrievalChannelType.FTS_MEMORY, 1, null)));
    List<RecallCandidate> plain = new ReciprocalRankFusion(60, weights(), 1.0)
      .fuse(List.of(new RetrievalCandidate(broken, RetrievalChannelType.FTS_MEMORY, 1, null)));

    assertThat(boosted.getFirst().score()).isEqualTo(plain.getFirst().score());
  }
}
