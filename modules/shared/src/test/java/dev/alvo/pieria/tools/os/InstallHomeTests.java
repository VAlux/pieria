package dev.alvo.pieria.tools.os;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class InstallHomeTests {

  private static Function<String, String> env(Map<String, String> map) {
    return map::get;
  }

  @Test
  void unixDefaultIsUnconditionalLocalShare() {
    Path home = Path.of("/home/alex");
    Path result = InstallHome.defaultHome(env(Map.of()), home, false);
    assertThat(result).isEqualTo(home.resolve(".local").resolve("share").resolve("pieria"));
  }

  @Test
  void windowsDefaultUsesLocalAppDataWhenSet() {
    Path home = Path.of("C:\\Users\\alex");
    Path result = InstallHome.defaultHome(
      env(Map.of("LOCALAPPDATA", "C:\\Users\\alex\\AppData\\Local")), home, true);
    assertThat(result).isEqualTo(Path.of("C:\\Users\\alex\\AppData\\Local").resolve("Pieria"));
  }

  @Test
  void windowsDefaultFallsBackWhenLocalAppDataUnset() {
    Path home = Path.of("C:\\Users\\alex");
    Path result = InstallHome.defaultHome(env(Map.of()), home, true);
    assertThat(result).isEqualTo(home.resolve("AppData").resolve("Local").resolve("Pieria"));
  }
}
