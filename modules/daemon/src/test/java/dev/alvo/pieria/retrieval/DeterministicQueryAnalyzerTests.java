package dev.alvo.pieria.retrieval;

import dev.alvo.pieria.retrieval.model.QueryAnalysis;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicQueryAnalyzerTests {

  private final DeterministicQueryAnalyzer analyzer = new DeterministicQueryAnalyzer();

  @Test
  void tokenizesLowercasesAndSplitsOnNonAlphanumerics() {
    QueryAnalysis a = analyzer.analyze("Editor-Settings, theme!");

    assertThat(a.ftsTerms()).containsExactly("editor", "settings", "theme");
  }

  @Test
  void removesStopwordsAndShortTokens() {
    QueryAnalysis a = analyzer.analyze("What is the editor I use?");

    // "what", "is", "the", "i" are stopwords; "i" is also too short. "use" survives.
    assertThat(a.ftsTerms()).containsExactly("editor", "use");
  }

  @Test
  void deduplicatesTerms() {
    QueryAnalysis a = analyzer.analyze("editor editor editor settings");

    assertThat(a.ftsTerms()).containsExactly("editor", "settings");
  }

  @Test
  void derivesTopicKeysFullFirstAndPairs() {
    QueryAnalysis a = analyzer.analyze("user editor theme");

    assertThat(a.topicKeys()).containsExactly(
      "user.editor.theme",  // full key
      "user",               // first-token key
      "user.editor",        // adjacent pair
      "editor.theme");      // adjacent pair
  }

  @Test
  void singleTokenYieldsSingleTopicKey() {
    QueryAnalysis a = analyzer.analyze("Postgres?");

    assertThat(a.ftsTerms()).containsExactly("postgres");
    assertThat(a.topicKeys()).containsExactly("postgres");
  }

  @Test
  void neverProducesHydeStatement() {
    assertThat(analyzer.analyze("anything goes here").hydeStatement()).isNull();
  }

  @Test
  void blankOrNullQueryYieldsEmptyAnalysis() {
    for (String q : new String[]{null, "", "   "}) {
      QueryAnalysis a = analyzer.analyze(q);
      assertThat(a.ftsTerms()).isEmpty();
      assertThat(a.topicKeys()).isEmpty();
      assertThat(a.hydeStatement()).isNull();
    }
  }

  @Test
  void queryOfOnlyStopwordsYieldsEmptyAnalysis() {
    QueryAnalysis a = analyzer.analyze("what is the");

    assertThat(a.ftsTerms()).isEmpty();
    assertThat(a.topicKeys()).isEmpty();
  }

  @Test
  void isDeterministicForSameInput() {
    QueryAnalysis a = analyzer.analyze("which editor do I use for Java?");
    QueryAnalysis b = analyzer.analyze("which editor do I use for Java?");

    assertThat(a).isEqualTo(b);
  }
}
