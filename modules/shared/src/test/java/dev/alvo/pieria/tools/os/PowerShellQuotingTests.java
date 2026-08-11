package dev.alvo.pieria.tools.os;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PowerShellQuotingTests {

  /** The common Windows case: a home directory with a space in it. */
  @Test
  void quotesPathsContainingSpaces() {
    assertThat(PowerShellQuoting.singleQuote("C:\\Users\\First Last\\AppData\\Local\\Pieria\\bin\\pieria-daemon.exe"))
      .isEqualTo("'C:\\Users\\First Last\\AppData\\Local\\Pieria\\bin\\pieria-daemon.exe'");
  }

  /** Backslashes are literal inside single quotes — no doubling, unlike JSON or TOML. */
  @Test
  void leavesBackslashesAlone() {
    assertThat(PowerShellQuoting.singleQuote("a\\b")).isEqualTo("'a\\b'");
  }

  /** `$` and backtick are only special in double-quoted strings; single quotes must not escape them. */
  @Test
  void leavesExpansionCharactersInert() {
    assertThat(PowerShellQuoting.singleQuote("$env:PATH `x")).isEqualTo("'$env:PATH `x'");
  }

  @Test
  void doublesEmbeddedSingleQuotes() {
    assertThat(PowerShellQuoting.singleQuote("O'Brien")).isEqualTo("'O''Brien'");
  }

  @Test
  void rendersArrayLiterals() {
    assertThat(PowerShellQuoting.array(List.of("--host=127.0.0.1", "--dir=C:\\a b")))
      .isEqualTo("@('--host=127.0.0.1','--dir=C:\\a b')");
    assertThat(PowerShellQuoting.array(List.of())).isEqualTo("@()");
  }
}
