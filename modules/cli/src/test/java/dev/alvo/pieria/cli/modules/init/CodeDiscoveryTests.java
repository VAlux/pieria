package dev.alvo.pieria.cli.modules.init;

import dev.alvo.pieria.api.request.CodeIndexRequest.FileDto;
import dev.alvo.pieria.config.model.DiscoveryConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CodeDiscovery}: candidate filtering, and content reading with binary/size
 * skipping. Git enumeration is injected so no real repository is needed.
 */
class CodeDiscoveryTests {

  @Test
  void isCandidateAcceptsSourceAndBuildFilesOnly() {
    CodeDiscovery discovery = new CodeDiscovery(Path.of(""), _ -> Optional.empty());
    assertThat(discovery.isCandidate("src/Main.java")).isTrue();
    assertThat(discovery.isCandidate("build.gradle.kts")).isTrue();
    assertThat(discovery.isCandidate("modules/daemon/pom.xml")).isTrue();
    assertThat(discovery.isCandidate("README.md")).isFalse();
    assertThat(discovery.isCandidate("logo.png")).isFalse();
    assertThat(discovery.isCandidate("Makefile")).isFalse();
  }

  @Test
  void discoverReadsCandidatesAndSkipsNonCandidatesAndBinaries(@TempDir Path proj) throws IOException {
    Files.writeString(proj.resolve("Main.java"), "class Main {}");
    Files.writeString(proj.resolve("build.gradle.kts"), "plugins {}");
    Files.writeString(proj.resolve("README.md"), "# docs");
    Files.write(proj.resolve("Blob.java"), new byte[]{'a', 0, 'b'}); // candidate ext but binary

    List<String> tracked = List.of("Main.java", "build.gradle.kts", "README.md", "Blob.java");
    CodeDiscovery discovery = new CodeDiscovery(proj, _ -> Optional.of(tracked));

    List<FileDto> files = discovery.discover();

    assertThat(files).extracting(FileDto::repoRelPath)
      .containsExactly("Main.java", "build.gradle.kts"); // sorted, md + binary excluded
    assertThat(files.getFirst().content()).isEqualTo("class Main {}");
    assertThat(files.getFirst().language()).isNull();      // daemon detects
    assertThat(files.getFirst().contentHash()).isNull();   // daemon content-addresses
  }

  @Test
  void discoverSkipsOversizedFiles(@TempDir Path proj) throws IOException {
    byte[] big = new byte[(int) DiscoveryConfig.DEFAULT_MAX_FILE_BYTES + 1];
    java.util.Arrays.fill(big, (byte) 'x');
    Files.write(proj.resolve("Big.java"), big);
    Files.writeString(proj.resolve("Small.java"), "class Small {}");

    CodeDiscovery discovery = new CodeDiscovery(proj,
      _ -> Optional.of(List.of("Big.java", "Small.java")));

    assertThat(discovery.discover()).extracting(FileDto::repoRelPath).containsExactly("Small.java");
  }

  @Test
  void fallsBackToFilesystemWalkWhenNotAGitRepo(@TempDir Path proj) throws IOException {
    Files.createDirectories(proj.resolve("build")); // skipped dir
    Files.writeString(proj.resolve("build/Generated.java"), "class Generated {}");
    Files.writeString(proj.resolve("Kept.java"), "class Kept {}");

    CodeDiscovery discovery = new CodeDiscovery(proj, _ -> Optional.empty()); // git unavailable

    assertThat(discovery.discover()).extracting(FileDto::repoRelPath).containsExactly("Kept.java");
  }

  @Test
  void configOverridesExtensionsMarkersAndSizeCap(@TempDir Path proj) throws IOException {
    Files.writeString(proj.resolve("query.sql"), "SELECT 1;");
    Files.writeString(proj.resolve("Main.java"), "class Main {}");
    Files.writeString(proj.resolve("Justfile"), "build:");
    Files.writeString(proj.resolve("Tiny.sql"), "SELECT 22;"); // 10 bytes > 9-byte cap below

    DiscoveryConfig config = new DiscoveryConfig(
      java.util.Set.of("sql"), java.util.Set.of("Justfile"), null, 9L);
    CodeDiscovery discovery = new CodeDiscovery(proj,
      _ -> Optional.of(List.of("query.sql", "Main.java", "Justfile", "Tiny.sql")), config);

    // Only the configured extension and marker survive; the java default no longer applies and
    // the lowered size cap drops the larger sql file.
    assertThat(discovery.discover()).extracting(FileDto::repoRelPath)
      .containsExactly("Justfile", "query.sql");
  }

  @Test
  void configOverridesSkipDirsForFilesystemWalk(@TempDir Path proj) throws IOException {
    Files.createDirectories(proj.resolve("vendor"));
    Files.writeString(proj.resolve("vendor/Dep.java"), "class Dep {}");
    Files.createDirectories(proj.resolve("build"));
    Files.writeString(proj.resolve("build/Generated.java"), "class Generated {}");
    Files.writeString(proj.resolve("Kept.java"), "class Kept {}");

    // Replacing skip-dirs wholesale: vendor is now skipped, the default build no longer is.
    DiscoveryConfig config = new DiscoveryConfig(null, null, java.util.Set.of("vendor"), null);
    CodeDiscovery discovery = new CodeDiscovery(proj, _ -> Optional.empty(), config);

    assertThat(discovery.discover()).extracting(FileDto::repoRelPath)
      .containsExactly("Kept.java", "build/Generated.java".replace('/', java.io.File.separatorChar));
  }
}
