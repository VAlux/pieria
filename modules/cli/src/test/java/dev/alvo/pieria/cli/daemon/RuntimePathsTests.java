package dev.alvo.pieria.cli.daemon;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimePathsTests {

  @Test
  void overrideWinsAndPidFileNestsUnderIt() {
    RuntimePaths paths = RuntimePaths.resolve("/tmp/pieria-run");

    assertThat(paths.runtimeDir()).isEqualTo(Path.of("/tmp/pieria-run").toAbsolutePath());
    assertThat(paths.pidFile()).isEqualTo(Path.of("/tmp/pieria-run").toAbsolutePath().resolve("pieria-daemon.pid"));
  }

  @Test
  void blankOverrideFallsBackToAnAbsoluteOsDefault() {
    RuntimePaths paths = RuntimePaths.resolve("  ");

    assertThat(paths.runtimeDir()).isAbsolute();
    assertThat(paths.logsDir()).isAbsolute();
    assertThat(paths.pidFile().getFileName().toString()).isEqualTo("pieria-daemon.pid");
  }
}
