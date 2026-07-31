package dev.alvo.pieria.cli.modules.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BinarySwapperTests {

  private static Path writeFile(Path path, String content) throws IOException {
    Files.createDirectories(path.getParent());
    return Files.writeString(path, content);
  }

  private StagedDist nativeDist(Path root, String suffix) throws IOException {
    for (String name : BinarySource.BINARIES) {
      writeFile(root.resolve("bin").resolve(name), name + "-" + suffix);
    }
    return new StagedDist(root);
  }

  @Test
  void swapsBinariesHardensAndStampsVersion(@TempDir Path tmp) throws IOException {
    StagedDist dist = nativeDist(tmp.resolve("staging"), "new");
    writeFile(dist.binDir().resolve("version.txt"), "9.9.9");

    Path installBin = tmp.resolve("install").resolve("bin");
    for (String name : BinarySource.BINARIES) {
      writeFile(installBin.resolve(name), name + "-old");
    }
    InstallLayout install = new InstallLayout(installBin);

    TestPlatform platform = new TestPlatform();
    new BinarySwapper(platform).swap(dist, install);

    for (String name : BinarySource.BINARIES) {
      assertThat(Files.readString(installBin.resolve(name))).isEqualTo(name + "-new");
    }
    assertThat(Files.readString(installBin.resolve("version.txt"))).isEqualTo("9.9.9");
    assertThat(platform.hardened).hasSize(3);
    // No staging/backup residue.
    try (var entries = Files.list(installBin)) {
      assertThat(entries.map(p -> p.getFileName().toString()))
        .noneMatch(n -> n.endsWith(".new") || n.endsWith(".bak"));
    }
  }

  @Test
  void rollsBackEarlierBinariesWhenOneIsMissing(@TempDir Path tmp) throws IOException {
    // Staging is missing pieria-gateway, so the third swap fails after the first two committed.
    Path staging = tmp.resolve("staging");
    writeFile(staging.resolve("bin").resolve("pieria"), "pieria-new");
    writeFile(staging.resolve("bin").resolve("pieria-daemon"), "pieria-daemon-new");
    StagedDist dist = new StagedDist(staging);

    Path installBin = tmp.resolve("install").resolve("bin");
    for (String name : BinarySource.BINARIES) {
      writeFile(installBin.resolve(name), name + "-old");
    }
    InstallLayout install = new InstallLayout(installBin);

    assertThatThrownBy(() -> new BinarySwapper(new TestPlatform()).swap(dist, install))
      .isInstanceOf(UpdateException.class);

    // Every binary restored to its original content; no residue.
    for (String name : BinarySource.BINARIES) {
      assertThat(Files.readString(installBin.resolve(name))).isEqualTo(name + "-old");
    }
    try (var entries = Files.list(installBin)) {
      assertThat(entries.map(p -> p.getFileName().toString()))
        .noneMatch(n -> n.endsWith(".new") || n.endsWith(".bak"));
    }
  }

}
