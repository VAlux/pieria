package dev.alvo.pieria.onboarding;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Validation for a client-supplied source {@code root}. The daemon reads files under this path, so a
 * request must name an absolute path to an existing directory. Failure messages are deliberately
 * path-free (they never echo the offending value) so error bodies leak no filesystem layout.
 */
final class Roots {

  private Roots() {
  }

  /** Resolve and validate a directory source root, or throw {@link IllegalArgumentException} (→ HTTP 400). */
  static Path require(String root) {
    Path path = resolve(root);
    if (!Files.isDirectory(path)) {
      throw new IllegalArgumentException("source root is not an existing directory");
    }
    return path;
  }

  /**
   * Resolve and validate a source root that may name either an existing directory (scanned) or a
   * single existing file (ingested directly), or throw {@link IllegalArgumentException} (→ HTTP 400).
   */
  static Path requireFileOrDirectory(String root) {
    Path path = resolve(root);
    if (!Files.isRegularFile(path) && !Files.isDirectory(path)) {
      throw new IllegalArgumentException("source root is not an existing file or directory");
    }
    return path;
  }

  /** Shared null/blank/absolute/parse checks; existence is checked by the caller. */
  private static Path resolve(String root) {
    if (root == null || root.isBlank()) {
      throw new IllegalArgumentException("source root is required");
    }
    Path path;
    try {
      path = Path.of(root).normalize();
    } catch (InvalidPathException e) {
      throw new IllegalArgumentException("source root is not a valid path");
    }
    if (!path.isAbsolute()) {
      throw new IllegalArgumentException("source root must be an absolute path");
    }
    return path;
  }
}
