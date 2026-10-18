package dev.alvo.pieria.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FtsQueryTests {

  @Test
  void quotesEveryTokenAsAPrefix() {
    assertEquals("\"web\"* AND \"console\"*", FtsQuery.allPrefixTerms("Web console"));
  }

  @Test
  void keepsIdentifierPunctuationAndQuotesOperatorsAsWords() {
    assertEquals("\"rrf-k\"* AND \"or\"* AND \"top_k\"*", FtsQuery.allPrefixTerms("rrf-k OR: top_k*"));
  }

  @Test
  void returnsNullWithoutTokens() {
    assertNull(FtsQuery.allPrefixTerms("  ?! "));
    assertNull(FtsQuery.allPrefixTerms(null));
  }
}
