package dev.alvo.pieria.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Fixed-vector tests for {@link ContentId}.
 *
 * <p>The expected hex strings below are HARD-CODED on purpose. Content-addressed ids are persisted
 * and used for idempotent insert-or-ignore: if the hashing scheme ever changes, previously stored
 * rows would no longer collide with re-ingested content and ingestion would silently duplicate.
 * Pinning the exact output means any change to id semantics breaks these tests loudly instead.
 */
class ContentIdTests {

  @Test
  void messageIdMatchesFixedVector() {
    assertThat(ContentId.forMessage("s1", "user", "hello"))
      .isEqualTo("8ce1155ea603cf102780b91fec225f1a");
  }

  @Test
  void threeArgMemoryIdMatchesFixedVector() {
    assertThat(ContentId.forMemory("s1", MemoryType.FACT, "The sky is blue"))
      .isEqualTo("98f9392f6c0981f187c5a9fbf95959d4");
  }

  @Test
  void fullMemoryIdMatchesFixedVector() {
    assertThat(ContentId.forMemory("s1", MemoryType.FACT, "content", "topic.key", "{\"k\":1}"))
      .isEqualTo("26c1b6636cdbf1935bd3f2b00e8134b6");
  }

  @Test
  void distinctTopicKeyProducesDistinctId() {
    String a = ContentId.forMemory("s1", MemoryType.FACT, "content", "topic.key", "{\"k\":1}");
    String b = ContentId.forMemory("s1", MemoryType.FACT, "content", "other.key", "{\"k\":1}");
    assertThat(a).isNotEqualTo(b);
    assertThat(b).isEqualTo("ff7226017fac5b425a2969a93077a7ab");
  }

  @Test
  void distinctPayloadProducesDistinctId() {
    String a = ContentId.forMemory("s1", MemoryType.FACT, "content", "topic.key", "{\"k\":1}");
    String b = ContentId.forMemory("s1", MemoryType.FACT, "content", "topic.key", "{\"k\":2}");
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void threeArgEqualsFullWithNullExtras() {
    // Backwards compatibility: empty/absent topicKey+payload must not change the historical id.
    assertThat(ContentId.forMemory("s1", MemoryType.FACT, "content"))
      .isEqualTo(ContentId.forMemory("s1", MemoryType.FACT, "content", null, null));
  }
}
