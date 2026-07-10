package dev.alvo.pieria.model;

import dev.alvo.pieria.ingestion.model.Chunk;
import dev.alvo.pieria.ingestion.model.Classification;
import dev.alvo.pieria.ingestion.model.UnifiedCandidate;
import dev.alvo.pieria.domain.memory.Memory;
import dev.alvo.pieria.domain.memory.MemoryType;
import dev.alvo.pieria.retrieval.model.RecallCandidate;
import dev.alvo.pieria.ingestion.model.VerificationVerdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakeModelGatewayTests {

  private final FakeModelGateway gateway = new FakeModelGateway();

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

    assertThatThrownBy(() -> gateway.synthesizeRecall("q", List.of()))
      .isInstanceOf(ModelUnavailableException.class);
    assertThatThrownBy(() -> gateway.embed("x"))
      .isInstanceOf(ModelUnavailableException.class);
    assertThatThrownBy(() -> gateway.extractUnified(chunk(0, "t")))
      .isInstanceOf(ModelUnavailableException.class);
    assertThatThrownBy(() -> gateway.verify("c", "t"))
      .isInstanceOf(ModelUnavailableException.class);
    assertThatThrownBy(() -> gateway.classify("c"))
      .isInstanceOf(ModelUnavailableException.class);
  }

  private static Chunk chunk(int index, String transcript) {
    return new Chunk(index, 0, 0, List.of(), transcript);
  }

  @Test
  void extractUnifiedEchoesChunkTranscriptAsSingleClassifiedCandidate() {
    List<UnifiedCandidate> candidates = gateway.extractUnified(chunk(2, "user: hi"));

    assertThat(candidates).hasSize(1);
    UnifiedCandidate c = candidates.get(0);
    assertThat(c.content()).isEqualTo("chunk:2:user: hi");
    assertThat(c.chunkIndex()).isEqualTo(2);
    assertThat(c.classification().type()).isEqualTo(MemoryType.FACT);
    assertThat(c.classification().interrogativeQueries()).hasSize(3);
  }

  @Test
  void extractUnifiedReturnsEmptyForBlankTranscript() {
    assertThat(gateway.extractUnified(chunk(0, "  "))).isEmpty();
    assertThat(gateway.extractUnified(chunk(0, null))).isEmpty();
    assertThat(gateway.extractUnified(null)).isEmpty();
  }

  @Test
  void verifyPassesByDefaultEchoingContent() {
    var result = gateway.verify("uses Postgres", "t");

    assertThat(result.verdict()).isEqualTo(VerificationVerdict.PASS);
    assertThat(result.content()).isEqualTo("uses Postgres");
  }

  @Test
  void verifyDropsOnUnsupportedSentinel() {
    var result = gateway.verify("this is UNSUPPORTED claim", "t");

    assertThat(result.verdict()).isEqualTo(VerificationVerdict.DROP);
    assertThat(result.content()).isEmpty();
  }

  @Test
  void verifyCorrectsOnTypoSentinel() {
    var result = gateway.verify("a TYPO here", "t");

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
  void analyzeQueryDerivesTermsTopicKeyAndHydeStatement() {
    var analysis = gateway.analyzeQuery("Which editor do I use?");

    assertThat(analysis.ftsTerms()).contains("which", "editor", "do", "use");
    assertThat(analysis.topicKeys()).containsExactly("topic.which");
    assertThat(analysis.hydeStatement()).isEqualTo("answer: Which editor do I use?");
  }

  @Test
  void analyzeQueryIsDeterministicAndEmptyForBlank() {
    assertThat(gateway.analyzeQuery("same query")).isEqualTo(gateway.analyzeQuery("same query"));
    var empty = gateway.analyzeQuery("   ");
    assertThat(empty.ftsTerms()).isEmpty();
    assertThat(empty.topicKeys()).isEmpty();
    assertThat(empty.hydeStatement()).isNull();
  }

  @Test
  void analyzeQueryThrowsWhenUnavailable() {
    gateway.setUnavailable(true);
    assertThatThrownBy(() -> gateway.analyzeQuery("q"))
      .isInstanceOf(ModelUnavailableException.class);
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
