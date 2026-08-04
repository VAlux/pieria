package dev.alvo.pieria.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextSimilarityTests {

  @Test
  void identicalTextScoresOne() {
    assertThat(TextSimilarity.similarity("the daemon binds to localhost", "the daemon binds to localhost"))
      .isEqualTo(1.0);
  }

  @Test
  void punctuationAndCaseAreNotContentDifferences() {
    assertThat(TextSimilarity.similarity(
      "Use JDK 21 for all repositories. RAG modules require a vector database.",
      "use jdk 21 for all repositories; rag modules require a vector database"))
      .isEqualTo(1.0);
  }

  @Test
  void unrelatedTextScoresZero() {
    assertThat(TextSimilarity.similarity("the daemon binds to localhost", "flyway runs the migrations"))
      .isZero();
  }

  // The real drift this exists to catch: the same fact re-extracted with different wording.
  @Test
  void paraphrasesOfTheSameStatementScoreHigh() {
    double score = TextSimilarity.similarity(
      "The Pieria daemon MCP is the primary long-term knowledge base for durable facts, "
        + "preferences, project context, and decisions likely to help in future sessions.",
      "The Pieria daemon MCP is treated as the primary long-term knowledge base for durable facts, "
        + "preferences, project context, and decisions likely to help in future sessions.");

    assertThat(score).isGreaterThan(0.7);
  }

  // Trigrams, not bare tokens: same vocabulary rearranged is a different statement.
  @Test
  void reorderedWordsAreNotTreatedAsTheSameStatement() {
    assertThat(TextSimilarity.similarity(
      "the daemon writes and the gateway reads",
      "the gateway writes and the daemon reads"))
      .isLessThan(0.5);
  }

  // Structurally identical sentences about different subjects must stay apart — the false positive
  // that showed up between two code-index memories describing different source files.
  @Test
  void sameShapeDifferentSubjectStaysBelowTheDuplicateThreshold() {
    double score = TextSimilarity.similarity(
      "Source file onboarding/TextDiscovery.java defines class Doc and method scan",
      "Source file onboarding/PdfDiscovery.java defines class Doc and method scan");

    assertThat(score).isLessThan(0.7);
  }

  @Test
  void blankAndNullTextsCompareToZero() {
    assertThat(TextSimilarity.similarity(null, "anything")).isZero();
    assertThat(TextSimilarity.similarity("   ", "anything")).isZero();
    assertThat(TextSimilarity.similarity(null, null)).isZero();
  }

  @Test
  void textsShorterThanATrigramCompareAsTokenSets() {
    assertThat(TextSimilarity.similarity("sqlite backend", "sqlite backend")).isEqualTo(1.0);
    assertThat(TextSimilarity.similarity("sqlite backend", "postgres backend")).isEqualTo(1.0 / 3);
  }

  @Test
  void shinglesAreReusableForOneAgainstMany() {
    assertThat(TextSimilarity.jaccard(
      TextSimilarity.shingles("the daemon binds to localhost by default"),
      TextSimilarity.shingles("the daemon binds to localhost by default")))
      .isEqualTo(1.0);
    assertThat(TextSimilarity.jaccard(TextSimilarity.shingles(""), TextSimilarity.shingles("x y z")))
      .isZero();
  }
}
