package dev.alvo.pieria.cli.modules.config;

import dev.alvo.pieria.config.model.DiscoveryConfig;
import dev.alvo.pieria.config.model.PieriaConfigFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Layering tests over real files: project {@code .pieria/config.toml} overrides the global
 * {@code config.toml}, absent layers fall through to code defaults, malformed files fail loud.
 */
class ProjectConfigLoaderTests {

  @TempDir
  Path dir;

  private Path global;
  private Path project;

  private ProjectConfigLoader loader() {
    global = dir.resolve("global.toml");
    project = dir.resolve(".pieria").resolve("config.toml");
    return new ProjectConfigLoader(global, project);
  }

  private void write(Path file, String content) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  @Test
  void bothFilesAbsentYieldsAllDefaults() throws IOException {
    PieriaConfigFile config = loader().load();
    assertThat(config.discovery()).isEqualTo(DiscoveryConfig.defaults());
    assertThat(config.pieria().isEmpty()).isTrue();
  }

  @Test
  void projectOverridesGlobalWhichOverridesDefaults() throws IOException {
    ProjectConfigLoader loader = loader();
    write(global, """
      [discovery]
      max-file-bytes = 4096

      [pieria.retrieval]
      rrf-k = 30
      weight-graph = 2.0
      """);
    write(project, """
      [pieria.retrieval]
      weight-graph = 0.0
      """);

    PieriaConfigFile config = loader.load();

    assertThat(config.discovery().maxFileBytes()).isEqualTo(4096);    // global
    assertThat(config.discovery().skipDirs()).isEqualTo(DiscoveryConfig.DEFAULT_SKIP_DIRS); // defaults
    assertThat(config.pieria().retrieval().rrfK()).isEqualTo(30);     // global
    assertThat(config.pieria().retrieval().weightGraph()).isZero();   // project wins
  }

  @Test
  void projectOnlyConfigWorksWithoutGlobalFile() throws IOException {
    ProjectConfigLoader loader = loader();
    write(project, """
      [discovery]
      source-extensions = ["sql"]
      """);

    PieriaConfigFile config = loader.load();
    assertThat(config.discovery().sourceExtensions()).containsExactly("sql");
  }

  @Test
  void malformedTomlFailsLoud() throws IOException {
    ProjectConfigLoader loader = loader();
    write(project, "this is = not [ valid toml");

    assertThatThrownBy(loader::load).isInstanceOf(Exception.class);
  }
}
