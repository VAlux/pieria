package dev.alvo.pieria.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokensTests {

  @Test
  void estimateIsZeroForNullOrEmpty() {
    assertEquals(0L, Tokens.estimate(null));
    assertEquals(0L, Tokens.estimate(""));
  }

  @Test
  void estimateRoundsCharsUpAtFourPerToken() {
    assertEquals(1L, Tokens.estimate("a"));     // ceil(1/4)
    assertEquals(1L, Tokens.estimate("abcd"));  // ceil(4/4)
    assertEquals(2L, Tokens.estimate("abcde")); // ceil(5/4)
    assertEquals(10L, Tokens.estimate("a".repeat(40)));
  }

  @Test
  void fromCharsMatchesEstimateAndFloorsAtZero() {
    assertEquals(0L, Tokens.fromChars(0));
    assertEquals(0L, Tokens.fromChars(-5));
    assertEquals(20L, Tokens.fromChars(80));
    assertEquals(Tokens.estimate("x".repeat(37)), Tokens.fromChars(37));
  }
}
