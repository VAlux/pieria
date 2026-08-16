package dev.alvo.pieria.retrieval;

import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.retrieval.model.RetrievalCandidate;
import dev.alvo.pieria.retrieval.model.RetrievalChannelType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ReciprocalRankFusionTests {

  private static final int K = 60;

  private static final Map<RetrievalChannelType, Double> WEIGHTS = Map.of(
    RetrievalChannelType.EXACT_KEY, 3.0,
    RetrievalChannelType.FTS_MEMORY, 1.0,
    RetrievalChannelType.HYDE_VECTOR, 1.0,
    RetrievalChannelType.DIRECT_VECTOR, 1.0,
    RetrievalChannelType.FTS_MESSAGE, 0.5);

  private static Memory mem(String id, Instant createdAt) {
    return new Memory(id, "s1", MemoryType.FACT, "content-" + id, null, null,
      false, "{}", null, createdAt);
  }

  private static RetrievalCandidate hit(Memory m, RetrievalChannelType channel, int rank) {
    return new RetrievalCandidate(m, channel, rank, null);
  }

  private static ReciprocalRankFusion rrf() {
    return new ReciprocalRankFusion(K, WEIGHTS);
  }

  @Test
  void emptyOrNullInputYieldsEmptyList() {
    assertThat(rrf().fuse(null)).isEmpty();
    assertThat(rrf().fuse(List.of())).isEmpty();
  }

  @Test
  void singleChannelPreservesRankOrder() {
    Instant t = Instant.parse("2026-01-01T00:00:00Z");
    Memory a = mem("a", t);
    Memory b = mem("b", t);
    Memory c = mem("c", t);

    List<RecallCandidate> fused = rrf().fuse(List.of(
      hit(b, RetrievalChannelType.FTS_MEMORY, 2),
      hit(a, RetrievalChannelType.FTS_MEMORY, 1),
      hit(c, RetrievalChannelType.FTS_MEMORY, 3)));

    assertThat(fused).extracting(rc -> rc.memory().id()).containsExactly("a", "b", "c");
  }

  @Test
  void multiChannelHitOutranksEqualSingleChannelHits() {
    Instant t = Instant.parse("2026-01-01T00:00:00Z");
    Memory both = mem("both", t);
    Memory one = mem("one", t);

    // `both` hit at rank 1 by two channels; `one` hit at rank 1 by a single channel.
    List<RecallCandidate> fused = rrf().fuse(List.of(
      hit(both, RetrievalChannelType.FTS_MEMORY, 1),
      hit(both, RetrievalChannelType.DIRECT_VECTOR, 1),
      hit(one, RetrievalChannelType.FTS_MEMORY, 1)));

    assertThat(fused).extracting(rc -> rc.memory().id()).containsExactly("both", "one");
    assertThat(fused.get(0).score()).isGreaterThan(fused.get(1).score());
  }

  @Test
  void higherWeightChannelBeatsLowerAtEqualRank() {
    Instant t = Instant.parse("2026-01-01T00:00:00Z");
    Memory exact = mem("exact", t);
    Memory fts = mem("fts", t);

    List<RecallCandidate> fused = rrf().fuse(List.of(
      hit(exact, RetrievalChannelType.EXACT_KEY, 1),
      hit(fts, RetrievalChannelType.FTS_MEMORY, 1)));

    assertThat(fused.get(0).memory().id()).isEqualTo("exact");
    assertThat(fused.get(0).score()).isCloseTo(3.0 / (K + 1), within(1e-9));
    assertThat(fused.get(1).score()).isCloseTo(1.0 / (K + 1), within(1e-9));
  }

  @Test
  void scoreFormulaMatchesSpec() {
    Instant t = Instant.parse("2026-01-01T00:00:00Z");
    Memory m = mem("m", t);

    List<RecallCandidate> fused = rrf().fuse(List.of(
      hit(m, RetrievalChannelType.EXACT_KEY, 1),
      hit(m, RetrievalChannelType.FTS_MEMORY, 3)));

    double expected = 3.0 / (K + 1) + 1.0 / (K + 3);
    assertThat(fused.get(0).score()).isCloseTo(expected, within(1e-9));
  }

  @Test
  void smallerKWeightsTopRanksMoreHeavily() {
    Instant t = Instant.parse("2026-01-01T00:00:00Z");
    Memory m = mem("m", t);
    var hits = List.of(hit(m, RetrievalChannelType.FTS_MEMORY, 1));

    double small = new ReciprocalRankFusion(1, WEIGHTS).fuse(hits).get(0).score();
    double large = new ReciprocalRankFusion(60, WEIGHTS).fuse(hits).get(0).score();

    assertThat(small).isGreaterThan(large);
  }

  @Test
  void missingChannelWeightDefaultsToZero() {
    // only FTS_MESSAGE weighted; an unweighted channel contributes nothing.
    var custom = new ReciprocalRankFusion(K, Map.of(RetrievalChannelType.FTS_MESSAGE, 0.5));
    Memory m = mem("m", Instant.parse("2026-01-01T00:00:00Z"));

    List<RecallCandidate> fused = custom.fuse(List.of(
      hit(m, RetrievalChannelType.DIRECT_VECTOR, 1)));

    assertThat(fused.get(0).score()).isZero();
  }

  @Test
  void tieBreakByRecencyThenId() {
    // All hit at the same channel/rank -> identical scores -> deterministic tie-break.
    Instant older = Instant.parse("2026-01-01T00:00:00Z");
    Instant newer = Instant.parse("2026-02-01T00:00:00Z");
    Memory zNewer = mem("z", newer);
    Memory aNewer = mem("a", newer);
    Memory mOlder = mem("m", older);

    List<RecallCandidate> fused = rrf().fuse(List.of(
      hit(mOlder, RetrievalChannelType.FTS_MEMORY, 1),
      hit(zNewer, RetrievalChannelType.FTS_MEMORY, 1),
      hit(aNewer, RetrievalChannelType.FTS_MEMORY, 1)));

    // newer first; among the two newer, id ascending (a before z); older last.
    assertThat(fused).extracting(rc -> rc.memory().id()).containsExactly("a", "z", "m");
  }

  @Test
  void recencyMeansWhenStatedNotWhenStored() {
    // A back-filled 2023 transcript ingested today: newest in the store, oldest in the conversation.
    Memory backFilled = stated("b", "2026-06-01T00:00:00Z", "2023-05-25T00:00:00Z");
    Memory current = stated("a", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");

    List<RecallCandidate> fused = rrf().fuse(List.of(
      hit(backFilled, RetrievalChannelType.FTS_MEMORY, 1),
      hit(current, RetrievalChannelType.FTS_MEMORY, 1)));

    // Ranking on store time would put the back-filled memory first purely for arriving last.
    assertThat(fused).extracting(rc -> rc.memory().id()).containsExactly("a", "b");
  }

  @Test
  void memoriesWithoutAStatedTimeStillRankByStoreTime() {
    Memory older = mem("m", Instant.parse("2026-01-01T00:00:00Z"));
    Memory newer = mem("z", Instant.parse("2026-02-01T00:00:00Z"));

    List<RecallCandidate> fused = rrf().fuse(List.of(
      hit(older, RetrievalChannelType.FTS_MEMORY, 1),
      hit(newer, RetrievalChannelType.FTS_MEMORY, 1)));

    assertThat(fused).extracting(rc -> rc.memory().id()).containsExactly("z", "m");
  }

  /** A memory stored at one time and stated at another, the way a replayed transcript arrives. */
  private static Memory stated(String id, String createdAt, String statedAt) {
    return new Memory(id, "s1", MemoryType.FACT, "content-" + id, null, null, false,
      "{\"stated_at\":\"" + statedAt + "\"}", null, Instant.parse(createdAt));
  }

  @Test
  void duplicateSameChannelHitsUseBestRank() {
    Instant t = Instant.parse("2026-01-01T00:00:00Z");
    Memory m = mem("m", t);

    List<RecallCandidate> fused = rrf().fuse(List.of(
      hit(m, RetrievalChannelType.FTS_MEMORY, 5),
      hit(m, RetrievalChannelType.FTS_MEMORY, 2)));

    // best (lowest) rank wins => 1.0/(60+2)
    assertThat(fused.get(0).score()).isCloseTo(1.0 / (K + 2), within(1e-9));
  }

  @Test
  void sourceListsContributingChannelsByContributionThenName() {
    Instant t = Instant.parse("2026-01-01T00:00:00Z");
    Memory m = mem("m", t);

    // exact_key (weight 3) at rank 1 contributes more than fts_memory (weight 1) at rank 1.
    List<RecallCandidate> fused = rrf().fuse(List.of(
      hit(m, RetrievalChannelType.FTS_MEMORY, 1),
      hit(m, RetrievalChannelType.EXACT_KEY, 1)));

    assertThat(fused.get(0).source()).isEqualTo("rrf:exact_key+fts_memory");
  }

  @Test
  void sourceForSingleChannel() {
    Instant t = Instant.parse("2026-01-01T00:00:00Z");
    Memory m = mem("m", t);
    List<RecallCandidate> fused = rrf().fuse(List.of(
      hit(m, RetrievalChannelType.DIRECT_VECTOR, 1)));
    assertThat(fused.get(0).source()).isEqualTo("rrf:direct_vector");
  }

  @Test
  void nullCandidateAndNullMemoryAreSkipped() {
    Instant t = Instant.parse("2026-01-01T00:00:00Z");
    Memory m = mem("m", t);
    var nullMem = new RetrievalCandidate(null, RetrievalChannelType.FTS_MEMORY, 1, null);

    List<RecallCandidate> fused = rrf().fuse(java.util.Arrays.asList(
      null, nullMem, hit(m, RetrievalChannelType.FTS_MEMORY, 1)));

    assertThat(fused).hasSize(1);
    assertThat(fused.get(0).memory().id()).isEqualTo("m");
  }

  @Test
  void invalidKRejected() {
    assertThatThrownBy(() -> new ReciprocalRankFusion(0, WEIGHTS))
      .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nullWeightsTreatedAsAllZero() {
    var custom = new ReciprocalRankFusion(K, null);
    Memory m = mem("m", Instant.parse("2026-01-01T00:00:00Z"));
    List<RecallCandidate> fused = custom.fuse(List.of(hit(m, RetrievalChannelType.EXACT_KEY, 1)));
    assertThat(fused.get(0).score()).isZero();
  }

  @Test
  void fullyDeterministicForFixedInput() {
    Instant t = Instant.parse("2026-01-01T00:00:00Z");
    Memory a = mem("a", t);
    Memory b = mem("b", t);
    var input = List.of(
      hit(a, RetrievalChannelType.FTS_MEMORY, 1),
      hit(b, RetrievalChannelType.EXACT_KEY, 2),
      hit(a, RetrievalChannelType.HYDE_VECTOR, 3));

    List<RecallCandidate> first = rrf().fuse(input);
    List<RecallCandidate> second = rrf().fuse(input);

    assertThat(first).usingRecursiveComparison().isEqualTo(second);
  }
}
