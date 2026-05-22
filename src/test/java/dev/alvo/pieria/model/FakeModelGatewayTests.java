package dev.alvo.pieria.model;

import dev.alvo.pieria.domain.Memory;
import dev.alvo.pieria.domain.MemoryType;
import dev.alvo.pieria.domain.Message;
import dev.alvo.pieria.domain.RecallCandidate;
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
  }
}
