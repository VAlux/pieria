package dev.alvo.pieria.onboarding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Enumerates the markdown documentation of a project to seed a Pieria profile.
 *
 * <p>Primary source is {@code git ls-files '*.md'}, which naturally excludes build output,
 * {@code .git/}, {@code node_modules/}, and anything gitignored. When the directory is not a git
 * repository (or git is unavailable), it falls back to a filesystem walk that filters {@code *.md}
 * and skips the obvious non-source directories.
 *
 * <p>{@code CLAUDE.md} and {@code AGENTS.md} are excluded by default: harnesses already load those
 * into context every session, so seeding them as memories is redundant. Pass {@code includeAgentDocs}
 * to opt them back in.
 *
 * <h2>Testability</h2>
 * Git enumeration is an injected seam ({@link GitLsFiles}), so tests need no real repository.
 * Use {@link #create(Path)} for production wiring.
 */
public final class MarkdownDiscovery {

  /**
   * Agent docs that are always-in-context for harnesses; excluded unless explicitly requested.
   */
  private static final Set<String> AGENT_DOCS = Set.of("CLAUDE.md", "AGENTS.md");
  /**
   * Directories skipped by the non-git fallback walk (git enumeration excludes these for free).
   */
  private static final Set<String> SKIP_DIRS = Set.of(".git", "node_modules", "build", ".gradle", ".idea");
  private final Path projectDir;
  private final GitLsFiles gitLsFiles;

  /**
   * Test constructor: inject the git enumeration seam.
   */
  MarkdownDiscovery(Path projectDir, GitLsFiles gitLsFiles) {
    this.projectDir = projectDir;
    this.gitLsFiles = gitLsFiles;
  }

  /**
   * Production factory: wires a {@link ProcessBuilder}-based {@code git ls-files} reader.
   */
  public static MarkdownDiscovery create(Path projectDir) {
    return new MarkdownDiscovery(projectDir, realGitReader());
  }

  /**
   * True when the path's file name is one of the always-in-context agent docs.
   */
  static boolean isAgentDoc(Path relative) {
    Path name = relative.getFileName();
    return name != null && AGENT_DOCS.contains(name.toString());
  }

  /**
   * Fallback when not in a git repo: walk the tree for {@code *.md}, skipping non-source dirs.
   */
  private static List<String> walkFilesystem(Path root) {
    try (Stream<Path> stream = Files.walk(root)) {
      return stream
        .filter(Files::isRegularFile)
        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
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

  /**
   * Production reader: {@code git ls-files -z '*.md'}, fail-closed to empty on any error.
   */
  private static GitLsFiles realGitReader() {
    return projectDir -> {
      try {
        Process process = new ProcessBuilder("git", "ls-files", "-z", "*.md")
          .directory(projectDir.toFile())
          .redirectErrorStream(true)
          .start();
        byte[] out = process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
          return Optional.empty();
        }
        // -z gives NUL-separated paths (safe for spaces/newlines); drop the trailing empty token.
        String raw = new String(out);
        List<String> paths = new ArrayList<>();
        for (String token : raw.split("\0")) {
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

  /**
   * Discover the markdown docs to seed, sorted by relative path for deterministic output.
   *
   * @param includeAgentDocs when false (default), drop {@code CLAUDE.md}/{@code AGENTS.md}
   */
  public List<Doc> discover(boolean includeAgentDocs) {
    List<String> relative = gitLsFiles.list(projectDir)
      .orElseGet(() -> walkFilesystem(projectDir));

    List<Doc> docs = new ArrayList<>();
    for (String rel : relative) {
      Path relPath = Path.of(rel);
      if (!includeAgentDocs && isAgentDoc(relPath)) {
        continue;
      }
      docs.add(new Doc(relPath, projectDir.resolve(relPath)));
    }
    docs.sort((a, b) -> a.relative().toString().compareTo(b.relative().toString()));
    return docs;
  }

  /**
   * Lists repo-relative {@code *.md} paths for a directory, or empty when not a git repo / git failed.
   */
  @FunctionalInterface
  public interface GitLsFiles {
    Optional<List<String>> list(Path projectDir);
  }

  /**
   * A discovered markdown document: its repo-relative path (for provenance) and absolute path (for reading).
   */
  public record Doc(Path relative, Path absolute) {
  }
}
