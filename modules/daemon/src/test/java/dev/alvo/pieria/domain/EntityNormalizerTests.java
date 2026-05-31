package dev.alvo.pieria.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.alvo.pieria.domain.graph.EntityNormalizer;
import org.junit.jupiter.api.Test;

/**
 * Deterministic-normalization tests for {@link EntityNormalizer}. Normalization runs before
 * content-addressed id computation, so surface variants must collapse to one stable form.
 */
class EntityNormalizerTests {

  @Test
  void nameTrimsCollapsesAndLowercases() {
    assertThat(EntityNormalizer.normalizeName("  Redis   Cluster ")).isEqualTo("redis cluster");
    assertThat(EntityNormalizer.normalizeName("REDIS")).isEqualTo("redis");
  }

  @Test
  void nameAppliesAliases() {
    assertThat(EntityNormalizer.normalizeName("Postgres")).isEqualTo("postgresql");
    assertThat(EntityNormalizer.normalizeName("pg")).isEqualTo("postgresql");
    assertThat(EntityNormalizer.normalizeName("JS")).isEqualTo("javascript");
  }

  @Test
  void blankNameNormalizesToEmpty() {
    assertThat(EntityNormalizer.normalizeName(null)).isEmpty();
    assertThat(EntityNormalizer.normalizeName("   ")).isEmpty();
  }

  @Test
  void typeFallsBackToConcept() {
    assertThat(EntityNormalizer.normalizeType(null)).isEqualTo("concept");
    assertThat(EntityNormalizer.normalizeType("  ")).isEqualTo("concept");
    assertThat(EntityNormalizer.normalizeType("Tool")).isEqualTo("tool");
  }

  @Test
  void relationCollapsesAndLowercases() {
    assertThat(EntityNormalizer.normalizeRelation("  Depends   On ")).isEqualTo("depends on");
    assertThat(EntityNormalizer.normalizeRelation(null)).isEmpty();
  }

  @Test
  void normalizationIsIdempotent() {
    String once = EntityNormalizer.normalizeName("Postgres DB");
    assertThat(EntityNormalizer.normalizeName(once)).isEqualTo(once);
  }
}
