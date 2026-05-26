package dev.alvo.pieria.cli.init;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownDiscoveryTests {

  private static List<String> relatives(List<MarkdownDiscovery.Doc> docs) {
    return docs.stream().map(d -> d.relative().toString()).toList();
  }

  @Test
  void usesGitListingAndExcludesAgentDocsByDefault(@TempDir Path proj) {
    MarkdownDiscovery discovery = new MarkdownDiscovery(proj,
      dir -> Optional.of(List.of("README.md", "docs/SPEC.md", "CLAUDE.md", "AGENTS.md")));

    assertThat(relatives(discovery.discover(false)))
      .containsExactly("README.md", "docs/SPEC.md");
  }

  @Test
  void includesAgentDocsWhenRequested(@TempDir Path proj) {
    MarkdownDiscovery discovery = new MarkdownDiscovery(proj,
      dir -> Optional.of(List.of("README.md", "CLAUDE.md", "AGENTS.md")));

    assertThat(relatives(discovery.discover(true)))
      .containsExactly("AGENTS.md", "CLAUDE.md", "README.md"); // sorted
  }

  @Test
  void resolvesAbsolutePathsAgainstProjectDir(@TempDir Path proj) {
    MarkdownDiscovery discovery = new MarkdownDiscovery(proj,
      dir -> Optional.of(List.of("docs/SPEC.md")));

    MarkdownDiscovery.Doc doc = discovery.discover(false).get(0);
    assertThat(doc.absolute()).isEqualTo(proj.resolve("docs/SPEC.md"));
  }

  @Test
  void fallsBackToFilesystemWalkWhenNotAGitRepo(@TempDir Path proj) throws IOException {
    Files.writeString(proj.resolve("README.md"), "# Readme");
    Files.createDirectories(proj.resolve("docs"));
    Files.writeString(proj.resolve("docs/guide.md"), "# Guide");
    Files.writeString(proj.resolve("notes.txt"), "ignored");
    Files.createDirectories(proj.resolve("node_modules/pkg"));
    Files.writeString(proj.resolve("node_modules/pkg/dep.md"), "# Skip me");
    Files.createDirectories(proj.resolve("build"));
    Files.writeString(proj.resolve("build/out.md"), "# Skip me too");

    MarkdownDiscovery discovery = new MarkdownDiscovery(proj, dir -> Optional.empty());

    assertThat(relatives(discovery.discover(false)))
      .containsExactly("README.md", "docs/guide.md");
  }

  @Test
  void emptyWhenNoMarkdownFound(@TempDir Path proj) {
    MarkdownDiscovery discovery = new MarkdownDiscovery(proj, dir -> Optional.of(List.of()));

    assertThat(discovery.discover(false)).isEmpty();
  }
}
