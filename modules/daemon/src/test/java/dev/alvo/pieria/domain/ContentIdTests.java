package dev.alvo.pieria.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.alvo.pieria.domain.memory.MemoryType;
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
  void profileScopedMessageIdMatchesFixedVectorAndVariesByProfile() {
    String id = ContentId.forMessage("prof-1", "s1", "user", "hello");

    assertThat(id).isEqualTo("f1062cb2308f2c360236a44f50ce208c");
    assertThat(id).isNotEqualTo(ContentId.forMessage("prof-2", "s1", "user", "hello"));
  }

  @Test
  void profileScopedMemoryIdMatchesFixedVectorAndVariesByProfile() {
    String id = ContentId.forMemory("prof-1", "s1", MemoryType.FACT, "The sky is blue");

    assertThat(id).isEqualTo("d56d6fa1b3a564d80959942caad78617");
    assertThat(id).isNotEqualTo(
      ContentId.forMemory("prof-2", "s1", MemoryType.FACT, "The sky is blue"));
  }

  @Test
  void entityIdMatchesFixedVector() {
    assertThat(ContentId.forEntity("prof-1", "tool", "redis"))
      .isEqualTo("309dc61a412ad7afe25b2ce6c8732215");
  }

  @Test
  void edgeIdMatchesFixedVector() {
    assertThat(ContentId.forEdge("prof-1", "src-id", "uses", "tgt-id", "mem-id"))
      .isEqualTo("b7df579e3652d6c24deec2d154dd4891");
  }

  @Test
  void entityIdVariesByTypeAndName() {
    String base = ContentId.forEntity("prof-1", "tool", "redis");
    assertThat(base).isNotEqualTo(ContentId.forEntity("prof-1", "project", "redis"));
    assertThat(base).isNotEqualTo(ContentId.forEntity("prof-1", "tool", "postgres"));
    assertThat(base).isNotEqualTo(ContentId.forEntity("prof-2", "tool", "redis"));
  }

  @Test
  void edgeIdVariesByMemoryProvenance() {
    // Same triple grounded in a different memory is a distinct edge (provenance is part of identity).
    String a = ContentId.forEdge("prof-1", "src-id", "uses", "tgt-id", "mem-1");
    String b = ContentId.forEdge("prof-1", "src-id", "uses", "tgt-id", "mem-2");
    assertThat(a).isNotEqualTo(b);
  }
}
