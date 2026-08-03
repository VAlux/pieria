package dev.alvo.pieria.onboarding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PdfDiscoveryTests {

  // Assertions below hardcode "/"-separated expectations (matching git's own listing format);
  // normalize the actual Path's native separator so the comparison is OS-independent.
  private static List<String> relatives(List<PdfDiscovery.Doc> docs) {
    return docs.stream().map(d -> d.relative().toString().replace('\\', '/')).toList();
  }

  @Test
  void usesGitListingSortedByRelativePath(@TempDir Path proj) {
    PdfDiscovery discovery = new PdfDiscovery(proj,
      dir -> Optional.of(List.of("docs/spec.pdf", "manual.pdf")));

    assertThat(relatives(discovery.discover()))
      .containsExactly("docs/spec.pdf", "manual.pdf");
  }

  @Test
  void resolvesAbsolutePathsAgainstProjectDir(@TempDir Path proj) {
    PdfDiscovery discovery = new PdfDiscovery(proj,
      dir -> Optional.of(List.of("docs/spec.pdf")));

    PdfDiscovery.Doc doc = discovery.discover().get(0);
    assertThat(doc.absolute()).isEqualTo(proj.resolve("docs/spec.pdf"));
  }

  @Test
  void fallsBackToFilesystemWalkWhenNotAGitRepo(@TempDir Path proj) throws IOException {
    Files.writeString(proj.resolve("manual.pdf"), "%PDF-1.4");
    Files.createDirectories(proj.resolve("docs"));
    Files.writeString(proj.resolve("docs/guide.pdf"), "%PDF-1.4");
    Files.writeString(proj.resolve("notes.txt"), "ignored");
    Files.createDirectories(proj.resolve("node_modules/pkg"));
    Files.writeString(proj.resolve("node_modules/pkg/dep.pdf"), "%PDF-1.4");
    Files.createDirectories(proj.resolve("build"));
    Files.writeString(proj.resolve("build/out.pdf"), "%PDF-1.4");

    PdfDiscovery discovery = new PdfDiscovery(proj, dir -> Optional.empty());

    assertThat(relatives(discovery.discover()))
      .containsExactly("docs/guide.pdf", "manual.pdf");
  }

  @Test
  void emptyWhenNoPdfFound(@TempDir Path proj) {
    PdfDiscovery discovery = new PdfDiscovery(proj, dir -> Optional.of(List.of()));

    assertThat(discovery.discover()).isEmpty();
  }

  @Test
  void singlePdfFileRootIsIngestedDirectlyBypassingGit(@TempDir Path proj) throws IOException {
    Path file = proj.resolve("paper.pdf");
    Files.writeString(file, "%PDF-1.4");
    PdfDiscovery discovery = new PdfDiscovery(file, dir -> {
      throw new AssertionError("git seam must not be consulted for a single file");
    });

    List<PdfDiscovery.Doc> docs = discovery.discover();
    assertThat(relatives(docs)).containsExactly("paper.pdf");
    assertThat(docs.get(0).absolute()).isEqualTo(file);
  }

  @Test
  void singleFileWithWrongExtensionYieldsNothing(@TempDir Path proj) throws IOException {
    Path file = proj.resolve("notes.txt");
    Files.writeString(file, "plain");
    PdfDiscovery discovery = new PdfDiscovery(file, dir -> Optional.of(List.of()));

    assertThat(discovery.discover()).isEmpty();
  }
}
