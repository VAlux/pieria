package dev.alvo.pieria.retrieval.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire form is what a small model types, so parsing must be forgiving in one direction only:
 * anything unrecognised becomes RELATED, which reorders nothing and drops nothing.
 */
class RerankLabelTests {

  @Test
  void parsesTheThreeLabelsCaseAndWhitespaceInsensitively() {
    assertThat(RerankLabel.fromWire("essential")).isEqualTo(RerankLabel.ESSENTIAL);
    assertThat(RerankLabel.fromWire("  ESSENTIAL ")).isEqualTo(RerankLabel.ESSENTIAL);
    assertThat(RerankLabel.fromWire("Related")).isEqualTo(RerankLabel.RELATED);
    assertThat(RerankLabel.fromWire("IRRELEVANT")).isEqualTo(RerankLabel.IRRELEVANT);
  }

  @Test
  void unknownNullAndBlankAllBecomeRelated() {
    assertThat(RerankLabel.fromWire("banana")).isEqualTo(RerankLabel.RELATED);
    assertThat(RerankLabel.fromWire(null)).isEqualTo(RerankLabel.RELATED);
    assertThat(RerankLabel.fromWire("   ")).isEqualTo(RerankLabel.RELATED);
    assertThat(RerankLabel.fromWire("9")).isEqualTo(RerankLabel.RELATED);
  }
}
