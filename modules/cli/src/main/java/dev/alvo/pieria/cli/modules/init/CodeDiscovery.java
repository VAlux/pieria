package dev.alvo.pieria.cli.modules.init;

import dev.alvo.pieria.api.request.CodeIndexRequest.FileDto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Enumerates a project's source files to index. Primary source is {@code git ls-files -z} (which
 * respects {@code .gitignore} and excludes build output / {@code .git} / {@code node_modules}); a
 * filesystem walk is the fallback outside a git repo.
 *
 * <p>Kept files are those with a recognized source extension, plus build-marker files (so the daemon
 * can detect module roots). Binary files and files over a size cap are skipped. Language and content
 * hash are left blank on the wire — the daemon detects the language by extension and
 * content-addresses by a hash of the content.
 *
 * <h2>Testability</h2>
 * Git enumeration is an injected seam ({@link MarkdownDiscovery.GitLsFiles}), so tests need no real
 * repository.
 */
public final class CodeDiscovery {

  static final Set<String> SOURCE_EXTENSIONS = Set.of(
    "java", "kt", "kts", "scala", "sc",
    "ts", "tsx", "js", "jsx", "mjs", "cjs",
    "py", "go", "rs", "rb", "php", "cs",
    "c", "h", "cpp", "cc", "hpp", "swift");

  static final Set<String> BUILD_MARKERS = Set.of(
    "build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle",
    "pom.xml", "package.json", "go.mod", "Cargo.toml");

  static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules", "build", ".gradle", ".idea", "target", "dist");

  /**
   * Files larger than this are skipped (generated bundles, vendored blobs, data files).
   */
  static final long MAX_FILE_BYTES = 1_048_576; // 1 MiB

  /**
   * Scanned prefix for a NUL byte to detect (and skip) binary files.
   */
  private static final int BINARY_SNIFF_BYTES = 8000;

  private final Path projectDir;
  private final MarkdownDiscovery.GitLsFiles gitLsFiles;

  CodeDiscovery(Path projectDir, MarkdownDiscovery.GitLsFiles gitLsFiles) {
    this.projectDir = projectDir;
    this.gitLsFiles = gitLsFiles;
  }

  /**
   * Production factory: wires a {@code git ls-files -z} reader (all tracked files).
   */
  public static CodeDiscovery create(Path projectDir) {
    return new CodeDiscovery(projectDir, realGitReader());
  }

  /**
   * True when a path should be sent: a recognized source extension or a build-marker file name.
   */
  static boolean isCandidate(String repoRelPath) {
    String name = fileName(repoRelPath);
    if (BUILD_MARKERS.contains(name)) {
      return true;
    }
    int dot = name.lastIndexOf('.');
    if (dot < 0 || dot == name.length() - 1) {
      return false;
    }
    return SOURCE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
  }

  /**
   * Discover the source files to index, sorted by path for deterministic batching. Unreadable,
   * binary, or oversized files are skipped.
   */
  public List<FileDto> discover() {
    List<String> relative = gitLsFiles.list(projectDir).orElseGet(() -> walkFilesystem(projectDir));

    List<FileDto> files = new ArrayList<>();
    for (String rel : relative.stream().filter(CodeDiscovery::isCandidate).sorted().toList()) {
      Optional<String> content = readText(projectDir.resolve(rel));
      content.ifPresent(text -> files.add(new FileDto(rel, null, null, text)));
    }
    return files;
  }

  /**
   * Read a file as UTF-8 text, skipping (empty) when missing, oversized, or binary.
   */
  private static Optional<String> readText(Path path) {
    try {
      if (!Files.isRegularFile(path) || Files.size(path) > MAX_FILE_BYTES) {
        return Optional.empty();
      }
      byte[] bytes = Files.readAllBytes(path);
      int sniff = Math.min(bytes.length, BINARY_SNIFF_BYTES);
      for (int i = 0; i < sniff; i++) {
        if (bytes[i] == 0) {
          return Optional.empty(); // binary
        }
      }
      return Optional.of(new String(bytes, StandardCharsets.UTF_8));
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  private static List<String> walkFilesystem(Path root) {
    try (Stream<Path> stream = Files.walk(root)) {
      return stream
        .filter(Files::isRegularFile)
        .filter(p -> !isUnderSkippedDir(root.relativize(p)))
        .map(p -> root.relativize(p).toString())
        .toList();
    } catch (IOException e) {
      return List.of();
    }
  }

  private static boolean isUnderSkippedDir(Path relative) {
    for (Path segment : relative) {
      if (SKIP_DIRS.contains(segment.toString())) {
        return true;
      }
    }
    return false;
  }

  private static String fileName(String repoRelPath) {
    int slash = Math.max(repoRelPath.lastIndexOf('/'), repoRelPath.lastIndexOf('\\'));
    return slash < 0 ? repoRelPath : repoRelPath.substring(slash + 1);
  }

  /**
   * Production reader: {@code git ls-files -z}, fail-closed to empty on any error.
   */
  private static MarkdownDiscovery.GitLsFiles realGitReader() {
    return projectDir -> {
      try {
        Process process = new ProcessBuilder("git", "ls-files", "-z")
          .directory(projectDir.toFile())
          .redirectErrorStream(true)
          .start();
        byte[] out = process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
          return Optional.empty();
        }
        List<String> paths = new ArrayList<>();
        for (String token : new String(out, StandardCharsets.UTF_8).split("\0")) {
          if (!token.isBlank()) {
            paths.add(token);
          }
        }
        return Optional.of(paths);
      } catch (IOException e) {
        return Optional.empty();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return Optional.empty();
      }
    };
  }
}
