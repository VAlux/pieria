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

  /**
   * On Windows the original must be <em>moved</em> aside, not copied: the OS refuses to overwrite a
   * running image but does allow renaming it. The observable difference is that the target's
   * original inode/name is vacated before the new file lands.
   */
  @Test
  void movesTheOriginalAsideWhenTheOsLocksRunningBinaries(@TempDir Path tmp) throws IOException {
    StagedDist dist = nativeDist(tmp.resolve("staging"), "new");
    Path installBin = tmp.resolve("install").resolve("bin");
    for (String name : BinarySource.BINARIES) {
      writeFile(installBin.resolve(name), name + "-old");
    }

    new BinarySwapper(new TestPlatform().lockingRunningBinaries())
      .swap(dist, new InstallLayout(installBin));

    for (String name : BinarySource.BINARIES) {
      assertThat(Files.readString(installBin.resolve(name))).isEqualTo(name + "-new");
    }
    // The backups are deletable here (nothing is really running), so the swap still cleans up.
    try (var entries = Files.list(installBin)) {
      assertThat(entries.map(p -> p.getFileName().toString()))
        .noneMatch(n -> n.endsWith(".new") || n.endsWith(".bak"));
    }
  }

  /** A leftover from a previous update must be swept rather than left to accumulate. */
  @Test
  void sweepsStaleBackupAndStagingFilesBeforeSwapping(@TempDir Path tmp) throws IOException {
    StagedDist dist = nativeDist(tmp.resolve("staging"), "new");
    Path installBin = tmp.resolve("install").resolve("bin");
    for (String name : BinarySource.BINARIES) {
      writeFile(installBin.resolve(name), name + "-old");
    }
    writeFile(installBin.resolve("pieria.exe.bak"), "leftover");
    writeFile(installBin.resolve("pieria-daemon.exe.bak.1700000000000"), "leftover");
    writeFile(installBin.resolve("pieria-gateway.exe.new"), "leftover");

    new BinarySwapper(new TestPlatform().lockingRunningBinaries())
      .swap(dist, new InstallLayout(installBin));

    try (var entries = Files.list(installBin)) {
      assertThat(entries.map(p -> p.getFileName().toString()))
        .containsExactlyInAnyOrderElementsOf(BinarySource.BINARIES);
    }
  }
}
