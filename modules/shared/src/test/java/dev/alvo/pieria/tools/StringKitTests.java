package dev.alvo.pieria.tools;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StringKitTests {

  @Test
  void quoteIfSpacedReturnsNullUnchanged() {
    assertThat(StringKit.quoteIfSpaced(null)).isNull();
  }

  @Test
  void quoteIfSpacedReturnsEmptyUnchanged() {
    assertThat(StringKit.quoteIfSpaced("")).isEmpty();
  }

  @Test
  void quoteIfSpacedLeavesSimplePathUnquoted() {
    assertThat(StringKit.quoteIfSpaced("/opt/pieria/bin/pieria"))
      .isEqualTo("/opt/pieria/bin/pieria");
  }

  @Test
  void quoteIfSpacedWrapsPathContainingSpaces() {
    assertThat(StringKit.quoteIfSpaced("C:\\Users\\First Last\\Pieria\\bin\\pieria.exe"))
      .isEqualTo("\"C:\\Users\\First Last\\Pieria\\bin\\pieria.exe\"");
  }

  @Test
  void quoteIfSpacedWrapsUnixPathContainingSpaces() {
    assertThat(StringKit.quoteIfSpaced("/Users/ada lovelace/.local/share/pieria/bin/pieria"))
      .isEqualTo("\"/Users/ada lovelace/.local/share/pieria/bin/pieria\"");
  }

  @Test
  void quoteIfSpacedDoesNotDoubleQuoteAlreadyQuotedPath() {
    assertThat(StringKit.quoteIfSpaced("\"/opt/my pieria/pieria\""))
      .isEqualTo("\"/opt/my pieria/pieria\"");
  }
}
