package dev.alvo.pieria.cli.command;

import dev.alvo.pieria.cli.PieriaCli;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HarnessInstallCommandTests {

  private int run(String... args) {
    return new CommandLine(new PieriaCli()).execute(args);
  }

  @Test
  void unknownHarnessExitsTwoAndWritesNothing(@TempDir Path proj) {
    int code = run("harness", "install", "bogus", "--project-dir", proj.toString());
    assertThat(code).isEqualTo(2);
    assertThat(Files.exists(proj.resolve(".mcp.json"))).isFalse();
  }

  @Test
  void dryRunWritesNothing(@TempDir Path proj) {
    int code = run("harness", "install", "claude-code", "--project-dir", proj.toString(), "--dry-run");
    assertThat(code).isEqualTo(0);
    assertThat(Files.exists(proj.resolve(".mcp.json"))).isFalse();
    assertThat(Files.exists(proj.resolve(".claude").resolve("settings.json"))).isFalse();
  }

  @Test
  void unknownHarnessUninstallExitsTwo(@TempDir Path proj) {
    int code = run("harness", "uninstall", "bogus", "--project-dir", proj.toString());
    assertThat(code).isEqualTo(2);
  }
}
