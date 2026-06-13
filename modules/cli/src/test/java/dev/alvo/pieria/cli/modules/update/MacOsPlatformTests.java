package dev.alvo.pieria.cli.modules.update;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MacOsPlatformTests {

  @Test
  void slugAndExeName() {
    MacOsPlatform platform = new MacOsPlatform("aarch64");
    assertThat(platform.slug()).isEqualTo("macos-aarch64");
    assertThat(platform.exeName("pieria")).isEqualTo("pieria");
    assertThat(platform.supported()).isTrue();
  }

  @Test
  void hardenStripsQuarantineThenCodesigns() {
    List<List<String>> commands = new ArrayList<>();
    CommandRunner runner = command -> {
      commands.add(command);
      return new CommandRunner.Result(0, "");
    };
    new MacOsPlatform("aarch64", runner).harden(Path.of("/opt/pieria/bin/pieria-daemon"));

    assertThat(commands).hasSize(2);
    assertThat(commands.get(0)).containsExactly(
      "xattr", "-dr", "com.apple.quarantine", "/opt/pieria/bin/pieria-daemon");
    assertThat(commands.get(1)).containsExactly(
      "codesign", "--force", "--sign", "-", "/opt/pieria/bin/pieria-daemon");
  }

  @Test
  void hardenToleratesCodesignFailure() {
    CommandRunner runner = command -> new CommandRunner.Result(
      command.contains("codesign") ? 1 : 0, "boom");
    assertThatCode(() -> new MacOsPlatform("aarch64", runner).harden(Path.of("/bin/x")))
      .doesNotThrowAnyException();
  }

  @Test
  void extractFailurePropagatesAsUpdateException() {
    CommandRunner runner = command -> new CommandRunner.Result(1, "tar: bad archive");
    assertThatThrownBy(() -> new MacOsPlatform("aarch64", runner)
      .extractTarGz(Path.of("/tmp/a.tar.gz"), Path.of("/tmp/dest")))
      .isInstanceOf(UpdateException.class)
      .hasMessageContaining("bad archive");
  }
}
