package dev.alvo.pieria.cli.modules.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PathResolverTests {

  @Test
  void cliCommandUsesPieriaHomeBinDirectory(@TempDir Path tmp) {
    PathResolver resolver = new PathResolver(
      Map.of("PIERIA_HOME", tmp.toString())::get, null, tmp.resolve("home"), false);

    assertThat(resolver.cliCommand()).isEqualTo(tmp.resolve("bin").resolve("pieria").toString());
  }

  @Test
  void cliCommandAppendsExeOnWindows(@TempDir Path tmp) {
    PathResolver resolver = new PathResolver(
      Map.of("PIERIA_HOME", tmp.toString())::get, null, tmp.resolve("home"), true);

    assertThat(resolver.cliCommand()).endsWith("pieria.exe");
  }
}
