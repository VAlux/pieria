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

  private static List<String> relatives(List<PdfDiscovery.Doc> docs) {
    return docs.stream().map(d -> d.relative().toString()).toList();
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
}
