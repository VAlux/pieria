package dev.alvo.pieria.model;

import dev.alvo.pieria.domain.Chunk;
import dev.alvo.pieria.domain.Classification;
import dev.alvo.pieria.domain.ExtractedCandidate;
import dev.alvo.pieria.domain.Memory;
import dev.alvo.pieria.domain.MemoryType;
import dev.alvo.pieria.domain.Message;
import dev.alvo.pieria.domain.RecallCandidate;
import dev.alvo.pieria.domain.VerificationVerdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakeModelGatewayTests {

  private final FakeModelGateway gateway = new FakeModelGateway();

  @Test
  void extractMemoriesEchoesLastUserMessageAsFact() {
    List<Message> messages = List.of(
      Message.of("s1", "user", "I prefer dark mode"),
      Message.of("s1", "assistant", "Noted."),
      Message.of("s1", "user", "And I use Postgres"));

    List<Memory> memories = gateway.extractMemories(messages);

    assertThat(memories).hasSize(1);
    Memory memory = memories.get(0);
    assertThat(memory.type()).isEqualTo(MemoryType.FACT);
    assertThat(memory.content()).isEqualTo("And I use Postgres");
    assertThat(memory.sessionId()).isEqualTo("s1");
  }

  @Test
  void extractMemoriesReturnsEmptyForEmptyInput() {
    assertThat(gateway.extractMemories(List.of())).isEmpty();
    assertThat(gateway.extractMemories(null)).isEmpty();
  }

  @Test
  void synthesizeRecallReferencesQueryAndCandidateCount() {
    Memory m = Memory.of(MemoryType.FACT, "uses Postgres", "s1", null, "{}");
    List<RecallCandidate> candidates = List.of(new RecallCandidate(m, 1.0, "fts"));

    String answer = gateway.synthesizeRecall("what db?", candidates);

    assertThat(answer).contains("what db?").contains("1 candidate");
  }

  @Test
  void synthesizeRecallHandlesNoCandidates() {
    assertThat(gateway.synthesizeRecall("anything", List.of())).contains("0 candidate");
    assertThat(gateway.synthesizeRecall("anything", null)).contains("0 candidate");
  }

  @Test
  void embedReturnsFixedLengthDeterministicVector() {
    float[] a = gateway.embed("hello");
    float[] b = gateway.embed("hello");
    float[] c = gateway.embed("different");

    assertThat(a).hasSize(FakeModelGateway.EMBEDDING_DIMENSION);
    assertThat(a).containsExactly(b); // deterministic
    assertThat(c).hasSize(FakeModelGateway.EMBEDDING_DIMENSION);
  }

  @Test
  void unavailableModeThrowsOnEveryCall() {
    gateway.setUnavailable(true);

    assertThatThrownBy(() -> gateway.extractMemories(
      List.of(Message.of("s1", "user", "x"))))
      .isInstanceOf(ModelUnavailableException.class);
    assertThatThrownBy(() -> gateway.synthesizeRecall("q", List.of()))
      .isInstanceOf(ModelUnavailableException.class);
    assertThatThrownBy(() -> gateway.embed("x"))
      .isInstanceOf(ModelUnavailableException.class);
    assertThatThrownBy(() -> gateway.extract(chunk(0, "t")))
      .isInstanceOf(ModelUnavailableException.class);
    assertThatThrownBy(() -> gateway.extractDetail(chunk(0, "t")))
      .isInstanceOf(ModelUnavailableException.class);
    assertThatThrownBy(() -> gateway.verify(
      new ExtractedCandidate("c", MemoryType.FACT, 0, null), "t"))
      .isInstanceOf(ModelUnavailableException.class);
    assertThatThrownBy(() -> gateway.classify("c"))
      .isInstanceOf(ModelUnavailableException.class);
  }

  private static Chunk chunk(int index, String transcript) {
    return new Chunk(index, 0, 0, List.of(), transcript);
  }

  @Test
  void extractEchoesChunkTranscriptAsSingleCandidate() {
    List<ExtractedCandidate> candidates = gateway.extract(chunk(2, "user: hi"));

    assertThat(candidates).hasSize(1);
    ExtractedCandidate c = candidates.get(0);
    assertThat(c.content()).isEqualTo("chunk:2:user: hi");
    assertThat(c.chunkIndex()).isEqualTo(2);
    assertThat(c.suggestedType()).isEqualTo(MemoryType.FACT);
  }

  @Test
  void extractDetailSuffixesDetailMarker() {
    List<ExtractedCandidate> candidates = gateway.extractDetail(chunk(1, "x"));

    assertThat(candidates).hasSize(1);
    assertThat(candidates.get(0).content()).isEqualTo("chunk:1:x [detail]");
  }

  @Test
  void extractReturnsEmptyForBlankTranscript() {
    assertThat(gateway.extract(chunk(0, "  "))).isEmpty();
    assertThat(gateway.extractDetail(chunk(0, null))).isEmpty();
    assertThat(gateway.extract(null)).isEmpty();
  }

  @Test
  void verifyPassesByDefaultEchoingContent() {
    var result = gateway.verify(new ExtractedCandidate("uses Postgres", MemoryType.FACT, 0, null), "t");

    assertThat(result.verdict()).isEqualTo(VerificationVerdict.PASS);
    assertThat(result.content()).isEqualTo("uses Postgres");
  }

  @Test
  void verifyDropsOnUnsupportedSentinel() {
    var result = gateway.verify(
      new ExtractedCandidate("this is UNSUPPORTED claim", MemoryType.FACT, 0, null), "t");

    assertThat(result.verdict()).isEqualTo(VerificationVerdict.DROP);
    assertThat(result.content()).isEmpty();
  }

  @Test
  void verifyCorrectsOnTypoSentinel() {
    var result = gateway.verify(
      new ExtractedCandidate("a TYPO here", MemoryType.FACT, 0, null), "t");

    assertThat(result.verdict()).isEqualTo(VerificationVerdict.CORRECT);
    assertThat(result.content()).isEqualTo("corrected: a TYPO here");
  }

  @Test
  void classifyAssignsFactWithTopicKeyAndQueries() {
    Classification c = gateway.classify("Postgres is the database");

    assertThat(c.type()).isEqualTo(MemoryType.FACT);
    assertThat(c.topicKey()).isEqualTo("topic.postgres");
    assertThat(c.interrogativeQueries()).hasSize(3);
    assertThat(c.payload()).isEqualTo("{}");
  }

  @Test
  void classifyDerivesTypeFromSentinels() {
    assertThat(gateway.classify("EVENT happened today").type()).isEqualTo(MemoryType.EVENT);
    assertThat(gateway.classify("EVENT happened today").topicKey()).isNull();
    assertThat(gateway.classify("INSTRUCTION always lint").type()).isEqualTo(MemoryType.INSTRUCTION);
    assertThat(gateway.classify("INSTRUCTION always lint").topicKey()).isNotNull();
    assertThat(gateway.classify("TASK ship release").type()).isEqualTo(MemoryType.TASK);
    assertThat(gateway.classify("TASK ship release").topicKey()).isNull();
  }
}
