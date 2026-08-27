package dev.alvo.pieria.code;

import dev.alvo.pieria.code.CodeIndexingService.SourceFile;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministic module-root resolution for a batch of source files, shared by the code indexer and
 * the code summarizer so both agree on module membership: build-marker files (gradle/maven/npm/…)
 * define module roots; a file belongs to the longest marker dir that is its ancestor, else its top
 * directory.
 */
public final class ModulePaths {

  /**
   * Build-file names used to locate module roots within a batch.
   */
  public static final Set<String> BUILD_MARKERS = Set.of(
    "build.gradle.kts", "build.gradle", "pom.xml", "package.json", "go.mod", "Cargo.toml");

  private ModulePaths() {
  }

  /**
   * The directories containing a build-marker file, sorted.
   */
  public static Set<String> markerDirs(List<SourceFile> files) {
    Set<String> dirs = new TreeSet<>();
    for (SourceFile f : files) {
      String path = f.repoRelPath();
      String name = lastSegment(path);
      if (name != null && BUILD_MARKERS.contains(name)) {
        dirs.add(parentDir(path));
      }
    }
    return dirs;
  }

  /**
   * The module root for a path: the longest marker dir that is its ancestor, else its top dir.
   */
  public static Optional<String> moduleDir(String path, Set<String> markerDirs) {
    String best = null;
    for (String d : markerDirs) {
      String prefix = d.isEmpty() ? "" : d + "/";
      if ((d.isEmpty() || path.startsWith(prefix)) && (best == null || d.length() > best.length())) {
        best = d;
      }
    }
    if (best != null) {
      return Optional.of(best);
    }
    int slash = path.indexOf('/');
    return slash > 0 ? Optional.of(path.substring(0, slash)) : Optional.empty();
  }

  public static String parentDir(String path) {
    int slash = path.lastIndexOf('/');
    return slash <= 0 ? "" : path.substring(0, slash);
  }

  public static String lastSegment(String path) {
    if (path == null || path.isBlank()) {
      return null;
    }
    int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return slash < 0 ? path : path.substring(slash + 1);
  }
}
