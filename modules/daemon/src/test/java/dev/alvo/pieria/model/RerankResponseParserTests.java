package dev.alvo.pieria.model;

import dev.alvo.pieria.retrieval.model.RerankLabel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parsing is index-addressed rather than positional, because a small model reorders lines, skips
 * some, and occasionally prefixes prose. Anything it fails to say becomes RELATED — the label that
 * changes nothing — and a reply with nothing parseable at all becomes "no signal".
 */
class RerankResponseParserTests {

  @Test
  void parsesOneLabelPerNumberedLine() {
    String raw = """
      1 essential
      2 irrelevant
      3 related
      """;

    assertThat(OpenAiModelGateway.parseRerankLabels(raw, 3))
      .containsExactly(RerankLabel.ESSENTIAL, RerankLabel.IRRELEVANT, RerankLabel.RELATED);
  }

  @Test
  void toleratesSeparatorsBlankLinesAndSurroundingProse() {
    String raw = """
      Here are the labels:

      1: essential
      2 - irrelevant

      3\tessential
      Hope that helps.
      """;

    assertThat(OpenAiModelGateway.parseRerankLabels(raw, 3))
      .containsExactly(RerankLabel.ESSENTIAL, RerankLabel.IRRELEVANT, RerankLabel.ESSENTIAL);
  }

  @Test
  void missingIndicesDefaultToRelated() {
    String raw = "2 essential\n";

    assertThat(OpenAiModelGateway.parseRerankLabels(raw, 3))
      .containsExactly(RerankLabel.RELATED, RerankLabel.ESSENTIAL, RerankLabel.RELATED);
  }

  @Test
  void unknownLabelTokensDefaultToRelated() {
    String raw = "1 critical\n2 irrelevant\n";

    assertThat(OpenAiModelGateway.parseRerankLabels(raw, 2))
      .containsExactly(RerankLabel.RELATED, RerankLabel.IRRELEVANT);
  }

  @Test
  void outOfRangeIndicesAreIgnoredRatherThanShiftingTheRest() {
    String raw = "0 essential\n1 essential\n9 irrelevant\n";

    assertThat(OpenAiModelGateway.parseRerankLabels(raw, 2))
      .containsExactly(RerankLabel.ESSENTIAL, RerankLabel.RELATED);
  }

  @Test
  void aReplyWithNothingParseableIsNoSignal() {
    assertThat(OpenAiModelGateway.parseRerankLabels("I cannot help with that.", 3)).isEmpty();
    assertThat(OpenAiModelGateway.parseRerankLabels("", 3)).isEmpty();
    assertThat(OpenAiModelGateway.parseRerankLabels(null, 3)).isEmpty();
  }

  @Test
  void aDuplicateIndexTakesTheFirstLabel() {
    String raw = "1 essential\n1 irrelevant\n2 related\n";

    assertThat(OpenAiModelGateway.parseRerankLabels(raw, 2))
      .containsExactly(RerankLabel.ESSENTIAL, RerankLabel.RELATED);
  }
}
