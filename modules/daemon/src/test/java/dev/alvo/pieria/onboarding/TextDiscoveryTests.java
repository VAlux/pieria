package dev.alvo.pieria.onboarding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TextDiscoveryTests {

  // Assertions below hardcode "/"-separated expectations (matching git's own listing format);
  // normalize the actual Path's native separator so the comparison is OS-independent.
  private static List<String> relatives(List<TextDiscovery.Doc> docs) {
    return docs.stream().map(d -> d.relative().toString().replace('\\', '/')).toList();
  }

  @Test
  void usesGitListingSortedByRelativePath(@TempDir Path proj) {
    TextDiscovery discovery = new TextDiscovery(proj,
      dir -> Optional.of(List.of("docs/spec.txt", "notes.txt")));

    assertThat(relatives(discovery.discover()))
      .containsExactly("docs/spec.txt", "notes.txt");
  }

  @Test
  void resolvesAbsolutePathsAgainstProjectDir(@TempDir Path proj) {
    TextDiscovery discovery = new TextDiscovery(proj,
      dir -> Optional.of(List.of("docs/spec.txt")));

    TextDiscovery.Doc doc = discovery.discover().get(0);
    assertThat(doc.absolute()).isEqualTo(proj.resolve("docs/spec.txt"));
  }

  @Test
  void fallsBackToFilesystemWalkWhenNotAGitRepo(@TempDir Path proj) throws IOException {
    Files.writeString(proj.resolve("notes.txt"), "hello");
    Files.createDirectories(proj.resolve("docs"));
    Files.writeString(proj.resolve("docs/guide.txt"), "guide");
    Files.writeString(proj.resolve("README.md"), "# ignored");
    Files.createDirectories(proj.resolve("node_modules/pkg"));
    Files.writeString(proj.resolve("node_modules/pkg/dep.txt"), "skip");
    Files.createDirectories(proj.resolve("build"));
    Files.writeString(proj.resolve("build/out.txt"), "skip");

    TextDiscovery discovery = new TextDiscovery(proj, dir -> Optional.empty());

    assertThat(relatives(discovery.discover()))
      .containsExactly("docs/guide.txt", "notes.txt");
  }

  @Test
  void emptyWhenNoTextFound(@TempDir Path proj) {
    TextDiscovery discovery = new TextDiscovery(proj, dir -> Optional.of(List.of()));

    assertThat(discovery.discover()).isEmpty();
  }

  @Test
  void singleTextFileRootIsIngestedDirectlyBypassingGit(@TempDir Path proj) throws IOException {
    Path file = proj.resolve("notes.txt");
    Files.writeString(file, "hello");
    TextDiscovery discovery = new TextDiscovery(file, dir -> {
      throw new AssertionError("git seam must not be consulted for a single file");
    });

    List<TextDiscovery.Doc> docs = discovery.discover();
    assertThat(relatives(docs)).containsExactly("notes.txt");
    assertThat(docs.get(0).absolute()).isEqualTo(file);
  }

  @Test
  void singleFileWithWrongExtensionYieldsNothing(@TempDir Path proj) throws IOException {
    Path file = proj.resolve("README.md");
    Files.writeString(file, "# md");
    TextDiscovery discovery = new TextDiscovery(file, dir -> Optional.of(List.of()));

    assertThat(discovery.discover()).isEmpty();
  }
}
