package dev.alvo.pieria.cli.modules.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalDistSourceTests {

  private final Platform platform = new TestPlatform();

  private static void touch(Path path) throws IOException {
    Files.createDirectories(path.getParent());
    Files.writeString(path, "x");
  }

  @Test
  void resolvesValidNativeDist(@TempDir Path tmp) throws IOException {
    for (String name : BinarySource.BINARIES) {
      touch(tmp.resolve("bin").resolve(name));
    }
    StagedDist dist = new LocalDistSource(tmp, false, platform).resolve();
    assertThat(dist.jar()).isFalse();
    assertThat(dist.binDir()).isEqualTo(tmp.resolve("bin"));
  }

  @Test
  void rejectsMissingBinary(@TempDir Path tmp) throws IOException {
    touch(tmp.resolve("bin").resolve("pieria"));
    assertThatThrownBy(() -> new LocalDistSource(tmp, false, platform).resolve())
      .isInstanceOf(UpdateException.class)
      .hasMessageContaining("missing");
  }

  @Test
  void resolvesJarDist(@TempDir Path tmp) throws IOException {
    touch(tmp.resolve("lib").resolve("pieria.jar"));
    touch(tmp.resolve("lib").resolve("pieria-gateway.jar"));
    touch(tmp.resolve("lib").resolve("pieria-cli.jar"));
    StagedDist dist = new LocalDistSource(tmp, true, platform).resolve();
    assertThat(dist.jar()).isTrue();
  }

  @Test
  void rejectsMissingDir(@TempDir Path tmp) {
    assertThatThrownBy(() -> new LocalDistSource(tmp.resolve("nope"), false, platform).resolve())
      .isInstanceOf(UpdateException.class)
      .hasMessageContaining("not found");
  }
}
