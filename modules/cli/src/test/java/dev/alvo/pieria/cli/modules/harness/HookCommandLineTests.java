package dev.alvo.pieria.cli.modules.harness;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HookCommandLineTests {

  @Test
  void leavesASimplePathUnquoted() {
    assertThat(HookCommandLine.of("/opt/pieria/bin/pieria", "hook", "claude-code", "stop"))
      .isEqualTo("/opt/pieria/bin/pieria hook claude-code stop");
  }

  @Test
  void quotesAPathContainingSpaces() {
    assertThat(HookCommandLine.of("C:\\Users\\First Last\\Pieria\\bin\\pieria.exe", "hook", "codex", "stop"))
      .isEqualTo("\"C:\\Users\\First Last\\Pieria\\bin\\pieria.exe\" hook codex stop");
  }

  @Test
  void quotesAUnixPathContainingSpaces() {
    assertThat(HookCommandLine.of("/Users/ada lovelace/.local/share/pieria/bin/pieria", "hook", "remember"))
      .isEqualTo("\"/Users/ada lovelace/.local/share/pieria/bin/pieria\" hook remember");
  }

  @Test
  void doesNotDoubleQuoteAnAlreadyQuotedExecutable() {
    assertThat(HookCommandLine.of("\"/opt/my pieria/pieria\"", "hook", "stop"))
      .isEqualTo("\"/opt/my pieria/pieria\" hook stop");
  }
}
