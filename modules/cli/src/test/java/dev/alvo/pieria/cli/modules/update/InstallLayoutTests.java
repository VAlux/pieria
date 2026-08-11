package dev.alvo.pieria.cli.modules.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

class InstallLayoutTests {

  private final Platform platform = new TestPlatform();

  private static UnaryOperator<String> env(Map<String, String> map) {
    return map::get;
  }

  @Test
  void explicitInstallRootWins(@TempDir Path tmp) {
    InstallLayout layout = InstallLayout.resolve(env(Map.of()), tmp, platform, tmp.resolve("root"));
    assertThat(layout.binDir()).isEqualTo(tmp.resolve("root").resolve("bin"));
  }

  @Test
  void pieriaHomeEnvWins(@TempDir Path tmp) {
    Path home = tmp.resolve("custom-home");
    InstallLayout layout = InstallLayout.resolve(env(Map.of("PIERIA_HOME", home.toString())), tmp, platform, null);
    assertThat(layout.binDir()).isEqualTo(home.resolve("bin"));
  }

  @Test
  void followsInstalledSymlinkToRealBinDir(@TempDir Path tmp) throws IOException {
    Path realBin = Files.createDirectories(tmp.resolve("share/pieria/bin"));
    Files.writeString(realBin.resolve("pieria"), "binary");
    Path linkDir = Files.createDirectories(tmp.resolve("local/bin"));
    Files.createSymbolicLink(linkDir.resolve("pieria"), realBin.resolve("pieria"));

    InstallLayout layout = InstallLayout.resolve(
      env(Map.of("PIERIA_BIN_DIR", linkDir.toString())), tmp, platform, null);

    assertThat(layout.binDir()).isEqualTo(realBin.toRealPath());
  }

  /** `C:\Users\First Last\...` is the common Windows case, not the exception. */
  @Test
  void resolvesInstallRootsContainingSpaces(@TempDir Path tmp) {
    Path spaced = tmp.resolve("First Last").resolve("Local App Data").resolve("Pieria");

    assertThat(InstallLayout.resolve(env(Map.of("PIERIA_HOME", spaced.toString())), tmp, platform, null)
      .binDir()).isEqualTo(spaced.resolve("bin"));
    assertThat(InstallLayout.resolve(env(Map.of()), tmp, platform, spaced).binDir())
      .isEqualTo(spaced.resolve("bin"));
  }
}
