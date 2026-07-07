package dev.alvo.pieria.onboarding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link Roots}. {@code require} accepts only directories (source-code index);
 * {@code requireFileOrDirectory} additionally accepts a single existing file (content sources
 * pointed at one document). Error messages stay path-free so 400 bodies leak no filesystem layout.
 */
class RootsTests {

  @Test
  void requireAcceptsAnExistingDirectory(@TempDir Path dir) {
    assertThat(Roots.require(dir.toString())).isEqualTo(dir);
  }

  @Test
  void requireRejectsAFile(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("a.md");
    Files.writeString(file, "x");

    assertThatThrownBy(() -> Roots.require(file.toString()))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("existing directory");
  }

  @Test
  void requireFileOrDirectoryAcceptsADirectory(@TempDir Path dir) {
    assertThat(Roots.requireFileOrDirectory(dir.toString())).isEqualTo(dir);
  }

  @Test
  void requireFileOrDirectoryAcceptsAFile(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("a.txt");
    Files.writeString(file, "x");

    assertThat(Roots.requireFileOrDirectory(file.toString())).isEqualTo(file);
  }

  @Test
  void requireFileOrDirectoryRejectsANonexistentPath(@TempDir Path dir) {
    Path missing = dir.resolve("nope.txt");

    assertThatThrownBy(() -> Roots.requireFileOrDirectory(missing.toString()))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("existing file or directory");
  }

  @Test
  void requireFileOrDirectoryRejectsARelativePath() {
    assertThatThrownBy(() -> Roots.requireFileOrDirectory("relative/notes.txt"))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("absolute");
  }

  @Test
  void requireFileOrDirectoryRejectsBlank() {
    assertThatThrownBy(() -> Roots.requireFileOrDirectory("  "))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("required");
  }
}
